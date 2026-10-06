package com.example.ui.tools.screens

import android.graphics.Bitmap
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.core.*
import com.example.ui.common.*
import com.example.ui.tools.ToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Content handed to the app from the share sheet or "Open with". */
object Incoming {
    var uris: List<Uri> = emptyList()
    var text: String? = null
    var mime: String? = null
    fun take(): List<Uri> = uris.also { uris = emptyList() }
    fun takeText(): String? = text.also { text = null }
}

// ------------------------------------------------------------------ Images → PDF

@Composable
fun ImagesToPdfScreen(initial: List<Uri> = emptyList()) {
    val context = LocalContext.current
    val tool = ToolCatalog.get("images_to_pdf")
    val job = rememberJob("images_to_pdf")
    val items = remember { mutableStateListOf<ImageItem>().apply { addAll(initial.map { ImageItem(it) }) } }
    var opt by remember { mutableStateOf(ImagesToPdfOptions()) }
    var name by remember { mutableStateOf("Images_${Workspace.stamp()}") }
    var showOptions by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> items.addAll(uris.map { ImageItem(it) }) }

    Scaffold(
        topBar = {
            ToolTopBar(tool, subtitle = if (items.isEmpty()) tool?.subtitle else "${items.size} image(s)") {
                if (items.isNotEmpty()) IconButton({ showOptions = true }) { Icon(Icons.Rounded.Tune, "Options") }
            }
        },
        bottomBar = {
            if (items.isNotEmpty() && job.state !is JobState.Done) BottomAction(
                "Create PDF", true, Icons.Rounded.PictureAsPdf,
                secondary = { FilledTonalButton({ pick.launch("image/*") }, Modifier.height(56.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.Add, null) } }
            ) {
                val list = items.toList()
                job.runFile { prog -> ConvertOps.imagesToPdf(context, list, name, opt, prog) }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val s = job.state
            when {
                s is JobState.Done -> ResultPanel(s.result, "images_to_pdf") { job.reset(); items.clear() }
                items.isEmpty() -> EmptyPick(Icons.Rounded.Collections, "Images to PDF", "Pick photos, scans or screenshots. Reorder and rotate them, then create one PDF.",
                    "Choose images", false, tool!!.category.accent) { pick.launch("image/*") }
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    full("name") {
                        Section {
                            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("PDF name") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                            Text(
                                "${opt.pageSize.label} • ${listOf("Auto", "Portrait", "Landscape")[opt.orientation]} • margin ${opt.marginPt.toInt()} pt • quality ${opt.quality}%" + if (opt.grayscale) " • grayscale" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton({ showOptions = true }) { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Page & quality options") }
                        }
                    }
                    items(items.size, key = { items[it].uri.toString() + it }) { i ->
                        ImageTile(items[i], i, opt,
                            onRotate = { items[i] = items[i].copy(rotation = (items[i].rotation + 90) % 360) },
                            onLeft = { if (i > 0) items.add(i - 1, items.removeAt(i)) },
                            onRemove = { items.removeAt(i) })
                    }
                }
            }
            JobOverlay(job)
        }
    }
    if (showOptions) ImageOptionsSheet(opt, { opt = it }) { showOptions = false }
}

@Composable
private fun ImageTile(item: ImageItem, index: Int, opt: ImagesToPdfOptions, onRotate: () -> Unit, onLeft: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    var bmp by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri) { bmp = withContext(Dispatchers.IO) { ConvertOps.decodeImage(context, item.uri, 400) } }
    val pageRatio = opt.pageSize.rect(false).let { it.width / it.height }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(10.dp), shadowElevation = 3.dp, color = Color.White,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (opt.pageSize == PageSize.FIT) 0.8f else pageRatio)) {
            Box(Modifier.padding((opt.marginPt / 8).dp), contentAlignment = Alignment.Center) {
                bmp?.let {
                    Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().rotate(item.rotation.toFloat()))
                } ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Box(Modifier.fillMaxSize().padding(4.dp)) {
                Box(Modifier.align(Alignment.TopStart).size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Row {
            IconButton(onLeft, Modifier.size(34.dp), enabled = index > 0) { Icon(Icons.Rounded.ChevronLeft, "Move left", Modifier.size(18.dp)) }
            IconButton(onRotate, Modifier.size(34.dp)) { Icon(Icons.AutoMirrored.Rounded.RotateRight, "Rotate", Modifier.size(18.dp)) }
            IconButton(onRemove, Modifier.size(34.dp)) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageOptionsSheet(opt: ImagesToPdfOptions, onChange: (ImagesToPdfOptions) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Page & quality", style = MaterialTheme.typography.titleLarge)
            Text("Page size", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(PageSize.entries.map { it to (if (it == PageSize.FIT) "Fit image" else it.label) }, opt.pageSize, { onChange(opt.copy(pageSize = it)) })
            if (opt.pageSize != PageSize.FIT) SegmentedChoice(listOf(0 to "Auto", 1 to "Portrait", 2 to "Landscape"), opt.orientation, { onChange(opt.copy(orientation = it)) })
            LabeledSlider("Margin", opt.marginPt, { onChange(opt.copy(marginPt = it)) }, 0f..72f, valueText = "${opt.marginPt.toInt()} pt")
            LabeledSlider("JPEG quality", opt.quality.toFloat(), { onChange(opt.copy(quality = it.toInt())) }, 30f..100f, valueText = "${opt.quality}%")
            LabeledSlider("Max resolution", opt.maxSide.toFloat(), { onChange(opt.copy(maxSide = it.toInt())) }, 1000f..4000f, valueText = "${opt.maxSide}px")
            SwitchRow("Grayscale", "Convert photos to black & white", opt.grayscale) { onChange(opt.copy(grayscale = it)) }
        }
    }
}

// ------------------------------------------------------------------ PDF → images / text

@Composable
fun PdfToImagesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var dpi by remember { mutableFloatStateOf(150f) }
    var png by remember { mutableStateOf(false) }
    var quality by remember { mutableFloatStateOf(90f) }
    var zip by remember { mutableStateOf(false) }
    PdfToolFrame(
        "pdf_to_images", initialUri, "Export ${if (selected.isEmpty()) "all" else selected.size.toString()} page(s)",
        onPdfChanged = { selected = emptySet() },
        onRun = { p -> runFiles { prog -> ConvertOps.pdfToImages(context, p, selected.toList(), dpi.toInt(), png, quality.toInt(), zip, prog) } }
    ) { p, r ->
        full("opts") {
            Section("Image options", Icons.Rounded.Image) {
                SegmentedChoice(listOf(false to "JPG", true to "PNG"), png, { png = it })
                LabeledSlider("Resolution", dpi, { dpi = it }, 72f..300f, valueText = "${dpi.toInt()} DPI")
                if (!png) LabeledSlider("Quality", quality, { quality = it }, 40f..100f, valueText = "${quality.toInt()}%")
                SwitchRow("Bundle as ZIP", "One archive instead of many images", zip) { zip = it }
                PageSelectionBar(selected, p.pageCount, { selected = it }, "All pages (or tap to choose)")
            }
        }
        if (r != null) pageItems(r, selected, { selected = if (it in selected) selected - it else selected + it })
    }
}

@Composable
fun PdfToTextScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var layout by remember { mutableStateOf(false) }
    PdfToolFrame("pdf_to_text", initialUri, "Extract text", onRun = { p -> run { ConvertOps.pdfToText(context, p, layout) } }) { _, _ ->
        full("opts") {
            Section("Text export", Icons.Rounded.TextSnippet) {
                SwitchRow("Keep reading order by position", "Better for columns & tables", layout) { layout = it }
                InfoBanner("Scanned PDFs have no text layer — use \"Extract Scanned Text\" (OCR) instead.")
            }
        }
    }
}

// ------------------------------------------------------------------ Text / Markdown / HTML → PDF

enum class TextKind { TEXT, MARKDOWN, HTML, WEBPAGE }

@Composable
fun TextToPdfScreen(kind: TextKind, initialUri: Uri? = null, initialText: String? = null) {
    val context = LocalContext.current
    val toolId = when (kind) { TextKind.TEXT -> "text_to_pdf"; TextKind.MARKDOWN -> "markdown_to_pdf"; TextKind.HTML -> "html_to_pdf"; TextKind.WEBPAGE -> "webpage_to_pdf" }
    val tool = ToolCatalog.get(toolId)
    val job = rememberJob(toolId)
    var text by remember { mutableStateOf(initialText ?: sample(kind)) }
    var name by remember { mutableStateOf(if (kind == TextKind.WEBPAGE) "Webpage" else "Document") }
    var fileUrl by remember { mutableStateOf<String?>(null) }
    var opt by remember { mutableStateOf(TextPdfOptions(monospace = false)) }
    var htmlMode by remember { mutableIntStateOf(if (kind == TextKind.WEBPAGE) 1 else 0) } // 0 fast text, 1 full render, 2 system print
    var allowJs by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val mimes = when (kind) {
        TextKind.TEXT -> arrayOf("text/plain", "text/*")
        TextKind.MARKDOWN -> arrayOf("text/markdown", "text/x-markdown", "text/plain", "*/*")
        else -> arrayOf("text/html", "application/xhtml+xml", "*/*")
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()?.let { text = it }
            name = Workspace.baseName(Workspace.displayName(context, uri))
            if (kind == TextKind.WEBPAGE) {
                // Keep a local copy so relative images next to the file still resolve when possible.
                val local = Workspace.temp(context, "html").apply { writeText(text) }
                fileUrl = Uri.fromFile(local).toString()
            }
        }
    }
    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(initialUri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() } ?: text
            name = Workspace.baseName(Workspace.displayName(context, initialUri))
        }
    }

    fun convert() {
        val o = opt.copy(title = if (kind == TextKind.TEXT) opt.title else "")
        when {
            kind == TextKind.TEXT -> job.runFile { ConvertOps.textToPdf(context, text, name, o) }
            kind == TextKind.MARKDOWN -> job.runFile { ConvertOps.markdownToPdf(context, text, name, o) }
            htmlMode == 0 -> job.runFile { ConvertOps.htmlToPdf(context, text, name, o) }
            htmlMode == 1 -> job.runFile { prog -> WebPdf.capture(context, if (fileUrl == null) text else null, fileUrl, name, o.pageSize, allowJs, prog) }
            else -> WebPdf.printToPdf(context, if (fileUrl == null) text else null, fileUrl, name, allowJs)
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(tool) {
                IconButton({ importer.launch(mimes) }) { Icon(Icons.Rounded.FileOpen, "Import file") }
            }
        },
        bottomBar = {
            if (job.state !is JobState.Done) BottomAction(
                if (kind >= TextKind.HTML && htmlMode == 2) "Open print dialog" else "Create PDF", text.isNotBlank(),
                if (kind >= TextKind.HTML && htmlMode == 2) Icons.Rounded.Print else Icons.Rounded.PictureAsPdf
            ) { convert() }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val s = job.state
            if (s is JobState.Done) ResultPanel(s.result, toolId) { job.reset() }
            else Column(Modifier.fillMaxSize()) {
                if (kind != TextKind.TEXT) TabRow(tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Edit") }, icon = { Icon(Icons.Rounded.Edit, null) })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Preview") }, icon = { Icon(Icons.Rounded.Visibility, null) })
                    Tab(tab == 2, { tab = 2 }, text = { Text("Options") }, icon = { Icon(Icons.Rounded.Tune, null) })
                } else TabRow(if (tab == 2) 1 else 0) {
                    Tab(tab != 2, { tab = 0 }, text = { Text("Edit") }, icon = { Icon(Icons.Rounded.Edit, null) })
                    Tab(tab == 2, { tab = 2 }, text = { Text("Options") }, icon = { Icon(Icons.Rounded.Tune, null) })
                }
                when (tab) {
                    0 -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("File name") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            text, { text = it; if (kind == TextKind.WEBPAGE) fileUrl = null }, Modifier.fillMaxWidth().weight(1f),
                            label = { Text(when (kind) { TextKind.TEXT -> "Text"; TextKind.MARKDOWN -> "Markdown"; else -> "HTML" }) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (kind == TextKind.TEXT) FontFamily.Default else FontFamily.Monospace),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Text("${text.length} characters • ${text.lines().size} lines", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                    1 -> LivePreview(kind, text)
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (kind == TextKind.HTML || kind == TextKind.WEBPAGE) Section("Rendering engine", Icons.Rounded.Html) {
                            SegmentedChoice(listOf(0 to "Fast text", 1 to "Full render", 2 to "Print (vector)"), htmlMode, { htmlMode = it })
                            Text(
                                when (htmlMode) {
                                    0 -> "Basic HTML tags converted to selectable text. Fast, small files."
                                    1 -> "Renders exactly like a browser using the on-device WebView and saves each page as an image."
                                    else -> "Uses the system print dialog — choose \"Save as PDF\" for a vector PDF with selectable text."
                                }, style = MaterialTheme.typography.bodySmall
                            )
                            if (htmlMode > 0) SwitchRow("Run page scripts", "Off is safer for untrusted files", allowJs) { allowJs = it }
                        }
                        Section("Page", Icons.Rounded.Description) {
                            ChoiceChips(PageSize.entries.filter { it != PageSize.FIT }.map { it to it.label }, opt.pageSize, { opt = opt.copy(pageSize = it) })
                            SegmentedChoice(listOf(false to "Portrait", true to "Landscape"), opt.landscape, { opt = opt.copy(landscape = it) })
                            if (kind != TextKind.WEBPAGE || htmlMode == 0) {
                                LabeledSlider("Font size", opt.fontSize, { opt = opt.copy(fontSize = it) }, 7f..24f, valueText = "${"%.0f".format(opt.fontSize)} pt")
                                LabeledSlider("Margins", opt.marginPt, { opt = opt.copy(marginPt = it) }, 18f..108f, valueText = "${opt.marginPt.toInt()} pt")
                                SwitchRow("Page numbers", null, opt.pageNumbers) { opt = opt.copy(pageNumbers = it) }
                            }
                            if (kind == TextKind.TEXT) {
                                SwitchRow("Monospace font", "Good for code and logs", opt.monospace) { opt = opt.copy(monospace = it) }
                                OutlinedTextField(opt.title, { opt = opt.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Title (optional)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                            }
                        }
                    }
                }
            }
            JobOverlay(job)
        }
    }
}

@Composable
private fun LivePreview(kind: TextKind, text: String) {
    val textColor = Color(0xFF1C1B1F).toArgb()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp)) {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(8.dp), color = Color.White, shadowElevation = 6.dp) {
            AndroidView(
                factory = { ctx -> TextView(ctx).apply { setPadding(48, 48, 48, 48); setTextColor(textColor); movementMethod = LinkMovementMethod.getInstance() } },
                update = { tv ->
                    tv.text = when (kind) {
                        TextKind.MARKDOWN -> Markdown.toSpanned(text)
                        TextKind.HTML, TextKind.WEBPAGE -> if (android.os.Build.VERSION.SDK_INT >= 24) android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY)
                            else @Suppress("DEPRECATION") android.text.Html.fromHtml(text)
                        TextKind.TEXT -> text
                    }
                },
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            )
        }
    }
}

private fun sample(kind: TextKind) = when (kind) {
    TextKind.TEXT -> ""
    TextKind.MARKDOWN -> """# My Document

Write **Markdown** here and see a *live preview*.

## Features
- Headings, **bold**, *italic*, `code`, ~~strikethrough~~
- [x] Task lists
- > Quotes and [links](https://example.com)

```
code blocks keep their spacing
```
"""
    TextKind.HTML -> "<h1>Hello</h1>\n<p>This is <b>HTML</b> rendered to PDF <i>offline</i>.</p>\n<ul><li>Lists</li><li>Links</li></ul>"
    TextKind.WEBPAGE -> ""
}
