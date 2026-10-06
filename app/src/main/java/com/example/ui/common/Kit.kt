package com.example.ui.common

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.*
import com.example.ui.theme.Accents
import com.example.ui.tools.ToolCatalog
import com.example.ui.tools.ToolDef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------- Navigation

interface AppNav {
    fun back()
    fun openUri(uri: Uri)
    fun openFile(file: File)
    fun openTool(id: String, uri: Uri? = null)
    fun openLink(url: String)
    fun home()
    /** Switches between the main tabs: home, tools, files, about, settings. */
    fun tab(route: String)
    fun openCategory(category: String)
    /** Opens any other in-app route, e.g. "storage". */
    fun open(route: String)
}

val LocalNav = staticCompositionLocalOf<AppNav> { error("No navigator") }

/** True on tablets, foldables and landscape phones: show a navigation rail and centred content. */
val LocalWideLayout = staticCompositionLocalOf { false }

/** Keeps text and cards at a comfortable reading width on large screens, centred. */
fun Modifier.readableWidth(max: Dp = 840.dp): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/** Same as the system back gesture, so on-screen and system back always behave identically. */
@Composable
fun rememberBackAction(): () -> Unit {
    val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val nav = LocalNav.current
    return remember(dispatcher, nav) { { dispatcher?.onBackPressed() ?: nav.back() } }
}

// ---------------------------------------------------------------- Picking PDFs

class PdfPickerState(
    val launch: () -> Unit,
    val load: (Uri) -> Unit,
    val loading: Boolean,
    val loadMany: (List<Uri>) -> Unit = { it.firstOrNull()?.let(load) }
)

/**
 * Opens the system picker, copies the PDF to the workspace and asks for a password when needed.
 * [onCancel] runs when the person closes the password prompt without unlocking.
 */
@Composable
fun rememberPdfPicker(onCancel: () -> Unit = {}, onPicked: (PickedPdf) -> Unit): PdfPickerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var needPassword by remember { mutableStateOf<PasswordRequiredException?>(null) }
    var attempts by remember { mutableIntStateOf(0) }

    fun open(uri: Uri, password: String? = null) {
        loading = true
        scope.launch {
            try {
                val p = Workspace.pick(context, uri, password)
                needPassword = null
                attempts = 0
                onPicked(p)
            } catch (e: PasswordRequiredException) {
                if (password != null) attempts++
                needPassword = e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't open this file: ${e.message ?: "unsupported"}", Toast.LENGTH_LONG).show()
            } finally {
                loading = false
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            open(uri)
        }
    }

    needPassword?.let { req ->
        PasswordDialog(
            fileName = req.name, wrong = attempts > 0,
            onDismiss = { needPassword = null; attempts = 0; onCancel() },
            onSubmit = { open(req.uri, it) },
            loading = loading, attempt = attempts
        )
    }
    return PdfPickerState({ launcher.launch(arrayOf("application/pdf")) }, { open(it) }, loading)
}

/** Unlock prompt for encrypted PDFs: focused field, Enter to submit, shake on a wrong password. */
@Composable
fun PasswordDialog(
    fileName: String,
    wrong: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    loading: Boolean = false,
    attempt: Int = if (wrong) 1 else 0
) {
    var pw by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var show by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(attempt) {
        if (attempt > 0) {
            // Select the wrong password so typing replaces it.
            pw = pw.copy(selection = androidx.compose.ui.text.TextRange(0, pw.text.length))
            for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) shake.animateTo(x, androidx.compose.animation.core.tween(45))
        }
    }
    fun submit() { if (pw.text.isNotEmpty() && !loading) onSubmit(pw.text) }

    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!loading) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 420.dp).offset { androidx.compose.ui.unit.IntOffset(shake.value.toInt(), 0) }
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).background(if (wrong) cs.errorContainer else cs.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Lock, null, Modifier.size(30.dp),
                        tint = if (wrong) cs.onErrorContainer else cs.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("Unlock PDF", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "This document is protected. Enter its password to open it.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainer).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(20.dp), tint = Accents.red)
                    Spacer(Modifier.width(10.dp))
                    Text(fileName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true, enabled = !loading,
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Rounded.Key, null) },
                    isError = wrong,
                    supportingText = if (wrong) ({ Text(if (attempt > 1) "Still incorrect — attempt $attempt. Passwords are case-sensitive." else "Incorrect password. Check Caps Lock and try again.") }) else null,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton({ show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide password" else "Show password") }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, Modifier.size(14.dp), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Checked on this device only — never sent anywhere", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onDismiss, Modifier.weight(1f).height(48.dp), enabled = !loading, shape = RoundedCornerShape(14.dp)) { Text("Cancel") }
                    Button(::submit, Modifier.weight(1f).height(48.dp), enabled = pw.text.isNotEmpty() && !loading, shape = RoundedCornerShape(14.dp)) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                        else { Icon(Icons.Rounded.LockOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Unlock") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Saving files

class FileSaver(
    /** Copies files into Downloads/PDF Studio (asks for permission on old Android, falls back to "Save as"). */
    val toDownloads: (List<File>, String?) -> Unit,
    /** Lets the person pick any folder or cloud drive. */
    val saveAs: (File, String) -> Unit
)

@Composable
fun rememberFileSaver(): FileSaver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingAs by remember { mutableStateOf<File?>(null) }
    var pendingDownloads by remember { mutableStateOf<Pair<List<File>, String?>?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pendingAs
        pendingAs = null
        if (uri != null && f != null) scope.launch {
            runCatching { Workspace.writeToUri(context, f, uri) }
                .onSuccess { Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val createAny = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pendingAs
        pendingAs = null
        if (uri != null && f != null) scope.launch {
            runCatching { Workspace.writeToUri(context, f, uri) }
                .onSuccess { Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    fun saveAs(file: File, name: String) {
        pendingAs = file
        if (file.extension.equals("pdf", true)) createDoc.launch(name) else createAny.launch(name)
    }

    fun doSave(files: List<File>, name: String?) {
        scope.launch {
            try {
                val paths = files.mapIndexed { i, f -> Workspace.saveToDownloads(context, f, if (i == 0 && name != null) name else f.name) }
                val msg = if (paths.size == 1) "Saved to ${paths[0]}" else "${paths.size} files saved to Downloads/PDF Studio"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                PdfIndexRefresher.request(context)
            } catch (e: StoragePermissionNeeded) {
                pendingDownloads = files to name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't save to Downloads (${e.message}). Choose a folder instead.", Toast.LENGTH_LONG).show()
                files.firstOrNull()?.let { saveAs(it, name ?: it.name) }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingDownloads
        pendingDownloads = null
        if (p != null) {
            if (granted) doSave(p.first, p.second) else p.first.firstOrNull()?.let { saveAs(it, p.second ?: it.name) }
        }
    }
    LaunchedEffect(pendingDownloads) {
        if (pendingDownloads != null) permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    return remember { FileSaver({ files, name -> if (files.isNotEmpty()) doSave(files, name) }, ::saveAs) }
}

/** Refreshes the shared PDF list in the background after something new was saved. */
object PdfIndexRefresher {
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun request(context: Context) {
        val app = context.applicationContext
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { runCatching { com.example.utils.PdfIndex.refresh(app) } }
    }
}

// ---------------------------------------------------------------- Rendering

@Composable
fun rememberRenderer(file: File?): PageRenderer? {
    val renderer = remember(file) { file?.let { runCatching { PageRenderer(it) }.getOrNull() } }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    return renderer
}

/** Aspect ratio (width / height) of a page, measured off the UI thread when not yet known. */
@Composable
fun rememberPageRatio(renderer: PageRenderer, index: Int): Float {
    var ratio by remember(renderer, index) { mutableFloatStateOf(renderer.cachedSize(index)?.let { it.first.toFloat() / it.second } ?: 0.707f) }
    LaunchedEffect(renderer, index) {
        if (renderer.cachedSize(index) == null) {
            ratio = withContext(Dispatchers.IO) { runCatching { renderer.pageSize(index).let { it.first.toFloat() / it.second } }.getOrDefault(0.707f) }
        }
    }
    return ratio
}

@Composable
fun PageImage(
    renderer: PageRenderer,
    index: Int,
    modifier: Modifier = Modifier,
    widthPx: Int = 360,
    contentScale: ContentScale = ContentScale.Fit,
    rotation: Int = 0
) {
    var bmp by remember(renderer, index, widthPx) { mutableStateOf(ThumbCache.peek(renderer, index, widthPx)) }
    LaunchedEffect(renderer, index, widthPx) { if (bmp == null) bmp = ThumbCache.get(renderer, index, widthPx) }
    val ratio = rememberPageRatio(renderer, index)
    val displayRatio = if (rotation % 180 != 0) 1f / ratio else ratio
    BoxWithConstraints(modifier.aspectRatio(displayRatio), contentAlignment = Alignment.Center) {
        val inner = if (rotation % 180 != 0) Modifier.requiredSize(maxHeight, maxWidth) else Modifier.fillMaxSize()
        Box(inner.rotate(rotation.toFloat()).background(Color.White), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), "Page ${index + 1}", contentScale = contentScale, modifier = Modifier.fillMaxSize())
            else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
fun PageThumbCard(
    renderer: PageRenderer,
    index: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    label: String = "${index + 1}",
    rotation: Int = 0,
    dim: Boolean = false,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (selected) badgeColor.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) badgeColor else cs.outlineVariant, RoundedCornerShape(14.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            PageImage(
                renderer, index,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).then(if (dim) Modifier.background(Color.Black) else Modifier),
                rotation = rotation
            )
            if (dim) Box(Modifier.matchParentSize().background(cs.surface.copy(alpha = 0.6f)))
            if (selected) Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(badgeColor, CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) badgeColor else cs.onSurfaceVariant, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Layout pieces

@Composable
fun ToolTopBar(tool: ToolDef?, title: String = tool?.title ?: "", subtitle: String? = tool?.subtitle, actions: @Composable RowScope.() -> Unit = {}) {
    val back = rememberBackAction()
    val accent = tool?.category?.accent ?: MaterialTheme.colorScheme.primary
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            if (tool != null) {
                Box(Modifier.size(40.dp).background(Accents.container(accent), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(tool.icon, null, tint = Accents.onContainer(accent), modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            actions()
        }
    }
}

@Composable
fun Section(title: String? = null, icon: ImageVector? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) },
                leadingIcon = if (value == selected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected, onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size)
            ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String, value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>,
    steps: Int = 0, valueText: String = value.toInt().toString()
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, onChange, valueRange = range, steps = steps)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

val PaletteColors = listOf(
    0xFF1B1B1F.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF5E35B1.toInt(), 0xFFD81B60.toInt(), 0xFFFFFFFF.toInt()
)

@Composable
fun ColorRow(selected: Int, onSelect: (Int) -> Unit, colors: List<Int> = PaletteColors, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            val sel = c == selected
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                    .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onSelect(c) },
                contentAlignment = Alignment.Center
            ) {
                if (sel) Icon(Icons.Rounded.Check, null, tint = if (c == 0xFFFFFFFF.toInt() || c == 0xFFFDD835.toInt()) Color.Black else Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun InfoBanner(text: String, icon: ImageVector = Icons.Rounded.Info, color: Color = MaterialTheme.colorScheme.primary) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.1f)).padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun BottomAction(text: String, enabled: Boolean = true, icon: ImageVector = Icons.Rounded.PlayArrow, secondary: (@Composable RowScope.() -> Unit)? = null, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            secondary?.invoke(this)
            Button(
                onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun EmptyPick(
    icon: ImageVector, title: String, subtitle: String, button: String, loading: Boolean,
    accent: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(132.dp).clip(RoundedCornerShape(40.dp))
                .background(Brush.linearGradient(listOf(Accents.container(accent), accent.copy(alpha = 0.28f)))),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Accents.onContainer(accent), modifier = Modifier.size(60.dp)) }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Button(onClick, enabled = !loading, modifier = Modifier.height(54.dp), shape = RoundedCornerShape(18.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Icon(Icons.Rounded.FolderOpen, null)
            Spacer(Modifier.width(10.dp)); Text(button, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Processed on your device — never uploaded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PdfSourceCard(pdf: PickedPdf, onChange: (() -> Unit)?, modifier: Modifier = Modifier) {
    var thumb by remember(pdf.file) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pdf.file) { thumb = ThumbCache.first(pdf.file, 200) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp, 68.dp).shadow(2.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Icon(Icons.Rounded.PictureAsPdf, null, tint = Accents.red)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(pdf.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${pdf.pageCount} page${if (pdf.pageCount == 1) "" else "s"} • ${Workspace.formatSize(pdf.size)}" + if (pdf.wasEncrypted) " • unlocked" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onChange != null) FilledTonalButton(onChange, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Change")
            }
        }
    }
}

// ---------------------------------------------------------------- Running jobs

sealed class JobState {
    data object Idle : JobState()
    data class Running(val progress: Float, val message: String) : JobState()
    data class Done(val result: ToolResult) : JobState()
    data class Failed(val message: String) : JobState()
}

class JobRunner(private val context: Context, private val scope: kotlinx.coroutines.CoroutineScope, private val toolId: String) {
    var state by mutableStateOf<JobState>(JobState.Idle)
        private set
    private var job: Job? = null

    fun run(block: suspend (Progress) -> ToolResult) {
        job?.cancel()
        state = JobState.Running(0f, "Starting…")
        job = scope.launch {
            try {
                val result = block { p, m -> state = JobState.Running(p.coerceIn(0f, 1f), m) }
                withContext(Dispatchers.IO) { result.files.forEach { RecentStore.addOutput(context, it, toolId) } }
                state = JobState.Done(result)
            } catch (e: CancellationException) {
                state = JobState.Idle
            } catch (e: OutOfMemoryError) {
                state = JobState.Failed("Not enough memory for this file. Try a lower quality/DPI setting.")
            } catch (e: Throwable) {
                state = JobState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun runFile(block: suspend (Progress) -> File) = run { p -> ToolResult(listOf(block(p))) }
    fun runFiles(message: String = "", block: suspend (Progress) -> List<File>) = run { p -> ToolResult(block(p), message) }

    fun cancel() { job?.cancel(); state = JobState.Idle }
    fun reset() { state = JobState.Idle }
}

@Composable
fun rememberJob(toolId: String): JobRunner {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { JobRunner(context.applicationContext, scope, toolId) }
}

@Composable
fun JobOverlay(job: JobRunner) {
    val s = job.state
    AnimatedVisibility(s is JobState.Running, enter = fadeIn(), exit = fadeOut()) {
        val r = s as? JobState.Running ?: JobState.Running(0f, "")
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)).clickable(enabled = true) {},
            contentAlignment = Alignment.Center
        ) {
            Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, modifier = Modifier.padding(32.dp).widthIn(max = 380.dp)) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        if (r.progress > 0f) CircularProgressIndicator(progress = { r.progress }, modifier = Modifier.size(72.dp), strokeWidth = 6.dp)
                        else CircularProgressIndicator(Modifier.size(72.dp), strokeWidth = 6.dp)
                        if (r.progress > 0f) Text("${(r.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(r.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text("Working offline on your device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    TextButton({ job.cancel() }) { Text("Cancel") }
                }
            }
        }
    }
    if (s is JobState.Failed) {
        AlertDialog(
            onDismissRequest = { job.reset() },
            icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Something went wrong") },
            text = { Text(s.message) },
            confirmButton = { TextButton({ job.reset() }) { Text("OK") } }
        )
    }
}

// ---------------------------------------------------------------- Results

@Composable
fun ResultPanel(result: ToolResult, toolId: String, onStartOver: () -> Unit) {
    val context = LocalContext.current
    val nav = LocalNav.current
    var showTools by remember { mutableStateOf(false) }
    val saver = rememberFileSaver()
    val primary = result.primary
    LaunchedEffect(result) { if (result.files.isNotEmpty() || result.openUri != null) PdfIndexRefresher.request(context) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).readableWidth(720.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = Accents.container(Accents.green)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Accents.green, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Done!", style = MaterialTheme.typography.titleLarge, color = Accents.onContainer(Accents.green))
                    Text(
                        result.message.ifBlank { if (result.files.size == 1) "Your file is ready" else "${result.files.size} files are ready" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        if (result.details.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            result.details.take(3).forEach { (k, v) ->
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(12.dp)) {
                        Text(v, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (primary != null) OutputPreview(primary, Modifier.fillMaxWidth().height(380.dp))

        result.files.forEach { f ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(fileIcon(f), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Workspace.formatSize(f.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (f.extension == "pdf") IconButton({ nav.openFile(f) }) { Icon(Icons.Rounded.Visibility, "Open") }
                    IconButton({ saver.saveAs(f, f.name) }) { Icon(Icons.Rounded.SaveAs, "Save as") }
                    IconButton({ Workspace.share(context, listOf(f)) }) { Icon(Icons.Rounded.Share, "Share") }
                }
            }
        }

        if (result.files.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { saver.toDownloads(result.files, null) },
                modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text("Save to Downloads", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            FilledTonalButton({ Workspace.share(context, result.files) }, Modifier.height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Share")
            }
        }
        result.openUri?.let { u ->
            Button({ nav.openUri(u) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.MenuBook, null); Spacer(Modifier.width(8.dp)); Text("Open updated document")
            }
        }
        if (primary?.extension == "pdf") Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ nav.openFile(primary) }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open")
            }
            OutlinedButton({ showTools = true }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("More tools")
            }
            OutlinedButton({ PrintHelper.printFile(context, primary, primary.nameWithoutExtension) }, Modifier.height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.Print, null, Modifier.size(18.dp))
            }
        }
        TextButton(onStartOver, Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Start over")
        }
        if (primary != null) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Kept in Files → Saved by app", style = MaterialTheme.typography.labelMedium)
                Text(com.example.utils.FileUtils.displayFolder(primary.path), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
    if (showTools && primary != null) ToolPickerSheet(onDismiss = { showTools = false }) { t ->
        showTools = false
        nav.openTool(t.id, Workspace.uriFor(context, primary))
    }
}

fun fileIcon(f: File): ImageVector = when (f.extension.lowercase()) {
    "pdf" -> Icons.Rounded.PictureAsPdf
    "png", "jpg", "jpeg" -> Icons.Rounded.Image
    "txt" -> Icons.Rounded.Description
    "zip" -> Icons.Rounded.FolderZip
    else -> Icons.Rounded.InsertDriveFile
}

@Composable
fun OutputPreview(file: File, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh) {
        when (file.extension.lowercase()) {
            "pdf" -> PdfPagerPreview(file)
            "png", "jpg", "jpeg" -> {
                var bmp by remember(file) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(file) { bmp = withContext(Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull() } }
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) } ?: CircularProgressIndicator()
                }
            }
            "txt" -> {
                var text by remember(file) { mutableStateOf("") }
                LaunchedEffect(file) { text = withContext(Dispatchers.IO) { runCatching { file.readText().take(20000) }.getOrDefault("") } }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text.ifBlank { "(no text)" }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(fileIcon(file), null, Modifier.size(64.dp), tint = cs.primary)
                    Text(file.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun PdfPagerPreview(file: File, modifier: Modifier = Modifier) {
    val renderer = rememberRenderer(file)
    if (renderer == null) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Preview unavailable") }; return }
    val pager = androidx.compose.foundation.pager.rememberPagerState { renderer.pageCount }
    Box(modifier.fillMaxSize()) {
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), pageSpacing = 16.dp) { i ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PageImage(renderer, i, Modifier.shadow(6.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)), widthPx = 900)
            }
        }
        Surface(
            Modifier.align(Alignment.BottomCenter).padding(10.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)
        ) {
            Text("${pager.currentPage + 1} / ${renderer.pageCount}", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPickerSheet(tools: List<ToolDef> = ToolCatalog.forOpenPdf, title: String = "Continue with…", onDismiss: () -> Unit, onPick: (ToolDef) -> Unit) {
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search tools") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(10.dp))
            val list = tools.filter { q.isBlank() || it.title.contains(q, true) || it.keywords.contains(q, true) }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Adaptive(96.dp),
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(list.size) { i -> ToolTile(list[i], Modifier.fillMaxWidth()) { onPick(list[i]) } }
            }
        }
    }
}

@Composable
fun ToolTile(tool: ToolDef, modifier: Modifier = Modifier, compact: Boolean = true, onClick: () -> Unit) {
    val accent = tool.category.accent
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(if (compact) 52.dp else 58.dp).background(Accents.container(accent), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(tool.icon, null, tint = Accents.onContainer(accent), modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(tool.title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StatPill(value: String, label: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Accents.container(accent)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = Accents.onContainer(accent))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, width: Dp? = null) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(7)) }, (width?.let { modifier.width(it) } ?: modifier),
        label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
