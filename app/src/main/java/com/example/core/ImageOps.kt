package com.example.core

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap

data class CompressOptions(
    val jpegQuality: Int = 70,
    val maxDpi: Int = 150,
    val grayscaleImages: Boolean = false,
    val stripMetadata: Boolean = true,
    val rasterize: Boolean = false
) {
    companion object {
        val LOW = CompressOptions(85, 220)
        val MEDIUM = CompressOptions(70, 150)
        val HIGH = CompressOptions(50, 110)
        val EXTREME = CompressOptions(40, 96, rasterize = true)
    }
}

data class ImageRef(
    val page: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val suffix: String
)

object ImageOps {

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ---------------- Compression ----------------

    suspend fun compress(context: Context, pdf: PickedPdf, opt: CompressOptions, progress: Progress = NoProgress): ToolResult = io {
        val out = Workspace.newOutput(context, pdf.baseName, "compressed")
        if (opt.rasterize) {
            rasterizeTo(pdf, out, opt.maxDpi, opt.jpegQuality, opt.grayscaleImages, progress)
        } else {
            Workspace.loadForEdit(pdf).use { doc ->
                val done = IdentityHashMap<COSBase, PDImageXObject>()
                for (i in 0 until doc.numberOfPages) {
                    progress(i / doc.numberOfPages.toFloat(), "Optimising page ${i + 1}")
                    val page = doc.getPage(i)
                    val longestPt = maxOf(page.mediaBox.width, page.mediaBox.height)
                    val maxSide = (longestPt / 72f * opt.maxDpi).toInt().coerceAtLeast(300)
                    page.resources?.let { recompressResources(doc, it, opt, maxSide, done, 0) }
                    if (opt.stripMetadata) {
                        page.cosObject.removeItem(COSName.getPDFName("Thumb"))
                        page.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
                        page.metadata = null
                    }
                }
                if (opt.stripMetadata) stripDocMetadata(doc)
                doc.save(out)
            }
        }
        // Never hand back a bigger file.
        val before = pdf.file.length()
        var after = out.length()
        var note = ""
        if (after >= before) {
            pdf.file.copyTo(out, overwrite = true)
            after = before
            note = "This PDF is already well optimised — no further reduction possible at this level."
        }
        val pct = if (before > 0) ((before - after) * 100 / before) else 0
        ToolResult(
            listOf(out),
            note.ifEmpty { "Reduced by $pct%" },
            listOf("Original" to Workspace.formatSize(before), "Compressed" to Workspace.formatSize(after), "Saved" to "$pct%")
        )
    }

    private fun recompressResources(
        doc: PDDocument, res: PDResources, opt: CompressOptions, maxSide: Int,
        done: IdentityHashMap<COSBase, PDImageXObject>, depth: Int
    ) {
        if (depth > 6) return
        for (name in res.xObjectNames.toList()) {
            val xo = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            when (xo) {
                is PDImageXObject -> {
                    val key = xo.cosObject
                    done[key]?.let { res.put(name, it); return@let } ?: run {
                        val replacement = recompressImage(doc, xo, opt, maxSide)
                        if (replacement != null) { done[key] = replacement; res.put(name, replacement) }
                    }
                }
                is PDFormXObject -> xo.resources?.let { recompressResources(doc, it, opt, maxSide, done, depth + 1) }
            }
        }
    }

    private fun recompressImage(doc: PDDocument, img: PDImageXObject, opt: CompressOptions, maxSide: Int): PDImageXObject? {
        return runCatching {
            if (img.isStencil || img.bitsPerComponent == 1) return null
            if (img.cosObject.containsKey(COSName.SMASK) || img.cosObject.containsKey(COSName.MASK)) return null
            val originalLen = img.cosObject.length
            if (originalLen in 1..20_000) return null
            var bmp = img.image ?: return null
            bmp = Bitmaps.scaleToMax(bmp, maxSide)
            if (opt.grayscaleImages) bmp = Bitmaps.grayscale(bmp)
            val candidate = JPEGFactory.createFromImage(doc, bmp, opt.jpegQuality / 100f)
            if (candidate.cosObject.length < originalLen * 0.95) candidate else null
        }.getOrNull()
    }

    private fun stripDocMetadata(doc: PDDocument) {
        doc.documentCatalog.metadata = null
        val info = doc.documentInformation
        doc.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation().apply {
            title = info.title
        }
        doc.documentCatalog.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
    }

    /** Re-creates every page as a single JPEG image (strong compression / flattening). */
    fun rasterizeTo(pdf: PickedPdf, out: File, dpi: Int, quality: Int, gray: Boolean, progress: Progress) {
        PageRenderer(pdf.file).use { r ->
            Workspace.load(pdf.file).use { src ->
                PDDocument().use { doc ->
                    for (i in 0 until r.pageCount) {
                        progress(i / r.pageCount.toFloat(), "Rendering page ${i + 1}")
                        var bmp = r.renderDpi(i, dpi) ?: continue
                        if (gray) bmp = Bitmaps.grayscale(bmp)
                        val (w, h) = r.pageSize(i)
                        val page = PDPage(PDRectangle(w.toFloat(), h.toFloat()))
                        doc.addPage(page)
                        val img = JPEGFactory.createFromImage(doc, bmp, quality / 100f)
                        PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, w.toFloat(), h.toFloat()) }
                        bmp.recycle()
                    }
                    PageOps.copyInfo(src, doc)
                    doc.save(out)
                }
            }
        }
    }

    suspend fun grayscale(context: Context, pdf: PickedPdf, fullPage: Boolean, dpi: Int, progress: Progress = NoProgress): File = io {
        val out = Workspace.newOutput(context, pdf.baseName, "grayscale")
        if (fullPage) rasterizeTo(pdf, out, dpi, 85, true, progress)
        else Workspace.loadForEdit(pdf).use { doc ->
            val opt = CompressOptions(jpegQuality = 88, maxDpi = 400, grayscaleImages = true, stripMetadata = false)
            val done = IdentityHashMap<COSBase, PDImageXObject>()
            for (i in 0 until doc.numberOfPages) {
                progress(i / doc.numberOfPages.toFloat(), "Page ${i + 1}")
                doc.getPage(i).resources?.let { grayResources(doc, it, opt, done, 0) }
            }
            doc.save(out)
        }
        out
    }

    private fun grayResources(doc: PDDocument, res: PDResources, opt: CompressOptions, done: IdentityHashMap<COSBase, PDImageXObject>, depth: Int) {
        if (depth > 6) return
        for (name in res.xObjectNames.toList()) {
            when (val xo = runCatching { res.getXObject(name) }.getOrNull()) {
                is PDImageXObject -> {
                    val cached = done[xo.cosObject]
                    if (cached != null) { res.put(name, cached); continue }
                    if (xo.isStencil || xo.cosObject.containsKey(COSName.SMASK)) continue
                    val bmp = runCatching { xo.image }.getOrNull() ?: continue
                    val g = JPEGFactory.createFromImage(doc, Bitmaps.grayscale(bmp), opt.jpegQuality / 100f)
                    done[xo.cosObject] = g
                    res.put(name, g)
                }
                is PDFormXObject -> xo.resources?.let { grayResources(doc, it, opt, done, depth + 1) }
                else -> {}
            }
        }
    }

    // ---------------- Crop / resize ----------------

    /** Margins are fractions (0..0.45) of the displayed page: left, top, right, bottom. */
    suspend fun crop(
        context: Context, pdf: PickedPdf, left: Float, top: Float, right: Float, bottom: Float,
        pages: Set<Int>?, alsoMediaBox: Boolean
    ): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                if (pages != null && i !in pages) continue
                val page = doc.getPage(i)
                applyCrop(page, left, top, 1f - right, 1f - bottom, alsoMediaBox)
            }
            val f = Workspace.newOutput(context, pdf.baseName, "cropped")
            doc.save(f); f
        }
    }

    private fun applyCrop(page: PDPage, l: Float, t: Float, r: Float, b: Float, alsoMediaBox: Boolean) {
        val g = PageGeometry(page)
        val rc = g.rectToPdf(l, t, r, b)
        val rect = PDRectangle(rc[0], rc[1], rc[2], rc[3])
        page.cropBox = rect
        if (alsoMediaBox) page.mediaBox = rect
    }

    /** Detects the content bounding box of each page and trims surrounding whitespace. */
    suspend fun autoCrop(context: Context, pdf: PickedPdf, padding: Float, progress: Progress = NoProgress): File = io {
        val boxes = PageRenderer(pdf.file).use { r ->
            (0 until r.pageCount).map { i ->
                progress(i / r.pageCount.toFloat() * 0.8f, "Analysing page ${i + 1}")
                r.render(i, 500)?.let { contentBounds(it) }
            }
        }
        Workspace.loadForEdit(pdf).use { doc ->
            boxes.forEachIndexed { i, box ->
                if (box == null) return@forEachIndexed
                val (l, t, r, b) = box
                applyCrop(
                    doc.getPage(i),
                    (l - padding).coerceAtLeast(0f), (t - padding).coerceAtLeast(0f),
                    (r + padding).coerceAtMost(1f), (b + padding).coerceAtMost(1f), false
                )
            }
            val f = Workspace.newOutput(context, pdf.baseName, "autocropped")
            doc.save(f); f
        }
    }

    /** Normalised (l, t, r, b) of non-white content, or null for blank pages. */
    fun contentBounds(bmp: Bitmap, threshold: Int = 40): List<Float>? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                val lum = ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000
                if (255 - lum > threshold) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return listOf(minX / w.toFloat(), minY / h.toFloat(), (maxX + 1) / w.toFloat(), (maxY + 1) / h.toFloat())
    }

    /** Places each page, scaled to fit, on a new page of [size]; keeps the displayed orientation. */
    suspend fun resize(
        context: Context, pdf: PickedPdf, size: PageSize, customW: Float?, customH: Float?,
        keepOrientation: Boolean, marginPt: Float, progress: Progress = NoProgress
    ): File = io {
        Workspace.loadForEdit(pdf).use { src ->
            PDDocument().use { out ->
                val layer = LayerUtility(out)
                for (i in 0 until src.numberOfPages) {
                    progress(i / src.numberOfPages.toFloat(), "Page ${i + 1}")
                    val sp = src.getPage(i)
                    val g = PageGeometry(sp)
                    val landscape = keepOrientation && g.displayW > g.displayH
                    val target = if (customW != null && customH != null)
                        (if (landscape) PDRectangle(maxOf(customW, customH), minOf(customW, customH)) else PDRectangle(customW, customH))
                    else size.rect(landscape)
                    val page = PDPage(target)
                    out.addPage(page)
                    val form = layer.importPageAsForm(src, sp)
                    val availW = target.width - 2 * marginPt
                    val availH = target.height - 2 * marginPt
                    val s = minOf(availW / g.displayW, availH / g.displayH)
                    val tx = marginPt + (availW - g.displayW * s) / 2
                    val ty = marginPt + (availH - g.displayH * s) / 2
                    val m = Matrix.getTranslateInstance(tx, ty)
                    m.concatenate(Matrix.getScaleInstance(s, s))
                    m.concatenate(rotationMatrix(g))
                    m.concatenate(Matrix.getTranslateInstance(-g.llx, -g.lly))
                    PDPageContentStream(out, page).use { cs ->
                        cs.saveGraphicsState(); cs.transform(m); cs.drawForm(form); cs.restoreGraphicsState()
                    }
                }
                PageOps.copyInfo(src, out)
                val f = Workspace.newOutput(context, pdf.baseName, "resized")
                out.save(f); f
            }
        }
    }

    /** Maps user space (origin at crop box corner) to upright display space (y up). */
    fun rotationMatrix(g: PageGeometry): Matrix = when (g.rotation) {
        90 -> Matrix(0f, -1f, 1f, 0f, 0f, g.w)
        180 -> Matrix(-1f, 0f, 0f, -1f, g.w, g.h)
        270 -> Matrix(0f, 1f, -1f, 0f, g.h, 0f)
        else -> Matrix()
    }

    // ---------------- Embedded images ----------------

    fun listImages(file: File): List<ImageRef> = runCatching {
        Workspace.load(file).use { doc ->
            val out = mutableListOf<ImageRef>()
            for (i in 0 until doc.numberOfPages) {
                val res = doc.getPage(i).resources ?: continue
                for (name in res.xObjectNames) {
                    val xo = runCatching { res.getXObject(name) }.getOrNull()
                    if (xo is PDImageXObject) out += ImageRef(i, name.name, xo.width, xo.height, xo.cosObject.length, xo.suffix ?: "img")
                }
            }
            out
        }
    }.getOrDefault(emptyList())

    fun imageBitmap(file: File, ref: ImageRef, maxSide: Int = 600): Bitmap? = runCatching {
        Workspace.load(file).use { doc ->
            val xo = doc.getPage(ref.page).resources.getXObject(COSName.getPDFName(ref.name)) as PDImageXObject
            Bitmaps.scaleToMax(xo.image, maxSide)
        }
    }.getOrNull()

    suspend fun extractImages(context: Context, pdf: PickedPdf, progress: Progress = NoProgress): List<File> = io {
        val refs = listImages(pdf.file)
        require(refs.isNotEmpty()) { "No embedded images found in this PDF" }
        Workspace.load(pdf.file).use { doc ->
            val seen = IdentityHashMap<COSBase, Boolean>()
            refs.mapIndexedNotNull { idx, ref ->
                progress(idx / refs.size.toFloat(), "Image ${idx + 1} of ${refs.size}")
                val xo = doc.getPage(ref.page).resources.getXObject(COSName.getPDFName(ref.name)) as? PDImageXObject ?: return@mapIndexedNotNull null
                if (seen.put(xo.cosObject, true) != null) return@mapIndexedNotNull null
                val bmp = runCatching { xo.image }.getOrNull() ?: return@mapIndexedNotNull null
                val jpg = ref.suffix == "jpg"
                val f = Workspace.newOutput(context, pdf.baseName, "p${ref.page + 1}_img${idx + 1}", if (jpg) "jpg" else "png")
                f.outputStream().use { bmp.compress(if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, it) }
                f
            }
        }
    }

    /** Replaces (or, with a null bitmap, hides) an embedded image while keeping the page layout. */
    suspend fun replaceImage(context: Context, pdf: PickedPdf, ref: ImageRef, replacement: Bitmap?): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            val res = doc.getPage(ref.page).resources
            val newImg = if (replacement == null) {
                val clear = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(0) }
                LosslessFactory.createFromImage(doc, clear)
            } else JPEGFactory.createFromImage(doc, Bitmaps.scaleToMax(replacement, 2400), 0.9f)
            res.put(COSName.getPDFName(ref.name), newImg)
            val f = Workspace.newOutput(context, pdf.baseName, if (replacement == null) "image_removed" else "image_replaced")
            doc.save(f); f
        }
    }
}
