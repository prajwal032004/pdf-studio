package com.example.ui.tools.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.*
import com.example.ui.common.*
import com.example.ui.tools.ToolCatalog

// ------------------------------------------------------------------ Split

@Composable
fun SplitScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(PageOps.SplitMode.RANGES) }
    var ranges by remember { mutableStateOf("") }
    var everyN by remember { mutableFloatStateOf(2f) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }

    PdfToolFrame(
        "split", initialUri, "Split PDF",
        canRun = { p ->
            when (mode) {
                PageOps.SplitMode.RANGES -> PageRanges.parseGroups(ranges, p.pageCount).isNotEmpty()
                PageOps.SplitMode.SELECTED -> selected.isNotEmpty()
                else -> p.pageCount > 1
            }
        },
        onPdfChanged = { p -> selected = emptySet(); ranges = if (p.pageCount > 1) "1-${(p.pageCount + 1) / 2}, ${(p.pageCount + 1) / 2 + 1}-${p.pageCount}" else "1" },
        onRun = { p ->
            runFiles("Split into parts") { prog -> PageOps.split(context, p, mode, ranges, everyN.toInt(), selected, prog) }
        }
    ) { p, r ->
        full("opts") {
            Section("How to split", Icons.Rounded.CallSplit) {
                ChoiceChips(
                    listOf(
                        PageOps.SplitMode.RANGES to "By ranges", PageOps.SplitMode.EVERY_N to "Every N pages",
                        PageOps.SplitMode.EACH_PAGE to "Each page", PageOps.SplitMode.SELECTED to "Pick pages"
                    ), mode, { mode = it }
                )
                when (mode) {
                    PageOps.SplitMode.RANGES -> {
                        OutlinedTextField(
                            ranges, { ranges = it }, Modifier.fillMaxWidth(), label = { Text("Ranges — one file per group") },
                            placeholder = { Text("1-3, 4-6, 7") }, singleLine = true, shape = RoundedCornerShape(14.dp)
                        )
                        val groups = PageRanges.parseGroups(ranges, p.pageCount)
                        Text(
                            if (groups.isEmpty()) "Enter page ranges like 1-3, 4-6" else "Creates ${groups.size} file(s): " +
                                groups.joinToString("  •  ") { PageRanges.format(it) },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PageOps.SplitMode.EVERY_N -> {
                        LabeledSlider("Pages per file", everyN, { everyN = it }, 1f..maxOf(2f, p.pageCount.toFloat()), valueText = "${everyN.toInt()}")
                        Text("Creates ${(p.pageCount + everyN.toInt() - 1) / everyN.toInt()} files", style = MaterialTheme.typography.bodySmall)
                    }
                    PageOps.SplitMode.EACH_PAGE -> Text("Creates ${p.pageCount} single-page PDFs.", style = MaterialTheme.typography.bodyMedium)
                    PageOps.SplitMode.SELECTED -> PageSelectionBar(selected, p.pageCount, { selected = it })
                }
            }
        }
        if (r != null && mode == PageOps.SplitMode.SELECTED) pageItems(r, selected, { selected = if (it in selected) selected - it else selected + it })
        else if (r != null) {
            val groups = when (mode) {
                PageOps.SplitMode.RANGES -> PageRanges.parseGroups(ranges, p.pageCount)
                PageOps.SplitMode.EVERY_N -> (0 until p.pageCount).chunked(everyN.toInt().coerceAtLeast(1))
                else -> (0 until p.pageCount).map { listOf(it) }
            }
            val partOf = HashMap<Int, Int>().apply { groups.forEachIndexed { gi, g -> g.forEach { put(it, gi) } } }
            pageItems(r, emptySet(), {}, labels = { i -> partOf[i]?.let { "${i + 1} · part ${it + 1}" } ?: "${i + 1} · skipped" },
                dimmed = (0 until p.pageCount).filter { it !in partOf }.toSet())
        }
    }
}

// ------------------------------------------------------------------ Extract

@Composable
fun ExtractPagesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var separate by remember { mutableStateOf(false) }
    PdfToolFrame(
        "extract_pages", initialUri, "Extract ${if (selected.isEmpty()) "" else "${selected.size} "}pages",
        canRun = { selected.isNotEmpty() }, onPdfChanged = { selected = emptySet() },
        onRun = { p ->
            if (separate) runFiles { prog ->
                val groups = selected.sorted().joinToString(",") { (it + 1).toString() }
                PageOps.split(context, p, PageOps.SplitMode.RANGES, ranges = groups, progress = prog)
            }
            else runFile { PageOps.extractPages(context, p, selected.toList()) }
        }
    ) { p, r ->
        full("opts") {
            Section {
                PageSelectionBar(selected, p.pageCount, { selected = it })
                SwitchRow("Separate files", "One PDF per selected page", separate) { separate = it }
            }
        }
        if (r != null) pageItems(r, selected, { selected = if (it in selected) selected - it else selected + it })
    }
}

// ------------------------------------------------------------------ Add blank pages

@Composable
fun AddPagesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var position by remember { mutableIntStateOf(-1) }
    var count by remember { mutableFloatStateOf(1f) }
    var size by remember { mutableStateOf(PageSize.FIT) }
    var landscape by remember { mutableStateOf(false) }
    PdfToolFrame(
        "add_pages", initialUri, "Insert ${count.toInt()} blank page${if (count.toInt() == 1) "" else "s"}",
        onPdfChanged = { position = it.pageCount },
        onRun = { p -> runFile { PageOps.addBlankPages(context, p, position.coerceIn(0, p.pageCount), count.toInt(), size, landscape) } }
    ) { p, r ->
        full("opts") {
            Section("Blank pages", Icons.Rounded.AddBox) {
                LabeledSlider("How many", count, { count = it }, 1f..20f, 18, "${count.toInt()}")
                Text("Page size", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(PageSize.entries.map { it to (if (it == PageSize.FIT) "Match neighbour" else it.label) }, size, { size = it })
                if (size != PageSize.FIT) SegmentedChoice(listOf(false to "Portrait", true to "Landscape"), landscape, { landscape = it })
                Text(
                    when (position) {
                        0 -> "Insert at the very beginning"
                        p.pageCount -> "Insert at the end"
                        else -> "Insert after page $position"
                    },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ position = 0 }, { Text("At start") })
                    AssistChip({ position = p.pageCount }, { Text("At end") })
                }
                Text("Or tap a page below to insert after it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (r != null) pageItems(r, if (position in 1..p.pageCount) setOf(position - 1) else emptySet(), { position = it + 1 },
            labels = { i -> if (i == position - 1) "${i + 1} ← insert after" else "${i + 1}" })
    }
}

// ------------------------------------------------------------------ Insert from another PDF

@Composable
fun InsertPagesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var source by remember { mutableStateOf<PickedPdf?>(null) }
    var srcRange by remember { mutableStateOf("") }
    var position by remember { mutableIntStateOf(0) }
    PdfToolFrame(
        "insert_pages", initialUri, "Insert pages",
        canRun = { source != null && PageRanges.parse(srcRange.ifBlank { "all" }, source!!.pageCount).isNotEmpty() },
        onPdfChanged = { position = it.pageCount },
        onRun = { p ->
            val s = source!!
            runFile { PageOps.insertFrom(context, p, s, PageRanges.parse(srcRange.ifBlank { "all" }, s.pageCount), position) }
        }
    ) { p, r ->
        full("src") {
            Section("Pages to insert", Icons.Rounded.PostAdd) {
                SecondaryPdfCard("Choose the PDF to take pages from", source) { source = it; srcRange = "" }
                source?.let { s ->
                    OutlinedTextField(srcRange, { srcRange = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
                        label = { Text("Pages from ${s.name}") }, placeholder = { Text("All pages (e.g. 1-3, 5)") })
                }
                Text(
                    if (position >= p.pageCount) "Insert at the end" else if (position == 0) "Insert at the beginning" else "Insert after page $position",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ position = 0 }, { Text("At start") }); AssistChip({ position = p.pageCount }, { Text("At end") })
                }
            }
        }
        if (r != null) pageItems(r, if (position in 1..p.pageCount) setOf(position - 1) else emptySet(), { position = it + 1 })
    }
}

// ------------------------------------------------------------------ Replace pages

@Composable
fun ReplacePagesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var targets by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var source by remember { mutableStateOf<PickedPdf?>(null) }
    var srcRange by remember { mutableStateOf("") }
    PdfToolFrame(
        "replace_pages", initialUri, "Replace ${targets.size} page(s)",
        canRun = { targets.isNotEmpty() && source != null },
        onPdfChanged = { targets = emptySet() },
        onRun = { p ->
            val s = source!!
            val srcPages = PageRanges.parse(srcRange.ifBlank { "1-${targets.size}" }, s.pageCount)
            runFile { PageOps.replacePages(context, p, targets.toList(), s, srcPages) }
        }
    ) { p, r ->
        full("src") {
            Section("Replacement", Icons.Rounded.FindReplace) {
                SecondaryPdfCard("Choose the PDF with replacement pages", source) { source = it }
                if (source != null) OutlinedTextField(srcRange, { srcRange = it }, Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(14.dp), label = { Text("Replacement pages (in order)") },
                    placeholder = { Text("1-${maxOf(1, targets.size)}") })
                InfoBanner("Select the pages to replace below. They're swapped in order with the replacement pages.")
                PageSelectionBar(targets, p.pageCount, { targets = it }, "Tap pages to replace")
            }
        }
        if (r != null) pageItems(r, targets, { targets = if (it in targets) targets - it else targets + it }, accent = com.example.ui.theme.Accents.red)
    }
}

// ------------------------------------------------------------------ Move pages between PDFs

@Composable
fun MovePagesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var dest by remember { mutableStateOf<PickedPdf?>(null) }
    var position by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf(true) }
    PdfToolFrame(
        "move_pages", initialUri, if (remove) "Move pages" else "Copy pages",
        canRun = { selected.isNotEmpty() && dest != null },
        onPdfChanged = { selected = emptySet() },
        onRun = { p ->
            val d = dest!!
            val pos = position.toIntOrNull()?.coerceIn(0, d.pageCount) ?: d.pageCount
            runFiles(if (remove) "Moved ${selected.size} page(s)" else "Copied ${selected.size} page(s)") {
                PageOps.movePages(context, p, selected.toList(), d, pos, remove)
            }
        }
    ) { p, r ->
        full("dest") {
            Section("Destination", Icons.Rounded.MoveUp) {
                SecondaryPdfCard("Choose the PDF to move pages into", dest) { dest = it; position = it.pageCount.toString() }
                dest?.let { d ->
                    NumberField("Insert after page (0 = start, ${d.pageCount} = end)", position, { position = it }, Modifier.fillMaxWidth())
                }
                SwitchRow("Remove from source", "Off = copy instead of move", remove) { remove = it }
                PageSelectionBar(selected, p.pageCount, { selected = it }, "Tap pages to move")
            }
        }
        if (r != null) pageItems(r, selected, { selected = if (it in selected) selected - it else selected + it })
    }
}

// ------------------------------------------------------------------ Merge

@Composable
fun MergeScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val tool = ToolCatalog.get("merge")
    val job = rememberJob("merge")
    val files = remember { mutableStateListOf<PickedPdf>() }
    var name by remember { mutableStateOf("Merged") }
    var preserve by remember { mutableStateOf(false) }
    val picker = rememberMultiPdfPicker { files.addAll(it) }
    LaunchedEffect(initialUri) {
        val shared = Incoming.take()
        when {
            shared.isNotEmpty() -> picker.loadMany(shared)
            initialUri != null && files.isEmpty() -> picker.load(initialUri)
        }
    }

    Scaffold(
        topBar = { ToolTopBar(tool, subtitle = if (files.isEmpty()) tool?.subtitle else "${files.size} files • ${files.sumOf { it.pageCount }} pages") },
        bottomBar = {
            if (files.isNotEmpty() && job.state !is JobState.Done) BottomAction(
                "Merge ${files.size} PDFs", files.size >= 2, Icons.Rounded.MergeType,
                secondary = {
                    FilledTonalButton({ picker.launch() }, Modifier.height(56.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add")
                    }
                }
            ) {
                val list = files.toList()
                job.runFile { prog -> if (preserve) PageOps.mergePreserving(context, list, name) else PageOps.merge(context, list, name, prog) }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val s = job.state
            when {
                s is JobState.Done -> ResultPanel(s.result, "merge") { job.reset(); files.clear() }
                files.isEmpty() -> EmptyPick(Icons.Rounded.MergeType, "Merge PDFs", "Pick two or more PDFs. You can reorder them before merging.",
                    "Choose PDFs", picker.loading, tool!!.category.accent) { picker.launch() }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Section("Output", Icons.Rounded.Tune) {
                            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("File name") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                            SwitchRow("Keep bookmarks & forms", "Uses the structure-preserving merger", preserve) { preserve = it }
                        }
                    }
                    itemsIndexed(files, key = { i, f -> f.file.path + i }) { i, f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.width(8.dp))
                            PdfSourceCard(f, null, Modifier.weight(1f))
                            Column {
                                IconButton({ if (i > 0) files.add(i - 1, files.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Up") }
                                IconButton({ if (i < files.size - 1) files.add(i + 1, files.removeAt(i)) }, enabled = i < files.size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "Down") }
                            }
                            IconButton({ files.removeAt(i) }) { Icon(Icons.Rounded.Close, "Remove") }
                        }
                    }
                    item {
                        OutlinedButton({ picker.launch() }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                            if (picker.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(8.dp)); Text("Add more PDFs")
                        }
                    }
                }
            }
            JobOverlay(job)
        }
    }
}

// ------------------------------------------------------------------ Blank PDF

@Composable
fun CreateBlankScreen() {
    val context = LocalContext.current
    val tool = ToolCatalog.get("create_blank")
    val job = rememberJob("create_blank")
    var name by remember { mutableStateOf("Untitled") }
    var count by remember { mutableFloatStateOf(1f) }
    var size by remember { mutableStateOf(PageSize.A4) }
    var landscape by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { ToolTopBar(tool) },
        bottomBar = { if (job.state !is JobState.Done) BottomAction("Create PDF", name.isNotBlank(), Icons.Rounded.NoteAdd) {
            job.runFile { PageOps.createBlank(context, name, count.toInt(), size, landscape) }
        } }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val s = job.state
            if (s is JobState.Done) ResultPanel(s.result, "create_blank") { job.reset() }
            else Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Section("New document", Icons.Rounded.NoteAdd) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    LabeledSlider("Pages", count, { count = it }, 1f..50f, 48, "${count.toInt()}")
                    Text("Size", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(PageSize.entries.filter { it != PageSize.FIT }.map { it to it.label }, size, { size = it })
                    SegmentedChoice(listOf(false to "Portrait", true to "Landscape"), landscape, { landscape = it })
                }
                // Visual size preview
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val r = size.rect(landscape)
                    Surface(
                        Modifier.fillMaxHeight(0.8f).aspectRatio(r.width / r.height), shape = RoundedCornerShape(6.dp),
                        shadowElevation = 8.dp, color = androidx.compose.ui.graphics.Color.White
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${size.label}\n${(r.width / 72 * 25.4).toInt()} × ${(r.height / 72 * 25.4).toInt()} mm",
                                style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.Gray,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Clip)
                        }
                    }
                }
            }
            JobOverlay(job)
        }
    }
}
