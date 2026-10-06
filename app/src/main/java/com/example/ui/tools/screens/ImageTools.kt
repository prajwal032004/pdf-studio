package com.example.ui.tools.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.*
import com.example.ui.common.*
import com.example.ui.theme.Accents
import com.example.ui.tools.ToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ------------------------------------------------------------------ Crop

@Composable
fun CropScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var auto by remember { mutableStateOf(false) }
    var l by remember { mutableFloatStateOf(0.05f) }
    var t by remember { mutableFloatStateOf(0.05f) }
    var r by remember { mutableFloatStateOf(0.05f) }
    var b by remember { mutableFloatStateOf(0.05f) }
    var previewPage by remember { mutableIntStateOf(0) }
    var range by remember { mutableStateOf("") }
    var hard by remember { mutableStateOf(false) }
    var padding by remember { mutableFloatStateOf(0.02f) }
    PdfToolFrame("crop", initialUri, if (auto) "Auto-crop pages" else "Crop pages", runIcon = Icons.Rounded.Crop,
        onRun = { p ->
            if (auto) runFile { prog -> ImageOps.autoCrop(context, p, padding, prog) }
            else runFile { ImageOps.crop(context, p, l, t, r, b, if (range.isBlank()) null else PageRanges.parse(range, p.pageCount).toSet(), hard) }
        }
    ) { p, rend ->
        full("mode") {
            Section("Crop mode", Icons.Rounded.Crop) {
                SegmentedChoice(listOf(false to "Manual", true to "Auto (trim whitespace)"), auto, { auto = it })
                if (auto) {
                    LabeledSlider("Padding", padding * 100, { padding = it / 100 }, 0f..10f, valueText = "${"%.1f".format(padding * 100)}%")
                    InfoBanner("Each page is analysed and trimmed to its content — ideal for scanned documents with uneven borders.")
                } else {
                    Text("Drag the edges on the preview. Margins: L ${(l * 100).toInt()}%  T ${(t * 100).toInt()}%  R ${(r * 100).toInt()}%  B ${(b * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ l = 0.05f; t = 0.05f; r = 0.05f; b = 0.05f }, { Text("5% all") })
                        AssistChip({ l = 0.1f; t = 0.1f; r = 0.1f; b = 0.1f }, { Text("10% all") })
                        AssistChip({ l = 0f; t = 0f; r = 0f; b = 0f }, { Text("Reset") })
                    }
                    OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), label = { Text("Pages (empty = all)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    SwitchRow("Hard crop", "Also shrink the media box (removes hidden area for all readers)", hard) { hard = it }
                }
            }
        }
        if (rend != null) full("preview") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (p.pageCount > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ previewPage = (previewPage - 1).coerceAtLeast(0) }) { Icon(Icons.Rounded.ChevronLeft, null) }
                    Text("Preview page ${previewPage + 1}", style = MaterialTheme.typography.labelLarge)
                    IconButton({ previewPage = (previewPage + 1).coerceAtMost(p.pageCount - 1) }) { Icon(Icons.Rounded.ChevronRight, null) }
                }
                PagePreview(rend, previewPage, Modifier.fillMaxWidth(0.9f), 900) {
                    if (!auto) CropOverlay(l, t, r, b) { nl, nt, nr, nb -> l = nl; t = nt; r = nr; b = nb }
                }
            }
        }
    }
}

@Composable
private fun CropOverlay(l: Float, t: Float, r: Float, b: Float, onChange: (Float, Float, Float, Float) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    var edge by remember { mutableIntStateOf(-1) }
    val cur by rememberUpdatedState(listOf(l, t, r, b))
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { pos ->
                    val (cl, ct, cr, cb) = cur
                    val x = pos.x / size.width; val y = pos.y / size.height
                    val d = listOf(abs(x - cl), abs(y - ct), abs(x - (1 - cr)), abs(y - (1 - cb)))
                    val min = d.indices.minBy { d[it] }
                    edge = if (d[min] < 0.12f) min else 4 // 4 = move the whole box
                },
                onDrag = { change, amount ->
                    change.consume()
                    val dx = amount.x / size.width; val dy = amount.y / size.height
                    var (nl, nt, nr, nb) = cur
                    when (edge) {
                        0 -> nl = (nl + dx).coerceIn(0f, 0.9f - nr)
                        1 -> nt = (nt + dy).coerceIn(0f, 0.9f - nb)
                        2 -> nr = (nr - dx).coerceIn(0f, 0.9f - nl)
                        3 -> nb = (nb - dy).coerceIn(0f, 0.9f - nt)
                        4 -> {
                            val mx = dx.coerceIn(-nl, nr); val my = dy.coerceIn(-nt, nb)
                            nl += mx; nr -= mx; nt += my; nb -= my
                        }
                    }
                    onChange(nl, nt, nr, nb)
                },
                onDragEnd = { edge = -1 }
            )
        }
    ) {
        val x0 = l * size.width; val y0 = t * size.height
        val x1 = (1 - r) * size.width; val y1 = (1 - b) * size.height
        val shade = Color.Black.copy(alpha = 0.45f)
        drawRect(shade, Offset.Zero, Size(size.width, y0))
        drawRect(shade, Offset(0f, y1), Size(size.width, size.height - y1))
        drawRect(shade, Offset(0f, y0), Size(x0, y1 - y0))
        drawRect(shade, Offset(x1, y0), Size(size.width - x1, y1 - y0))
        drawRect(accent, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = Stroke(3f))
        for (k in 1..2) {
            val gx = x0 + (x1 - x0) * k / 3; val gy = y0 + (y1 - y0) * k / 3
            drawLine(Color.White.copy(alpha = 0.5f), Offset(gx, y0), Offset(gx, y1), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x0, gy), Offset(x1, gy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        val hs = 26f
        listOf(Offset((x0 + x1) / 2, y0), Offset((x0 + x1) / 2, y1), Offset(x0, (y0 + y1) / 2), Offset(x1, (y0 + y1) / 2)).forEach { c ->
            drawRoundRect(accent, Offset(c.x - hs / 2, c.y - hs / 2), Size(hs, hs), androidx.compose.ui.geometry.CornerRadius(8f))
        }
    }
}

// ------------------------------------------------------------------ Resize

@Composable
fun ResizeScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(PageSize.A4) }
    var custom by remember { mutableStateOf(false) }
    var w by remember { mutableStateOf("210") }
    var h by remember { mutableStateOf("297") }
    var keep by remember { mutableStateOf(true) }
    var margin by remember { mutableFloatStateOf(0f) }
    PdfToolFrame("resize", initialUri, "Resize pages", runIcon = Icons.Rounded.AspectRatio,
        onRun = { p ->
            val cw = if (custom) (w.toFloatOrNull() ?: 210f) / 25.4f * 72f else null
            val ch = if (custom) (h.toFloatOrNull() ?: 297f) / 25.4f * 72f else null
            runFile { prog -> ImageOps.resize(context, p, size, cw, ch, keep, margin, prog) }
        }
    ) { _, r ->
        full("o") {
            Section("Target page size", Icons.Rounded.AspectRatio) {
                SegmentedChoice(listOf(false to "Standard", true to "Custom (mm)"), custom, { custom = it })
                if (!custom) ChoiceChips(PageSize.entries.filter { it != PageSize.FIT }.map { it to it.label }, size, { size = it })
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Width mm", w, { w = it }, Modifier.weight(1f)); NumberField("Height mm", h, { h = it }, Modifier.weight(1f))
                }
                SwitchRow("Keep each page's orientation", "Landscape pages stay landscape", keep) { keep = it }
                LabeledSlider("Margin", margin, { margin = it }, 0f..72f, valueText = "${margin.toInt()} pt")
                InfoBanner("Content is scaled proportionally and centred — nothing is cut off.")
            }
        }
        if (r != null) pageItems(r, emptySet(), {})
    }
}

// ------------------------------------------------------------------ Grayscale

@Composable
fun GrayscaleScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var full by remember { mutableStateOf(false) }
    var dpi by remember { mutableFloatStateOf(200f) }
    PdfToolFrame("grayscale", initialUri, "Convert to grayscale", runIcon = Icons.Rounded.Contrast,
        onRun = { p -> runFile { prog -> ImageOps.grayscale(context, p, full, dpi.toInt(), prog) } }
    ) { _, r ->
        full("o") {
            Section("Method", Icons.Rounded.Contrast) {
                SegmentedChoice(listOf(false to "Images only", true to "Entire page"), full, { full = it })
                Text(if (full) "Every element becomes gray, including coloured text and drawings. Pages are re-rendered as images."
                    else "Photos and scans turn gray; text stays sharp and selectable.", style = MaterialTheme.typography.bodySmall)
                if (full) LabeledSlider("Resolution", dpi, { dpi = it }, 100f..300f, valueText = "${dpi.toInt()} DPI")
            }
        }
        if (r != null) {
            full("cap") { Text("Before and after", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(minOf(r.pageCount, 4) * 2) { k ->
                val i = k / 2
                if (k % 2 == 0) PageImage(r, i, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                else {
                    var gray by remember(i) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(i) { gray = ThumbCache.get(r, i, 360)?.let { withContext(Dispatchers.Default) { Bitmaps.grayscale(it) } } }
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                        gray?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                            ?: CircularProgressIndicator(Modifier.padding(24.dp).size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Embedded images

@Composable
fun ManageImagesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refs by remember { mutableStateOf<List<ImageRef>?>(null) }
    var target by remember { mutableStateOf<ImageRef?>(null) }
    var pdfRef by remember { mutableStateOf<PickedPdf?>(null) }
    val job = rememberJob("manage_images")
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val t = target; val p = pdfRef
        if (uri != null && t != null && p != null) {
            val bmp = ConvertOps.decodeImage(context, uri, 2400)
            if (bmp != null) job.runFile { ImageOps.replaceImage(context, p, t, bmp) }
        }
    }
    val tool = ToolCatalog.get("manage_images")
    PdfToolFrame("manage_images", initialUri, "Extract all images", runIcon = Icons.Rounded.Download,
        canRun = { !refs.isNullOrEmpty() },
        onPdfChanged = { p -> pdfRef = p; refs = null; scope.launch { refs = withContext(Dispatchers.IO) { ImageOps.listImages(p.file) } } },
        onRun = { p -> runFiles { prog -> ImageOps.extractImages(context, p, prog) } }
    ) { p, _ ->
        full("h") {
            val list = refs
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                list.isEmpty() -> InfoBanner("No embedded images in this PDF.")
                else -> Text("${list.size} image(s) found. Tap an image to replace or remove it.", style = MaterialTheme.typography.titleSmall)
            }
        }
        refs?.let { list ->
            items(list.size) { i ->
                val ref = list[i]
                var bmp by remember(ref) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(ref) { bmp = withContext(Dispatchers.IO) { ImageOps.imageBitmap(p.file, ref, 300) } }
                var menu by remember { mutableStateOf(false) }
                Surface(onClick = { menu = true }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(6.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEEEEEE)), contentAlignment = Alignment.Center) {
                            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                                ?: CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Text("p.${ref.page + 1} · ${ref.width}×${ref.height}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Workspace.formatSize(ref.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Replace…") }, { menu = false; target = ref; replacePicker.launch("image/*") }, leadingIcon = { Icon(Icons.Rounded.FindReplace, null) })
                        DropdownMenuItem({ Text("Remove") }, { menu = false; job.runFile { ImageOps.replaceImage(context, p, ref, null) } }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                    }
                }
            }
        }
    }
    // Replace/remove runs as a secondary job with its own result screen.
    val s = job.state
    if (s is JobState.Done || s is JobState.Running || s is JobState.Failed) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = { ToolTopBar(tool) }) { pad ->
                Box(Modifier.padding(pad)) {
                    if (s is JobState.Done) ResultPanel(s.result, "manage_images") { job.reset() }
                    JobOverlay(job)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Basics: properties / metadata / print / repair

@Composable
fun PropertiesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val nav = LocalNav.current
    var info by remember { mutableStateOf<DocInfo?>(null) }
    var picked by remember { mutableStateOf<PickedPdf?>(null) }
    var editing by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf(OrganizeOps.Meta()) }
    var toOriginal by remember { mutableStateOf(false) }
    var canOverwrite by remember { mutableStateOf(false) }
    LaunchedEffect(picked) {
        val p = picked ?: return@LaunchedEffect
        info = null
        info = runCatching { AnalysisOps.info(p) }.getOrNull()
        info?.let { meta = it.meta }
        canOverwrite = !p.wasEncrypted && Workspace.canOverwrite(context, p.uri)
        toOriginal = canOverwrite
    }
    val original = info?.meta
    val changed = original != null && meta != original
    PdfToolFrame(
        "properties", initialUri,
        if (!editing) "Edit properties" else if (toOriginal) "Save to original file" else "Save as new copy",
        runIcon = if (editing) Icons.Rounded.Save else Icons.Rounded.EditNote,
        canRun = { !editing || changed },
        onPdfChanged = { picked = it; editing = false },
        onRun = { p ->
            if (!editing) editing = true
            else {
                val m = meta
                val inPlace = toOriginal && canOverwrite
                run { prog ->
                    prog(0.2f, "Writing properties…")
                    val result = if (inPlace) {
                        val tmp = Workspace.temp(context, "pdf")
                        try {
                            OrganizeOps.writeMetaTo(p, m, tmp)
                            prog(0.7f, "Saving to the original file…")
                            Workspace.overwrite(context, p.uri, tmp)
                        } finally { tmp.delete() }
                        ToolResult(
                            emptyList(), "Properties saved to the original file",
                            listOf("Title" to m.title.ifBlank { "—" }, "Author" to m.author.ifBlank { "—" }), openUri = p.uri
                        )
                    } else {
                        val out = Workspace.newOutput(context, p.baseName, "edited")
                        OrganizeOps.writeMetaTo(p, m, out)
                        ToolResult(listOf(out), "Saved a copy with the new properties")
                    }
                    info = info?.copy(meta = m)
                    editing = false
                    result
                }
            }
        }
    ) { p, _ ->
        full("i") {
            val i = info
            if (i == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatPill("${i.pages}", "Pages", Modifier.weight(1f), Accents.blue)
                    StatPill(Workspace.formatSize(i.fileSize), "Size", Modifier.weight(1f), Accents.green)
                    StatPill("PDF ${i.version}", "Version", Modifier.weight(1f), Accents.violet)
                }
                if (editing) Section("Edit document properties", Icons.Rounded.EditNote) {
                    listOf(
                        "Title" to meta.title, "Author" to meta.author, "Subject" to meta.subject,
                        "Keywords" to meta.keywords, "Creator app" to meta.creator, "Producer" to meta.producer
                    ).forEach { (label, value) ->
                        OutlinedTextField(value, { v ->
                            meta = when (label) {
                                "Title" -> meta.copy(title = v); "Author" -> meta.copy(author = v); "Subject" -> meta.copy(subject = v)
                                "Keywords" -> meta.copy(keywords = v); "Creator app" -> meta.copy(creator = v); else -> meta.copy(producer = v)
                            }
                        }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = label != "Keywords" && label != "Subject",
                            shape = RoundedCornerShape(14.dp),
                            trailingIcon = if (value.isNotEmpty()) ({
                                IconButton({
                                    meta = when (label) {
                                        "Title" -> meta.copy(title = ""); "Author" -> meta.copy(author = ""); "Subject" -> meta.copy(subject = "")
                                        "Keywords" -> meta.copy(keywords = ""); "Creator app" -> meta.copy(creator = ""); else -> meta.copy(producer = "")
                                    }
                                }) { Icon(Icons.Rounded.Close, "Clear $label") }
                            }) else null
                        )
                    }
                    Text("Save to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (canOverwrite) SegmentedChoice(listOf(true to "Original file", false to "New copy"), toOriginal, { toOriginal = it })
                    else InfoBanner(
                        if (p.wasEncrypted) "This PDF is password protected, so a new unprotected copy will be saved. Re-protect it afterwards if needed."
                        else "The original is in a read-only location, so a new copy will be saved in PDF Studio's folder.",
                        Icons.Rounded.Info
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ meta = original ?: meta; editing = false }) { Text("Cancel") }
                        TextButton({ meta = original ?: meta }, enabled = changed) { Text("Reset changes") }
                    }
                } else Section("Document", Icons.Rounded.Description) {
                    InfoRow("Title", i.meta.title); InfoRow("Author", i.meta.author); InfoRow("Subject", i.meta.subject)
                    InfoRow("Keywords", i.meta.keywords); InfoRow("Creator", i.meta.creator); InfoRow("Producer", i.meta.producer)
                    InfoRow("Created", i.created); InfoRow("Modified", i.modified)
                    FilledTonalButton({ editing = true }, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit")
                    }
                }
                Section("Structure", Icons.Rounded.AccountTree) {
                    InfoRow("Page sizes", i.pageSizes.joinToString("\n"))
                    InfoRow("Images", "${i.images}"); InfoRow("Annotations", "${i.annotations}"); InfoRow("Form fields", "${i.formFields}")
                    InfoRow("Bookmarks", "${i.bookmarks}"); InfoRow("Attachments", "${i.attachments}"); InfoRow("Digital signatures", "${i.signatures}")
                    InfoRow("Tagged (accessible)", if (i.tagged) "Yes" else "No"); InfoRow("JavaScript", if (i.hasJavaScript) "Present" else "None")
                    InfoRow("Encryption", if (i.encrypted) "Password protected" else "None"); InfoRow("PDF/A", i.pdfaClaim ?: "Not declared")
                }
                Section("Fonts (${i.fonts.size})", Icons.Rounded.FontDownload) {
                    if (i.fonts.isEmpty()) Text("No fonts — likely a scanned document", style = MaterialTheme.typography.bodySmall)
                    i.fonts.take(40).forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (f.embedded) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = if (f.embedded) Accents.green else Accents.orange)
                            Spacer(Modifier.width(8.dp))
                            Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.type + if (f.embedded) "" else " · not embedded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ nav.openFile(p.file) }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Rounded.MenuBook, null); Spacer(Modifier.width(6.dp)); Text("Open") }
                    OutlinedButton({ PrintHelper.printFile(context, p.file, p.baseName) }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Rounded.Print, null); Spacer(Modifier.width(6.dp)); Text("Print") }
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MetadataScreen(toolId: String, initialUri: Uri?) {
    val context = LocalContext.current
    var meta by remember { mutableStateOf(OrganizeOps.Meta()) }
    var strip by remember { mutableStateOf(toolId == "remove_metadata") }
    PdfToolFrame(toolId, initialUri, if (strip) "Remove all metadata" else "Save metadata", runIcon = if (strip) Icons.Rounded.CleaningServices else Icons.Rounded.Save,
        onPdfChanged = { meta = OrganizeOps.readMeta(it.file) },
        onRun = { p -> runFile { OrganizeOps.writeMeta(context, p, meta, strip) } }
    ) { _, _ ->
        full("m") {
            Section(if (strip) "Privacy clean-up" else "Document properties", if (strip) Icons.Rounded.CleaningServices else Icons.Rounded.EditNote) {
                SwitchRow("Strip everything", "Removes title, author, dates, software info and XMP metadata", strip) { strip = it }
                if (!strip) {
                    listOf(
                        "Title" to meta.title, "Author" to meta.author, "Subject" to meta.subject,
                        "Keywords" to meta.keywords, "Creator app" to meta.creator, "Producer" to meta.producer
                    ).forEach { (label, value) ->
                        OutlinedTextField(value, { v ->
                            meta = when (label) {
                                "Title" -> meta.copy(title = v); "Author" -> meta.copy(author = v); "Subject" -> meta.copy(subject = v)
                                "Keywords" -> meta.copy(keywords = v); "Creator app" -> meta.copy(creator = v); else -> meta.copy(producer = v)
                            }
                        }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    }
                } else {
                    Text("Current: ${listOf(meta.title, meta.author, meta.creator, meta.producer).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "no metadata found" }}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun PrintScreen(initialUri: Uri?) {
    val context = LocalContext.current
    PdfToolFrame("print", initialUri, "Print", runIcon = Icons.Rounded.Print,
        onRun = { p -> PrintHelper.printFile(context, p.file, p.baseName) }
    ) { _, r ->
        full("i") { InfoBanner("Opens Android's print dialog — pick a printer, copies, page range and paper size, or \"Save as PDF\".", Icons.Rounded.Print) }
        if (r != null) pageItems(r, emptySet(), {})
    }
}

@Composable
fun RepairScreen() {
    val context = LocalContext.current
    val tool = ToolCatalog.get("repair")
    val job = rememberJob("repair")
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) job.run { prog -> AnalysisOps.repair(context, uri, Workspace.displayName(context, uri), prog) }
    }
    Scaffold(topBar = { ToolTopBar(tool) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val s = job.state
            if (s is JobState.Done) ResultPanel(s.result, "repair") { job.reset() }
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                EmptyPick(Icons.Rounded.Healing, "Repair a damaged PDF",
                    "Rebuilds broken cross-reference tables and recovers pages from truncated or corrupted files. If the structure can't be saved, pages are recovered visually.",
                    "Choose damaged file", false, Accents.slate) { picker.launch(arrayOf("application/pdf", "application/octet-stream", "*/*")) }
            }
            JobOverlay(job)
        }
    }
}
