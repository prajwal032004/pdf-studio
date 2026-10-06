package com.example.ui.editor

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.core.*
import com.example.ui.common.*
import com.example.ui.theme.Accents
import com.example.ui.tools.ToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

enum class EditTool(val label: String, val icon: ImageVector) {
    SELECT("Move", Icons.Rounded.PanTool),
    HIGHLIGHT("Highlight", Icons.Rounded.BorderColor),
    UNDERLINE("Underline", Icons.Rounded.FormatUnderlined),
    STRIKE("Strike", Icons.Rounded.StrikethroughS),
    SQUIGGLY("Squiggly", Icons.Rounded.Gesture),
    PEN("Pen", Icons.Rounded.Edit),
    PENCIL("Pencil", Icons.Rounded.Create),
    MARKER("Marker", Icons.Rounded.Brush),
    RECT("Rectangle", Icons.Rounded.CropSquare),
    OVAL("Circle", Icons.Rounded.RadioButtonUnchecked),
    LINE("Line", Icons.Rounded.HorizontalRule),
    ARROW("Arrow", Icons.Rounded.ArrowRightAlt),
    TEXT("Text", Icons.Rounded.TextFields),
    NOTE("Comment", Icons.Rounded.Comment),
    STAMP("Stamp", Icons.Rounded.Approval),
    IMAGE("Image", Icons.Rounded.Image),
    SIGN("Sign", Icons.Rounded.HistoryEdu),
    DATE("Date", Icons.Rounded.Event),
    ERASER("Eraser", Icons.Rounded.AutoFixNormal)
}

private data class EditState(val marks: List<Mark> = emptyList(), val removed: Map<Int, Set<Int>> = emptyMap())

private fun initialToolFor(id: String) = when (id) {
    "sign" -> EditTool.SIGN; "add_text" -> EditTool.TEXT; "highlight" -> EditTool.HIGHLIGHT
    "comments" -> EditTool.NOTE; "stamps" -> EditTool.STAMP; "add_image" -> EditTool.IMAGE
    else -> EditTool.PEN
}

@Composable
fun EditorScreen(toolId: String, initialUri: Uri?) {
    val context = LocalContext.current
    val toolDef = ToolCatalog.get(toolId)
    val job = rememberJob(toolId)
    var pdf by remember { mutableStateOf<PickedPdf?>(null) }
    val picker = rememberPdfPicker { pdf = it; job.reset() }
    LaunchedEffect(initialUri) { if (initialUri != null && pdf == null) picker.load(initialUri) }
    val renderer = rememberRenderer(pdf?.file)

    var tool by remember { mutableStateOf(initialToolFor(toolId)) }
    var page by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(listOf(EditState())) }
    var cursor by remember { mutableIntStateOf(0) }
    val state = history[cursor]
    fun commit(s: EditState) { history = history.take(cursor + 1) + s; cursor = history.size - 1 }

    var color by remember { mutableIntStateOf(0xFF1E88E5.toInt()) }
    var width by remember { mutableFloatStateOf(0.004f) }
    var textSize by remember { mutableFloatStateOf(0.022f) }
    var selected by remember { mutableIntStateOf(-1) }
    var pendingImage by remember { mutableStateOf<Pair<Bitmap, ImageKind>?>(null) }
    var pendingLabel by remember { mutableStateOf("") }
    var showSignPad by remember { mutableStateOf(false) }
    var showStamps by remember { mutableStateOf(false) }
    var textDialogAt by remember { mutableStateOf<NPoint?>(null) }
    var noteDialogAt by remember { mutableStateOf<NPoint?>(null) }
    var dateDialogAt by remember { mutableStateOf<NPoint?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var author by remember { mutableStateOf(context.getSharedPreferences("editor", 0).getString("author", "") ?: "") }
    var flatten by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ConvertOps.decodeImage(context, uri, 1600)?.let { pendingImage = it to ImageKind.IMAGE; pendingLabel = "Image" }
    }

    LaunchedEffect(pdf) {
        if (pdf == null) return@LaunchedEffect
        when (tool) {
            EditTool.SIGN -> showSignPad = true
            EditTool.STAMP -> showStamps = true
            EditTool.IMAGE -> imagePicker.launch("image/*")
            else -> {}
        }
    }

    // Per-tool colour defaults make each tool feel right immediately.
    fun selectTool(t: EditTool) {
        tool = t; selected = -1
        when (t) {
            EditTool.HIGHLIGHT -> color = 0xFFFDD835.toInt()
            EditTool.UNDERLINE, EditTool.SQUIGGLY -> color = 0xFF43A047.toInt()
            EditTool.STRIKE -> color = 0xFFE53935.toInt()
            EditTool.MARKER -> { color = 0xFFFDD835.toInt(); width = 0.02f }
            EditTool.PENCIL -> { color = 0xFF424242.toInt(); width = 0.0018f }
            EditTool.PEN -> if (width > 0.012f) width = 0.004f
            EditTool.SIGN -> showSignPad = true
            EditTool.STAMP -> showStamps = true
            EditTool.IMAGE -> imagePicker.launch("image/*")
            else -> {}
        }
    }

    val dirty = state.marks.isNotEmpty() || state.removed.isNotEmpty()
    val nav = LocalNav.current
    var confirmLeave by remember { mutableStateOf(false) }
    // Unsaved edits are never lost silently: back asks first.
    BackHandler(enabled = dirty && job.state is JobState.Idle) { confirmLeave = true }

    Scaffold(
        topBar = {
            ToolTopBar(toolDef, subtitle = pdf?.let { "Page ${page + 1} of ${it.pageCount}" + if (dirty) " • ${state.marks.size} edit(s)" else "" } ?: toolDef?.subtitle) {
                if (pdf != null && job.state !is JobState.Done) {
                    IconButton({ cursor--; selected = -1 }, enabled = cursor > 0) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
                    IconButton({ cursor++; selected = -1 }, enabled = cursor < history.size - 1) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
                    FilledIconButton({ showSave = true }, enabled = dirty) { Icon(Icons.Rounded.Check, "Save") }
                }
            }
        },
        bottomBar = {
            if (pdf != null && job.state !is JobState.Done) EditorToolbar(tool, ::selectTool, color, { color = it }, width, { width = it }, textSize, { textSize = it },
                selectedIsImage = state.marks.getOrNull(selected) is Mark.Image, onDeleteSelected = {
                    if (selected >= 0) { commit(state.copy(marks = state.marks.filterIndexed { i, _ -> i != selected })); selected = -1 }
                })
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val s = job.state
            val p = pdf
            when {
                s is JobState.Done -> Surface(color = MaterialTheme.colorScheme.surface) { ResultPanel(s.result, toolId) { job.reset(); history = listOf(EditState()); cursor = 0 } }
                p == null || renderer == null -> Surface(color = MaterialTheme.colorScheme.surface) {
                    EmptyPick(toolDef?.icon ?: Icons.Rounded.Draw, toolDef?.title ?: "Annotate", "Choose a PDF to mark up, sign, stamp or comment on.",
                        "Choose PDF", picker.loading, toolDef?.category?.accent ?: Accents.pink) { picker.launch() }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    PageCanvas(
                        renderer, p.file, page, tool, state, color, width, textSize, selected,
                        onSelect = { selected = it },
                        onCommit = ::commit,
                        onTapPoint = { pt ->
                            when (tool) {
                                EditTool.TEXT -> textDialogAt = pt
                                EditTool.NOTE -> noteDialogAt = pt
                                EditTool.DATE -> dateDialogAt = pt
                                else -> {}
                            }
                        },
                        pendingImage = pendingImage, onPlaced = { idx -> pendingImage = null; selected = idx; tool = EditTool.SELECT }, pendingLabel = pendingLabel,
                        modifier = Modifier.weight(1f)
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ page = (page - 1).coerceAtLeast(0); selected = -1 }, enabled = page > 0) { Icon(Icons.Rounded.ChevronLeft, "Previous page") }
                        Text("${page + 1} / ${p.pageCount}", style = MaterialTheme.typography.labelLarge)
                        IconButton({ page = (page + 1).coerceAtMost(p.pageCount - 1); selected = -1 }, enabled = page < p.pageCount - 1) { Icon(Icons.Rounded.ChevronRight, "Next page") }
                        Spacer(Modifier.weight(1f))
                        if (pendingImage != null) AssistChip({}, { Text("Tap the page to place") }, leadingIcon = { Icon(Icons.Rounded.TouchApp, null, Modifier.size(16.dp)) })
                        else Text(hintFor(tool), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            JobOverlay(job)
        }
    }

    if (showSignPad) SignaturePadDialog(onDismiss = { showSignPad = false; if (tool == EditTool.SIGN) tool = EditTool.SELECT }) { bmp ->
        showSignPad = false; pendingImage = bmp to ImageKind.SIGNATURE; pendingLabel = "Signature"
    }
    if (showStamps) StampSheet(onDismiss = { showStamps = false }) { bmp, label -> showStamps = false; pendingImage = bmp to ImageKind.STAMP; pendingLabel = label }
    textDialogAt?.let { at ->
        TextEntryDialog("Add text", "", multiline = true, onDismiss = { textDialogAt = null }) { txt, bold, bg ->
            textDialogAt = null
            if (txt.isNotBlank()) commit(state.copy(marks = state.marks + Mark.Text(page, at, txt, color, textSize, bold, if (bg) 0xFFFFFFFF.toInt() else null, pageAspect(renderer, page))))
        }
    }
    noteDialogAt?.let { at ->
        TextEntryDialog("Comment", "", multiline = true, showStyle = false, onDismiss = { noteDialogAt = null }) { txt, _, _ ->
            noteDialogAt = null
            if (txt.isNotBlank()) commit(state.copy(marks = state.marks + Mark.Note(page, at, txt, if (color == 0xFF1E88E5.toInt()) 0xFFFFB300.toInt() else color)))
        }
    }
    dateDialogAt?.let { at ->
        DateStampDialog(onDismiss = { dateDialogAt = null }) { text, asStamp ->
            dateDialogAt = null
            if (asStamp) {
                val bmp = AnnotOps.stampBitmap(text, color, null)
                val w = 0.28f; val h = w * bmp.height / bmp.width * (renderer?.pageSize(page)?.let { it.first.toFloat() / it.second } ?: 0.707f)
                commit(state.copy(marks = state.marks + Mark.Image(page, NRect(at.x, at.y, at.x + w, at.y + h), bmp, ImageKind.STAMP, "Date stamp")))
            } else commit(state.copy(marks = state.marks + Mark.Text(page, at, text, color, textSize, aspect = pageAspect(renderer, page))))
        }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        icon = { Icon(Icons.Rounded.Warning, null) },
        title = { Text("Discard your edits?") },
        text = { Text("You have ${state.marks.size} unsaved change(s). Save them as a new PDF, or discard and leave.") },
        confirmButton = { Button({ confirmLeave = false; showSave = true }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton({ confirmLeave = false; nav.back() }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                TextButton({ confirmLeave = false }) { Text("Keep editing") }
            }
        }
    )
    if (showSave) AlertDialog(
        onDismissRequest = { showSave = false },
        icon = { Icon(Icons.Rounded.Save, null) },
        title = { Text("Save changes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${state.marks.size} new annotation(s)" + if (state.removed.isNotEmpty()) ", ${state.removed.values.sumOf { it.size }} removed" else "")
                OutlinedTextField(author, { author = it }, label = { Text("Author name (shown on comments)") }, singleLine = true)
                SwitchRow("Flatten", "Burn edits into the page so they can't be moved or deleted", flatten) { flatten = it }
            }
        },
        confirmButton = {
            Button({
                showSave = false
                context.getSharedPreferences("editor", 0).edit().putString("author", author).apply()
                val p = pdf ?: return@Button
                val marks = state.marks; val removed = state.removed; val a = author.ifBlank { "PDF Studio" }; val fl = flatten
                job.runFile { prog -> AnnotOps.apply(context, p, marks, removed, a, fl, prog) }
            }) { Text("Save PDF") }
        },
        dismissButton = { TextButton({ showSave = false }) { Text("Cancel") } }
    )
}

private fun pageAspect(r: PageRenderer?, page: Int): Float =
    r?.let { runCatching { it.pageSize(page).let { (w, h) -> w.toFloat() / h } }.getOrNull() } ?: 0.707f

private fun hintFor(t: EditTool) = when (t) {
    EditTool.SELECT -> "Pinch to zoom · drag items to move"
    EditTool.HIGHLIGHT, EditTool.UNDERLINE, EditTool.STRIKE, EditTool.SQUIGGLY -> "Drag across text"
    EditTool.PEN, EditTool.PENCIL, EditTool.MARKER -> "Draw with your finger"
    EditTool.RECT, EditTool.OVAL, EditTool.LINE, EditTool.ARROW -> "Drag to draw"
    EditTool.TEXT, EditTool.NOTE, EditTool.DATE -> "Tap where it should go"
    EditTool.ERASER -> "Tap an item to erase it"
    else -> "Tap the page to place"
}

// ---------------------------------------------------------------- Page canvas

@Composable
private fun PageCanvas(
    renderer: PageRenderer, file: File, page: Int, tool: EditTool, state: EditState, color: Int, width: Float, textSize: Float,
    selected: Int, onSelect: (Int) -> Unit, onCommit: (EditState) -> Unit, onTapPoint: (NPoint) -> Unit,
    pendingImage: Pair<Bitmap, ImageKind>?, onPlaced: (Int) -> Unit, pendingLabel: String, modifier: Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(page) { scale = 1f; offset = Offset.Zero }
    val ratio = remember(page) { renderer.pageSize(page).let { it.first.toFloat() / it.second } }
    var words by remember(file, page) { mutableStateOf<List<WordBox>?>(null) }
    LaunchedEffect(file, page, tool) {
        if (words == null && tool in listOf(EditTool.HIGHLIGHT, EditTool.UNDERLINE, EditTool.STRIKE, EditTool.SQUIGGLY))
            words = withContext(Dispatchers.IO) { TextOps.words(file, page) }
    }
    var existing by remember(file, page) { mutableStateOf<List<ExistingAnnot>>(emptyList()) }
    LaunchedEffect(file, page) { existing = withContext(Dispatchers.IO) { AnnotOps.listAnnotations(file, page) } }

    // Live gesture state
    var stroke by remember { mutableStateOf<List<NPoint>>(emptyList()) }
    var dragStart by remember { mutableStateOf<NPoint?>(null) }
    var dragEnd by remember { mutableStateOf<NPoint?>(null) }
    var moveMode by remember { mutableIntStateOf(0) } // 1 move, 2 resize
    val cur by rememberUpdatedState(state)
    val curTool by rememberUpdatedState(tool)
    val curSel by rememberUpdatedState(selected)
    val curPending by rememberUpdatedState(pendingImage)

    BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp))
            .pointerInput(tool) {
                if (tool == EditTool.SELECT) detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val boxW = maxWidth; val boxH = maxHeight
        val (pw, ph) = if (boxW / boxH > ratio) (boxH * ratio) to boxH else boxW to (boxW / ratio)
        Box(
            Modifier.size(pw, ph).graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                .background(Color.White)
        ) {
            PageImage(renderer, page, Modifier.fillMaxSize(), widthPx = 1400)
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(tool, page, pendingImage) {
                        detectTapGestures { pos ->
                            val pt = NPoint(pos.x / size.width, pos.y / size.height)
                            val st = cur
                            val pend = curPending
                            if (pend != null) {
                                val (bmp, kind) = pend
                                val w = if (kind == ImageKind.SIGNATURE) 0.3f else if (kind == ImageKind.STAMP) 0.3f else 0.4f
                                val h = w * bmp.height / bmp.width * ratio
                                val r = NRect((pt.x - w / 2).coerceIn(0f, 1f - w), (pt.y - h / 2).coerceIn(0f, 1f - h), 0f, 0f).let { NRect(it.left, it.top, it.left + w, it.top + h) }
                                onCommit(st.copy(marks = st.marks + Mark.Image(page, r, bmp, kind, pendingLabel)))
                                onPlaced(st.marks.size)
                                return@detectTapGestures
                            }
                            when (curTool) {
                                EditTool.TEXT, EditTool.NOTE, EditTool.DATE -> onTapPoint(pt)
                                EditTool.ERASER -> {
                                    val hit = st.marks.indexOfLast { it.page == page && hitTest(it, pt) }
                                    if (hit >= 0) onCommit(st.copy(marks = st.marks.filterIndexed { i, _ -> i != hit }))
                                    else existing.lastOrNull { it.rect.contains(pt.x, pt.y) && it.index !in st.removed[page].orEmpty() }?.let { ex ->
                                        onCommit(st.copy(removed = st.removed + (page to (st.removed[page].orEmpty() + ex.index))))
                                    }
                                }
                                EditTool.SELECT -> onSelect(st.marks.indexOfLast { it.page == page && (it is Mark.Image || it is Mark.Text) && hitTest(it, pt) })
                                else -> {}
                            }
                        }
                    }
                    .pointerInput(tool, page) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val pt = NPoint(pos.x / size.width, pos.y / size.height)
                                when (curTool) {
                                    EditTool.PEN, EditTool.PENCIL, EditTool.MARKER -> stroke = listOf(pt)
                                    EditTool.SELECT -> {
                                        val m = cur.marks.getOrNull(curSel)
                                        moveMode = when {
                                            m == null || m.page != page -> 0
                                            m is Mark.Image && hypot(pt.x - m.rect.right, pt.y - m.rect.bottom) < 0.05f -> 2
                                            hitTest(m, pt) -> 1
                                            else -> 0
                                        }
                                        dragStart = pt; dragEnd = pt
                                    }
                                    else -> { dragStart = pt; dragEnd = pt }
                                }
                            },
                            onDrag = { change, amount ->
                                val t = curTool
                                if (t == EditTool.SELECT && moveMode == 0) return@detectDragGestures
                                change.consume()
                                val pt = NPoint(change.position.x / size.width, change.position.y / size.height)
                                when (t) {
                                    EditTool.PEN, EditTool.PENCIL, EditTool.MARKER -> {
                                        val last = stroke.lastOrNull()
                                        if (last == null || abs(last.x - pt.x) + abs(last.y - pt.y) > 0.002f) stroke = stroke + pt
                                    }
                                    else -> dragEnd = pt
                                }
                            },
                            onDragEnd = {
                                val st = cur
                                val s0 = dragStart; val e0 = dragEnd
                                when (curTool) {
                                    EditTool.PEN, EditTool.PENCIL, EditTool.MARKER -> {
                                        if (stroke.size > 1) onCommit(st.copy(marks = st.marks + Mark.Ink(page, listOf(stroke), color, width, if (curTool == EditTool.MARKER) 0.4f else 1f)))
                                        stroke = emptyList()
                                    }
                                    EditTool.RECT, EditTool.OVAL, EditTool.LINE, EditTool.ARROW -> if (s0 != null && e0 != null && hypot(e0.x - s0.x, e0.y - s0.y) > 0.01f) {
                                        val kind = when (curTool) { EditTool.RECT -> ShapeKind.RECT; EditTool.OVAL -> ShapeKind.OVAL; EditTool.LINE -> ShapeKind.LINE; else -> ShapeKind.ARROW }
                                        onCommit(st.copy(marks = st.marks + Mark.Shape(page, kind, s0, e0, color, width)))
                                    }
                                    EditTool.HIGHLIGHT, EditTool.UNDERLINE, EditTool.STRIKE, EditTool.SQUIGGLY -> if (s0 != null && e0 != null) {
                                        val sel = NRect(minOf(s0.x, e0.x), minOf(s0.y, e0.y), maxOf(s0.x, e0.x), maxOf(s0.y, e0.y))
                                        val rects = snapToWords(words.orEmpty(), sel, s0, e0)
                                        if (rects.isNotEmpty()) {
                                            val kind = when (curTool) { EditTool.HIGHLIGHT -> MarkupKind.HIGHLIGHT; EditTool.UNDERLINE -> MarkupKind.UNDERLINE; EditTool.STRIKE -> MarkupKind.STRIKEOUT; else -> MarkupKind.SQUIGGLY }
                                            onCommit(st.copy(marks = st.marks + Mark.Markup(page, kind, rects, color)))
                                        }
                                    }
                                    EditTool.SELECT -> if (moveMode != 0 && s0 != null && e0 != null) {
                                        val m = st.marks.getOrNull(curSel)
                                        val moved = if (m != null) transformMark(m, s0, e0, moveMode) else null
                                        if (moved != null) onCommit(st.copy(marks = st.marks.mapIndexed { i, x -> if (i == curSel) moved else x }))
                                    }
                                    else -> {}
                                }
                                dragStart = null; dragEnd = null; moveMode = 0
                            },
                            onDragCancel = { stroke = emptyList(); dragStart = null; dragEnd = null; moveMode = 0 }
                        )
                    }
            ) {
                // Existing annotations (outlined in eraser mode, crossed when marked for removal)
                if (tool == EditTool.ERASER) existing.forEach { ex ->
                    val removed = ex.index in state.removed[page].orEmpty()
                    val o = Offset(ex.rect.left * size.width, ex.rect.top * size.height)
                    val sz = Size(ex.rect.width * size.width, ex.rect.height * size.height)
                    drawRect(if (removed) Color.Red.copy(alpha = 0.25f) else Color(0x221E88E5), o, sz)
                    drawRect(if (removed) Color.Red else Color(0xFF1E88E5), o, sz, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                }
                state.marks.forEachIndexed { i, m ->
                    if (m.page != page) return@forEachIndexed
                    val preview = if (i == selected && moveMode != 0 && dragStart != null && dragEnd != null) transformMark(m, dragStart!!, dragEnd!!, moveMode) ?: m else m
                    drawMark(preview)
                    if (i == selected) {
                        val b = preview.bounds()
                        drawRect(Color(0xFF7E57C2), Offset(b.left * size.width, b.top * size.height), Size(b.width * size.width, b.height * size.height),
                            style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                        if (preview is Mark.Image) drawCircle(Color(0xFF7E57C2), 14f, Offset(b.right * size.width, b.bottom * size.height))
                    }
                }
                // Live previews
                if (stroke.size > 1) drawMark(Mark.Ink(page, listOf(stroke), color, width, if (tool == EditTool.MARKER) 0.4f else 1f))
                val s0 = dragStart; val e0 = dragEnd
                if (s0 != null && e0 != null) when (tool) {
                    EditTool.RECT -> drawMark(Mark.Shape(page, ShapeKind.RECT, s0, e0, color, width))
                    EditTool.OVAL -> drawMark(Mark.Shape(page, ShapeKind.OVAL, s0, e0, color, width))
                    EditTool.LINE -> drawMark(Mark.Shape(page, ShapeKind.LINE, s0, e0, color, width))
                    EditTool.ARROW -> drawMark(Mark.Shape(page, ShapeKind.ARROW, s0, e0, color, width))
                    EditTool.HIGHLIGHT, EditTool.UNDERLINE, EditTool.STRIKE, EditTool.SQUIGGLY -> {
                        val sel = NRect(minOf(s0.x, e0.x), minOf(s0.y, e0.y), maxOf(s0.x, e0.x), maxOf(s0.y, e0.y))
                        snapToWords(words.orEmpty(), sel, s0, e0).forEach { r ->
                            drawRect(Color(color).copy(alpha = 0.35f), Offset(r.left * size.width, r.top * size.height), Size(r.width * size.width, r.height * size.height))
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

/** Picks words between the drag start and end in reading order; falls back to the dragged box. */
private fun snapToWords(words: List<WordBox>, sel: NRect, s: NPoint, e: NPoint): List<NRect> {
    if (words.isEmpty()) return if (sel.width > 0.01f) listOf(if (sel.height < 0.01f) NRect(sel.left, sel.top - 0.008f, sel.right, sel.top + 0.012f) else sel) else emptyList()
    val (a, b) = if (s.y < e.y || (abs(s.y - e.y) < 0.01f && s.x < e.x)) s to e else e to s
    fun lineOf(w: WordBox) = (w.rect.top + w.rect.bottom) / 2
    val picked = words.filter { w ->
        val cy = lineOf(w)
        val h = w.rect.height
        when {
            abs(a.y - b.y) < h -> abs(cy - a.y) < h && w.rect.right > minOf(a.x, b.x) && w.rect.left < maxOf(a.x, b.x)
            abs(cy - a.y) < h / 2 -> w.rect.right > a.x
            abs(cy - b.y) < h / 2 -> w.rect.left < b.x
            else -> cy > a.y && cy < b.y
        }
    }
    if (picked.isEmpty()) return if (sel.width > 0.01f && sel.height > 0.005f) listOf(sel) else emptyList()
    // Merge words on the same line into single rectangles.
    val out = mutableListOf<NRect>()
    picked.sortedWith(compareBy({ (lineOf(it) * 200).toInt() }, { it.rect.left })).forEach { w ->
        val last = out.lastOrNull()
        if (last != null && abs((last.top + last.bottom) / 2 - lineOf(w)) < w.rect.height * 0.5f) out[out.size - 1] = last.union(w.rect)
        else out += w.rect
    }
    return out
}

private fun hitTest(m: Mark, p: NPoint): Boolean {
    val b = m.bounds()
    val pad = 0.015f
    return p.x in (b.left - pad)..(b.right + pad) && p.y in (b.top - pad)..(b.bottom + pad)
}

private fun transformMark(m: Mark, s: NPoint, e: NPoint, mode: Int): Mark? {
    val dx = e.x - s.x; val dy = e.y - s.y
    return when (m) {
        is Mark.Image -> if (mode == 2) {
            val w = (m.rect.width + dx).coerceAtLeast(0.04f)
            val h = w * m.rect.height / m.rect.width
            m.copy(rect = NRect(m.rect.left, m.rect.top, m.rect.left + w, m.rect.top + h))
        } else m.copy(rect = NRect(m.rect.left + dx, m.rect.top + dy, m.rect.right + dx, m.rect.bottom + dy))
        is Mark.Text -> m.copy(at = NPoint(m.at.x + dx, m.at.y + dy))
        else -> null
    }
}

private fun DrawScope.drawMark(m: Mark) {
    val w = size.width; val h = size.height
    fun o(p: NPoint) = Offset(p.x * w, p.y * h)
    val c = Color(m.color)
    when (m) {
        is Mark.Ink -> m.strokes.forEach { s ->
            if (s.size < 2) return@forEach
            val path = Path().apply { moveTo(s[0].x * w, s[0].y * h); s.drop(1).forEach { lineTo(it.x * w, it.y * h) } }
            drawPath(path, c.copy(alpha = m.opacity), style = Stroke(m.width * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        is Mark.Shape -> {
            val sw = m.width * w
            val l = minOf(m.start.x, m.end.x) * w; val t = minOf(m.start.y, m.end.y) * h
            val sz = Size(abs(m.end.x - m.start.x) * w, abs(m.end.y - m.start.y) * h)
            when (m.kind) {
                ShapeKind.RECT -> drawRect(c, Offset(l, t), sz, style = Stroke(sw, join = StrokeJoin.Round))
                ShapeKind.OVAL -> drawOval(c, Offset(l, t), sz, style = Stroke(sw))
                ShapeKind.LINE -> drawLine(c, o(m.start), o(m.end), sw, StrokeCap.Round)
                ShapeKind.ARROW -> {
                    drawLine(c, o(m.start), o(m.end), sw, StrokeCap.Round)
                    val ang = kotlin.math.atan2((m.end.y - m.start.y) * h, (m.end.x - m.start.x) * w)
                    val len = maxOf(sw * 4, hypot((m.end.x - m.start.x) * w, (m.end.y - m.start.y) * h) * 0.08f).coerceAtMost(60f)
                    for (d in floatArrayOf(-0.45f, 0.45f)) {
                        drawLine(c, o(m.end), Offset(m.end.x * w - len * kotlin.math.cos(ang + d), m.end.y * h - len * kotlin.math.sin(ang + d)), sw, StrokeCap.Round)
                    }
                }
            }
        }
        is Mark.Markup -> m.rects.forEach { r ->
            val tl = Offset(r.left * w, r.top * h); val sz = Size(r.width * w, r.height * h)
            val thick = (sz.height * 0.08f).coerceIn(1.5f, 5f)
            when (m.kind) {
                MarkupKind.HIGHLIGHT -> drawRect(c.copy(alpha = 0.4f), tl, sz)
                MarkupKind.UNDERLINE -> drawLine(c, Offset(tl.x, tl.y + sz.height * 0.92f), Offset(tl.x + sz.width, tl.y + sz.height * 0.92f), thick)
                MarkupKind.STRIKEOUT -> drawLine(c, Offset(tl.x, tl.y + sz.height * 0.55f), Offset(tl.x + sz.width, tl.y + sz.height * 0.55f), thick)
                MarkupKind.SQUIGGLY -> {
                    val path = Path(); val steps = ((sz.width / sz.height) * 4).toInt().coerceIn(4, 400)
                    for (k in 0..steps) {
                        val x = tl.x + sz.width * k / steps; val y = tl.y + sz.height * (if (k % 2 == 0) 0.88f else 0.98f)
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, c, style = Stroke(thick * 0.8f))
                }
            }
        }
        is Mark.Text -> drawIntoCanvas { cv ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = m.color; textSize = m.size * w
                typeface = Typeface.create(Typeface.SANS_SERIF, if (m.bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val b = m.bounds()
            if (m.background != null) cv.nativeCanvas.drawRect(b.left * w, b.top * h, b.right * w, b.bottom * h, Paint().apply { color = m.background })
            m.text.lines().forEachIndexed { i, line ->
                cv.nativeCanvas.drawText(line, m.at.x * w, (m.at.y + m.sizeY * 1.25f * i + m.sizeY * 0.95f) * h, paint)
            }
        }
        is Mark.Note -> {
            val p = o(m.at); val s = 0.04f * w
            drawRoundRect(c, Offset(p.x, p.y), Size(s, s * 0.8f), androidx.compose.ui.geometry.CornerRadius(6f))
            for (k in 0..2) drawLine(Color.White, Offset(p.x + s * 0.2f, p.y + s * (0.22f + k * 0.18f)), Offset(p.x + s * 0.8f, p.y + s * (0.22f + k * 0.18f)), 2f)
        }
        is Mark.Image -> drawImage(
            m.bitmap.asImageBitmap(), dstOffset = androidx.compose.ui.unit.IntOffset((m.rect.left * w).toInt(), (m.rect.top * h).toInt()),
            dstSize = IntSize((m.rect.width * w).toInt().coerceAtLeast(1), (m.rect.height * h).toInt().coerceAtLeast(1))
        )
    }
}

// ---------------------------------------------------------------- Toolbar

@Composable
private fun EditorToolbar(
    tool: EditTool, onTool: (EditTool) -> Unit, color: Int, onColor: (Int) -> Unit, width: Float, onWidth: (Float) -> Unit,
    textSize: Float, onTextSize: (Float) -> Unit, selectedIsImage: Boolean, onDeleteSelected: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 12.dp) {
        Column(Modifier.navigationBarsPadding()) {
            val showColor = tool !in listOf(EditTool.SELECT, EditTool.ERASER, EditTool.IMAGE, EditTool.SIGN)
            if (showColor || tool == EditTool.SELECT) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showColor) ColorRow(color, onColor, modifier = Modifier.weight(1f))
                if (tool == EditTool.SELECT) {
                    Text(if (selectedIsImage) "Drag to move · corner dot to resize" else "Tap text or an image to select it",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (selectedIsImage) IconButton(onDeleteSelected) { Icon(Icons.Rounded.DeleteOutline, "Delete selected") }
                }
            }
            if (tool in listOf(EditTool.PEN, EditTool.PENCIL, EditTool.MARKER, EditTool.RECT, EditTool.OVAL, EditTool.LINE, EditTool.ARROW)) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.LineWeight, null, Modifier.size(18.dp))
                Slider(width, onWidth, valueRange = 0.001f..0.03f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Box(Modifier.size((4 + width * 700).dp.coerceAtMost(26.dp)).background(Color(color), CircleShape)) }
            }
            if (tool == EditTool.TEXT || tool == EditTool.DATE) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FormatSize, null, Modifier.size(18.dp))
                Slider(textSize, onTextSize, valueRange = 0.01f..0.06f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                EditTool.entries.forEach { t ->
                    val sel = t == tool
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onTool(t) }.padding(horizontal = 10.dp, vertical = 6.dp).widthIn(min = 52.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(t.icon, t.label, tint = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Dialogs

@Composable
private fun TextEntryDialog(title: String, initial: String, multiline: Boolean, showStyle: Boolean = true, onDismiss: () -> Unit, onDone: (String, Boolean, Boolean) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var bold by remember { mutableStateOf(false) }
    var bg by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = if (multiline) 3 else 1, singleLine = !multiline, placeholder = { Text("Type here") })
                if (showStyle) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(bold, { bold = !bold }, { Text("Bold", fontWeight = FontWeight.Bold) })
                    FilterChip(bg, { bg = !bg }, { Text("White background") })
                }
            }
        },
        confirmButton = { Button({ onDone(text, bold, bg) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DateStampDialog(onDismiss: () -> Unit, onDone: (String, Boolean) -> Unit) {
    val now = Date()
    val formats = listOf("dd MMM yyyy", "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "dd MMM yyyy, HH:mm", "EEEE, d MMMM yyyy", "HH:mm")
    var fmt by remember { mutableStateOf(formats[0]) }
    var prefix by remember { mutableStateOf("") }
    var asStamp by remember { mutableStateOf(false) }
    val text = (if (prefix.isNotBlank()) "$prefix " else "") + SimpleDateFormat(fmt, Locale.getDefault()).format(now)
    AlertDialog(
        onDismissRequest = onDismiss, icon = { Icon(Icons.Rounded.Event, null) }, title = { Text("Date & time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                formats.forEach { f ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { fmt = f }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(f == fmt, { fmt = f }); Text(SimpleDateFormat(f, Locale.getDefault()).format(now))
                    }
                }
                OutlinedTextField(prefix, { prefix = it }, label = { Text("Prefix (e.g. Signed on)") }, singleLine = true)
                SwitchRow("Stamp style", "Bordered stamp instead of plain text", asStamp) { asStamp = it }
                Text("Preview: $text", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { Button({ onDone(text, asStamp) }) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StampSheet(onDismiss: () -> Unit, onPick: (Bitmap, String) -> Unit) {
    val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
    val stamps = listOf(
        Triple("APPROVED", 0xFF2E7D32.toInt(), date), Triple("REJECTED", 0xFFC62828.toInt(), date), Triple("DRAFT", 0xFF546E7A.toInt(), null),
        Triple("CONFIDENTIAL", 0xFFC62828.toInt(), null), Triple("PAID", 0xFF2E7D32.toInt(), date), Triple("RECEIVED", 0xFF1565C0.toInt(), date),
        Triple("FINAL", 0xFF6A1B9A.toInt(), null), Triple("VOID", 0xFFC62828.toInt(), null), Triple("REVIEWED", 0xFF1565C0.toInt(), date),
        Triple("URGENT", 0xFFE65100.toInt(), null), Triple("COPY", 0xFF546E7A.toInt(), null), Triple("SIGN HERE", 0xFFE65100.toInt(), null)
    )
    var custom by remember { mutableStateOf("") }
    var customColor by remember { mutableIntStateOf(0xFF1565C0.toInt()) }
    val bitmaps = remember { stamps.map { (t, c, s) -> AnnotOps.stampBitmap(t, c, s) } }
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Stamps", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.heightIn(max = 360.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(stamps.size) { i ->
                    Surface(onClick = { onPick(bitmaps[i], stamps[i].first) }, shape = RoundedCornerShape(14.dp), color = Color.White,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Image(bitmaps[i].asImageBitmap(), stamps[i].first, Modifier.padding(10.dp).fillMaxWidth().height(56.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Custom stamp", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(custom, { custom = it.uppercase() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("YOUR TEXT") })
                Button({ onPick(AnnotOps.stampBitmap(custom, customColor, date), custom) }, enabled = custom.isNotBlank()) { Text("Use") }
            }
            ColorRow(customColor, { customColor = it }, listOf(0xFF2E7D32.toInt(), 0xFFC62828.toInt(), 0xFF1565C0.toInt(), 0xFF6A1B9A.toInt(), 0xFFE65100.toInt(), 0xFF212121.toInt()))
        }
    }
}

// ---------------------------------------------------------------- Signature pad

private fun signaturesDir(context: android.content.Context) = File(context.filesDir, "signatures").apply { mkdirs() }

@Composable
fun SignaturePadDialog(onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(0) } // 0 draw, 1 type, 2 saved
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var inkColor by remember { mutableIntStateOf(0xFF0D1B6E.toInt()) }
    var thickness by remember { mutableFloatStateOf(6f) }
    var typed by remember { mutableStateOf("") }
    var save by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf(signaturesDir(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()) }
    if (saved.isNotEmpty() && mode == 0 && strokes.isEmpty()) LaunchedEffect(Unit) { mode = 2 }

    fun renderDrawn(): Bitmap? {
        val pts = strokes.flatten()
        if (pts.isEmpty()) return null
        val minX = pts.minOf { it.x } - thickness * 2; val minY = pts.minOf { it.y } - thickness * 2
        val maxX = pts.maxOf { it.x } + thickness * 2; val maxY = pts.maxOf { it.y } + thickness * 2
        val w = (maxX - minX).toInt().coerceAtLeast(4); val h = (maxY - minY).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor; style = Paint.Style.STROKE; strokeWidth = thickness; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        strokes.forEach { s ->
            val path = android.graphics.Path()
            s.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x - minX, p.y - minY) else path.lineTo(p.x - minX, p.y - minY) }
            if (s.size == 1) path.lineTo(s[0].x - minX + 0.5f, s[0].y - minY)
            c.drawPath(path, paint)
        }
        return bmp
    }

    fun renderTyped(): Bitmap? {
        if (typed.isBlank()) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor; textSize = 140f; typeface = Typeface.create("cursive", Typeface.NORMAL) }
        val w = (paint.measureText(typed) + 40).toInt(); val h = 200
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { android.graphics.Canvas(it).drawText(typed, 20f, 145f, paint) }
    }

    fun finish(bmp: Bitmap?) {
        if (bmp == null) return
        if (save && mode != 2) runCatching { File(signaturesDir(context), "sig_${System.currentTimeMillis()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        onDone(bmp)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.HistoryEdu, null) },
        title = { Text("Your signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedChoice(listOf(0 to "Draw", 1 to "Type", 2 to "Saved (${saved.size})"), mode, { mode = it })
                when (mode) {
                    0 -> {
                        Box(
                            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        ) {
                            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { current = listOf(it) },
                                    onDrag = { c, _ -> c.consume(); current = current + c.position },
                                    onDragEnd = { strokes.add(current); current = emptyList() }
                                )
                            }) {
                                drawLine(Color.LightGray, Offset(24f, size.height * 0.75f), Offset(size.width - 24f, size.height * 0.75f), 2f)
                                (strokes + listOf(current)).forEach { s ->
                                    if (s.size > 1) drawPath(Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } },
                                        Color(inkColor), style = Stroke(thickness, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                            }
                            if (strokes.isEmpty() && current.isEmpty()) Text("Sign here", Modifier.align(Alignment.Center), color = Color.LightGray)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(thickness, { thickness = it }, valueRange = 2f..14f, modifier = Modifier.weight(1f))
                            TextButton({ strokes.clear() }) { Text("Clear") }
                        }
                    }
                    1 -> {
                        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("Type your name") }, singleLine = true)
                        renderTyped()?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(80.dp).background(Color.White, RoundedCornerShape(12.dp))) }
                    }
                    else -> Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (saved.isEmpty()) Text("No saved signatures yet.", style = MaterialTheme.typography.bodySmall)
                        saved.forEach { f ->
                            val bmp = remember(f) { android.graphics.BitmapFactory.decodeFile(f.path) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(onClick = { if (bmp != null) onDone(bmp) }, shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.weight(1f).height(70.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                    bmp?.let { Image(it.asImageBitmap(), "Saved signature", Modifier.padding(8.dp)) }
                                }
                                IconButton({ f.delete(); saved = saved - f }) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
                            }
                        }
                    }
                }
                if (mode != 2) {
                    ColorRow(inkColor, { inkColor = it }, listOf(0xFF0D1B6E.toInt(), 0xFF000000.toInt(), 0xFF1E88E5.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt()))
                    SwitchRow("Save for next time", "Stored only on this device", save) { save = it }
                }
            }
        },
        confirmButton = {
            if (mode != 2) Button({ finish(if (mode == 0) renderDrawn() else renderTyped()) }, enabled = if (mode == 0) strokes.isNotEmpty() else typed.isNotBlank()) { Text("Use signature") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
