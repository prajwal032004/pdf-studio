package com.example.ui.tools.screens

import android.net.Uri
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.core.PageOps
import com.example.core.PickedPdf
import com.example.ui.common.*
import com.example.ui.tools.ToolCatalog
import kotlinx.coroutines.launch

private data class Slot(val id: Long, val src: Int, val rot: Int = 0)

/**
 * One screen for reordering, rotating, deleting and duplicating pages. The tool id only changes
 * the emphasis (hint and highlighted actions) so each tile feels purpose-built.
 */
@Composable
fun PageOrganizerScreen(toolId: String, initialUri: Uri?) {
    val context = LocalContext.current
    val tool = ToolCatalog.get(toolId)
    val job = rememberJob(toolId)
    var pdf by remember { mutableStateOf<PickedPdf?>(null) }
    var slots by remember { mutableStateOf<List<Slot>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var nextId by remember { mutableLongStateOf(100_000L) }
    val picker = rememberPdfPicker { p ->
        pdf = p; slots = (0 until p.pageCount).map { Slot(it.toLong(), it) }; selected = emptySet(); job.reset()
    }
    LaunchedEffect(initialUri) { if (initialUri != null && pdf == null) picker.load(initialUri) }
    val renderer = rememberRenderer(pdf?.file)
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var dragStartOffset by remember { mutableStateOf(IntOffset.Zero) }

    val p0 = pdf
    val changed = p0 != null && (slots.size != p0.pageCount || slots.withIndex().any { (i, s) -> s.src != i || s.rot != 0 })

    fun rotate(deg: Int) {
        val target = if (selected.isEmpty()) slots.map { it.id }.toSet() else selected
        slots = slots.map { if (it.id in target) it.copy(rot = ((it.rot + deg) % 360 + 360) % 360) else it }
    }
    fun delete() {
        if (selected.isEmpty() || selected.size >= slots.size) return
        slots = slots.filterNot { it.id in selected }; selected = emptySet()
    }
    fun duplicate() {
        val out = mutableListOf<Slot>()
        for (s in slots) { out += s; if (s.id in selected) out += s.copy(id = nextId++) }
        slots = out
    }
    fun moveSelected(toStart: Boolean) {
        val sel = slots.filter { it.id in selected }
        val rest = slots.filterNot { it.id in selected }
        slots = if (toStart) sel + rest else rest + sel
    }

    val hint = when (toolId) {
        "rotate" -> "Select pages, then rotate. With nothing selected, rotation applies to every page."
        "delete_pages" -> "Select the pages to remove, then tap Delete."
        "duplicate_pages" -> "Select pages to copy — each copy appears right after its original."
        else -> "Long-press and drag a page to move it. Tap pages to select them for bulk actions."
    }

    Scaffold(
        topBar = {
            ToolTopBar(tool, subtitle = p0?.let { "${slots.size} pages" + if (changed) " • edited" else "" } ?: tool?.subtitle) {
                if (p0 != null && changed) IconButton({ slots = (0 until p0.pageCount).map { Slot(it.toLong(), it) }; selected = emptySet() }) {
                    Icon(Icons.Rounded.Restore, "Reset")
                }
            }
        },
        bottomBar = {
            if (p0 != null && job.state !is JobState.Done) Column {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OrgAction(Icons.AutoMirrored.Rounded.RotateLeft, "Left", toolId == "rotate") { rotate(-90) }
                        OrgAction(Icons.AutoMirrored.Rounded.RotateRight, "Right", toolId == "rotate") { rotate(90) }
                        OrgAction(Icons.Rounded.DeleteOutline, "Delete", toolId == "delete_pages", selected.isNotEmpty() && selected.size < slots.size) { delete() }
                        OrgAction(Icons.Rounded.ContentCopy, "Duplicate", toolId == "duplicate_pages", selected.isNotEmpty()) { duplicate() }
                        OrgAction(Icons.Rounded.VerticalAlignTop, "To start", false, selected.isNotEmpty()) { moveSelected(true) }
                        OrgAction(Icons.Rounded.VerticalAlignBottom, "To end", false, selected.isNotEmpty()) { moveSelected(false) }
                        OrgAction(Icons.Rounded.SwapVert, "Reverse", false) { slots = slots.reversed() }
                    }
                }
                BottomAction("Save ${slots.size} pages", changed, Icons.Rounded.Check) {
                    val order = slots.map { it.src }
                    val rotations = slots.withIndex().filter { it.value.rot != 0 }.associate { it.index to it.value.rot }
                    job.runFile { prog -> PageOps.rebuild(context, p0, order, rotations, "organised", prog) }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val s = job.state
            when {
                s is JobState.Done -> ResultPanel(s.result, toolId) { job.reset() }
                p0 == null || renderer == null -> EmptyPick(
                    tool?.icon ?: Icons.Rounded.DragIndicator, tool?.title ?: "Organise pages",
                    "${tool?.subtitle ?: ""}. Select a PDF to get started.", "Choose PDF", picker.loading,
                    tool?.category?.accent ?: MaterialTheme.colorScheme.primary
                ) { picker.launch() }
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(108.dp), state = grid,
                    modifier = Modifier.fillMaxSize().pointerInput(slots) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pos ->
                                val hit = grid.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                    info.key is Long && pos.x.toInt() in info.offset.x..(info.offset.x + info.size.width) &&
                                        pos.y.toInt() in info.offset.y..(info.offset.y + info.size.height)
                                }
                                if (hit != null) { dragId = hit.key as Long; dragStartOffset = hit.offset; dragDelta = Offset.Zero }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val id = dragId ?: return@detectDragGesturesAfterLongPress
                                dragDelta += amount
                                val current = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return@detectDragGesturesAfterLongPress
                                val center = Offset(
                                    dragStartOffset.x + dragDelta.x + current.size.width / 2f,
                                    dragStartOffset.y + dragDelta.y + current.size.height / 2f
                                )
                                val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                    info.key is Long && info.key != id &&
                                        center.x.toInt() in info.offset.x..(info.offset.x + info.size.width) &&
                                        center.y.toInt() in info.offset.y..(info.offset.y + info.size.height)
                                }
                                if (target != null) {
                                    val from = slots.indexOfFirst { it.id == id }
                                    val to = slots.indexOfFirst { it.id == target.key }
                                    if (from >= 0 && to >= 0) slots = slots.toMutableList().apply { add(to, removeAt(from)) }
                                }
                                val viewport = grid.layoutInfo.viewportSize.height
                                when {
                                    center.y < 120 -> scope.launch { grid.scrollBy(-24f) }
                                    center.y > viewport - 120 -> scope.launch { grid.scrollBy(24f) }
                                }
                            },
                            onDragEnd = { dragId = null; dragDelta = Offset.Zero },
                            onDragCancel = { dragId = null; dragDelta = Offset.Zero }
                        )
                    },
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    full("source") { PdfSourceCard(p0, { picker.launch() }) }
                    full("hint") { InfoBanner(hint, Icons.Rounded.TouchApp) }
                    full("sel") {
                        PageSelectionBar(
                            slots.withIndex().filter { it.value.id in selected }.map { it.index }.toSet(), slots.size,
                            { idx -> selected = idx.mapNotNull { slots.getOrNull(it)?.id }.toSet() }
                        )
                    }
                    items(slots.size, key = { slots[it].id }) { i ->
                        val slot = slots[i]
                        val dragging = slot.id == dragId
                        val current = if (dragging) grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == slot.id } else null
                        PageThumbCard(
                            renderer, slot.src, slot.id in selected,
                            modifier = Modifier
                                .then(if (dragging) Modifier.zIndex(2f) else Modifier.animateItem())
                                .graphicsLayer {
                                    if (dragging && current != null) {
                                        translationX = dragStartOffset.x + dragDelta.x - current.offset.x
                                        translationY = dragStartOffset.y + dragDelta.y - current.offset.y
                                        scaleX = 1.06f; scaleY = 1.06f; shadowElevation = 24f
                                    }
                                },
                            label = if (slot.src == i) "${i + 1}" else "${i + 1}  (was ${slot.src + 1})",
                            rotation = slot.rot,
                            onClick = { selected = if (slot.id in selected) selected - slot.id else selected + slot.id }
                        )
                    }
                    full("space") { Spacer(Modifier.height(24.dp)) }
                }
            }
            JobOverlay(job)
        }
    }
}

@Composable
private fun OrgAction(icon: ImageVector, label: String, emphasised: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    TextButton(
        onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = if (emphasised) ButtonDefaults.textButtonColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer)
        else ButtonDefaults.textButtonColors(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
