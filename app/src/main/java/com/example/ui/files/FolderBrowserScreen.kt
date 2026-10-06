package com.example.ui.files

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.common.LocalNav
import com.example.ui.common.rememberBackAction
import com.example.ui.theme.Accents
import com.example.utils.DevicePdf
import com.example.utils.FileUtils
import com.example.utils.PdfIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Browses device folders that contain PDFs. Back goes up one folder at a time. */
@Composable
fun FolderBrowserScreen() {
    val context = LocalContext.current
    val nav = LocalNav.current
    val leave = rememberBackAction()
    val root = remember { Environment.getExternalStorageDirectory().absolutePath }
    var current by rememberSaveable { mutableStateOf(root) }
    val indexed by PdfIndex.files.collectAsState()
    var preview by remember { mutableStateOf<DevicePdf?>(null) }
    var renaming by remember { mutableStateOf<DevicePdf?>(null) }
    val deleter = rememberFileDeleter()
    val hasPermission = remember { FileUtils.hasStoragePermission(context) }

    LaunchedEffect(Unit) { if (PdfIndex.files.value == null) PdfIndex.refresh(context) }

    // Folder path → number of PDFs inside it (including sub-folders), from the shared index.
    val counts = remember(indexed) {
        val m = HashMap<String, Int>()
        indexed.orEmpty().forEach { f ->
            var p = f.path?.let { File(it).parent }
            while (p != null && p.length >= root.length) { m[p] = (m[p] ?: 0) + 1; p = File(p).parent }
        }
        m
    }
    val byPath = remember(indexed) { indexed.orEmpty().filter { it.path != null }.associateBy { it.path!! } }

    var entries by remember { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(current, counts) {
        entries = withContext(Dispatchers.IO) {
            File(current).listFiles()?.filter { f ->
                !f.isHidden && if (f.isDirectory) (counts[f.absolutePath] ?: 0) > 0 else f.extension.equals("pdf", true) && f.length() > 0
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        }
    }

    fun up() { File(current).parent?.takeIf { current != root }?.let { current = it } }
    BackHandler(enabled = current != root) { up() }

    val listState = rememberLazyListState()
    LaunchedEffect(current) { listState.scrollToItem(0) }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ if (current != root) up() else leave() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(if (current == root) "Internal storage" else File(current).name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${counts[current] ?: 0} PDFs in this folder and below", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (current != root) IconButton({ current = root }) { Icon(Icons.Rounded.Home, "Storage root") }
                    }
                    // Breadcrumb: tap any part of the path to jump there.
                    val parts = remember(current) {
                        val out = mutableListOf(root to "Internal storage")
                        var acc = root
                        current.removePrefix(root).split('/').filter { it.isNotEmpty() }.forEach { seg -> acc = "$acc/$seg"; out += acc to seg }
                        out
                    }
                    val crumbScroll = rememberScrollState()
                    LaunchedEffect(parts) { crumbScroll.scrollTo(crumbScroll.maxValue) }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(crumbScroll).padding(horizontal = 12.dp).padding(bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        parts.forEachIndexed { i, (path, label) ->
                            if (i > 0) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            val last = i == parts.lastIndex
                            Text(
                                label,
                                Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !last) { current = path }
                                    .background(if (last) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (last) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    ) { pad ->
        val list = entries
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            when {
                !hasPermission -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.FolderSpecial, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("File access needed", style = MaterialTheme.typography.titleMedium)
                    Text("Allow access from the Files tab to browse folders.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                list == null || (indexed == null && list.isEmpty()) -> CircularProgressIndicator(Modifier.padding(48.dp))
                list.isEmpty() -> Column(Modifier.padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.FolderOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))
                    Text("No PDFs here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    Modifier.fillMaxHeight().widthIn(max = 900.dp), state = listState,
                    contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list, key = { it.absolutePath }) { f ->
                        if (f.isDirectory) FolderRow(f.name, counts[f.absolutePath] ?: 0) { current = f.absolutePath }
                        else {
                            val item = byPath[f.absolutePath] ?: DevicePdf(FileUtils.getUriForFile(context, f), f.name, f.length(), f.lastModified(), path = f.absolutePath)
                            PdfListItem(
                                item, selected = false, selectionMode = false, showPath = false,
                                menu = FileMenu(
                                    open = { nav.openUri(item.uri) }, preview = { preview = item },
                                    share = { shareUris(context, listOf(item.uri)) },
                                    properties = { nav.openTool("properties", item.uri) },
                                    rename = { renaming = item }, delete = { deleter.request(listOf(item)) }
                                ),
                                onClick = { nav.openUri(item.uri) }, onLongClick = { preview = item }
                            )
                        }
                    }
                }
            }
        }
    }

    preview?.let { item ->
        QuickPreviewSheet(
            item, onDismiss = { preview = null }, onOpen = { preview = null; nav.openUri(item.uri) },
            onRename = { preview = null; renaming = item }, onDelete = { preview = null; deleter.request(listOf(item)) },
            onProperties = { preview = null; nav.openTool("properties", item.uri) }
        )
    }
    renaming?.let { item -> RenameDialog(item, onDismiss = { renaming = null }) { renaming = null } }
}

@Composable
private fun FolderRow(name: String, count: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Accents.container(Accents.amber), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, null, tint = Accents.amber)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$count PDF${if (count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
