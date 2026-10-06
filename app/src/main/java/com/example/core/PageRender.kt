package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe wrapper over the platform [PdfRenderer] for one document.
 * [cacheKey] identifies the rendered content in [ThumbCache].
 */
class PageRenderer private constructor(
    /** Backing file. For renderers opened from a Uri this is only a stand-in — never read it. */
    val file: File,
    private val fd: ParcelFileDescriptor,
    val cacheKey: String
) : Closeable {
    constructor(file: File) : this(
        file, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), "${file.absolutePath}:${file.lastModified()}"
    )

    private val renderer = try { PdfRenderer(fd) } catch (e: Exception) { runCatching { fd.close() }; throw e }
    private val sizes = ConcurrentHashMap<Int, Pair<Int, Int>>()
    // Queues async renders so requests for pages that scrolled away are dropped instead of rendered.
    private val gate = Mutex()
    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Page size in points as displayed (rotation applied). */
    fun pageSize(index: Int): Pair<Int, Int> = sizes[index] ?: synchronized(this) {
        sizes.getOrPut(index) { if (closed) 612 to 792 else renderer.openPage(index).use { it.width to it.height } }
    }

    /** The page size if it is already known — never blocks on a render in progress. */
    fun cachedSize(index: Int): Pair<Int, Int>? = sizes[index]

    /** Measures every page up front so layouts never block the UI thread. */
    suspend fun preloadSizes() = withContext(Dispatchers.IO) {
        for (i in 0 until pageCount) {
            if (!isActive || closed) break
            runCatching { pageSize(i) }
        }
    }

    /** Cancellable render: if the caller goes away while waiting its turn, nothing is rendered. */
    suspend fun renderAsync(index: Int, widthPx: Int): Bitmap? = gate.withLock {
        withContext(Dispatchers.IO) { if (isActive) render(index, widthPx) else null }
    }

    @Synchronized
    fun render(index: Int, widthPx: Int, forPrint: Boolean = false): Bitmap? {
        if (closed || index !in 0 until pageCount) return null
        return runCatching {
            renderer.openPage(index).use { page ->
                sizes.putIfAbsent(index, page.width to page.height)
                val w = widthPx.coerceIn(16, 4096)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(16, 6000)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(
                    bmp, null, null,
                    if (forPrint) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
                bmp
            }
        }.getOrNull()
    }

    /** Renders at a given DPI (72 points per inch). */
    fun renderDpi(index: Int, dpi: Int): Bitmap? {
        val (wPt, _) = pageSize(index)
        return render(index, (wPt * dpi / 72f).toInt(), forPrint = true)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        /** Opens a document straight from a Uri, without copying it (read-only previews). */
        fun open(context: Context, uri: Uri): PageRenderer {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open file")
            return PageRenderer(File(context.cacheDir, "uri_preview"), fd, "uri:$uri")
        }
    }
}

object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // File lists ask for many documents at once; keep a few in flight, not dozens.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val uriDispatcher = Dispatchers.IO.limitedParallelism(3)

    private fun key(base: String, page: Int, width: Int) = "$base:$page@$width"
    private fun fileKey(file: File) = "${file.absolutePath}:${file.lastModified()}"

    fun peek(renderer: PageRenderer, page: Int, width: Int): Bitmap? = cache.get(key(renderer.cacheKey, page, width))

    suspend fun get(renderer: PageRenderer, page: Int, width: Int): Bitmap? {
        val k = key(renderer.cacheKey, page, width)
        cache.get(k)?.let { return it }
        return renderer.renderAsync(page, width)?.also { cache.put(k, it) }
    }

    /** One-shot thumbnail without keeping a renderer open. */
    suspend fun first(file: File, width: Int = 300, page: Int = 0): Bitmap? {
        val k = key(fileKey(file), page, width)
        cache.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { PageRenderer(file).use { it.render(page, width) } }.getOrNull()
        }?.also { cache.put(k, it) }
    }

    private fun uriKey(uri: Uri, width: Int, version: Long) = key("uri:$uri#$version", 0, width)

    fun peekUri(uri: Uri, width: Int, version: Long = 0): Bitmap? = cache.get(uriKey(uri, width, version))

    /** First-page thumbnail straight from a Uri (file lists, recents). Failures are remembered. */
    suspend fun forUri(context: Context, uri: Uri, width: Int, version: Long = 0): Bitmap? {
        val k = uriKey(uri, width, version)
        cache.get(k)?.let { return it }
        if (k in failed) return null
        return withContext(uriDispatcher) {
            cache.get(k) ?: runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                    PdfRenderer(fd).use { pr ->
                        pr.openPage(0).use { pg ->
                            val h = (width.toFloat() * pg.height / pg.width).toInt().coerceIn(16, width * 3)
                            Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(android.graphics.Color.WHITE)
                                pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }.getOrNull()?.also { cache.put(k, it) } ?: run { failed.add(k); null }
        }
    }

    /** Drops cached bitmaps for a document that changed or was deleted. */
    fun invalidate(uri: Uri) {
        val prefix = "uri:$uri"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        failed.removeAll { it.startsWith(prefix) }
    }
}

object Bitmaps {
    fun grayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return src
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
    }

    /** Fraction of pixels that differ noticeably from white, sampled on a grid. */
    fun inkRatio(bmp: Bitmap, threshold: Int = 60): Float {
        val step = maxOf(1, minOf(bmp.width, bmp.height) / 120)
        var ink = 0; var total = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val lum = (android.graphics.Color.red(c) * 299 + android.graphics.Color.green(c) * 587 + android.graphics.Color.blue(c) * 114) / 1000
                if (255 - lum > threshold) ink++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else ink.toFloat() / total
    }

    /** 64-bit difference hash, used to spot visually duplicate pages. */
    fun dHash(bmp: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            fun lum(c: Int) = android.graphics.Color.red(c) * 3 + android.graphics.Color.green(c) * 6 + android.graphics.Color.blue(c)
            if (lum(small.getPixel(x, y)) > lum(small.getPixel(x + 1, y))) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }
}

/**
 * Maps normalised display coordinates (0..1, origin top-left, as rendered by PdfRenderer with
 * the page's /Rotate applied) to PDF user space and back.
 */
class PageGeometry(page: PDPage) {
    private val box = page.cropBox
    val rotation = ((page.rotation % 360) + 360) % 360
    val llx = box.lowerLeftX
    val lly = box.lowerLeftY
    val w = box.width
    val h = box.height

    /** Display width/height in points. */
    val displayW: Float get() = if (rotation == 90 || rotation == 270) h else w
    val displayH: Float get() = if (rotation == 90 || rotation == 270) w else h

    fun toPdf(u: Float, v: Float): Pair<Float, Float> = when (rotation) {
        90 -> (llx + v * w) to (lly + u * h)
        180 -> (llx + (1 - u) * w) to (lly + v * h)
        270 -> (llx + (1 - v) * w) to (lly + (1 - u) * h)
        else -> (llx + u * w) to (lly + (1 - v) * h)
    }

    fun fromPdf(x: Float, y: Float): Pair<Float, Float> {
        val fx = (x - llx) / w
        val fy = (y - lly) / h
        return when (rotation) {
            90 -> fy to fx
            180 -> (1 - fx) to fy
            270 -> (1 - fy) to (1 - fx)
            else -> fx to (1 - fy)
        }
    }

    /** Converts a PDF rect (x0, y0, x1, y1) to normalised display (left, top, right, bottom). */
    fun rectFromPdf(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val a = fromPdf(x0, y0); val b = fromPdf(x1, y1)
        return floatArrayOf(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    /** Converts a normalised display rect to a PDF rect (x, y, width, height). */
    fun rectToPdf(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        val a = toPdf(left, top)
        val b = toPdf(right, bottom)
        val x0 = minOf(a.first, b.first); val x1 = maxOf(a.first, b.first)
        val y0 = minOf(a.second, b.second); val y1 = maxOf(a.second, b.second)
        return floatArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    /** Converts a length in display points (e.g. stroke width) — rotation invariant. */
    fun scale(normalized: Float): Float = normalized * displayW
}
