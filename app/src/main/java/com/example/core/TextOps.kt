package com.example.core

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringWriter

/** A rectangle in normalised display coordinates (0..1, origin top-left). */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun union(o: NRect) = NRect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
    fun intersects(o: NRect) = left < o.right && right > o.left && top < o.bottom && bottom > o.top
}

data class TextHit(val page: Int, val rects: List<NRect>, val snippet: String, val match: String)

data class WordBox(val text: String, val rect: NRect)

object PrintHelper {
    fun printFile(activityContext: Context, file: File, jobName: String) {
        val pm = activityContext.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback, extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(
                    PrintDocumentInfo.Builder(jobName).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true
                )
            }

            override fun onWrite(
                pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    file.inputStream().use { i -> ParcelFileDescriptor.AutoCloseOutputStream(destination).use { o -> i.copyTo(o) } }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }, null)
    }
}

/** Collects every glyph with its position so searches can be highlighted on the rendered page. */
private class PositionStripper(private val doc: PDDocument) : PDFTextStripper() {
    val chars = StringBuilder()
    val boxes = ArrayList<NRect?>()
    var pageIndex = 0
    private var dispW = 1f
    private var dispH = 1f

    init { sortByPosition = true }

    fun runPage(index: Int) {
        pageIndex = index
        chars.clear(); boxes.clear()
        val g = PageGeometry(doc.getPage(index))
        dispW = g.displayW; dispH = g.displayH
        startPage = index + 1; endPage = index + 1
        writeText(doc, StringWriter())
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        for (tp in textPositions) {
            val u = tp.unicode ?: continue
            val h = tp.heightDir.coerceAtLeast(tp.fontSizeInPt * 0.7f)
            val r = NRect(
                tp.xDirAdj / dispW, (tp.yDirAdj - h * 1.1f) / dispH,
                (tp.xDirAdj + tp.widthDirAdj) / dispW, (tp.yDirAdj + h * 0.28f) / dispH
            )
            for (c in u) { chars.append(c); boxes.add(r) }
        }
    }

    override fun writeWordSeparator() { chars.append(' '); boxes.add(null) }
    override fun writeLineSeparator() { chars.append('\n'); boxes.add(null) }
}

object TextOps {

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun rectsFor(boxes: List<NRect?>, from: Int, to: Int): List<NRect> {
        // Merge glyph boxes into one rect per text line.
        val out = mutableListOf<NRect>()
        var cur: NRect? = null
        for (i in from until to) {
            val b = boxes.getOrNull(i) ?: continue
            cur = when {
                cur == null -> b
                kotlin.math.abs(b.top - cur.top) < cur.height * 0.5f -> cur.union(b)
                else -> { out += cur; b }
            }
        }
        cur?.let { out += it }
        return out
    }

    suspend fun search(
        file: File, query: String, regex: Boolean = false, matchCase: Boolean = false,
        progress: Progress = NoProgress, pageFilter: Set<Int>? = null
    ): List<TextHit> = io {
        if (query.isBlank()) return@io emptyList()
        val pattern = if (regex) Regex(query, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
        else Regex(Regex.escape(query), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
        val hits = mutableListOf<TextHit>()
        Workspace.load(file).use { doc ->
            val s = PositionStripper(doc)
            for (p in 0 until doc.numberOfPages) {
                if (pageFilter != null && p !in pageFilter) continue
                progress(p / doc.numberOfPages.toFloat(), "Searching page ${p + 1}")
                if (runCatching { s.runPage(p) }.isFailure) continue
                val text = s.chars.toString()
                for (m in pattern.findAll(text)) {
                    if (m.value.isEmpty()) continue
                    val rects = rectsFor(s.boxes, m.range.first, m.range.last + 1)
                    if (rects.isEmpty()) continue
                    val a = (m.range.first - 30).coerceAtLeast(0)
                    val b = (m.range.last + 31).coerceAtMost(text.length)
                    hits += TextHit(p, rects, text.substring(a, b).replace('\n', ' '), m.value)
                }
            }
        }
        hits
    }

    /** Word boxes for one page — used to snap highlight/underline selections to text. */
    fun words(file: File, page: Int): List<WordBox> = runCatching {
        Workspace.load(file).use { doc ->
            val s = PositionStripper(doc)
            s.runPage(page)
            val text = s.chars.toString()
            Regex("\\S+").findAll(text).mapNotNull { m ->
                rectsFor(s.boxes, m.range.first, m.range.last + 1).firstOrNull()?.let { WordBox(m.value, it) }
            }.toList()
        }
    }.getOrDefault(emptyList())

    data class Counts(val pages: Int, val words: Int, val chars: Int, val charsNoSpaces: Int, val lines: Int, val perPage: List<Int>)

    suspend fun counts(file: File): Counts = io {
        Workspace.load(file).use { doc ->
            val st = PDFTextStripper()
            val perPage = (1..doc.numberOfPages).map { p ->
                st.startPage = p; st.endPage = p
                st.getText(doc).split(Regex("\\s+")).count { it.isNotBlank() }
            }
            st.startPage = 1; st.endPage = doc.numberOfPages
            val all = st.getText(doc)
            Counts(doc.numberOfPages, perPage.sum(), all.replace("\r", "").replace("\n", "").length,
                all.count { !it.isWhitespace() }, all.lines().count { it.isNotBlank() }, perPage)
        }
    }

    /**
     * Replaces text that is stored as plain strings in the page content. PDFs that split words
     * into positioned glyphs or use subset fonts may not match — the count tells the user.
     */
    suspend fun replace(context: Context, pdf: PickedPdf, find: String, replacement: String, matchCase: Boolean): ToolResult = io {
        require(find.isNotEmpty()) { "Enter the text to find" }
        var count = 0
        Workspace.loadForEdit(pdf).use { doc ->
            for (page in doc.pages) {
                val parser = PDFStreamParser(page)
                parser.parse()
                val tokens = parser.tokens
                var changed = false
                fun fix(str: COSString): COSString? {
                    val s = str.string
                    if (!s.contains(find, !matchCase)) return null
                    count += Regex(Regex.escape(find), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)).findAll(s).count()
                    return COSString(s.replace(find, replacement, !matchCase))
                }
                for (i in tokens.indices) {
                    val op = tokens[i] as? Operator ?: continue
                    when (op.name) {
                        "Tj", "'", "\"" -> {
                            val prev = tokens.getOrNull(i - 1) as? COSString ?: continue
                            fix(prev)?.let { tokens[i - 1] = it; changed = true }
                        }
                        "TJ" -> {
                            val arr = tokens.getOrNull(i - 1) as? COSArray ?: continue
                            for (j in 0 until arr.size()) {
                                val el = arr.get(j) as? COSString ?: continue
                                fix(el)?.let { arr.set(j, it); changed = true }
                            }
                        }
                    }
                }
                if (changed) {
                    val stream = PDStream(doc)
                    stream.createOutputStream(com.tom_roush.pdfbox.cos.COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
                    page.setContents(stream)
                }
            }
            val f = Workspace.newOutput(context, pdf.baseName, "replaced_text")
            doc.save(f)
            ToolResult(
                listOf(f),
                if (count == 0) "No editable matches found. This PDF's text may be encoded in a way that can't be edited directly."
                else "Replaced $count occurrence(s)",
                listOf("Replacements" to count.toString())
            )
        }
    }

    // ---------------- Sensitive information ----------------

    enum class Sensitive(val label: String, val pattern: String) {
        EMAIL("Email addresses", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        PHONE("Phone numbers", "(?<!\\d)(?:\\+?\\d{1,3}[\\s-]?)?(?:\\(?\\d{3,5}\\)?[\\s-]?)\\d{3,4}[\\s-]?\\d{3,4}(?!\\d)"),
        CARD("Card numbers", "(?<!\\d)(?:\\d{4}[\\s-]?){3}\\d{4}(?!\\d)"),
        AADHAAR("Aadhaar numbers", "(?<!\\d)\\d{4}\\s\\d{4}\\s\\d{4}(?!\\d)"),
        PAN("PAN numbers", "\\b[A-Z]{5}\\d{4}[A-Z]\\b"),
        SSN("US SSN", "\\b\\d{3}-\\d{2}-\\d{4}\\b"),
        IBAN("IBAN", "\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b"),
        DATE("Dates", "\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\\b"),
        URL("Web links", "https?://\\S+"),
        IP("IP addresses", "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    }

    suspend fun detectSensitive(file: File, kinds: Set<Sensitive>, progress: Progress = NoProgress): List<Pair<Sensitive, TextHit>> {
        val out = mutableListOf<Pair<Sensitive, TextHit>>()
        kinds.forEachIndexed { i, k ->
            progress(i / kinds.size.toFloat(), "Looking for ${k.label.lowercase()}")
            search(file, k.pattern, regex = true, matchCase = true).forEach { out += k to it }
        }
        return out
    }
}
