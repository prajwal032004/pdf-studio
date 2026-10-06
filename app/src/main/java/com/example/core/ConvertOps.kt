package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.text.Html
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ImageItem(val uri: Uri, val rotation: Int = 0)

data class ImagesToPdfOptions(
    val pageSize: PageSize = PageSize.A4,
    val orientation: Int = 0, // 0 auto, 1 portrait, 2 landscape
    val marginPt: Float = 18f,
    val quality: Int = 85,
    val grayscale: Boolean = false,
    val maxSide: Int = 2400
)

data class TextPdfOptions(
    val pageSize: PageSize = PageSize.A4,
    val landscape: Boolean = false,
    val fontSize: Float = 11f,
    val marginPt: Float = 54f,
    val monospace: Boolean = false,
    val pageNumbers: Boolean = true,
    val title: String = ""
)

object ConvertOps {

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ---------------- Images → PDF ----------------

    /** Decodes a picture at a bounded size with EXIF orientation applied. */
    fun decodeImage(context: Context, uri: Uri, maxSide: Int, extraRotation: Int = 0): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val exifRot = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        bmp = Bitmaps.rotate(bmp, (exifRot + extraRotation).toFloat())
        Bitmaps.scaleToMax(bmp, maxSide)
    }.getOrNull()

    suspend fun imagesToPdf(
        context: Context, images: List<ImageItem>, name: String, opt: ImagesToPdfOptions, progress: Progress = NoProgress
    ): File = io {
        require(images.isNotEmpty()) { "Add at least one image" }
        PDDocument().use { doc ->
            images.forEachIndexed { i, item ->
                progress(i / images.size.toFloat(), "Image ${i + 1} of ${images.size}")
                var bmp = decodeImage(context, item.uri, opt.maxSide, item.rotation) ?: return@forEachIndexed
                if (opt.grayscale) bmp = Bitmaps.grayscale(bmp)
                val imgLandscape = bmp.width > bmp.height
                val rect = if (opt.pageSize == PageSize.FIT) {
                    // One pixel ≈ one point at 150 dpi keeps pages a sensible physical size.
                    val s = 72f / 150f
                    PDRectangle(bmp.width * s + 2 * opt.marginPt, bmp.height * s + 2 * opt.marginPt)
                } else opt.pageSize.rect(
                    when (opt.orientation) { 1 -> false; 2 -> true; else -> imgLandscape }
                )
                val page = PDPage(rect)
                doc.addPage(page)
                val img = JPEGFactory.createFromImage(doc, bmp, opt.quality / 100f)
                val availW = rect.width - 2 * opt.marginPt
                val availH = rect.height - 2 * opt.marginPt
                val s = minOf(availW / bmp.width, availH / bmp.height)
                val w = bmp.width * s; val h = bmp.height * s
                PDPageContentStream(doc, page).use {
                    it.drawImage(img, opt.marginPt + (availW - w) / 2, opt.marginPt + (availH - h) / 2, w, h)
                }
                bmp.recycle()
            }
            require(doc.numberOfPages > 0) { "None of the images could be read" }
            doc.documentInformation.title = name
            doc.documentInformation.producer = "PDF Studio"
            val f = Workspace.newOutput(context, name.ifBlank { "Images" }, Workspace.stamp())
            doc.save(f); f
        }
    }

    // ---------------- PDF → images ----------------

    suspend fun pdfToImages(
        context: Context, pdf: PickedPdf, pages: List<Int>, dpi: Int, png: Boolean, quality: Int,
        zip: Boolean, progress: Progress = NoProgress
    ): List<File> = io {
        val targets = pages.ifEmpty { (0 until pdf.pageCount).toList() }
        val files = PageRenderer(pdf.file).use { r ->
            targets.mapIndexedNotNull { i, idx ->
                progress(i / targets.size.toFloat(), "Rendering page ${idx + 1}")
                val bmp = r.renderDpi(idx, dpi) ?: return@mapIndexedNotNull null
                val f = Workspace.newOutput(context, pdf.baseName, "page_${idx + 1}", if (png) "png" else "jpg")
                f.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, it) }
                bmp.recycle()
                f
            }
        }
        if (!zip || files.size < 2) return@io files
        val z = Workspace.newOutput(context, pdf.baseName, "images", "zip")
        ZipOutputStream(z.outputStream()).use { zos ->
            for (f in files) {
                zos.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(zos) }; zos.closeEntry()
            }
        }
        files.forEach { it.delete() }
        listOf(z)
    }

    // ---------------- PDF → text ----------------

    fun extractText(file: File, pages: List<Int>? = null, keepLayout: Boolean = false): String =
        Workspace.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = keepLayout
            if (pages.isNullOrEmpty()) stripper.getText(doc)
            else pages.sorted().joinToString("\n\n") { p ->
                stripper.startPage = p + 1; stripper.endPage = p + 1
                "—— Page ${p + 1} ——\n" + stripper.getText(doc)
            }
        }

    suspend fun pdfToText(context: Context, pdf: PickedPdf, keepLayout: Boolean): ToolResult = io {
        val text = extractText(pdf.file, null, keepLayout)
        val f = Workspace.newOutput(context, pdf.baseName, "text", "txt")
        f.writeText(text)
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        ToolResult(
            listOf(f),
            if (text.isBlank()) "No text layer found. Try OCR for scanned PDFs." else "Extracted $words words",
            listOf("Characters" to text.length.toString(), "Words" to words.toString())
        )
    }

    // ---------------- Text / Markdown / HTML → PDF ----------------

    suspend fun textToPdf(context: Context, text: String, name: String, opt: TextPdfOptions): File = io {
        val sb = SpannableStringBuilder()
        if (opt.title.isNotBlank()) {
            val s = sb.length; sb.append(opt.title).append("\n\n")
            sb.setSpan(StyleSpan(Typeface.BOLD), s, s + opt.title.length, 0)
            sb.setSpan(RelativeSizeSpan(1.6f), s, s + opt.title.length, 0)
        }
        sb.append(text)
        renderSpanned(context, sb, name, opt, if (opt.monospace) Typeface.MONOSPACE else Typeface.SERIF)
    }

    suspend fun markdownToPdf(context: Context, markdown: String, name: String, opt: TextPdfOptions): File = io {
        renderSpanned(context, Markdown.toSpanned(markdown), name, opt, Typeface.SANS_SERIF)
    }

    suspend fun htmlToPdf(context: Context, html: String, name: String, opt: TextPdfOptions): File = io {
        val spanned: Spanned = if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        else @Suppress("DEPRECATION") Html.fromHtml(html)
        renderSpanned(context, SpannableStringBuilder(spanned), name, opt, Typeface.SERIF)
    }

    /** Paginates styled text into a vector PDF with selectable text. */
    fun renderSpanned(context: Context, text: CharSequence, name: String, opt: TextPdfOptions, typeface: Typeface): File {
        val r = opt.pageSize.rect(opt.landscape)
        val pageW = r.width.toInt(); val pageH = r.height.toInt()
        val margin = opt.marginPt.toInt()
        val contentW = pageW - 2 * margin
        val footer = if (opt.pageNumbers) 24 else 0
        val contentH = pageH - 2 * margin - footer
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = opt.fontSize; color = Color.rgb(28, 27, 31); this.typeface = typeface
        }
        val layout = if (Build.VERSION.SDK_INT >= 23)
            StaticLayout.Builder.obtain(text, 0, text.length, paint, contentW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).setIncludePad(false).build()
        else @Suppress("DEPRECATION") StaticLayout(text, paint, contentW, Layout.Alignment.ALIGN_NORMAL, 1.25f, 0f, false)

        val doc = PdfDocument()
        try {
            var line = 0
            var pageNo = 1
            val footerPaint = TextPaint(paint).apply { textSize = 9f; color = Color.GRAY; this.typeface = Typeface.SANS_SERIF }
            do {
                val startTop = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - startTop <= contentH) end++
                if (end == line) end = line + 1 // a single oversized line still gets its own page
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                val c = page.canvas
                c.save()
                c.translate(margin.toFloat(), margin.toFloat() - startTop)
                c.clipRect(0f, startTop.toFloat(), contentW.toFloat(), layout.getLineBottom(end - 1).toFloat())
                layout.draw(c)
                c.restore()
                if (opt.pageNumbers) {
                    val label = "$pageNo"
                    c.drawText(label, (pageW - footerPaint.measureText(label)) / 2f, (pageH - margin / 2).toFloat(), footerPaint)
                }
                doc.finishPage(page)
                line = end; pageNo++
            } while (line < layout.lineCount)
            val f = Workspace.newOutput(context, name.ifBlank { "Document" }, Workspace.stamp())
            f.outputStream().use { doc.writeTo(it) }
            return f
        } finally { doc.close() }
    }
}

/** A compact Markdown → styled text converter (headings, emphasis, code, lists, quotes, rules, links). */
object Markdown {
    private val codeBg = Color.rgb(238, 236, 244)

    fun toSpanned(md: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        var inCode = false
        val codeBuf = StringBuilder()
        for (rawLine in md.replace("\r", "").lines()) {
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    val s = out.length
                    out.append(codeBuf.toString().trimEnd('\n')).append("\n\n")
                    out.setSpan(TypefaceSpan("monospace"), s, out.length - 2, 0)
                    out.setSpan(BackgroundColorSpan(codeBg), s, out.length - 2, 0)
                    out.setSpan(RelativeSizeSpan(0.9f), s, out.length - 2, 0)
                    codeBuf.clear()
                }
                inCode = !inCode
                continue
            }
            if (inCode) { codeBuf.append(rawLine).append('\n'); continue }
            when {
                line.isBlank() -> out.append("\n")
                Regex("^#{1,6} ").containsMatchIn(line) -> {
                    val level = line.takeWhile { it == '#' }.length
                    val s = out.length
                    appendInline(out, line.drop(level).trim())
                    out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, 0)
                    out.setSpan(RelativeSizeSpan(floatArrayOf(2f, 1.6f, 1.35f, 1.2f, 1.1f, 1f)[level - 1]), s, out.length, 0)
                    if (level <= 2) out.setSpan(ForegroundColorSpan(Color.rgb(40, 30, 90)), s, out.length, 0)
                    out.append("\n")
                }
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(line.trim()) -> {
                    val s = out.length
                    out.append("──────────────────────────────\n")
                    out.setSpan(ForegroundColorSpan(Color.LTGRAY), s, out.length, 0)
                }
                line.trimStart().startsWith(">") -> {
                    val s = out.length
                    appendInline(out, line.trimStart().removePrefix(">").trim())
                    out.append("\n")
                    out.setSpan(QuoteSpan(Color.rgb(120, 100, 200)), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, 0)
                }
                Regex("^\\s*[-*+] ").containsMatchIn(line) -> {
                    val indent = line.takeWhile { it == ' ' }.length / 2
                    val s = out.length
                    val body = line.trimStart().drop(2)
                    val task = Regex("^\\[( |x|X)] ").find(body)
                    if (task != null) out.append(if (task.groupValues[1].isBlank()) "☐ " else "☑ ")
                    appendInline(out, if (task != null) body.drop(4) else body)
                    out.append("\n")
                    out.setSpan(LeadingMarginSpan.Standard(indent * 18), s, out.length, 0)
                    out.setSpan(BulletSpan(10), s, out.length, 0)
                }
                Regex("^\\s*\\d+[.)] ").containsMatchIn(line) -> {
                    val s = out.length
                    val num = line.trim().takeWhile { it.isDigit() }
                    out.append("$num. ")
                    appendInline(out, line.trim().replaceFirst(Regex("^\\d+[.)] "), ""))
                    out.append("\n")
                    out.setSpan(LeadingMarginSpan.Standard(12, 24), s, out.length, 0)
                }
                line.trim().startsWith("|") -> {
                    if (Regex("^\\|?\\s*:?-+").containsMatchIn(line.trim().trimStart('|'))) continue
                    val s = out.length
                    val cells = line.trim().trim('|').split('|').map { it.trim() }
                    out.append(cells.joinToString("   │   ")).append("\n")
                    out.setSpan(TypefaceSpan("monospace"), s, out.length, 0)
                    out.setSpan(RelativeSizeSpan(0.9f), s, out.length, 0)
                }
                else -> { appendInline(out, line.trim()); out.append("\n") }
            }
        }
        if (inCode && codeBuf.isNotEmpty()) out.append(codeBuf)
        return out
    }

    private val inline = Regex("(\\*\\*|__)(.+?)\\1|(\\*|_)(.+?)\\3|`([^`]+)`|~~(.+?)~~|\\[([^]]+)]\\(([^)]+)\\)")

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var i = 0
        for (m in inline.findAll(text)) {
            out.append(text, i, m.range.first)
            val s = out.length
            when {
                m.groups[2] != null -> { out.append(m.groupValues[2]); out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, 0) }
                m.groups[4] != null -> { out.append(m.groupValues[4]); out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, 0) }
                m.groups[5] != null -> {
                    out.append(m.groupValues[5])
                    out.setSpan(TypefaceSpan("monospace"), s, out.length, 0)
                    out.setSpan(BackgroundColorSpan(codeBg), s, out.length, 0)
                }
                m.groups[6] != null -> { out.append(m.groupValues[6]); out.setSpan(StrikethroughSpan(), s, out.length, 0) }
                m.groups[7] != null -> {
                    out.append(m.groupValues[7])
                    out.setSpan(ForegroundColorSpan(Color.rgb(30, 90, 200)), s, out.length, 0)
                    out.setSpan(UnderlineSpan(), s, out.length, 0)
                    out.append(" (").append(m.groupValues[8]).append(")")
                    out.setSpan(AbsoluteSizeSpan(8), out.length - m.groupValues[8].length - 3, out.length, 0)
                }
            }
            i = m.range.last + 1
        }
        out.append(text, i, text.length)
    }
}
