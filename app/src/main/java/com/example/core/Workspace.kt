package com.example.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.utils.FileUtils
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.Date
import java.util.Locale

/** A PDF the user picked, copied to a private working file (decrypted if a password was given). */
data class PickedPdf(
    val uri: Uri,
    val name: String,
    val file: File,
    val pageCount: Int,
    val size: Long,
    val password: String? = null,
    val wasEncrypted: Boolean = false
) {
    val baseName: String get() = Workspace.baseName(name)
}

class PasswordRequiredException(val uri: Uri, val name: String) : Exception("Password required")

class StoragePermissionNeeded : Exception("Storage permission needed")

/** Output of a tool run. [openUri] is set when the original document was updated in place. */
data class ToolResult(
    val files: List<File>,
    val message: String = "",
    val details: List<Pair<String, String>> = emptyList(),
    val openUri: Uri? = null
) {
    val primary: File? get() = files.firstOrNull()
}

typealias Progress = (Float, String) -> Unit

val NoProgress: Progress = { _, _ -> }

object Workspace {

    fun outDir(context: Context): File = FileUtils.getAppFilesDir(context)

    private fun workDir(context: Context): File =
        File(context.cacheDir, "work").apply { mkdirs() }

    fun baseName(name: String): String =
        name.substringBeforeLast('.').ifBlank { "document" }.take(80)

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** A fresh, unique file in the PDF Studio output folder. */
    fun newOutput(context: Context, base: String, suffix: String, ext: String = "pdf"): File {
        val clean = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        val dir = outDir(context)
        var f = File(dir, "${clean}_$suffix.$ext")
        var i = 2
        while (f.exists()) { f = File(dir, "${clean}_${suffix}_$i.$ext"); i++ }
        return f
    }

    fun temp(context: Context, ext: String): File =
        File.createTempFile("tmp_", ".$ext", workDir(context))

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "document.pdf"
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    fun mimeOf(context: Context, uri: Uri): String? =
        context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                displayName(context, uri).substringAfterLast('.', "").lowercase()
            )

    suspend fun copyToWork(context: Context, uri: Uri, ext: String = "pdf"): File = withContext(Dispatchers.IO) {
        val out = temp(context, ext)
        val input = if (uri.scheme == "file") File(uri.path!!).inputStream()
        else context.contentResolver.openInputStream(uri) ?: error("Cannot open file")
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        out
    }

    private val ENCRYPT_MARKER = "/Encrypt".toByteArray()

    /**
     * Copies [uri] into the workspace and, in the same pass, looks for an /Encrypt entry —
     * which every encrypted PDF carries in its (never compressed) trailer.
     */
    private fun copyAndScan(context: Context, uri: Uri): Pair<File, Boolean> {
        val out = temp(context, "pdf")
        val input = if (uri.scheme == "file") File(uri.path!!).inputStream()
        else context.contentResolver.openInputStream(uri) ?: error("Cannot open file")
        var found = false
        val m = ENCRYPT_MARKER
        val carry = ByteArray(m.size - 1)
        var carryLen = 0
        try {
            input.use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        if (!found) {
                            // Matches that straddle the previous chunk.
                            val joined = carry.copyOf(carryLen) + buf.copyOf(minOf(n, m.size - 1))
                            found = indexOf(joined, joined.size, m) >= 0 || indexOf(buf, n, m) >= 0
                            val keep = minOf(n, m.size - 1)
                            System.arraycopy(buf, n - keep, carry, 0, keep)
                            carryLen = keep
                        }
                    }
                }
            }
        } catch (e: Exception) {
            out.delete(); throw e
        }
        return out to found
    }

    private fun indexOf(data: ByteArray, len: Int, pattern: ByteArray): Int {
        val first = pattern[0]
        var i = 0
        val last = len - pattern.size
        while (i <= last) {
            if (data[i] == first) {
                var j = 1
                while (j < pattern.size && data[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
            }
            i++
        }
        return -1
    }

    /** Size of the source document, or -1 when the provider doesn't say. */
    private fun sourceSize(context: Context, uri: Uri): Long = runCatching {
        if (uri.scheme == "file") File(uri.path!!).length()
        else context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    // Documents already opened this session: moving between the viewer and tools is instant
    // and protected files don't ask for their password again.
    private val opened = ConcurrentHashMap<String, Pair<Long, PickedPdf>>()

    private fun remember(uri: Uri, sourceSize: Long, pdf: PickedPdf): PickedPdf {
        if (sourceSize > 0) {
            if (opened.size > 16) opened.clear()
            opened[uri.toString()] = sourceSize to pdf
        }
        return pdf
    }

    /** Forgets everything cached about [uri] after it was changed or deleted. */
    fun invalidate(uri: Uri) {
        opened.remove(uri.toString())
        ThumbCache.invalidate(uri)
    }

    fun memory(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(48L * 1024 * 1024)

    fun load(file: File, password: String? = null): PDDocument =
        PDDocument.load(file, password ?: "", memory())

    /** Loads a document for editing; strips encryption so the result can be saved freely. */
    fun loadForEdit(pdf: PickedPdf): PDDocument =
        load(pdf.file).also { if (it.isEncrypted) it.isAllSecurityToBeRemoved = true }

    /**
     * Copies [uri] into the workspace and inspects it. Throws [PasswordRequiredException]
     * when the file is encrypted and [password] is missing or wrong.
     */
    suspend fun pick(context: Context, uri: Uri, password: String? = null): PickedPdf = withContext(Dispatchers.IO) {
        val srcSize = sourceSize(context, uri)
        opened[uri.toString()]?.let { (size, cached) ->
            if (srcSize > 0 && size == srcSize && cached.file.exists()) return@withContext cached
        }
        val name = displayName(context, uri)
        val (raw, maybeEncrypted) = copyAndScan(context, uri)
        if (!maybeEncrypted) {
            // Fast path: the platform renderer counts pages without parsing the whole document.
            val pages = runCatching {
                ParcelFileDescriptor.open(raw, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
            }.getOrNull()
            if (pages != null && pages > 0) return@withContext remember(uri, srcSize, PickedPdf(uri, name, raw, pages, raw.length()))
        }
        var encrypted = false
        val working: File
        val pages: Int
        try {
            val doc = load(raw, password)
            doc.use { d ->
                encrypted = d.isEncrypted
                pages = d.numberOfPages
                if (encrypted) {
                    // Keep a decrypted working copy so PdfRenderer and every tool can read it.
                    d.isAllSecurityToBeRemoved = true
                    val dec = temp(context, "pdf")
                    d.save(dec)
                    raw.delete()
                    working = dec
                } else working = raw
            }
        } catch (e: InvalidPasswordException) {
            raw.delete()
            throw PasswordRequiredException(uri, name)
        }
        remember(uri, srcSize, PickedPdf(uri, name, working, pages, working.length(), password, encrypted))
    }

    suspend fun pickFile(context: Context, file: File): PickedPdf =
        pick(context, Uri.fromFile(file))

    fun uriFor(context: Context, file: File): Uri = FileUtils.getUriForFile(context, file)

    fun mimeForFile(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        "html" -> "text/html"
        else -> "application/octet-stream"
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(context, it) })
        val mime = files.map { mimeForFile(it) }.distinct().singleOrNull() ?: "*/*"
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uris[0])
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openExternally(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mimeForFile(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Public folder where "Save to Downloads" puts files, kept separate from everything else. */
    const val SAVED_FOLDER = "Download/PDF Studio"

    /**
     * Copies a file into the public Downloads/PDF Studio folder under [name].
     * Returns the display path, including any " (1)" suffix the system added.
     * Throws [StoragePermissionNeeded] on Android 9 and older when write access is missing.
     */
    suspend fun saveToDownloads(context: Context, file: File, name: String = file.name): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeForFile(file))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PDF Studio")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create file in Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            val finalName = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: name
            "Downloads/PDF Studio/$finalName"
        } else {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) throw StoragePermissionNeeded()
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PDF Studio")
            dir.mkdirs()
            var dest = File(dir, name)
            var i = 1
            while (dest.exists()) { dest = File(dir, "${baseName(name)} ($i).${name.substringAfterLast('.', "pdf")}"); i++ }
            file.copyTo(dest)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.path), null, null)
            "Downloads/PDF Studio/${dest.name}"
        }
    }

    suspend fun writeToUri(context: Context, file: File, dest: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(dest, "wt")!!.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** True when [uri] can be written back in place (our own files, all-files access, writable documents). */
    suspend fun canOverwrite(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (uri.scheme == "file") File(uri.path!!).canWrite()
            else context.contentResolver.openFileDescriptor(uri, "rw")!!.use { true }
        }.getOrDefault(false)
    }

    /** Replaces the content of [dest] with [src], truncating any leftover bytes. */
    suspend fun overwrite(context: Context, dest: Uri, src: File) = withContext(Dispatchers.IO) {
        if (dest.scheme == "file") src.copyTo(File(dest.path!!), overwrite = true)
        else context.contentResolver.openFileDescriptor(dest, "rwt")!!.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { o ->
                src.inputStream().use { it.copyTo(o) }
                o.flush()
                runCatching { o.channel.truncate(src.length()) }
            }
        }
        invalidate(dest)
    }

    fun clearWork(context: Context) {
        opened.clear()
        workDir(context).listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Removes working copies left behind by earlier sessions (nothing can still be using them). */
    fun trimWork(context: Context, olderThan: Long) {
        workDir(context).listFiles()?.forEach { if (it.lastModified() < olderThan) it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".pdf") && f.lastModified() < olderThan) f.delete()
        }
    }

    fun workSize(context: Context): Long = workDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val g = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
        return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, g.toDouble()), units[g])
    }
}

/** Parses "1-3, 5, 8-" style page ranges into zero-based indices. */
object PageRanges {
    fun parse(text: String, pageCount: Int): List<Int> {
        if (text.isBlank()) return emptyList()
        val out = LinkedHashSet<Int>()
        for (raw in text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                raw.equals("all", true) -> (0 until pageCount).forEach { out.add(it) }
                raw.equals("odd", true) -> (0 until pageCount step 2).forEach { out.add(it) }
                raw.equals("even", true) -> (1 until pageCount step 2).forEach { out.add(it) }
                raw.contains('-') -> {
                    val (a, b) = raw.split('-', limit = 2)
                    val start = a.toIntOrNull() ?: 1
                    val end = b.toIntOrNull() ?: pageCount
                    val (lo, hi) = if (start <= end) start to end else end to start
                    for (p in lo..hi) if (p in 1..pageCount) out.add(p - 1)
                }
                else -> raw.toIntOrNull()?.let { if (it in 1..pageCount) out.add(it - 1) }
            }
        }
        return out.toList()
    }

    /** Parses groups like "1-3, 4-6" into separate lists (one output file per group). */
    fun parseGroups(text: String, pageCount: Int): List<List<Int>> =
        text.split(',', ';').map { parse(it, pageCount) }.filter { it.isNotEmpty() }

    fun format(indices: Collection<Int>): String {
        if (indices.isEmpty()) return ""
        val sorted = indices.sorted().map { it + 1 }
        val parts = mutableListOf<String>()
        var start = sorted[0]; var prev = start
        for (p in sorted.drop(1) + listOf(Int.MIN_VALUE)) {
            if (p == prev + 1) { prev = p; continue }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = p; prev = p
        }
        return parts.joinToString(", ")
    }
}
