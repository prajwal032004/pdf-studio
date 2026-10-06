package com.example.ui.tools.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.*
import com.example.ui.common.*
import com.example.ui.theme.Accents
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Compress

private data class Preset(val label: String, val desc: String, val opt: CompressOptions)

@Composable
fun CompressScreen(toolId: String, initialUri: Uri?) {
    val context = LocalContext.current
    val presets = listOf(
        Preset("Light", "Best quality · ~10–30% smaller", CompressOptions.LOW),
        Preset("Balanced", "Recommended · ~30–60% smaller", CompressOptions.MEDIUM),
        Preset("Strong", "Smaller · visible on zoom", CompressOptions.HIGH),
        Preset("Extreme", "Smallest · pages become images", CompressOptions.EXTREME)
    )
    var custom by remember { mutableStateOf(toolId == "image_quality") }
    var preset by remember { mutableIntStateOf(1) }
    var opt by remember { mutableStateOf(CompressOptions.MEDIUM) }
    val active = if (custom) opt else presets[preset].opt

    PdfToolFrame(toolId, initialUri, "Compress", runIcon = Icons.Rounded.Compress,
        onRun = { p -> run { prog -> ImageOps.compress(context, p, active, prog) } }
    ) { p, _ ->
        full("presets") {
            Section("Compression level", Icons.Rounded.Speed) {
                SegmentedChoice(listOf(false to "Presets", true to "Custom"), custom, { custom = it })
                if (!custom) presets.forEachIndexed { i, pr ->
                    val sel = i == preset
                    OutlinedCard(
                        onClick = { preset = i }, shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.outlinedCardColors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(sel, { preset = i })
                            Column(Modifier.weight(1f)) {
                                Text(pr.label, style = MaterialTheme.typography.titleSmall)
                                Text(pr.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${pr.opt.maxDpi} dpi", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    LabeledSlider("Image resolution", opt.maxDpi.toFloat(), { opt = opt.copy(maxDpi = it.toInt()) }, 50f..300f, valueText = "${opt.maxDpi} DPI")
                    LabeledSlider("JPEG quality", opt.jpegQuality.toFloat(), { opt = opt.copy(jpegQuality = it.toInt()) }, 10f..95f, valueText = "${opt.jpegQuality}%")
                    SwitchRow("Grayscale images", "Extra savings for documents", opt.grayscaleImages) { opt = opt.copy(grayscaleImages = it) }
                    SwitchRow("Remove metadata & thumbnails", "Strips hidden data", opt.stripMetadata) { opt = opt.copy(stripMetadata = it) }
                    SwitchRow("Rasterize pages", "Maximum reduction; text stops being selectable", opt.rasterize) { opt = opt.copy(rasterize = it) }
                }
            }
        }
        full("info") {
            InfoBanner("Current size ${Workspace.formatSize(p.size)}. Unused objects are always removed and images re-encoded only when that makes them smaller.", Icons.Rounded.Lightbulb)
        }
    }
}

// ------------------------------------------------------------------ Security

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit, error: String? = null) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
        isError = error != null, supportingText = error?.let { { Text(it) } },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } }
    )
}

@Composable
private fun PermissionToggles(perm: Permissions, onChange: (Permissions) -> Unit) {
    SwitchRow("Printing", null, perm.print) { onChange(perm.copy(print = it, printHighQuality = it && perm.printHighQuality)) }
    SwitchRow("High-quality printing", null, perm.printHighQuality) { onChange(perm.copy(printHighQuality = it)) }
    SwitchRow("Copy text & images", null, perm.copy) { onChange(perm.copy(copy = it)) }
    SwitchRow("Edit content", null, perm.modify) { onChange(perm.copy(modify = it)) }
    SwitchRow("Comments & annotations", null, perm.annotate) { onChange(perm.copy(annotate = it)) }
    SwitchRow("Fill forms", null, perm.fillForms) { onChange(perm.copy(fillForms = it)) }
    SwitchRow("Assemble (insert, rotate, delete pages)", null, perm.assemble) { onChange(perm.copy(assemble = it)) }
    SwitchRow("Accessibility (screen readers)", null, perm.extractForAccessibility) { onChange(perm.copy(extractForAccessibility = it)) }
}

private fun strength(pw: String): Pair<String, androidx.compose.ui.graphics.Color> {
    var s = 0
    if (pw.length >= 8) s++; if (pw.length >= 12) s++
    if (pw.any { it.isUpperCase() } && pw.any { it.isLowerCase() }) s++
    if (pw.any { it.isDigit() }) s++; if (pw.any { !it.isLetterOrDigit() }) s++
    return when {
        s <= 1 -> "Weak" to Accents.red
        s <= 3 -> "Fair" to Accents.orange
        else -> "Strong" to Accents.green
    }
}

@Composable
fun ProtectScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var user by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var restrict by remember { mutableStateOf(false) }
    var perm by remember { mutableStateOf(Permissions()) }
    var aes256 by remember { mutableStateOf(true) }
    val mismatch = confirm.isNotEmpty() && confirm != user
    PdfToolFrame("protect", initialUri, "Encrypt PDF", runIcon = Icons.Rounded.Lock,
        canRun = { user.length >= 4 && user == confirm },
        onRun = { p -> runFile { SecurityOps.protect(context, p, user, owner, if (restrict) perm else Permissions(), if (aes256) 256 else 128) } }
    ) { _, _ ->
        full("pw") {
            Section("Open password", Icons.Rounded.Key) {
                PasswordField("Password (min 4 characters)", user, { user = it })
                if (user.isNotEmpty()) {
                    val (label, color) = strength(user)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { when (label) { "Weak" -> 0.3f; "Fair" -> 0.65f; else -> 1f } }, color = color, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp)); Text(label, color = color, style = MaterialTheme.typography.labelMedium)
                    }
                }
                PasswordField("Confirm password", confirm, { confirm = it }, if (mismatch) "Passwords don't match" else null)
                SegmentedChoice(listOf(true to "AES-256 (recommended)", false to "AES-128 (older readers)"), aes256, { aes256 = it })
            }
        }
        full("perm") {
            Section("Restrictions (optional)", Icons.Rounded.AdminPanelSettings) {
                SwitchRow("Restrict what others can do", "Needs the owner password to change later", restrict) { restrict = it }
                if (restrict) {
                    PasswordField("Owner password (optional — random if empty)", owner, { owner = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ perm = Permissions.READ_ONLY }, { Text("Read-only preset") }, leadingIcon = { Icon(Icons.Rounded.Visibility, null, Modifier.size(16.dp)) })
                        AssistChip({ perm = Permissions() }, { Text("Allow all") })
                    }
                    PermissionToggles(perm) { perm = it }
                }
            }
        }
        full("note") { InfoBanner("Keep your password safe — encrypted PDFs cannot be recovered without it.", Icons.Rounded.Warning, Accents.orange) }
    }
}

@Composable
fun UnlockScreen(initialUri: Uri?) {
    val context = LocalContext.current
    PdfToolFrame("unlock", initialUri, "Remove password", runIcon = Icons.Rounded.LockOpen,
        emptySubtitle = "Choose a protected PDF. You'll be asked for its password — only files you're authorised to open can be unlocked.",
        onRun = { p -> runFile { SecurityOps.unlock(context, p) } }
    ) { p, _ ->
        full("s") {
            Section("Status", Icons.Rounded.Security) {
                if (p.wasEncrypted) InfoBanner("Password accepted. The saved copy will open without a password and without restrictions.", Icons.Rounded.LockOpen, Accents.green)
                else InfoBanner("This PDF isn't password-protected. Saving will still remove any leftover security settings.", Icons.Rounded.Info)
            }
        }
    }
}

@Composable
fun PermissionsScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SecurityInfo?>(null) }
    var perm by remember { mutableStateOf(Permissions()) }
    var owner by remember { mutableStateOf("") }
    var removeMode by remember { mutableStateOf(false) }
    var ownerOk by remember { mutableStateOf<Boolean?>(null) }
    PdfToolFrame("permissions", initialUri, if (removeMode) "Remove restrictions" else "Apply restrictions",
        runIcon = if (removeMode) Icons.Rounded.LockOpen else Icons.Rounded.AdminPanelSettings,
        canRun = { if (removeMode) ownerOk == true || info?.encrypted == false else true },
        onPdfChanged = { p ->
            ownerOk = null
            scope.launch {
                info = runCatching { SecurityOps.inspect(context, p.uri, p.password) }.getOrNull()
                info?.let { perm = it.permissions; removeMode = it.encrypted }
            }
        },
        onRun = { p ->
            if (removeMode) runFile { SecurityOps.unlock(context, p) }
            else runFile { SecurityOps.protect(context, p, "", owner, perm, 256, "restricted") }
        }
    ) { p, _ ->
        full("cur") {
            Section("Current security", Icons.Rounded.Security) {
                val i = info
                if (i == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    Text(if (i.encrypted) "Encrypted (${i.keyLength}-bit)" else "Not encrypted", style = MaterialTheme.typography.titleSmall)
                    if (i.encrypted) Text(if (i.needsUserPassword) "Requires a password to open" else "Opens freely, owner restrictions apply",
                        style = MaterialTheme.typography.bodySmall)
                    val p0 = i.permissions
                    listOf("Print" to p0.print, "Copy" to p0.copy, "Edit" to p0.modify, "Annotate" to p0.annotate, "Fill forms" to p0.fillForms, "Assemble" to p0.assemble)
                        .chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (k, v) ->
                                    AssistChip({}, { Text(k) }, leadingIcon = { Icon(if (v) Icons.Rounded.Check else Icons.Rounded.Block, null, Modifier.size(16.dp), tint = if (v) Accents.green else Accents.red) })
                                }
                            }
                        }
                }
            }
        }
        full("mode") {
            Section("Change", Icons.Rounded.Tune) {
                SegmentedChoice(listOf(false to "Set restrictions", true to "Remove restrictions"), removeMode, { removeMode = it })
                if (removeMode) {
                    Text("Enter the owner password to prove you're authorised to lift restrictions.", style = MaterialTheme.typography.bodySmall)
                    PasswordField("Owner password", owner, { owner = it; ownerOk = null },
                        if (ownerOk == false) "That isn't the owner password" else null)
                    Button({
                        scope.launch { ownerOk = SecurityOps.verifyOwner(context, p.uri, owner) }
                    }, enabled = owner.isNotEmpty(), shape = RoundedCornerShape(14.dp)) { Text("Verify") }
                    if (ownerOk == true) InfoBanner("Verified — you can remove the restrictions.", Icons.Rounded.VerifiedUser, Accents.green)
                } else {
                    PasswordField("Owner password (keep it to change these later)", owner, { owner = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ perm = Permissions.READ_ONLY }, { Text("Read-only") })
                        AssistChip({ perm = Permissions(copy = false) }, { Text("No copying") })
                        AssistChip({ perm = Permissions(print = false, printHighQuality = false) }, { Text("No printing") })
                    }
                    PermissionToggles(perm) { perm = it }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ OCR family

@Composable
fun OcrScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var opt by remember { mutableStateOf(OcrOptions()) }
    PdfToolFrame("ocr", initialUri, "Make searchable", runIcon = Icons.Rounded.TextRotationNone,
        onRun = { p -> run { prog -> OcrOps.makeSearchable(context, p, opt, prog) } }
    ) { _, _ ->
        full("o") {
            Section("Recognition", Icons.Rounded.DocumentScanner) {
                LabeledSlider("Scan resolution", opt.dpi.toFloat(), { opt = opt.copy(dpi = it.toInt()) }, 150f..400f, valueText = "${opt.dpi} DPI")
                SwitchRow("Auto-rotate pages", "Detects upside-down and sideways scans", opt.autoRotate) { opt = opt.copy(autoRotate = it) }
                SwitchRow("Deskew", "Straightens slightly tilted pages", opt.deskew) { opt = opt.copy(deskew = it) }
                SwitchRow("Remove blank pages", null, opt.removeBlank) { opt = opt.copy(removeBlank = it) }
                SwitchRow("Skip pages that already have text", "Faster for mixed documents", opt.skipPagesWithText) { opt = opt.copy(skipPagesWithText = it) }
            }
        }
        full("n") { InfoBanner("Runs fully offline with the on-device ML Kit Latin model (English and other Latin-script languages). Pages keep their look; an invisible text layer makes them searchable and copyable. A .txt copy is included.", Icons.Rounded.Shield) }
    }
}

@Composable
fun OcrTextScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var dpi by remember { mutableFloatStateOf(250f) }
    var rotate by remember { mutableStateOf(true) }
    PdfToolFrame("ocr_text", initialUri, "Recognise text", runIcon = Icons.Rounded.Abc,
        onRun = { p -> run { prog -> OcrOps.extractScannedText(context, p, dpi.toInt(), rotate, prog) } }
    ) { _, _ ->
        full("o") {
            Section("Recognition", Icons.Rounded.Abc) {
                LabeledSlider("Scan resolution", dpi, { dpi = it }, 150f..400f, valueText = "${dpi.toInt()} DPI")
                SwitchRow("Auto-rotate pages", null, rotate) { rotate = it }
            }
        }
    }
}

@Composable
fun DeskewScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var dpi by remember { mutableFloatStateOf(200f) }
    PdfToolFrame("deskew", initialUri, "Straighten pages", runIcon = Icons.Rounded.Straighten,
        onRun = { p -> run { prog -> OcrOps.deskewPdf(context, p, dpi.toInt(), prog) } }
    ) { _, r ->
        full("o") {
            Section("Deskew", Icons.Rounded.Straighten) {
                LabeledSlider("Output resolution", dpi, { dpi = it }, 120f..300f, valueText = "${dpi.toInt()} DPI")
                InfoBanner("Tilt is measured from text lines on each page. Tip: run OCR afterwards to make the result searchable.")
            }
        }
        if (r != null) pageItems(r, emptySet(), {})
    }
}

/** Detect-then-apply flow shared by orientation fixing and blank page removal. */
@Composable
fun AnalyseAndFixScreen(toolId: String, initialUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val blankMode = toolId == "remove_blank" || toolId == "blank_pages"
    var scanning by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var marked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var rotations by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var analysed by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableFloatStateOf(0.004f) }
    var useOcr by remember { mutableStateOf(false) }

    fun analyse(p: PickedPdf) {
        scanning = true
        scope.launch {
            try {
                if (blankMode) {
                    marked = if (useOcr) OcrOps.analyse(p) { _, m -> progressText = m }.filter { it.blank }.map { it.page }.toSet()
                    else AnalysisOps.blankPages(p, sensitivity) { _, m -> progressText = m }
                } else {
                    val scans = OcrOps.analyse(p) { _, m -> progressText = m }
                    rotations = scans.filter { it.orientation != 0 }.associate { it.page to it.orientation }
                    marked = rotations.keys
                }
                analysed = true
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Analysis failed", Toast.LENGTH_LONG).show()
            } finally { scanning = false }
        }
    }

    PdfToolFrame(toolId, initialUri,
        if (blankMode) "Remove ${marked.size} blank page(s)" else "Fix ${marked.size} page(s)",
        runIcon = if (blankMode) Icons.Rounded.LayersClear else Icons.Rounded.ScreenRotation,
        canRun = { p -> analysed && marked.isNotEmpty() && (!blankMode || marked.size < p.pageCount) },
        onPdfChanged = { marked = emptySet(); rotations = emptyMap(); analysed = false },
        onRun = { p ->
            if (blankMode) runFile { PageOps.deletePages(context, p, marked) }
            else runFile { PageOps.rebuild(context, p, (0 until p.pageCount).toList(), rotations.filterKeys { it in marked }, "oriented") }
        }
    ) { p, r ->
        full("a") {
            Section(if (blankMode) "Blank page detection" else "Orientation detection", if (blankMode) Icons.Rounded.CheckBoxOutlineBlank else Icons.Rounded.ScreenRotation) {
                if (blankMode) {
                    SwitchRow("Use OCR check", "Slower, catches faint scans with no text", useOcr) { useOcr = it }
                    if (!useOcr) LabeledSlider("Sensitivity", sensitivity * 1000, { sensitivity = it / 1000 }, 1f..20f,
                        valueText = if (sensitivity < 0.004f) "Strict" else if (sensitivity < 0.01f) "Normal" else "Loose")
                } else Text("Each page is read with on-device OCR at four angles to find which way is up.", style = MaterialTheme.typography.bodySmall)
                Button({ analyse(p) }, enabled = !scanning, shape = RoundedCornerShape(14.dp)) {
                    if (scanning) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(progressText.ifBlank { "Analysing…" }) }
                    else { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text(if (analysed) "Analyse again" else "Analyse pages") }
                }
                if (analysed) Text(
                    when {
                        marked.isEmpty() && blankMode -> "No blank pages found."
                        marked.isEmpty() -> "All pages are already upright."
                        blankMode -> "Found ${marked.size} blank page(s). Tap pages to adjust the selection."
                        else -> "${marked.size} page(s) need rotating — previews show the fix."
                    }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (r != null) pageItems(r, marked, { marked = if (it in marked) marked - it else marked + it },
            labels = { i -> rotations[i]?.let { "${i + 1} · ↻$it°" } ?: if (blankMode && i in marked) "${i + 1} · blank" else "${i + 1}" },
            rotations = rotations.filterKeys { it in marked }, accent = if (blankMode) Accents.red else Accents.teal)
    }
}
