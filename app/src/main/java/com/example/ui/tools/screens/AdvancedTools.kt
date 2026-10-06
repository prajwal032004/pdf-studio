package com.example.ui.tools.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.*
import com.example.ui.common.*
import com.example.ui.theme.Accents
import com.example.ui.tools.ToolCatalog
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pager of pages with a pointer overlay; used by redaction and form creation. */
@Composable
fun PageStage(
    renderer: PageRenderer, page: Int, onPage: (Int) -> Unit, modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onPage((page - 1).coerceAtLeast(0)) }, enabled = page > 0) { Icon(Icons.Rounded.ChevronLeft, "Previous page") }
            Text("Page ${page + 1} of ${renderer.pageCount}", style = MaterialTheme.typography.labelLarge)
            IconButton({ onPage((page + 1).coerceAtMost(renderer.pageCount - 1)) }, enabled = page < renderer.pageCount - 1) { Icon(Icons.Rounded.ChevronRight, "Next page") }
        }
        PagePreview(renderer, page, Modifier.fillMaxWidth(), 1100, overlay)
    }
}

/** Drag to draw rectangles; tap a rectangle to delete it. */
@Composable
fun RectDrawOverlay(rects: List<NRect>, color: Color, onAdd: (NRect) -> Unit, onRemove: (NRect) -> Unit, filled: Boolean = true, labels: List<String>? = null) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    val currentRects by rememberUpdatedState(rects)
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val x = pos.x / size.width; val y = pos.y / size.height
                    currentRects.lastOrNull { it.contains(x, y) }?.let(onRemove)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start = it; end = it },
                    onDrag = { c, a -> c.consume(); end = (end ?: Offset.Zero) + a },
                    onDragEnd = {
                        val s = start; val e = end
                        if (s != null && e != null) {
                            val r = NRect(minOf(s.x, e.x) / size.width, minOf(s.y, e.y) / size.height, maxOf(s.x, e.x) / size.width, maxOf(s.y, e.y) / size.height)
                            if (r.width > 0.01f && r.height > 0.005f) onAdd(NRect(r.left.coerceIn(0f, 1f), r.top.coerceIn(0f, 1f), r.right.coerceIn(0f, 1f), r.bottom.coerceIn(0f, 1f)))
                        }
                        start = null; end = null
                    },
                    onDragCancel = { start = null; end = null }
                )
            }
    ) {
        rects.forEach { r ->
            val o = Offset(r.left * size.width, r.top * size.height); val sz = Size(r.width * size.width, r.height * size.height)
            if (filled) drawRect(color.copy(alpha = 0.75f), o, sz)
            drawRect(color, o, sz, style = Stroke(2f))
        }
        val s = start; val e = end
        if (s != null && e != null) {
            drawRect(color.copy(alpha = 0.3f), Offset(minOf(s.x, e.x), minOf(s.y, e.y)), Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)))
            drawRect(color, Offset(minOf(s.x, e.x), minOf(s.y, e.y)), Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)), style = Stroke(3f))
        }
    }
}

// ------------------------------------------------------------------ Redaction

@Composable
fun RedactScreen(toolId: String, initialUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auto = toolId == "auto_redact"
    val areas = remember { mutableStateMapOf<Int, List<NRect>>() }
    var page by remember { mutableIntStateOf(0) }
    var fill by remember { mutableIntStateOf(0xFF000000.toInt()) }
    var searchable by remember { mutableStateOf(true) }
    var strip by remember { mutableStateOf(true) }
    var term by remember { mutableStateOf("") }
    var kinds by remember { mutableStateOf(setOf(TextOps.Sensitive.EMAIL, TextOps.Sensitive.PHONE, TextOps.Sensitive.CARD, TextOps.Sensitive.AADHAAR, TextOps.Sensitive.PAN)) }
    var findings by remember { mutableStateOf<List<Pair<TextOps.Sensitive, TextHit>>?>(null) }
    var chosen by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    val total = areas.values.sumOf { it.size }

    fun addHits(hits: List<TextHit>) {
        hits.forEach { h -> areas[h.page] = areas[h.page].orEmpty() + h.rects.map { NRect(it.left - 0.003f, it.top, it.right + 0.003f, it.bottom) } }
    }

    PdfToolFrame(toolId, initialUri, "Redact $total area(s)", runIcon = Icons.Rounded.FormatStrikethrough,
        canRun = { total > 0 },
        onPdfChanged = { areas.clear(); page = 0; findings = null; file = it.file },
        onRun = { p ->
            val snapshot = areas.toMap()
            run { prog -> AnnotOps.redact(context, p, snapshot, fill, 200, searchable, strip, prog) }
        }
    ) { p, r ->
        if (auto) full("auto") {
            Section("Find sensitive information", Icons.Rounded.PrivacyTip) {
                FlowRowChips(TextOps.Sensitive.entries.map { it to it.label }, kinds) { k -> kinds = if (k in kinds) kinds - k else kinds + k }
                Button({
                    busy = true
                    scope.launch {
                        findings = TextOps.detectSensitive(p.file, kinds)
                        chosen = findings!!.indices.toSet(); busy = false
                    }
                }, enabled = !busy && kinds.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Search, null)
                    Spacer(Modifier.width(8.dp)); Text("Scan document")
                }
                findings?.let { f ->
                    if (f.isEmpty()) InfoBanner("Nothing found. Scanned PDFs need OCR first so their text can be detected.", Icons.Rounded.Info)
                    else {
                        Text("${f.size} item(s) found — untick anything you want to keep.", style = MaterialTheme.typography.titleSmall)
                        f.forEachIndexed { i, (k, h) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { chosen = if (i in chosen) chosen - i else chosen + i },
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(i in chosen, { chosen = if (i in chosen) chosen - i else chosen + i })
                                Column(Modifier.weight(1f)) {
                                    Text(h.match, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${k.label} · page ${h.page + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        FilledTonalButton({ addHits(f.filterIndexed { i, _ -> i in chosen }.map { it.second }); findings = null }, shape = RoundedCornerShape(14.dp)) {
                            Text("Mark ${chosen.size} for redaction")
                        }
                    }
                }
            }
        }
        full("opts") {
            Section("Redaction", Icons.Rounded.FormatStrikethrough) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(term, { term = it }, Modifier.weight(1f), label = { Text("Redact every occurrence of…") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    FilledIconButton({
                        val f = file ?: return@FilledIconButton
                        scope.launch {
                            val hits = TextOps.search(f, term)
                            addHits(hits)
                            Toast.makeText(context, "${hits.size} occurrence(s) marked", Toast.LENGTH_SHORT).show()
                        }
                    }, enabled = term.length >= 2) { Icon(Icons.Rounded.Add, null) }
                }
                Text("Or drag on the page to box any area. Tap a box to remove it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ColorRow(fill, { fill = it }, listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF424242.toInt(), 0xFFE53935.toInt()))
                SwitchRow("Keep redacted pages searchable", "Re-runs OCR on the redacted image — hidden text stays gone", searchable) { searchable = it }
                SwitchRow("Remove metadata", null, strip) { strip = it }
                if (total > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$total area(s) on ${areas.count { it.value.isNotEmpty() }} page(s)", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    TextButton({ areas.clear() }) { Text("Clear all") }
                }
                InfoBanner("Redaction is permanent: marked pages are rebuilt so the covered text, images and vectors no longer exist in the file.", Icons.Rounded.Warning, Accents.orange)
            }
        }
        if (r != null) full("stage") {
            PageStage(r, page, { page = it }) {
                RectDrawOverlay(areas[page].orEmpty(), Color(fill or (0xFF shl 24)).let { if (it == Color.White) Color(0xFF9E9E9E) else it },
                    onAdd = { areas[page] = areas[page].orEmpty() + it }, onRemove = { rc -> areas[page] = areas[page].orEmpty() - rc })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FlowRowChips(options: List<Pair<T, String>>, selected: Set<T>, onToggle: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (v, label) -> FilterChip(v in selected, { onToggle(v) }, { Text(label) }) }
    }
}

// ------------------------------------------------------------------ Forms

@Composable
fun FillFormScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var fields by remember { mutableStateOf<List<FieldInfo>?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var flatten by remember { mutableStateOf(false) }
    val nav = LocalNav.current
    PdfToolFrame("fill_form", initialUri, if (flatten) "Fill & flatten" else "Save filled form", runIcon = Icons.Rounded.EditNote,
        canRun = { !fields.isNullOrEmpty() },
        onPdfChanged = { p -> values.clear(); fields = FormOps.listFields(p.file).also { l -> l.forEach { values[it.name] = it.value } } },
        onRun = { p -> val v = values.toMap(); runFile { FormOps.fill(context, p, v, flatten) } }
    ) { p, _ ->
        full("f") {
            val list = fields
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                list.isEmpty() -> Section("No form fields", Icons.Rounded.Info) {
                    Text("This PDF has no fillable fields. You can add some with Create Form, or type anywhere with Add Text.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ nav.openTool("create_form", p.uri) }) { Text("Create form") }
                        OutlinedButton({ nav.openTool("add_text", p.uri) }) { Text("Add text") }
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Section("${list.size} field(s)", Icons.Rounded.DynamicForm) {
                        SwitchRow("Flatten after filling", "Makes answers permanent and non-editable", flatten) { flatten = it }
                    }
                    list.groupBy { it.page }.forEach { (pg, group) ->
                        Section(if (pg >= 0) "Page ${pg + 1}" else "Fields", Icons.Rounded.Description) {
                            group.forEach { f -> FieldEditor(f, values[f.name] ?: "") { values[f.name] = it } }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditor(f: FieldInfo, value: String, onChange: (String) -> Unit) {
    val label = f.label + if (f.required) " *" else ""
    when (f.type) {
        FieldType.CHECKBOX -> SwitchRow(label, if (f.readOnly) "Read-only" else null, value == "true") { if (!f.readOnly) onChange(it.toString()) }
        FieldType.RADIO, FieldType.DROPDOWN, FieldType.LIST -> {
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(open, { open = it && !f.readOnly }) {
                OutlinedTextField(value, {}, Modifier.fillMaxWidth().menuAnchor(), readOnly = true, label = { Text(label) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, shape = RoundedCornerShape(14.dp))
                ExposedDropdownMenu(open, { open = false }) {
                    f.options.forEach { o -> DropdownMenuItem({ Text(o) }, { onChange(o); open = false }) }
                }
            }
        }
        FieldType.SIGNATURE -> InfoBanner("$label — signature field (use Sign PDF to add a visual signature)", Icons.Rounded.HistoryEdu)
        FieldType.BUTTON -> {}
        else -> OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = !f.readOnly,
            singleLine = f.type != FieldType.MULTILINE, minLines = if (f.type == FieldType.MULTILINE) 3 else 1, shape = RoundedCornerShape(14.dp))
    }
}

@Composable
fun CreateFormScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val specs = remember { mutableStateListOf<FieldSpec>() }
    var type by remember { mutableStateOf(FieldType.TEXT) }
    var page by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Int?>(null) }
    PdfToolFrame("create_form", initialUri, "Create ${specs.size} field(s)", runIcon = Icons.Rounded.DynamicForm,
        canRun = { specs.isNotEmpty() },
        onPdfChanged = { specs.clear(); page = 0 },
        onRun = { p -> val l = specs.toList(); runFile { FormOps.createFields(context, p, l) } }
    ) { _, r ->
        full("t") {
            Section("Field type", Icons.Rounded.DynamicForm) {
                ChoiceChips(listOf(FieldType.TEXT to "Text", FieldType.MULTILINE to "Paragraph", FieldType.CHECKBOX to "Checkbox", FieldType.DROPDOWN to "Dropdown", FieldType.LIST to "List"), type, { type = it })
                Text("Drag on the page to place a field. Tap a field to remove it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                specs.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (s.type) { FieldType.CHECKBOX -> Icons.Rounded.CheckBox; FieldType.DROPDOWN, FieldType.LIST -> Icons.Rounded.ArrowDropDownCircle; else -> Icons.Rounded.TextFields }, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${s.name} · p.${s.page + 1}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton({ editing = i }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)) }
                        IconButton({ specs.removeAt(i) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)) }
                    }
                }
            }
        }
        if (r != null) full("stage") {
            PageStage(r, page, { page = it }) {
                RectDrawOverlay(specs.filter { it.page == page }.map { it.rect }, Accents.violet,
                    onAdd = { rc ->
                        val rect = if (type == FieldType.CHECKBOX) NRect(rc.left, rc.top, rc.left + 0.035f, rc.top + 0.035f * 0.707f) else rc
                        specs.add(FieldSpec(page, rect, type, "${type.name.lowercase()}_${specs.size + 1}",
                            if (type == FieldType.DROPDOWN || type == FieldType.LIST) listOf("Option 1", "Option 2", "Option 3") else emptyList()))
                    },
                    onRemove = { rc -> specs.removeAll { it.page == page && it.rect == rc } }, filled = false)
            }
        }
    }
    editing?.let { i ->
        val s = specs.getOrNull(i) ?: return@let
        var name by remember(i) { mutableStateOf(s.name) }
        var opts by remember(i) { mutableStateOf(s.options.joinToString("\n")) }
        var def by remember(i) { mutableStateOf(s.defaultValue) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Field settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    if (s.type == FieldType.DROPDOWN || s.type == FieldType.LIST) OutlinedTextField(opts, { opts = it }, label = { Text("Options (one per line)") }, minLines = 3)
                    if (s.type != FieldType.CHECKBOX) OutlinedTextField(def, { def = it }, label = { Text("Default value") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ specs[i] = s.copy(name = name, options = opts.lines().filter { it.isNotBlank() }, defaultValue = def); editing = null }) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun FlattenScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var annots by remember { mutableStateOf(true) }
    PdfToolFrame("flatten", initialUri, "Flatten", runIcon = Icons.Rounded.Layers,
        onRun = { p -> runFile { FormOps.flatten(context, p, annots) } }
    ) { _, _ ->
        full("o") {
            Section("What to flatten", Icons.Rounded.Layers) {
                Text("Form fields become part of the page and can no longer be edited.", style = MaterialTheme.typography.bodyMedium)
                SwitchRow("Also flatten annotations", "Comments, drawings, stamps & highlights", annots) { annots = it }
            }
        }
    }
}

// ------------------------------------------------------------------ Signatures

@Composable
fun VerifySignaturesScreen(initialUri: Uri?) {
    var scan by remember { mutableStateOf<SignatureScan?>(null) }
    var picked by remember { mutableStateOf<PickedPdf?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(picked) { picked?.let { p -> scan = null; error = null; runCatching { AnalysisOps.signatures(p) }.onSuccess { scan = it }.onFailure { error = it.message } } }
    val nav = LocalNav.current
    PdfToolFrame("verify_signatures", initialUri, "Open document", runIcon = Icons.Rounded.MenuBook,
        onPdfChanged = { picked = it }, onRun = { p -> nav.openFile(p.file) }
    ) { p, _ ->
        full("s") {
            val s = scan
            when {
                error != null -> InfoBanner("Couldn't read signatures: $error", Icons.Rounded.ErrorOutline, Accents.red)
                s == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (p.wasEncrypted) InfoBanner("This file was decrypted to read it; signature byte checks use the decrypted copy and may report as modified.", Icons.Rounded.Info, Accents.orange)
                    if (s.signed.isEmpty() && s.emptyFields.isEmpty() && s.visualSignatures.isEmpty())
                        Section("No signatures", Icons.Rounded.GppMaybe) { Text("This PDF has no digital signatures, signature fields or visual signatures.") }
                    s.signed.forEach { r ->
                        val ok = r.integrityValid && r.coversWholeDocument
                        Surface(shape = RoundedCornerShape(22.dp), color = Accents.container(if (ok) Accents.green else if (r.integrityValid) Accents.orange else Accents.red)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (ok) Icons.Rounded.VerifiedUser else if (r.integrityValid) Icons.Rounded.GppMaybe else Icons.Rounded.GppBad, null,
                                        tint = if (ok) Accents.green else if (r.integrityValid) Accents.orange else Accents.red, modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(r.signer, style = MaterialTheme.typography.titleMedium)
                                        Text(when {
                                            r.error != null -> "Could not verify"
                                            ok -> "Signature valid — document unchanged since signing"
                                            r.integrityValid -> "Valid, but the document was changed after this signature"
                                            else -> "Invalid — content doesn't match the signature"
                                        }, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                InfoRow("Signed", r.signedAt); InfoRow("Issuer", r.issuer); InfoRow("Reason", r.reason); InfoRow("Location", r.location)
                                InfoRow("Certificate", (if (r.certValidNow) "Currently valid" else "Expired / not yet valid") + if (r.selfSigned) " · self-signed" else "")
                                InfoRow("Format", r.subFilter)
                                r.error?.let { InfoRow("Error", it) }
                            }
                        }
                    }
                    if (s.emptyFields.isNotEmpty()) Section("Unsigned signature fields", Icons.Rounded.Draw) { s.emptyFields.forEach { Text("• $it") } }
                    if (s.visualSignatures.isNotEmpty()) Section("Visual signatures", Icons.Rounded.HistoryEdu) {
                        Text("Drawn/stamped signatures found on page(s) ${s.visualSignatures.joinToString { "${it + 1}" }}. These are images, not cryptographic signatures.", style = MaterialTheme.typography.bodySmall)
                    }
                    InfoBanner("Trust in the signer's identity requires their certificate to chain to an authority you trust; this check confirms integrity and shows the certificate details.", Icons.Rounded.Info)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ PDF/A

@Composable
fun PdfAScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var checks by remember { mutableStateOf<List<Check>?>(null) }
    var picked by remember { mutableStateOf<PickedPdf?>(null) }
    var rasterize by remember { mutableStateOf(false) }
    LaunchedEffect(picked) { picked?.let { checks = null; checks = runCatching { AnalysisOps.validatePdfA(it) }.getOrNull() } }
    PdfToolFrame("pdfa", initialUri, "Convert to PDF/A-2b", runIcon = Icons.Rounded.Inventory2,
        onPdfChanged = { picked = it },
        onRun = { p -> runFile { prog -> AnalysisOps.convertPdfA(context, p, rasterize, prog) } }
    ) { _, _ ->
        full("v") {
            val c = checks
            if (c == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val passed = c.count { it.ok }
                Section("Archive readiness: $passed / ${c.size} checks", Icons.Rounded.Checklist) {
                    LinearProgressIndicator(progress = { passed / c.size.toFloat() }, Modifier.fillMaxWidth(), color = if (passed == c.size) Accents.green else Accents.orange)
                    c.forEach { ch ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(if (ch.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null, Modifier.size(20.dp), tint = if (ch.ok) Accents.green else Accents.red)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(ch.label, style = MaterialTheme.typography.bodyMedium)
                                Text(ch.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Section("Conversion", Icons.Rounded.Inventory2) {
                    Text("Adds PDF/A identification, an sRGB colour profile and document ID; removes scripts and attachments; fixes annotation flags.", style = MaterialTheme.typography.bodySmall)
                    SwitchRow("Guarantee font compliance", "Renders pages as images (needed when fonts aren't embedded)", rasterize) { rasterize = it }
                    if (c.any { it.label.startsWith("All fonts") && !it.ok } && !rasterize)
                        InfoBanner("Some fonts aren't embedded — turn on \"Guarantee font compliance\" for a fully valid result.", Icons.Rounded.Warning, Accents.orange)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Duplicate pages

@Composable
fun DuplicatesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<List<Int>>?>(null) }
    var remove by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    PdfToolFrame("duplicates", initialUri, "Remove ${remove.size} duplicate(s)", runIcon = Icons.Rounded.FilterNone,
        canRun = { p -> remove.isNotEmpty() && remove.size < p.pageCount },
        onPdfChanged = { p ->
            groups = null; remove = emptySet(); busy = true
            scope.launch {
                val g = AnalysisOps.duplicatePages(p) { _, m -> msg = m }
                groups = g; remove = g.flatMap { it.drop(1) }.toSet(); busy = false
            }
        },
        onRun = { p -> runFile { PageOps.deletePages(context, p, remove) } }
    ) { _, r ->
        full("g") {
            val g = groups
            when {
                busy || g == null -> Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(msg, style = MaterialTheme.typography.labelSmall) }
                g.isEmpty() -> InfoBanner("No duplicate pages found.", Icons.Rounded.CheckCircle, Accents.green)
                else -> Text("${g.size} group(s) of identical pages. The first of each is kept — tap to change.", style = MaterialTheme.typography.titleSmall)
            }
        }
        val g = groups
        if (r != null && !g.isNullOrEmpty()) g.forEachIndexed { gi, grp ->
            full("gh$gi") { Text("Group ${gi + 1}: pages ${grp.joinToString { "${it + 1}" }}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            items(grp.size, key = { "g${gi}_${grp[it]}" }) { k ->
                val i = grp[k]
                PageThumbCard(r, i, i in remove, label = if (i in remove) "${i + 1} · remove" else "${i + 1} · keep", badgeColor = Accents.red,
                    onClick = { remove = if (i in remove) remove - i else remove + i })
            }
        }
    }
}

// ------------------------------------------------------------------ Barcodes

@Composable
fun BarcodesScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hits by remember { mutableStateOf<List<BarcodeHit>?>(null) }
    var msg by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<PickedPdf?>(null) }
    PdfToolFrame("barcodes", initialUri, "Export results", runIcon = Icons.Rounded.Save,
        canRun = { !hits.isNullOrEmpty() },
        onPdfChanged = { p -> picked = p; hits = null; scope.launch { hits = runCatching { OcrOps.scanBarcodes(p) { _, m -> msg = m } }.getOrDefault(emptyList()) } },
        onRun = { p ->
            val list = hits.orEmpty()
            runFile {
                withContext(Dispatchers.IO) {
                    Workspace.newOutput(context, p.baseName, "codes", "txt").apply {
                        writeText(list.joinToString("\n\n") { "Page ${it.page + 1} · ${it.format} (${it.type})\n${it.value}" })
                    }
                }
            }
        }
    ) { _, r ->
        full("h") {
            val h = hits
            when {
                h == null -> Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(msg, style = MaterialTheme.typography.labelSmall) }
                h.isEmpty() -> InfoBanner("No QR codes or barcodes detected.", Icons.Rounded.QrCodeScanner)
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${h.size} code(s) found", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    h.forEach { c ->
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).background(Accents.container(Accents.slate), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    Icon(if (c.format == "QR Code") Icons.Rounded.QrCode2 else Icons.Rounded.ViewWeek, null, tint = Accents.onContainer(Accents.slate))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    androidx.compose.foundation.text.selection.SelectionContainer { Text(c.value, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                                    Text("${c.format} · ${c.type} · page ${c.page + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton({ copy(context, c.value) }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                                if (c.value.startsWith("http", true)) IconButton({
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.value))) }
                                }) { Icon(Icons.Rounded.OpenInNew, "Open link") }
                            }
                        }
                    }
                }
            }
        }
        val h = hits
        if (r != null && !h.isNullOrEmpty()) h.map { it.page }.distinct().forEach { pg ->
            full("pg$pg") {
                PagePreview(r, pg, Modifier.fillMaxWidth(), 900) {
                    Canvas(Modifier.fillMaxSize()) {
                        h.filter { it.page == pg }.mapNotNull { it.rect }.forEach { rc ->
                            drawRect(Accents.green, Offset(rc.left * size.width, rc.top * size.height), Size(rc.width * size.width, rc.height * size.height), style = Stroke(5f))
                        }
                    }
                }
            }
        }
    }
}

fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PDF Studio", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

// ------------------------------------------------------------------ Scan with camera

@Composable
fun ScanScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val tool = ToolCatalog.get("scan")
    val job = rememberJob("scan")
    var pages by remember { mutableFloatStateOf(20f) }
    var ocr by remember { mutableStateOf(true) }
    var gallery by remember { mutableStateOf(true) }
    var fallback by remember { mutableStateOf(false) }
    val name = remember { "Scan_${Workspace.stamp()}" }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
        val pdfUri = result?.pdf?.uri
        if (res.resultCode == Activity.RESULT_OK && pdfUri != null) job.run { prog ->
            prog(0.05f, "Saving scan")
            val out = Workspace.newOutput(context, name, "scan")
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(pdfUri)!!.use { i -> out.outputStream().use { i.copyTo(it) } } }
            if (ocr) {
                val picked = Workspace.pickFile(context, out)
                val r = OcrOps.makeSearchable(context, picked, OcrOptions(dpi = 220, autoRotate = false, skipPagesWithText = false), prog)
                out.delete()
                r.copy(message = "Scanned ${result.pages?.size ?: picked.pageCount} page(s) — searchable")
            } else ToolResult(listOf(out), "Scanned ${result.pages?.size ?: 0} page(s)")
        }
    }
    // Fallback when Google Play services' scanner isn't available: plain photos → PDF.
    val photoUris = remember { mutableStateListOf<Uri>() }
    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pendingPhoto?.let { photoUris.add(it) } }

    fun start() {
        if (activity == null) { fallback = true; return }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(gallery)
            .setPageLimit(pages.toInt())
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener {
                fallback = true
                Toast.makeText(context, "Document scanner unavailable — using the camera instead", Toast.LENGTH_LONG).show()
            }
    }

    Scaffold(
        topBar = { ToolTopBar(tool) },
        bottomBar = {
            if (job.state !is JobState.Done) {
                if (!fallback) BottomAction("Start scanning", true, Icons.Rounded.DocumentScanner) { start() }
                else BottomAction(if (photoUris.isEmpty()) "Take photo" else "Create PDF (${photoUris.size})", true, if (photoUris.isEmpty()) Icons.Rounded.PhotoCamera else Icons.Rounded.PictureAsPdf,
                    secondary = if (photoUris.isNotEmpty()) ({
                        FilledTonalButton({
                            val f = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg"); val u = Workspace.uriFor(context, f); pendingPhoto = u; takePicture.launch(u)
                        }, Modifier.height(56.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.PhotoCamera, null) }
                    }) else null
                ) {
                    if (photoUris.isEmpty()) {
                        val f = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg"); val u = Workspace.uriFor(context, f); pendingPhoto = u; takePicture.launch(u)
                    } else {
                        val list = photoUris.map { ImageItem(it) }
                        job.runFile { prog -> ConvertOps.imagesToPdf(context, list, name, ImagesToPdfOptions(marginPt = 0f), prog) }
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val s = job.state
            if (s is JobState.Done) ResultPanel(s.result, "scan") { job.reset(); photoUris.clear() }
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = RoundedCornerShape(28.dp), color = Accents.container(Accents.teal)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.DocumentScanner, null, Modifier.size(64.dp), tint = Accents.onContainer(Accents.teal))
                        Spacer(Modifier.height(12.dp))
                        Text("Scan paper to PDF", style = MaterialTheme.typography.headlineSmall)
                        Text("Automatic edge detection, perspective correction, cropping, shadow & stain clean-up, and colour filters.",
                            style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                if (!fallback) Section("Options", Icons.Rounded.Tune) {
                    LabeledSlider("Maximum pages", pages, { pages = it }, 1f..50f, valueText = "${pages.toInt()}")
                    SwitchRow("Make searchable (OCR)", "Recognise text after scanning — offline", ocr) { ocr = it }
                    SwitchRow("Allow importing from gallery", "Auto-crop & straighten existing photos too", gallery) { gallery = it }
                    InfoBanner("Uses Google Play services' on-device document scanner. Images never leave your phone.", Icons.Rounded.Shield)
                } else Section("Camera mode", Icons.Rounded.PhotoCamera) {
                    Text("${photoUris.size} photo(s) captured. Take as many as you need, then create the PDF.", style = MaterialTheme.typography.bodyMedium)
                    TextButton({ fallback = false }) { Text("Try the smart scanner again") }
                }
            }
            JobOverlay(job)
        }
    }
}

// ------------------------------------------------------------------ Batch

private enum class BatchAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    COMPRESS("Compress", Icons.Rounded.Compress), WATERMARK("Watermark", Icons.Rounded.BrandingWatermark),
    NUMBERS("Page numbers", Icons.Rounded.FormatListNumbered), PROTECT("Add password", Icons.Rounded.Lock),
    ROTATE("Rotate 90°", Icons.Rounded.RotateRight), GRAY("Grayscale", Icons.Rounded.Contrast),
    CLEAN("Remove metadata", Icons.Rounded.CleaningServices), FLATTEN("Flatten", Icons.Rounded.Layers),
    OCR("OCR searchable", Icons.Rounded.TextRotationNone), IMAGES("To images (ZIP)", Icons.Rounded.Image),
    MERGE("Merge all", Icons.Rounded.MergeType)
}

@Composable
fun BatchScreen() {
    val context = LocalContext.current
    val tool = ToolCatalog.get("batch")
    val job = rememberJob("batch")
    val files = remember { mutableStateListOf<PickedPdf>() }
    var action by remember { mutableStateOf(BatchAction.COMPRESS) }
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var pw by remember { mutableStateOf("") }
    val picker = rememberMultiPdfPicker { files.addAll(it) }
    LaunchedEffect(Unit) { Incoming.take().takeIf { it.isNotEmpty() }?.let { picker.loadMany(it) } }

    fun runBatch() {
        val list = files.toList(); val a = action; val t = text; val password = pw
        job.run { prog ->
            if (a == BatchAction.MERGE) return@run ToolResult(listOf(PageOps.merge(context, list, "Batch_merged") { p, m -> prog(p, m) }), "Merged ${list.size} files")
            val outs = mutableListOf<File>(); val failed = mutableListOf<String>()
            list.forEachIndexed { i, p ->
                val sub: Progress = { f, m -> prog((i + f) / list.size, "${i + 1}/${list.size} · ${p.name}: $m") }
                sub(0f, "Starting")
                runCatching {
                    when (a) {
                        BatchAction.COMPRESS -> ImageOps.compress(context, p, CompressOptions.MEDIUM, sub).files
                        BatchAction.WATERMARK -> listOf(OrganizeOps.watermark(context, p, OrganizeOps.WatermarkSpec(text = t), null, sub))
                        BatchAction.NUMBERS -> listOf(OrganizeOps.pageNumbers(context, p, "n_of_total", HAlign.CENTER, VAlign.BOTTOM, 10f, 1, false, 28f))
                        BatchAction.PROTECT -> listOf(SecurityOps.protect(context, p, password, "", Permissions()))
                        BatchAction.ROTATE -> listOf(PageOps.rotatePages(context, p, (0 until p.pageCount).toSet(), 90))
                        BatchAction.GRAY -> listOf(ImageOps.grayscale(context, p, false, 200, sub))
                        BatchAction.CLEAN -> listOf(OrganizeOps.writeMeta(context, p, OrganizeOps.Meta(), true))
                        BatchAction.FLATTEN -> listOf(FormOps.flatten(context, p, true))
                        BatchAction.OCR -> OcrOps.makeSearchable(context, p, OcrOptions(), sub).files.filter { it.extension == "pdf" }
                        BatchAction.IMAGES -> ConvertOps.pdfToImages(context, p, emptyList(), 150, false, 88, true, sub)
                        BatchAction.MERGE -> emptyList()
                    }
                }.onSuccess { outs += it }.onFailure { failed += p.name }
            }
            ToolResult(outs, "Processed ${list.size - failed.size} of ${list.size} file(s)" + if (failed.isNotEmpty()) " — failed: ${failed.joinToString()}" else "",
                listOf("Files" to "${list.size}", "Outputs" to "${outs.size}", "Failed" to "${failed.size}"))
        }
    }

    Scaffold(
        topBar = { ToolTopBar(tool, subtitle = if (files.isEmpty()) tool?.subtitle else "${files.size} files") },
        bottomBar = {
            if (files.isNotEmpty() && job.state !is JobState.Done) BottomAction(
                "${action.label} · ${files.size} files", action != BatchAction.PROTECT || pw.length >= 4, action.icon,
                secondary = { FilledTonalButton({ picker.launch() }, Modifier.height(56.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.Add, null) } }
            ) { runBatch() }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val s = job.state
            when {
                s is JobState.Done -> ResultPanel(s.result, "batch") { job.reset(); files.clear() }
                files.isEmpty() -> EmptyPick(Icons.Rounded.DynamicFeed, "Batch processing", "Apply one action to many PDFs at once — compress a folder, watermark a set, OCR a stack of scans.",
                    "Choose PDFs", picker.loading, Accents.slate) { picker.launch() }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Section("Action", Icons.Rounded.Bolt) {
                            FlowRowActions(action) { action = it }
                            when (action) {
                                BatchAction.WATERMARK -> OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Watermark text") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                                BatchAction.PROTECT -> OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Password for all files (min 4)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                                else -> {}
                            }
                        }
                    }
                    items(files, key = { it.file.path }) { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PdfSourceCard(f, null, Modifier.weight(1f))
                            IconButton({ files.remove(f) }) { Icon(Icons.Rounded.Close, "Remove") }
                        }
                    }
                }
            }
            JobOverlay(job)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowActions(selected: BatchAction, onSelect: (BatchAction) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BatchAction.entries.forEach { a ->
            FilterChip(a == selected, { onSelect(a) }, { Text(a.label) }, leadingIcon = { Icon(a.icon, null, Modifier.size(16.dp)) })
        }
    }
}
