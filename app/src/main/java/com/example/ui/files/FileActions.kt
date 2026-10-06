package com.example.ui.files

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.DeleteResult
import com.example.core.FileOps
import com.example.core.PageRenderer
import com.example.core.Workspace
import com.example.ui.common.PageImage
import com.example.ui.components.PdfThumbnail
import com.example.ui.theme.Accents
import com.example.utils.DevicePdf
import com.example.utils.PdfIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- Helpers

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

fun formatDate(t: Long): String = dateFormat.format(Date(t))

fun formatFileSize(size: Long): String = Workspace.formatSize(size)

fun shareUris(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uris[0])
    } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/pdf"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "No app can share this file", Toast.LENGTH_SHORT).show() }
}

fun copyText(context: Context, label: String, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

/** "Today", "Yesterday", "This week", "This month" or the month name, for grouping by date. */
fun dateSection(t: Long): String {
    val day = 24L * 3600 * 1000
    val now = System.currentTimeMillis()
    val startOfToday = now - (now + java.util.TimeZone.getDefault().getOffset(now)) % day
    return when {
        t >= startOfToday -> "Today"
        t >= startOfToday - day -> "Yesterday"
        t >= startOfToday - 6 * day -> "This week"
        t >= startOfToday - 30 * day -> "This month"
        else -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(t))
    }
}

// ---------------------------------------------------------------- Delete

class FileDeleter(val request: (List<DevicePdf>) -> Unit)

/** Confirms, deletes, and handles Android's "allow PDF Studio to delete?" prompt when needed. */
@Composable
fun rememberFileDeleter(onDeleted: (List<Uri>) -> Unit = {}): FileDeleter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<List<DevicePdf>?>(null) }
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val uris = pending
        pending = emptyList()
        if (res.resultCode == Activity.RESULT_OK) {
            FileOps.afterConsent(context, uris)
            Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
            onDeleted(uris)
        }
        scope.launch { PdfIndex.refresh(context) }
    }

    fun perform(items: List<DevicePdf>) {
        busy = true
        scope.launch {
            try {
                when (val r = FileOps.delete(context, items)) {
                    is DeleteResult.Done -> {
                        val msg = when {
                            r.failed == 0 -> if (r.deleted == 1) "Deleted" else "${r.deleted} files deleted"
                            r.deleted == 0 -> "Couldn't delete. Grant \"All files access\" in the Files tab and try again."
                            else -> "${r.deleted} deleted, ${r.failed} couldn't be deleted"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        if (r.deleted > 0) onDeleted(items.map { it.uri })
                        PdfIndex.refresh(context)
                    }
                    is DeleteResult.NeedsConsent -> {
                        pending = r.pending
                        consent.launch(IntentSenderRequest.Builder(r.sender).build())
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    confirm?.let { items ->
        val total = items.sumOf { it.size }
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(if (items.size == 1) "Delete this PDF?" else "Delete ${items.size} PDFs?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (items.size == 1) {
                        Text(items[0].name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (items[0].folder.isNotBlank()) Text(items[0].folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${formatFileSize(total)} will be freed. This permanently removes the file${if (items.size == 1) "" else "s"} from your phone — it can't be undone.", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(
                    { confirm = null; perform(items) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }
        )
    }
    if (busy) Dialog { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text("Deleting…") } }
    return remember { FileDeleter { if (it.isNotEmpty()) confirm = it } }
}

@Composable
private fun Dialog(content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) { content() }
    }
}

// ---------------------------------------------------------------- Rename

@Composable
fun RenameDialog(item: DevicePdf, onDismiss: () -> Unit, onRenamed: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val base = item.name.removeSuffix(".pdf").removeSuffix(".PDF")
    var value by remember { mutableStateOf(TextFieldValue(base, TextRange(0, base.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            runCatching { FileOps.rename(context, item, value.text) }
                .onSuccess { name -> PdfIndex.refresh(context); onRenamed(name) }
                .onFailure { error = it.message }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value, { value = it; error = null }, Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text("File name") }, suffix = { Text(".pdf") }, singleLine = true, isError = error != null,
                supportingText = error?.let { { Text(it) } }, shape = RoundedCornerShape(14.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() })
            )
        },
        confirmButton = { Button(::submit, enabled = value.text.isNotBlank() && !busy) { Text("Rename") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- Quick preview

/** Swipeable page preview of any PDF on the device, with its details and actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPreviewSheet(
    item: DevicePdf,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onProperties: () -> Unit
) {
    val context = LocalContext.current
    var renderer by remember(item.uri) { mutableStateOf<PageRenderer?>(null) }
    var failed by remember(item.uri) { mutableStateOf(false) }
    LaunchedEffect(item.uri) {
        val r = withContext(Dispatchers.IO) { runCatching { PageRenderer.open(context, item.uri) }.getOrNull() }
        if (r == null) failed = true else renderer = r
    }
    DisposableEffect(renderer) { val r = renderer; onDispose { r?.close() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val r = renderer
                Text(
                    listOfNotNull(r?.let { "${it.pageCount} page${if (it.pageCount == 1) "" else "s"}" }, formatFileSize(item.size), formatDate(item.dateModified)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(380.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                val r = renderer
                when {
                    r != null -> {
                        val pager = rememberPagerState { r.pageCount }
                        HorizontalPager(pager, Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp), pageSpacing = 16.dp, beyondViewportPageCount = 1) { i ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                PageImage(r, i, Modifier.shadow(6.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)), widthPx = 800)
                            }
                        }
                        Surface(Modifier.align(Alignment.BottomCenter).padding(10.dp), shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)) {
                            Text("${pager.currentPage + 1} / ${r.pageCount}", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                    }
                    failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Preview unavailable", style = MaterialTheme.typography.titleSmall)
                        Text("This file may be password protected. Open it to unlock.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    else -> CircularProgressIndicator()
                }
            }
            if (item.path != null) Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer).clickable { copyText(context, "Path", item.path) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Location", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.folder, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ContentCopy, "Copy path", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onOpen, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.MenuBook, null); Spacer(Modifier.width(8.dp)); Text("Open")
                }
                FilledTonalButton({ shareUris(context, listOf(item.uri)) }, Modifier.height(50.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.Share, "Share")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                SheetAction(Icons.Rounded.Info, "Properties", onClick = onProperties)
                if (onRename != null) SheetAction(Icons.Rounded.DriveFileRenameOutline, "Rename", onClick = onRename)
                if (onDelete != null) SheetAction(Icons.Rounded.DeleteOutline, "Delete", MaterialTheme.colorScheme.error, onDelete)
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

// ---------------------------------------------------------------- Items

/** Overflow-menu actions for one file. Null actions are hidden. */
class FileMenu(
    val open: () -> Unit,
    val preview: () -> Unit,
    val share: () -> Unit,
    val properties: () -> Unit,
    val saveToDownloads: (() -> Unit)? = null,
    val rename: (() -> Unit)? = null,
    val delete: (() -> Unit)? = null,
    val removeFromRecents: (() -> Unit)? = null
)

@Composable
private fun FileMenuButton(menu: FileMenu, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(Icons.Rounded.MoreVert, "More options", tint = tint) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Open") }, { open = false; menu.open() }, leadingIcon = { Icon(Icons.Rounded.MenuBook, null) })
            DropdownMenuItem({ Text("Quick preview") }, { open = false; menu.preview() }, leadingIcon = { Icon(Icons.Rounded.Visibility, null) })
            DropdownMenuItem({ Text("Share") }, { open = false; menu.share() }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
            menu.saveToDownloads?.let { f -> DropdownMenuItem({ Text("Save to Downloads") }, { open = false; f() }, leadingIcon = { Icon(Icons.Rounded.Download, null) }) }
            menu.rename?.let { f -> DropdownMenuItem({ Text("Rename") }, { open = false; f() }, leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) }) }
            DropdownMenuItem({ Text("Properties") }, { open = false; menu.properties() }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
            menu.removeFromRecents?.let { f -> DropdownMenuItem({ Text("Remove from recents") }, { open = false; f() }, leadingIcon = { Icon(Icons.Rounded.Close, null) }) }
            menu.delete?.let { f ->
                HorizontalDivider()
                DropdownMenuItem(
                    { Text("Delete", color = MaterialTheme.colorScheme.error) }, { open = false; f() },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfListItem(
    item: DevicePdf,
    selected: Boolean,
    selectionMode: Boolean,
    menu: FileMenu,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showPath: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp, 62.dp).shadow(2.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !selectionMode, onClick = menu.preview)
            ) {
                PdfThumbnail(item.uri, Modifier.fillMaxSize(), widthPx = 160, version = item.dateModified)
                if (selected) Box(Modifier.matchParentSize().background(cs.primary.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = cs.onPrimary)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.isAppFile || item.savedByApp) {
                        Box(Modifier.background(Accents.container(Accents.violet), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                            Text(if (item.isAppFile) "Created" else "Saved", style = MaterialTheme.typography.labelSmall, color = Accents.onContainer(Accents.violet))
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("${formatDate(item.dateModified)} · ${formatFileSize(item.size)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
                if (showPath && item.folder.isNotBlank()) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(12.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.8f))
                    Spacer(Modifier.width(4.dp))
                    Text(item.folder, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selectionMode) Checkbox(selected, { onClick() }) else FileMenuButton(menu)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfGridItem(
    item: DevicePdf,
    selected: Boolean,
    selectionMode: Boolean,
    menu: FileMenu,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .then(if (selected) Modifier.border(2.dp, cs.primary, RoundedCornerShape(20.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.78f).shadow(2.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))) {
                PdfThumbnail(item.uri, Modifier.fillMaxSize(), widthPx = 360, version = item.dateModified)
                if (selectionMode) Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp)
                        .background(if (selected) cs.primary else Color.White.copy(alpha = 0.9f), CircleShape)
                        .border(1.5.dp, if (selected) cs.primary else cs.outline, CircleShape),
                    contentAlignment = Alignment.Center
                ) { if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = cs.onPrimary) }
                if (item.isAppFile || item.savedByApp) Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp).background(Accents.violet, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                ) { Text(if (item.isAppFile) "Created" else "Saved", style = MaterialTheme.typography.labelSmall, color = Color.White) }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatFileSize(item.size) + " · " + formatDate(item.dateModified), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
                if (!selectionMode) Box(Modifier.offset(x = 6.dp, y = (-6).dp)) { FileMenuButton(menu) }
            }
        }
    }
}
