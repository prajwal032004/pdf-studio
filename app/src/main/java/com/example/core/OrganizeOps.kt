package com.example.core

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object PdfFonts {
    private val systemFonts = listOf(
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/DroidSans.ttf"
    )

    fun canEncode(font: PDFont, text: String): Boolean = runCatching { font.encode(text); true }.getOrDefault(false)

    /** Helvetica when the text fits WinAnsi, otherwise an embedded system Unicode font. */
    fun forText(doc: PDDocument, text: String, bold: Boolean = false): PDFont {
        val base = if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        if (canEncode(base, text)) return base
        for (path in systemFonts) {
            val f = File(path)
            if (!f.exists()) continue
            val font = runCatching { PDType0Font.load(doc, f) }.getOrNull() ?: continue
            if (canEncode(font, text)) return font
        }
        return base
    }

    /** Removes characters the font cannot draw, so a single odd glyph never aborts a whole job. */
    fun safe(font: PDFont, text: String): String {
        if (canEncode(font, text)) return text
        return text.filter { canEncode(font, it.toString()) }
    }

    fun width(font: PDFont, text: String, size: Float): Float =
        runCatching { font.getStringWidth(text) / 1000f * size }.getOrDefault(text.length * size * 0.5f)
}

enum class HAlign { LEFT, CENTER, RIGHT }
enum class VAlign { TOP, BOTTOM }

data class RGB(val r: Float, val g: Float, val b: Float) {
    companion object {
        fun of(argb: Int) = RGB(
            android.graphics.Color.red(argb) / 255f,
            android.graphics.Color.green(argb) / 255f,
            android.graphics.Color.blue(argb) / 255f
        )
        val BLACK = RGB(0f, 0f, 0f)
    }
}

data class StampText(
    val template: String,
    val h: HAlign,
    val v: VAlign,
    val fontSize: Float = 10f,
    val margin: Float = 28f,
    val color: RGB = RGB.BLACK,
    val bold: Boolean = false
)

data class BookmarkEntry(val title: String, val page: Int, val level: Int = 0)

object OrganizeOps {

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    fun expand(template: String, page: Int, total: Int, fileName: String, date: String): String =
        template.replace("{page}", page.toString())
            .replace("{total}", total.toString())
            .replace("{n}", page.toString())
            .replace("{file}", fileName)
            .replace("{date}", date)

    /** Draws text on each selected page in an upright orientation, respecting page rotation. */
    fun drawStamp(doc: PDDocument, page: PDPage, text: String, s: StampText, alpha: Float = 1f) {
        val font = PdfFonts.forText(doc, text, s.bold)
        val t = PdfFonts.safe(font, text)
        if (t.isBlank()) return
        val g = PageGeometry(page)
        val tw = PdfFonts.width(font, t, s.fontSize)
        // Position in display space (points, origin top-left).
        val dx = when (s.h) {
            HAlign.LEFT -> s.margin
            HAlign.CENTER -> (g.displayW - tw) / 2f
            HAlign.RIGHT -> g.displayW - s.margin - tw
        }
        val dyBaseline = when (s.v) {
            VAlign.TOP -> s.margin + s.fontSize
            VAlign.BOTTOM -> g.displayH - s.margin
        }
        drawDisplayText(doc, page, g, t, font, s.fontSize, dx, dyBaseline, 0f, s.color, alpha)
    }

    /**
     * Draws [text] with its baseline starting at display point (dx, dy) and an extra
     * counter-clockwise [angleDeg] as seen on screen.
     */
    fun drawDisplayText(
        doc: PDDocument, page: PDPage, g: PageGeometry, text: String, font: PDFont, size: Float,
        dx: Float, dy: Float, angleDeg: Float, color: RGB, alpha: Float
    ) {
        val (px, py) = g.toPdf(dx / g.displayW, dy / g.displayH)
        val theta = Math.toRadians((g.rotation + angleDeg).toDouble())
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            if (alpha < 1f) cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = alpha; strokingAlphaConstant = alpha
            })
            cs.beginText()
            cs.setFont(font, size)
            cs.setNonStrokingColor(color.r, color.g, color.b)
            cs.setTextMatrix(Matrix.getRotateInstance(theta, px, py))
            cs.showText(text)
            cs.endText()
        }
    }

    suspend fun headerFooter(
        context: Context, pdf: PickedPdf, stamps: List<StampText>, pages: Set<Int>?,
        startNumber: Int = 1, suffix: String = "stamped", progress: Progress = NoProgress
    ): File = io {
        val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        Workspace.loadForEdit(pdf).use { doc ->
            val total = doc.numberOfPages
            for (i in 0 until total) {
                if (pages != null && i !in pages) continue
                progress(i / total.toFloat(), "Page ${i + 1}")
                val page = doc.getPage(i)
                for (s in stamps) {
                    if (s.template.isBlank()) continue
                    val text = expand(s.template, i + startNumber, total + startNumber - 1, pdf.baseName, date)
                    drawStamp(doc, page, text, s)
                }
            }
            val f = Workspace.newOutput(context, pdf.baseName, suffix)
            doc.save(f); f
        }
    }

    suspend fun pageNumbers(
        context: Context, pdf: PickedPdf, format: String, h: HAlign, v: VAlign, size: Float,
        start: Int, skipFirst: Boolean, margin: Float
    ): File {
        val pages = (0 until pdf.pageCount).filter { !(skipFirst && it == 0) }.toSet()
        val tpl = when (format) {
            "n" -> "{page}"
            "page_n" -> "Page {page}"
            "n_of_total" -> "{page} / {total}"
            "page_n_of_total" -> "Page {page} of {total}"
            "dash" -> "- {page} -"
            else -> format
        }
        return headerFooter(context, pdf, listOf(StampText(tpl, h, v, size, margin)), pages, start, "numbered")
    }

    suspend fun bates(
        context: Context, pdf: PickedPdf, prefix: String, start: Int, digits: Int, suffix: String,
        h: HAlign, v: VAlign, size: Float
    ): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                val num = (start + i).toString().padStart(digits.coerceIn(1, 12), '0')
                drawStamp(doc, doc.getPage(i), "$prefix$num$suffix", StampText("", h, v, size, 24f, bold = true))
            }
            val f = Workspace.newOutput(context, pdf.baseName, "bates")
            doc.save(f); f
        }
    }

    data class WatermarkSpec(
        val text: String = "CONFIDENTIAL",
        val image: Bitmap? = null,
        val fontSize: Float = 60f,
        val opacity: Float = 0.2f,
        val angle: Float = 45f,
        val color: RGB = RGB(0.8f, 0.1f, 0.1f),
        val tiled: Boolean = false,
        val imageScale: Float = 0.5f
    )

    suspend fun watermark(context: Context, pdf: PickedPdf, spec: WatermarkSpec, pages: Set<Int>?, progress: Progress = NoProgress): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            val img = spec.image?.let { LosslessFactory.createFromImage(doc, it) }
            val font = PdfFonts.forText(doc, spec.text, bold = true)
            val text = PdfFonts.safe(font, spec.text)
            for (i in 0 until doc.numberOfPages) {
                if (pages != null && i !in pages) continue
                progress(i / doc.numberOfPages.toFloat(), "Page ${i + 1}")
                val page = doc.getPage(i)
                val g = PageGeometry(page)
                if (img != null) {
                    val w = g.displayW * spec.imageScale
                    val h = w * img.height / img.width
                    val positions = if (spec.tiled) tilePositions(g, w * 1.4f, h * 1.8f) else listOf(g.displayW / 2 to g.displayH / 2)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = spec.opacity })
                        for ((cx, cy) in positions) {
                            val (px, py) = g.toPdf(cx / g.displayW, cy / g.displayH)
                            val m = Matrix.getRotateInstance(Math.toRadians((g.rotation + spec.angle).toDouble()), px, py)
                            m.concatenate(Matrix.getTranslateInstance(-w / 2, -h / 2))
                            m.concatenate(Matrix.getScaleInstance(w, h))
                            cs.drawImage(img, m)
                        }
                    }
                } else if (text.isNotBlank()) {
                    val tw = PdfFonts.width(font, text, spec.fontSize)
                    val positions = if (spec.tiled) tilePositions(g, tw * 1.3f, spec.fontSize * 4f) else listOf(g.displayW / 2 to g.displayH / 2)
                    for ((cx, cy) in positions) {
                        // Offset so the text is centred on (cx, cy) along the rotated baseline.
                        val rad = Math.toRadians(spec.angle.toDouble())
                        val ox = (tw / 2 * Math.cos(rad) - spec.fontSize / 3 * Math.sin(rad)).toFloat()
                        val oy = (tw / 2 * Math.sin(rad) + spec.fontSize / 3 * Math.cos(rad)).toFloat()
                        drawDisplayText(doc, page, g, text, font, spec.fontSize, cx - ox, cy + oy, spec.angle, spec.color, spec.opacity)
                    }
                }
            }
            val f = Workspace.newOutput(context, pdf.baseName, "watermarked")
            doc.save(f); f
        }
    }

    private fun tilePositions(g: PageGeometry, stepX: Float, stepY: Float): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        var y = stepY / 2; var row = 0
        while (y < g.displayH + stepY) {
            var x = if (row % 2 == 0) stepX / 2 else 0f
            while (x < g.displayW + stepX) { out += x to y; x += stepX }
            y += stepY; row++
        }
        return out
    }

    // ---------- Bookmarks ----------

    fun readBookmarks(doc: PDDocument): List<BookmarkEntry> {
        val out = mutableListOf<BookmarkEntry>()
        val outline = doc.documentCatalog.documentOutline ?: return out
        fun walk(node: PDOutlineNode, level: Int) {
            for (item in node.children()) {
                val page = runCatching { item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } }.getOrNull() ?: -1
                out += BookmarkEntry(item.title ?: "(untitled)", page, level)
                walk(item, level + 1)
            }
        }
        walk(outline, 0)
        return out
    }

    fun readBookmarks(file: File): List<BookmarkEntry> =
        runCatching { Workspace.load(file).use { readBookmarks(it) } }.getOrDefault(emptyList())

    fun writeOutline(doc: PDDocument, entries: List<BookmarkEntry>) {
        if (entries.isEmpty()) { doc.documentCatalog.documentOutline = null; return }
        val outline = PDDocumentOutline()
        val stack = ArrayList<PDOutlineNode>().apply { add(outline) }
        for (e in entries) {
            val item = PDOutlineItem()
            item.title = e.title
            if (e.page in 0 until doc.numberOfPages) {
                item.destination = PDPageFitWidthDestination().apply { page = doc.getPage(e.page); top = doc.getPage(e.page).mediaBox.upperRightY.toInt() }
            }
            val level = e.level.coerceIn(0, stack.size - 1)
            while (stack.size > level + 1) stack.removeAt(stack.size - 1)
            stack.last().addLast(item)
            stack.add(item)
        }
        outline.openNode()
        doc.documentCatalog.documentOutline = outline
    }

    suspend fun saveBookmarks(context: Context, pdf: PickedPdf, entries: List<BookmarkEntry>): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            writeOutline(doc, entries)
            val f = Workspace.newOutput(context, pdf.baseName, if (entries.isEmpty()) "no_bookmarks" else "bookmarks")
            doc.save(f); f
        }
    }

    /** Inserts a clickable table-of-contents page at the start. */
    suspend fun addTocPage(context: Context, pdf: PickedPdf, entries: List<BookmarkEntry>, title: String, alsoBookmarks: Boolean): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            val first = doc.getPage(0)
            val size = PDRectangle(first.mediaBox.width, first.mediaBox.height)
            val lineH = 22f
            val perPage = ((size.height - 150f) / lineH).toInt().coerceAtLeast(5)
            val chunks = entries.chunked(perPage)
            val tocPages = chunks.indices.map { PDPage(size) }
            // Insert TOC pages first so the numbers we print already include them.
            tocPages.reversed().forEach { doc.pages.insertBefore(it, doc.getPage(0)) }
            val offset = tocPages.size
            val titleFont = PdfFonts.forText(doc, title, bold = true)
            chunks.forEachIndexed { ci, chunk ->
                val page = tocPages[ci]
                var y = size.height - 80f
                PDPageContentStream(doc, page).use { cs ->
                    if (ci == 0) {
                        cs.beginText(); cs.setFont(titleFont, 22f); cs.setNonStrokingColor(0.1f, 0.1f, 0.2f)
                        cs.newLineAtOffset(56f, y); cs.showText(PdfFonts.safe(titleFont, title)); cs.endText()
                        y -= 44f
                    } else y -= 10f
                    for (e in chunk) {
                        val target = e.page + offset
                        val font = PdfFonts.forText(doc, e.title, bold = e.level == 0)
                        val label = PdfFonts.safe(font, e.title).take(80)
                        val indent = 56f + e.level * 16f
                        val num = "${e.page + 1}"
                        cs.beginText(); cs.setFont(font, 12f); cs.setNonStrokingColor(0.1f, 0.1f, 0.1f)
                        cs.newLineAtOffset(indent, y); cs.showText(label); cs.endText()
                        val numW = PdfFonts.width(PDType1Font.HELVETICA, num, 12f)
                        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f)
                        cs.newLineAtOffset(size.width - 56f - numW, y); cs.showText(num); cs.endText()
                        // Dotted leader
                        val lw = PdfFonts.width(font, label, 12f)
                        cs.setStrokingColor(0.7f, 0.7f, 0.7f)
                        cs.setLineDashPattern(floatArrayOf(1f, 3f), 0f)
                        cs.moveTo(indent + lw + 6f, y + 2f); cs.lineTo(size.width - 62f - numW, y + 2f); cs.stroke()
                        cs.setLineDashPattern(floatArrayOf(), 0f)
                        if (target in 0 until doc.numberOfPages) {
                            val link = PDAnnotationLink()
                            link.rectangle = PDRectangle(indent, y - 5f, size.width - 56f - indent, lineH - 4f)
                            link.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
                            link.action = PDActionGoTo().apply {
                                destination = PDPageFitWidthDestination().apply { this.page = doc.getPage(target); top = size.height.toInt() }
                            }
                            page.annotations.add(link)
                        }
                        y -= lineH
                    }
                }
            }
            if (alsoBookmarks) writeOutline(doc, entries.map { it.copy(page = it.page + offset) })
            val f = Workspace.newOutput(context, pdf.baseName, "toc")
            doc.save(f); f
        }
    }

    // ---------- Page labels ----------

    data class LabelRange(val startPage: Int, val style: String, val prefix: String, val startNumber: Int)

    suspend fun pageLabels(context: Context, pdf: PickedPdf, ranges: List<LabelRange>): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            if (ranges.isEmpty()) doc.documentCatalog.pageLabels = null
            else {
                val labels = PDPageLabels(doc)
                for (r in ranges.sortedBy { it.startPage }) {
                    val range = PDPageLabelRange()
                    if (r.style.isNotEmpty()) range.style = r.style
                    if (r.prefix.isNotEmpty()) range.prefix = r.prefix
                    range.start = r.startNumber.coerceAtLeast(1)
                    labels.setLabelItem(r.startPage.coerceIn(0, doc.numberOfPages - 1), range)
                }
                doc.documentCatalog.pageLabels = labels
            }
            val f = Workspace.newOutput(context, pdf.baseName, "labeled")
            doc.save(f); f
        }
    }

    fun readPageLabels(file: File): List<String>? = runCatching {
        Workspace.load(file).use { doc -> doc.documentCatalog.pageLabels?.labelsByPageIndices?.toList() }
    }.getOrNull()

    // ---------- Metadata ----------

    data class Meta(
        val title: String = "", val author: String = "", val subject: String = "", val keywords: String = "",
        val creator: String = "", val producer: String = ""
    )

    fun readMeta(file: File): Meta = runCatching {
        Workspace.load(file).use { d ->
            val i = d.documentInformation
            Meta(i.title.orEmpty(), i.author.orEmpty(), i.subject.orEmpty(), i.keywords.orEmpty(), i.creator.orEmpty(), i.producer.orEmpty())
        }
    }.getOrDefault(Meta())

    suspend fun writeMeta(context: Context, pdf: PickedPdf, meta: Meta, stripAll: Boolean): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            if (stripAll) {
                doc.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
                doc.documentCatalog.metadata = null
            } else {
                doc.documentInformation.apply {
                    title = meta.title.ifBlank { null }; author = meta.author.ifBlank { null }
                    subject = meta.subject.ifBlank { null }; keywords = meta.keywords.ifBlank { null }
                    creator = meta.creator.ifBlank { null }; producer = meta.producer.ifBlank { null }
                    modificationDate = Calendar.getInstance()
                }
            }
            val f = Workspace.newOutput(context, pdf.baseName, if (stripAll) "clean" else "meta")
            doc.save(f); f
        }
    }

    /**
     * Writes [meta] into a copy of [pdf] at [out]. Stale XMP metadata is dropped (except on PDF/A files,
     * which require it) so every reader shows the new title and author.
     */
    suspend fun writeMetaTo(pdf: PickedPdf, meta: Meta, out: File): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            doc.documentInformation.apply {
                title = meta.title.trim().ifBlank { null }; author = meta.author.trim().ifBlank { null }
                subject = meta.subject.trim().ifBlank { null }; keywords = meta.keywords.trim().ifBlank { null }
                creator = meta.creator.trim().ifBlank { null }; producer = meta.producer.trim().ifBlank { null }
                modificationDate = Calendar.getInstance()
            }
            doc.documentCatalog.metadata?.let { xmp ->
                val pdfA = runCatching { String(xmp.toByteArray(), Charsets.UTF_8).contains("pdfaid") }.getOrDefault(true)
                if (!pdfA) doc.documentCatalog.metadata = null
            }
            doc.save(out)
            out
        }
    }
}
