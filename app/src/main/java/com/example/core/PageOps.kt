package com.example.core

import android.content.Context
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class PageSize(val label: String, val rect: PDRectangle?) {
    A4("A4", PDRectangle.A4),
    LETTER("Letter", PDRectangle.LETTER),
    LEGAL("Legal", PDRectangle.LEGAL),
    A3("A3", PDRectangle.A3),
    A5("A5", PDRectangle.A5),
    FIT("Fit content", null);

    fun rect(landscape: Boolean): PDRectangle {
        val r = rect ?: PDRectangle.A4
        return if (landscape) PDRectangle(r.height, r.width) else PDRectangle(r.width, r.height)
    }
}

/** Page-level document operations. Every function writes a new file and never touches the source. */
object PageOps {

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Deep-copies a page (including resources) into [target]. */
    fun clonePage(target: PDDocument, page: PDPage): PDPage {
        val imported = target.importPage(page)
        imported.rotation = page.rotation
        imported.mediaBox = page.mediaBox
        imported.cropBox = page.cropBox
        return imported
    }

    /** Builds a document made of [order] (indices into source, repeats allowed) and applies per-page rotation deltas. */
    suspend fun rebuild(
        context: Context, pdf: PickedPdf, order: List<Int>, rotations: Map<Int, Int> = emptyMap(),
        suffix: String, progress: Progress = NoProgress
    ): File = io {
        Workspace.loadForEdit(pdf).use { src ->
            PDDocument().use { out ->
                order.forEachIndexed { i, idx ->
                    progress(i / order.size.toFloat(), "Page ${i + 1} of ${order.size}")
                    val page = clonePage(out, src.getPage(idx))
                    rotations[i]?.let { page.rotation = ((page.rotation + it) % 360 + 360) % 360 }
                }
                copyInfo(src, out)
                val f = Workspace.newOutput(context, pdf.baseName, suffix)
                out.save(f)
                f
            }
        }
    }

    fun copyInfo(src: PDDocument, out: PDDocument) {
        runCatching {
            val i = src.documentInformation
            out.documentInformation.apply {
                title = i.title; author = i.author; subject = i.subject; keywords = i.keywords
                creator = i.creator; producer = "PDF Studio"
            }
        }
    }

    suspend fun deletePages(context: Context, pdf: PickedPdf, remove: Set<Int>): File {
        require(remove.size < pdf.pageCount) { "You can't delete every page" }
        val keep = (0 until pdf.pageCount).filterNot { it in remove }
        return rebuild(context, pdf, keep, suffix = "edited")
    }

    suspend fun duplicatePages(context: Context, pdf: PickedPdf, dup: Set<Int>, copies: Int = 1, atEnd: Boolean = false): File {
        val order = mutableListOf<Int>()
        for (i in 0 until pdf.pageCount) {
            order += i
            if (!atEnd && i in dup) repeat(copies) { order += i }
        }
        if (atEnd) dup.sorted().forEach { i -> repeat(copies) { order += i } }
        return rebuild(context, pdf, order, suffix = "duplicated")
    }

    suspend fun rotatePages(context: Context, pdf: PickedPdf, pages: Set<Int>, degrees: Int): File =
        rebuild(context, pdf, (0 until pdf.pageCount).toList(),
            (0 until pdf.pageCount).filter { it in pages }.associateWith { degrees }, "rotated")

    suspend fun extractPages(context: Context, pdf: PickedPdf, pages: List<Int>): File =
        rebuild(context, pdf, pages.sorted(), suffix = "extracted")

    /** Inserts [count] blank pages before [position] (0 = start, pageCount = end). */
    suspend fun addBlankPages(
        context: Context, pdf: PickedPdf, position: Int, count: Int, size: PageSize, landscape: Boolean
    ): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            val ref = if (doc.numberOfPages > 0) doc.getPage((position - 1).coerceIn(0, doc.numberOfPages - 1)) else null
            val rect = if (size == PageSize.FIT && ref != null) ref.mediaBox else size.rect(landscape)
            repeat(count) {
                val blank = PDPage(PDRectangle(rect.width, rect.height))
                val pos = position.coerceIn(0, doc.numberOfPages)
                if (pos >= doc.numberOfPages) doc.addPage(blank)
                else doc.pages.insertBefore(blank, doc.getPage(pos))
            }
            val f = Workspace.newOutput(context, pdf.baseName, "pages_added")
            doc.save(f); f
        }
    }

    suspend fun createBlank(context: Context, name: String, count: Int, size: PageSize, landscape: Boolean): File = io {
        PDDocument().use { doc ->
            val r = size.rect(landscape)
            repeat(count.coerceIn(1, 500)) { doc.addPage(PDPage(PDRectangle(r.width, r.height))) }
            doc.documentInformation.title = name
            doc.documentInformation.producer = "PDF Studio"
            val f = Workspace.newOutput(context, name.ifBlank { "Blank" }, Workspace.stamp())
            doc.save(f); f
        }
    }

    suspend fun merge(context: Context, pdfs: List<PickedPdf>, name: String, progress: Progress = NoProgress): File = io {
        val out = Workspace.newOutput(context, name.ifBlank { "Merged" }, Workspace.stamp())
        PDDocument().use { dest ->
            pdfs.forEachIndexed { i, p ->
                progress(i / pdfs.size.toFloat(), "Adding ${p.name}")
                Workspace.loadForEdit(p).use { src ->
                    for (pg in src.pages) clonePage(dest, pg)
                }
            }
            dest.documentInformation.title = name
            dest.documentInformation.producer = "PDF Studio"
            dest.save(out)
        }
        out
    }

    /** Merges keeping bookmarks/forms via PDFBox's merger utility. */
    suspend fun mergePreserving(context: Context, pdfs: List<PickedPdf>, name: String): File = io {
        val out = Workspace.newOutput(context, name.ifBlank { "Merged" }, Workspace.stamp())
        val merger = PDFMergerUtility()
        pdfs.forEach { merger.addSource(it.file) }
        merger.destinationFileName = out.absolutePath
        merger.mergeDocuments(Workspace.memory())
        out
    }

    enum class SplitMode { RANGES, EVERY_N, EACH_PAGE, SELECTED }

    suspend fun split(
        context: Context, pdf: PickedPdf, mode: SplitMode, ranges: String = "", n: Int = 1,
        selected: Set<Int> = emptySet(), progress: Progress = NoProgress
    ): List<File> = io {
        val groups: List<List<Int>> = when (mode) {
            SplitMode.RANGES -> PageRanges.parseGroups(ranges, pdf.pageCount)
            SplitMode.EVERY_N -> (0 until pdf.pageCount).chunked(n.coerceAtLeast(1))
            SplitMode.EACH_PAGE -> (0 until pdf.pageCount).map { listOf(it) }
            SplitMode.SELECTED -> listOf(selected.sorted())
        }.filter { it.isNotEmpty() }
        require(groups.isNotEmpty()) { "No pages match those ranges" }
        Workspace.loadForEdit(pdf).use { src ->
            groups.mapIndexed { gi, pages ->
                progress(gi / groups.size.toFloat(), "Part ${gi + 1} of ${groups.size}")
                PDDocument().use { out ->
                    pages.forEach { clonePage(out, src.getPage(it)) }
                    val label = if (pages.size == 1) "p${pages[0] + 1}" else "p${pages.first() + 1}-${pages.last() + 1}"
                    val f = Workspace.newOutput(context, pdf.baseName, label)
                    out.save(f); f
                }
            }
        }
    }

    /** Inserts pages from [source] into [target] before [position]. */
    suspend fun insertFrom(
        context: Context, target: PickedPdf, source: PickedPdf, sourcePages: List<Int>, position: Int
    ): File = io {
        Workspace.loadForEdit(target).use { dest ->
            Workspace.loadForEdit(source).use { src ->
                val imported = sourcePages.map { clonePage(dest, src.getPage(it)) }
                // importPage appends; move the imported pages to the requested position.
                imported.forEach { dest.pages.remove(it) }
                var anchor = if (position < dest.numberOfPages) dest.getPage(position) else null
                for (p in imported) {
                    if (anchor == null) dest.addPage(p) else dest.pages.insertBefore(p, anchor)
                }
                val f = Workspace.newOutput(context, target.baseName, "inserted")
                dest.save(f); f
            }
        }
    }

    /** Replaces target pages (in order) with source pages (in order). */
    suspend fun replacePages(
        context: Context, target: PickedPdf, targetPages: List<Int>, source: PickedPdf, sourcePages: List<Int>
    ): File = io {
        require(targetPages.isNotEmpty() && sourcePages.isNotEmpty()) { "Select pages on both sides" }
        Workspace.loadForEdit(target).use { dest ->
            Workspace.loadForEdit(source).use { src ->
                val originals = targetPages.sorted().map { dest.getPage(it) }
                val replacements = sourcePages.map { clonePage(dest, src.getPage(it)) }
                replacements.forEach { dest.pages.remove(it) }
                originals.forEachIndexed { i, old ->
                    val rep = replacements.getOrNull(i)
                    if (rep != null) dest.pages.insertBefore(rep, old)
                }
                // Extra replacement pages go after the last replaced page.
                if (replacements.size > originals.size) {
                    var after = originals.last()
                    for (rep in replacements.drop(originals.size)) { dest.pages.insertAfter(rep, after); after = rep }
                }
                originals.forEach { dest.pages.remove(it) }
                val f = Workspace.newOutput(context, target.baseName, "replaced")
                dest.save(f); f
            }
        }
    }

    /** Moves (or copies) pages from A into B at [position]; returns updated B and, when moving, updated A. */
    suspend fun movePages(
        context: Context, from: PickedPdf, pages: List<Int>, to: PickedPdf, position: Int, removeFromSource: Boolean
    ): List<File> {
        val b = insertFrom(context, to, from, pages.sorted(), position)
        if (!removeFromSource || pages.size >= from.pageCount) return listOf(b)
        val a = rebuild(context, from, (0 until from.pageCount).filterNot { it in pages }, suffix = "moved_out")
        return listOf(b, a)
    }

    /** Pages in a PDF, handy for tools needing a quick count without a picker. */
    fun pageCount(file: File): Int = runCatching { Workspace.load(file).use { it.numberOfPages } }.getOrDefault(0)
}
