package com.example.ui.common

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.PageRanges
import com.example.core.PageRenderer
import com.example.core.PasswordRequiredException
import com.example.core.PickedPdf
import com.example.core.Workspace
import com.example.ui.tools.ToolCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

fun LazyGridScope.full(key: Any? = null, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/** Page thumbnails as grid items. */
fun LazyGridScope.pageItems(
    renderer: PageRenderer,
    selected: Set<Int>,
    onClick: (Int) -> Unit,
    labels: ((Int) -> String)? = null,
    dimmed: Set<Int> = emptySet(),
    rotations: Map<Int, Int> = emptyMap(),
    accent: Color? = null
) {
    items(renderer.pageCount, key = { "page_$it" }) { i ->
        PageThumbCard(
            renderer, i, i in selected, label = labels?.invoke(i) ?: "${i + 1}", dim = i in dimmed,
            rotation = rotations[i] ?: 0, badgeColor = accent ?: MaterialTheme.colorScheme.primary, onClick = { onClick(i) }
        )
    }
}

@Composable
fun PageSelectionBar(
    selected: Set<Int>, pageCount: Int, onChange: (Set<Int>) -> Unit, hint: String = "Tap pages to select"
) {
    var rangeDialog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected.isEmpty()) hint else "${selected.size} of $pageCount selected",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AssistChip({ onChange((0 until pageCount).toSet()) }, { Text("All") }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null, Modifier.size(16.dp)) })
            AssistChip({ onChange(emptySet()) }, { Text("None") }, leadingIcon = { Icon(Icons.Rounded.Deselect, null, Modifier.size(16.dp)) })
            AssistChip({ onChange((0 until pageCount).filterNot { it in selected }.toSet()) }, { Text("Invert") })
            AssistChip({ rangeDialog = true }, { Text("Range…") }, leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp)) })
        }
    }
    if (rangeDialog) {
        var text by remember { mutableStateOf(PageRanges.format(selected)) }
        AlertDialog(
            onDismissRequest = { rangeDialog = false },
            title = { Text("Select pages") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, label = { Text("Pages") }, placeholder = { Text("1-3, 5, 8-") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    Text("Use commas and dashes. Also: odd, even, all.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton({ onChange(PageRanges.parse(text, pageCount).toSet()); rangeDialog = false }) { Text("Apply") } },
            dismissButton = { TextButton({ rangeDialog = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Standard single-PDF tool: pick → configure (options + optional page grid) → run → result preview.
 */
@Composable
fun PdfToolFrame(
    toolId: String,
    initialUri: Uri?,
    runLabel: String,
    canRun: (PickedPdf) -> Boolean = { true },
    runIcon: ImageVector = Icons.Rounded.PlayArrow,
    emptyTitle: String? = null,
    emptySubtitle: String? = null,
    onPdfChanged: (PickedPdf) -> Unit = {},
    onRun: JobRunner.(PickedPdf) -> Unit,
    content: LazyGridScope.(PickedPdf, PageRenderer?) -> Unit
) {
    val tool = ToolCatalog.get(toolId)
    val job = rememberJob(toolId)
    var pdf by remember { mutableStateOf<PickedPdf?>(null) }
    val picker = rememberPdfPicker { pdf = it; onPdfChanged(it); job.reset() }
    LaunchedEffect(initialUri) { if (initialUri != null && pdf == null) picker.load(initialUri) }
    val renderer = rememberRenderer(pdf?.file)

    Scaffold(
        topBar = { ToolTopBar(tool) },
        bottomBar = {
            val p = pdf
            if (p != null && job.state !is JobState.Done) BottomAction(runLabel, canRun(p), runIcon) { job.onRun(p) }
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val p = pdf
            val s = job.state
            when {
                s is JobState.Done -> ResultPanel(s.result, toolId) { job.reset() }
                p == null -> EmptyPick(
                    tool?.icon ?: Icons.Rounded.PictureAsPdf,
                    emptyTitle ?: (tool?.title ?: "Choose a PDF"),
                    emptySubtitle ?: (tool?.subtitle?.let { "$it. Select a PDF to get started." } ?: "Select a PDF to get started."),
                    "Choose PDF", picker.loading, tool?.category?.accent ?: MaterialTheme.colorScheme.primary
                ) { picker.launch() }
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(104.dp), Modifier.fillMaxSize().readableWidth(960.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    full("source") { PdfSourceCard(p, { picker.launch() }) }
                    content(p, renderer)
                    full("bottom_space") { Spacer(Modifier.height(24.dp)) }
                }
            }
            JobOverlay(job)
        }
    }
}

// ---------------------------------------------------------------- Multi picking

/** Picks several PDFs, prompting for passwords one file at a time. */
@Composable
fun rememberMultiPdfPicker(onPicked: (List<PickedPdf>) -> Unit): PdfPickerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var ask by remember { mutableStateOf<PasswordRequiredException?>(null) }
    var wrong by remember { mutableStateOf(false) }
    val collected = remember { mutableStateListOf<PickedPdf>() }

    fun process(password: String? = null) {
        val next = queue.firstOrNull() ?: run {
            loading = false
            if (collected.isNotEmpty()) onPicked(collected.toList())
            collected.clear()
            return
        }
        loading = true
        scope.launch {
            try {
                collected += Workspace.pick(context, next, password)
                ask = null; wrong = false
                queue = queue.drop(1)
                process()
            } catch (e: PasswordRequiredException) {
                wrong = password != null; ask = e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Skipped ${Workspace.displayName(context, next)}: ${e.message}", Toast.LENGTH_SHORT).show()
                queue = queue.drop(1); process()
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            queue = uris; process()
        }
    }
    ask?.let { req ->
        PasswordDialog(req.name, wrong, onDismiss = { ask = null; queue = queue.drop(1); process() }, onSubmit = { process(it) })
    }
    return PdfPickerState({ launcher.launch(arrayOf("application/pdf")) }, { queue = listOf(it); process() }, loading, { queue = it; process() })
}

/** Picks a second PDF inside a tool (e.g. the source for insert/replace). */
@Composable
fun SecondaryPdfCard(label: String, pdf: PickedPdf?, onPicked: (PickedPdf) -> Unit) {
    val picker = rememberPdfPicker(onPicked = onPicked)
    if (pdf == null) {
        OutlinedCard(onClick = { picker.launch() }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (picker.loading) CircularProgressIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.AddCircleOutline, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text("Tap to choose a PDF", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else PdfSourceCard(pdf, { picker.launch() })
}
