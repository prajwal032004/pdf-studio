package com.example.ui.tools.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.rounded.FormatIndentIncrease
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.*
import com.example.ui.common.*
import com.example.ui.theme.Accents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A page preview with an overlay laid out in the page's own coordinate box. */
@Composable
fun PagePreview(renderer: PageRenderer, index: Int, modifier: Modifier = Modifier, widthPx: Int = 900, overlay: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))) {
            PageImage(renderer, index, Modifier.fillMaxWidth(), widthPx = widthPx)
            Box(Modifier.matchParentSize(), content = overlay)
        }
    }
}

// ------------------------------------------------------------------ Bookmarks

@Composable
fun BookmarksScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val entries = remember { mutableStateListOf<BookmarkEntry>() }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var pageCount by remember { mutableIntStateOf(1) }
    PdfToolFrame("bookmarks", initialUri, if (entries.isEmpty() && loaded) "Remove all bookmarks" else "Save bookmarks", runIcon = Icons.Rounded.Bookmarks,
        canRun = { loaded },
        onPdfChanged = { p ->
            pageCount = p.pageCount
            entries.clear(); entries.addAll(OrganizeOps.readBookmarks(p.file)); loaded = true
        },
        onRun = { p -> val list = entries.toList(); runFile { OrganizeOps.saveBookmarks(context, p, list) } }
    ) { p, r ->
        full("head") {
            Section("Outline (${entries.size})", Icons.Rounded.Bookmarks) {
                if (entries.isEmpty()) Text("No bookmarks yet. Tap a page below or \"Add\" to create one.", style = MaterialTheme.typography.bodySmall)
                entries.forEachIndexed { i, e ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editing = i }.padding(start = (e.level * 18).dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (e.level == 0) Icons.Rounded.Bookmark else Icons.Rounded.SubdirectoryArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(e.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (e.level == 0) FontWeight.SemiBold else FontWeight.Normal)
                        Text(if (e.page >= 0) "p. ${e.page + 1}" else "—", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton({ entries[i] = e.copy(level = (e.level - 1).coerceAtLeast(0)) }, Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Rounded.FormatIndentDecrease, "Outdent", Modifier.size(18.dp)) }
                        IconButton({ entries[i] = e.copy(level = (e.level + 1).coerceAtMost(4)) }, Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Rounded.FormatIndentIncrease, "Indent", Modifier.size(18.dp)) }
                        IconButton({ entries.removeAt(i) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.DeleteOutline, "Delete", Modifier.size(18.dp)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ entries.add(BookmarkEntry("Chapter ${entries.size + 1}", 0)); editing = entries.size - 1 }, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add")
                    }
                    if (entries.isNotEmpty()) OutlinedButton({ entries.clear() }, shape = RoundedCornerShape(12.dp)) { Text("Remove all") }
                    OutlinedButton({
                        entries.clear(); entries.addAll((0 until p.pageCount).map { BookmarkEntry("Page ${it + 1}", it) })
                    }, shape = RoundedCornerShape(12.dp)) { Text("One per page") }
                }
            }
        }
        full("hint") { Text("Tap a page to bookmark it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (r != null) pageItems(r, entries.map { it.page }.toSet(), { i -> entries.add(BookmarkEntry("Page ${i + 1}", i)); editing = entries.size - 1 })
    }
    editing?.let { idx ->
        val e = entries.getOrNull(idx)
        if (e == null) editing = null
        else {
            var title by remember(idx) { mutableStateOf(e.title) }
            var page by remember(idx) { mutableStateOf((e.page + 1).toString()) }
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Bookmark") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                        NumberField("Page (1-$pageCount)", page, { page = it })
                    }
                },
                confirmButton = {
                    TextButton({
                        entries[idx] = e.copy(title = title.ifBlank { "Untitled" }, page = ((page.toIntOrNull() ?: 1) - 1).coerceIn(0, pageCount - 1)); editing = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton({ editing = null }) { Text("Cancel") } }
            )
        }
    }
}

// ------------------------------------------------------------------ Table of contents

@Composable
fun TocScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val entries = remember { mutableStateListOf<BookmarkEntry>() }
    var title by remember { mutableStateOf("Contents") }
    var alsoBookmarks by remember { mutableStateOf(true) }
    var newTitle by remember { mutableStateOf("") }
    var newPage by remember { mutableStateOf("") }
    var pc by remember { mutableIntStateOf(1) }
    PdfToolFrame("toc", initialUri, "Add contents page", runIcon = Icons.Rounded.List,
        canRun = { entries.isNotEmpty() },
        onPdfChanged = { p -> pc = p.pageCount; entries.clear(); entries.addAll(OrganizeOps.readBookmarks(p.file).filter { it.page >= 0 }) },
        onRun = { p -> val l = entries.toList(); runFile { OrganizeOps.addTocPage(context, p, l, title, alsoBookmarks) } }
    ) { _, _ ->
        full("toc") {
            Section("Entries", Icons.Rounded.List) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Heading") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                if (entries.isEmpty()) InfoBanner("No bookmarks found — add entries below. Each line links to its page.")
                entries.forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = (e.level * 14).dp)) {
                        Text(e.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${e.page + 1}", style = MaterialTheme.typography.labelLarge)
                        IconButton({ entries.removeAt(i) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newTitle, { newTitle = it }, Modifier.weight(1f), label = { Text("Title") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    NumberField("Page", newPage, { newPage = it }, width = 90.dp)
                    FilledIconButton({
                        val pg = newPage.toIntOrNull()
                        if (newTitle.isNotBlank() && pg != null && pg in 1..pc) { entries.add(BookmarkEntry(newTitle, pg - 1)); newTitle = ""; newPage = "" }
                    }) { Icon(Icons.Rounded.Add, null) }
                }
                SwitchRow("Also add as bookmarks", null, alsoBookmarks) { alsoBookmarks = it }
            }
        }
    }
}

// ------------------------------------------------------------------ Page labels

@Composable
fun PageLabelsScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val ranges = remember { mutableStateListOf<OrganizeOps.LabelRange>() }
    val styles = listOf("D" to "1, 2, 3", "r" to "i, ii, iii", "R" to "I, II, III", "a" to "a, b, c", "A" to "A, B, C", "" to "Prefix only")
    PdfToolFrame("page_labels", initialUri, "Apply labels", runIcon = Icons.Rounded.Label,
        onPdfChanged = { ranges.clear(); ranges.add(OrganizeOps.LabelRange(0, "D", "", 1)) },
        onRun = { p -> val l = ranges.toList(); runFile { OrganizeOps.pageLabels(context, p, l) } }
    ) { p, r ->
        fun labelFor(i: Int): String {
            val rg = ranges.filter { it.startPage <= i }.maxByOrNull { it.startPage } ?: return "${i + 1}"
            val n = rg.startNumber + (i - rg.startPage)
            val body = when (rg.style) {
                "D" -> "$n"; "r" -> roman(n).lowercase(); "R" -> roman(n); "a" -> letters(n).lowercase(); "A" -> letters(n); else -> ""
            }
            return rg.prefix + body
        }
        full("ranges") {
            Section("Label ranges", Icons.Rounded.Label) {
                ranges.forEachIndexed { i, rg ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField("From page", (rg.startPage + 1).toString(), { v -> ranges[i] = rg.copy(startPage = ((v.toIntOrNull() ?: 1) - 1).coerceIn(0, p.pageCount - 1)) }, Modifier.weight(1f))
                                NumberField("Start at", rg.startNumber.toString(), { v -> ranges[i] = rg.copy(startNumber = v.toIntOrNull() ?: 1) }, Modifier.weight(1f))
                                if (ranges.size > 1) IconButton({ ranges.removeAt(i) }) { Icon(Icons.Rounded.Close, null) }
                            }
                            ChoiceChips(styles, rg.style, { ranges[i] = rg.copy(style = it) })
                            OutlinedTextField(rg.prefix, { ranges[i] = rg.copy(prefix = it) }, Modifier.fillMaxWidth(), label = { Text("Prefix (e.g. A-)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                        }
                    }
                }
                TextButton({ ranges.add(OrganizeOps.LabelRange(minOf(p.pageCount - 1, (ranges.maxOfOrNull { it.startPage } ?: 0) + 1), "D", "", 1)) }) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add range")
                }
                InfoBanner("Labels show in the page box of PDF readers (e.g. front matter as i, ii, iii then 1, 2, 3).")
            }
        }
        if (r != null) pageItems(r, emptySet(), {}, labels = { labelFor(it) })
    }
}

private fun roman(n: Int): String {
    if (n <= 0) return "$n"
    val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    var x = n; val sb = StringBuilder()
    for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
    return sb.toString()
}

private fun letters(n: Int): String {
    if (n <= 0) return "$n"
    val c = ('A' + (n - 1) % 26)
    return c.toString().repeat((n - 1) / 26 + 1)
}

// ------------------------------------------------------------------ Header / footer / page numbers / bates

@Composable
private fun PositionPicker(h: HAlign, v: VAlign, onChange: (HAlign, VAlign) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (vv in VAlign.entries) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (hh in HAlign.entries) {
                val sel = hh == h && vv == v
                Box(
                    Modifier.size(width = 56.dp, height = 32.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onChange(hh, vv) },
                    contentAlignment = when (hh) { HAlign.LEFT -> Alignment.CenterStart; HAlign.CENTER -> Alignment.Center; HAlign.RIGHT -> Alignment.CenterEnd }
                ) {
                    Box(Modifier.padding(horizontal = 8.dp).size(16.dp, 4.dp).background(if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

/** Shows where stamped text will land on the first page. */
@Composable
private fun StampPreview(renderer: PageRenderer?, texts: List<Pair<StampText, String>>) {
    if (renderer == null) return
    PagePreview(renderer, 0, Modifier.fillMaxWidth().height(320.dp), 700) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val (wPt, _) = renderer.pageSize(0)
            val scale = maxWidth.value / wPt
            texts.forEach { (s, t) ->
                if (t.isBlank()) return@forEach
                Text(
                    t, fontSize = (s.fontSize * scale).sp, color = Color(s.color.r, s.color.g, s.color.b),
                    fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                    modifier = Modifier.align(
                        when (s.v to s.h) {
                            VAlign.TOP to HAlign.LEFT -> Alignment.TopStart; VAlign.TOP to HAlign.CENTER -> Alignment.TopCenter
                            VAlign.TOP to HAlign.RIGHT -> Alignment.TopEnd; VAlign.BOTTOM to HAlign.LEFT -> Alignment.BottomStart
                            VAlign.BOTTOM to HAlign.CENTER -> Alignment.BottomCenter; else -> Alignment.BottomEnd
                        }
                    ).padding(horizontal = (s.margin * scale).dp, vertical = (s.margin * scale * 0.7f).dp)
                )
            }
        }
    }
}

@Composable
fun HeaderFooterScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var header by remember { mutableStateOf("{file}") }
    var footer by remember { mutableStateOf("Page {page} of {total}") }
    var hAlignH by remember { mutableStateOf(HAlign.CENTER) }
    var hAlignF by remember { mutableStateOf(HAlign.CENTER) }
    var size by remember { mutableFloatStateOf(9f) }
    var color by remember { mutableIntStateOf(0xFF1B1B1F.toInt()) }
    var range by remember { mutableStateOf("") }
    PdfToolFrame("header_footer", initialUri, "Apply header & footer", runIcon = Icons.Rounded.VerticalAlignTop,
        canRun = { header.isNotBlank() || footer.isNotBlank() },
        onRun = { p ->
            val stamps = listOf(StampText(header, hAlignH, VAlign.TOP, size, 28f, RGB.of(color)), StampText(footer, hAlignF, VAlign.BOTTOM, size, 28f, RGB.of(color)))
            val pages = if (range.isBlank()) null else PageRanges.parse(range, p.pageCount).toSet()
            runFile { prog -> OrganizeOps.headerFooter(context, p, stamps, pages, progress = prog) }
        }
    ) { p, r ->
        val sample = { t: String -> OrganizeOps.expand(t, 1, p.pageCount, p.baseName, java.text.SimpleDateFormat("dd MMM yyyy").format(java.util.Date())) }
        full("prev") {
            StampPreview(r, listOf(
                StampText(header, hAlignH, VAlign.TOP, size, 28f, RGB.of(color)) to sample(header),
                StampText(footer, hAlignF, VAlign.BOTTOM, size, 28f, RGB.of(color)) to sample(footer)
            ))
        }
        full("opts") {
            Section("Text", Icons.Rounded.TextFields) {
                OutlinedTextField(header, { header = it }, Modifier.fillMaxWidth(), label = { Text("Header") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                SegmentedChoice(HAlign.entries.map { it to it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, hAlignH, { hAlignH = it })
                OutlinedTextField(footer, { footer = it }, Modifier.fillMaxWidth(), label = { Text("Footer") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                SegmentedChoice(HAlign.entries.map { it to it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, hAlignF, { hAlignF = it })
                Text("Tokens: {page} {total} {date} {file}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LabeledSlider("Font size", size, { size = it }, 6f..24f, valueText = "${size.toInt()} pt")
                ColorRow(color, { color = it })
                OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), label = { Text("Pages (empty = all)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            }
        }
    }
}

@Composable
fun PageNumbersScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var format by remember { mutableStateOf("n_of_total") }
    var h by remember { mutableStateOf(HAlign.CENTER) }
    var v by remember { mutableStateOf(VAlign.BOTTOM) }
    var size by remember { mutableFloatStateOf(10f) }
    var start by remember { mutableStateOf("1") }
    var skipFirst by remember { mutableStateOf(false) }
    var margin by remember { mutableFloatStateOf(28f) }
    val formats = listOf("n" to "1", "page_n" to "Page 1", "n_of_total" to "1 / 9", "page_n_of_total" to "Page 1 of 9", "dash" to "- 1 -")
    PdfToolFrame("page_numbers", initialUri, "Add page numbers", runIcon = Icons.Rounded.FormatListNumbered,
        onRun = { p -> runFile { OrganizeOps.pageNumbers(context, p, format, h, v, size, start.toIntOrNull() ?: 1, skipFirst, margin) } }
    ) { p, r ->
        val tpl = when (format) { "n" -> "{page}"; "page_n" -> "Page {page}"; "n_of_total" -> "{page} / {total}"; "page_n_of_total" -> "Page {page} of {total}"; else -> "- {page} -" }
        val first = start.toIntOrNull() ?: 1
        full("prev") { StampPreview(r, listOf(StampText(tpl, h, v, size, margin) to OrganizeOps.expand(tpl, first, p.pageCount + first - 1, "", ""))) }
        full("opts") {
            Section("Numbering", Icons.Rounded.FormatListNumbered) {
                ChoiceChips(formats, format, { format = it })
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PositionPicker(h, v) { a, b -> h = a; v = b }
                    Column(Modifier.weight(1f)) {
                        NumberField("Start number", start, { start = it }, Modifier.fillMaxWidth())
                        SwitchRow("Skip first page", null, skipFirst) { skipFirst = it }
                    }
                }
                LabeledSlider("Font size", size, { size = it }, 6f..28f, valueText = "${size.toInt()} pt")
                LabeledSlider("Margin", margin, { margin = it }, 10f..72f, valueText = "${margin.toInt()} pt")
            }
        }
    }
}

@Composable
fun BatesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var prefix by remember { mutableStateOf("ABC") }
    var suffix by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("1") }
    var digits by remember { mutableFloatStateOf(6f) }
    var h by remember { mutableStateOf(HAlign.RIGHT) }
    var v by remember { mutableStateOf(VAlign.BOTTOM) }
    var size by remember { mutableFloatStateOf(10f) }
    PdfToolFrame("bates", initialUri, "Apply Bates numbers", runIcon = Icons.Rounded.Tag,
        onRun = { p -> runFile { OrganizeOps.bates(context, p, prefix, start.toIntOrNull() ?: 1, digits.toInt(), suffix, h, v, size) } }
    ) { p, r ->
        val first = "$prefix${(start.toIntOrNull() ?: 1).toString().padStart(digits.toInt(), '0')}$suffix"
        full("prev") { StampPreview(r, listOf(StampText("", h, v, size, 24f, bold = true) to first)) }
        full("o") {
            Section("Bates numbering", Icons.Rounded.Tag) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(prefix, { prefix = it }, Modifier.weight(1f), label = { Text("Prefix") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(suffix, { suffix = it }, Modifier.weight(1f), label = { Text("Suffix") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PositionPicker(h, v) { a, b -> h = a; v = b }
                    NumberField("Start", start, { start = it }, Modifier.weight(1f))
                }
                LabeledSlider("Digits", digits, { digits = it }, 1f..10f, 8, "${digits.toInt()}")
                LabeledSlider("Font size", size, { size = it }, 6f..20f, valueText = "${size.toInt()} pt")
                Text("Range: $first → $prefix${((start.toIntOrNull() ?: 1) + p.pageCount - 1).toString().padStart(digits.toInt(), '0')}$suffix",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ------------------------------------------------------------------ Watermark

@Composable
fun WatermarkScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var imageMode by remember { mutableStateOf(false) }
    var spec by remember { mutableStateOf(OrganizeOps.WatermarkSpec()) }
    var color by remember { mutableIntStateOf(0xFFE53935.toInt()) }
    var range by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) image = ConvertOps.decodeImage(context, uri, 1600)
    }
    PdfToolFrame("watermark", initialUri, "Add watermark", runIcon = Icons.Rounded.BrandingWatermark,
        canRun = { if (imageMode) image != null else spec.text.isNotBlank() },
        onRun = { p ->
            val s = spec.copy(color = RGB.of(color), image = if (imageMode) image else null)
            val pages = if (range.isBlank()) null else PageRanges.parse(range, p.pageCount).toSet()
            runFile { prog -> OrganizeOps.watermark(context, p, s, pages, prog) }
        }
    ) { _, r ->
        full("prev") {
            if (r != null) PagePreview(r, 0, Modifier.fillMaxWidth().height(340.dp), 700) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val (wPt, _) = r.pageSize(0)
                    val scale = maxWidth.value / wPt
                    val mw = maxWidth
                    val positions = if (spec.tiled) listOf(-0.3f to -0.3f, 0.3f to -0.1f, -0.3f to 0.1f, 0.3f to 0.3f, 0f to 0f) else listOf(0f to 0f)
                    positions.forEach { (dx, dy) ->
                        Box(Modifier.offset(x = maxWidth * dx, y = maxHeight * dy).rotate(-spec.angle)) {
                            val img = image
                            if (imageMode && img != null) androidx.compose.foundation.Image(
                                img.asImageBitmap(), null, alpha = spec.opacity,
                                modifier = Modifier.width(mw * spec.imageScale)
                            ) else if (!imageMode) Text(spec.text, fontSize = (spec.fontSize * scale).sp, fontWeight = FontWeight.Bold,
                                color = Color(color).copy(alpha = spec.opacity), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
        full("opts") {
            Section("Watermark", Icons.Rounded.BrandingWatermark) {
                SegmentedChoice(listOf(false to "Text", true to "Image"), imageMode, { imageMode = it })
                if (!imageMode) {
                    OutlinedTextField(spec.text, { spec = spec.copy(text = it) }, Modifier.fillMaxWidth(), label = { Text("Text") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("CONFIDENTIAL", "DRAFT", "COPY", "SAMPLE", "APPROVED").forEach { t -> SuggestionChip({ spec = spec.copy(text = t) }, { Text(t, fontSize = 11.sp) }) }
                    }
                    LabeledSlider("Size", spec.fontSize, { spec = spec.copy(fontSize = it) }, 16f..140f, valueText = "${spec.fontSize.toInt()} pt")
                    ColorRow(color, { color = it })
                } else {
                    OutlinedButton({ imgPicker.launch("image/*") }, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(8.dp)); Text(if (image == null) "Choose logo / image" else "Change image")
                    }
                    LabeledSlider("Width", spec.imageScale * 100, { spec = spec.copy(imageScale = it / 100) }, 10f..100f, valueText = "${(spec.imageScale * 100).toInt()}% of page")
                }
                LabeledSlider("Opacity", spec.opacity * 100, { spec = spec.copy(opacity = it / 100) }, 5f..100f, valueText = "${(spec.opacity * 100).toInt()}%")
                LabeledSlider("Angle", spec.angle, { spec = spec.copy(angle = it) }, -90f..90f, valueText = "${spec.angle.toInt()}°")
                SwitchRow("Tile across page", null, spec.tiled) { spec = spec.copy(tiled = it) }
                OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), label = { Text("Pages (empty = all)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ Search tools

@Composable
fun SearchScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var matchCase by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<TextHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(0) }
    var color by remember { mutableIntStateOf(0xFFFDD835.toInt()) }
    var file by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(query, regex, matchCase, file) {
        val f = file ?: return@LaunchedEffect
        if (query.length < 2) { hits = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        searching = true
        hits = runCatching { TextOps.search(f, query, regex, matchCase) }.getOrDefault(emptyList())
        current = 0; searching = false
    }
    PdfToolFrame("search", initialUri, "Save ${hits?.size ?: 0} highlight(s)", runIcon = Icons.Rounded.BorderColor,
        canRun = { !hits.isNullOrEmpty() },
        onPdfChanged = { file = it.file; hits = null },
        onRun = { p ->
            val marks = hits.orEmpty().map { Mark.Markup(it.page, MarkupKind.HIGHLIGHT, it.rects, color) }
            runFile { prog -> AnnotOps.apply(context, p, marks, emptyMap(), "PDF Studio", false, prog) }
        }
    ) { p, r ->
        full("q") {
            Section {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search in ${p.name}") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(16.dp),
                    trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, null) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(matchCase, { matchCase = !matchCase }, { Text("Match case") })
                    FilterChip(regex, { regex = !regex }, { Text("Regex") })
                }
                hits?.let { h ->
                    Text(if (h.isEmpty()) "No matches" else "${h.size} match(es) on ${h.map { it.page }.distinct().size} page(s)",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (h.isNotEmpty()) { Text("Highlight colour", style = MaterialTheme.typography.labelMedium); ColorRow(color, { color = it }, listOf(0xFFFDD835.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(), 0xFFF48FB1.toInt(), 0xFFFFB74D.toInt())) }
                }
            }
        }
        val h = hits.orEmpty()
        if (h.isNotEmpty() && r != null) {
            val hit = h[current.coerceIn(0, h.size - 1)]
            full("page") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ current = (current - 1 + h.size) % h.size }) { Icon(Icons.Rounded.ChevronLeft, "Previous") }
                        Text("${current + 1} / ${h.size} · page ${hit.page + 1}", style = MaterialTheme.typography.labelLarge)
                        IconButton({ current = (current + 1) % h.size }) { Icon(Icons.Rounded.ChevronRight, "Next") }
                    }
                    PagePreview(r, hit.page, Modifier.fillMaxWidth(), 1000) {
                        Canvas(Modifier.fillMaxSize()) {
                            h.filter { it.page == hit.page }.forEach { x ->
                                x.rects.forEach { rc ->
                                    drawRect(Color(color).copy(alpha = if (x === hit) 0.55f else 0.3f),
                                        androidx.compose.ui.geometry.Offset(rc.left * size.width, rc.top * size.height),
                                        androidx.compose.ui.geometry.Size(rc.width * size.width, rc.height * size.height))
                                }
                            }
                        }
                    }
                }
            }
            items(h.size.coerceAtMost(200), span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { i ->
                val x = h[i]
                Surface(onClick = { current = i }, shape = RoundedCornerShape(12.dp),
                    color = if (i == current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("p.${x.page + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(44.dp))
                        Text("…${x.snippet}…", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun WordCountScreen(initialUri: Uri?) {
    var counts by remember { mutableStateOf<TextOps.Counts?>(null) }
    var file by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(file) { file?.let { counts = null; counts = runCatching { TextOps.counts(it) }.getOrNull() } }
    val context = LocalContext.current
    PdfToolFrame("word_count", initialUri, "Export text", runIcon = Icons.Rounded.TextSnippet,
        onPdfChanged = { file = it.file },
        onRun = { p -> run { ConvertOps.pdfToText(context, p, false) } }
    ) { _, _ ->
        full("c") {
            val c = counts
            if (c == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatPill("%,d".format(c.words), "Words", Modifier.weight(1f), Accents.blue)
                    StatPill("%,d".format(c.chars), "Characters", Modifier.weight(1f), Accents.violet)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatPill("%,d".format(c.charsNoSpaces), "No spaces", Modifier.weight(1f), Accents.teal)
                    StatPill("${c.pages}", "Pages", Modifier.weight(1f), Accents.orange)
                    StatPill("%,d".format(c.lines), "Lines", Modifier.weight(1f), Accents.pink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatPill("${(c.words / 230.0).let { if (it < 1) "<1" else "%.0f".format(it) }} min", "Reading time", Modifier.weight(1f), Accents.green)
                    StatPill(if (c.pages > 0) "${c.words / c.pages}" else "0", "Words / page", Modifier.weight(1f), Accents.indigo)
                }
                if (c.words == 0) InfoBanner("No text found — this may be a scanned PDF. Try OCR first.", Icons.Rounded.Info, Accents.orange)
                if (c.perPage.isNotEmpty()) Section("Words per page", Icons.Rounded.BarChart) {
                    val max = (c.perPage.maxOrNull() ?: 1).coerceAtLeast(1)
                    c.perPage.take(60).forEachIndexed { i, w ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall)
                            Box(Modifier.weight(1f).height(10.dp)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(w / max.toFloat()).clip(RoundedCornerShape(5.dp)).background(Accents.blue))
                            }
                            Text("$w", Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReplaceTextScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<Int?>(null) }
    var file by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(find, matchCase, file) {
        val f = file ?: return@LaunchedEffect
        if (find.isEmpty()) { preview = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(400)
        preview = runCatching { TextOps.search(f, find, false, matchCase).size }.getOrNull()
    }
    PdfToolFrame("replace_text", initialUri, "Replace all", runIcon = Icons.Rounded.FindReplace,
        canRun = { find.isNotEmpty() },
        onPdfChanged = { file = it.file },
        onRun = { p -> run { TextOps.replace(context, p, find, replacement, matchCase) } }
    ) { _, _ ->
        full("f") {
            Section("Find & replace", Icons.Rounded.FindReplace) {
                OutlinedTextField(find, { find = it }, Modifier.fillMaxWidth(), label = { Text("Find") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(replacement, { replacement = it }, Modifier.fillMaxWidth(), label = { Text("Replace with") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                SwitchRow("Match case", null, matchCase) { matchCase = it }
                preview?.let { Text("$it occurrence(s) found in the text layer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                InfoBanner("Works where the PDF stores text as editable strings with a font that contains the new characters. Some PDFs split or encode text so it can't be changed in place — you'll see how many replacements succeeded.", Icons.Rounded.Info, Accents.orange)
            }
        }
    }
}
