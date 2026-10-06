package com.example.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.DuplicateGroup
import com.example.core.FileOps
import com.example.ui.common.LocalNav
import com.example.ui.common.StatPill
import com.example.ui.common.ToolTopBar
import com.example.ui.components.PdfThumbnail
import com.example.ui.theme.Accents
import com.example.utils.DevicePdf
import com.example.utils.FileUtils
import com.example.utils.PdfIndex
import kotlinx.coroutines.CancellationException

private const val TAB_OVERVIEW = 0
private const val TAB_DUPLICATES = 1
private const val TAB_LARGEST = 2

/** Where PDF space goes on this phone, duplicate copies, and the biggest files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(initialTab: Int = TAB_OVERVIEW) {
    val context = LocalContext.current
    val nav = LocalNav.current
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val indexed by PdfIndex.files.collectAsState()
    val all = indexed.orEmpty()
    val hasPermission = remember { FileUtils.hasStoragePermission(context) }
    var preview by remember { mutableStateOf<DevicePdf?>(null) }
    val deleter = rememberFileDeleter()

    LaunchedEffect(Unit) { PdfIndex.refresh(context) }

    // Duplicate scan — runs once the list is ready; deleted files drop out automatically.
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }
    var scanKey by remember { mutableIntStateOf(0) }
    var scannedFor by remember { mutableIntStateOf(-1) }
    val scanReady = indexed != null
    LaunchedEffect(tab, scanReady, scanKey) {
        if (tab != TAB_DUPLICATES || !scanReady || scannedFor == scanKey) return@LaunchedEffect
        groups = null
        groups = try {
            FileOps.findDuplicates(context, PdfIndex.files.value.orEmpty()) { d, t -> progress = d to t }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        scannedFor = scanKey
    }
    val alive = remember(indexed) { all.map { it.uri }.toSet() }
    val liveGroups = remember(groups, alive) {
        groups?.map { g -> DuplicateGroup(g.files.filter { it.uri in alive }) }?.filter { it.files.size > 1 }
    }
    val keep = remember { mutableStateMapOf<String, android.net.Uri>() } // group key → copy to keep

    fun groupKey(g: DuplicateGroup) = "${g.size}:${g.files.first().uri}"
    fun extras(g: DuplicateGroup): List<DevicePdf> {
        val k = keep[groupKey(g)] ?: g.files.first().uri
        return g.files.filter { it.uri != k }
    }

    Scaffold(topBar = {
        Column {
            ToolTopBar(null, "PDF storage", "PDFs on this phone")
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Overview", "Duplicates", "Largest").forEachIndexed { i, label ->
                    Tab(tab == i, { tab = i }, text = { Text(label) })
                }
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            if (indexed == null) {
                Column(Modifier.padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Scanning your phone…")
                }
                return@Box
            }
            LazyColumn(
                Modifier.fillMaxHeight().widthIn(max = 900.dp), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!hasPermission) item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(12.dp))
                            Text("Only PDFs PDF Studio can see are counted. Allow file access in the Files tab to include every folder.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                when (tab) {
                    TAB_OVERVIEW -> {
                        val stats = FileOps.stats(context, all)
                        item {
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                    Text(formatFileSize(stats.bytes), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                                    Text("used by ${stats.count} PDFs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(16.dp))
                                    val total = stats.bytes.coerceAtLeast(1).toFloat()
                                    val parts = listOf(
                                        Triple("Created by app", stats.appBytes, Accents.violet),
                                        Triple("Saved to Downloads", stats.savedBytes, Accents.teal),
                                        Triple("Other PDFs", (stats.bytes - stats.appBytes - stats.savedBytes).coerceAtLeast(0), Accents.blue)
                                    )
                                    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                        parts.forEach { (_, b, c) -> if (b > 0) Box(Modifier.fillMaxHeight().weight(b / total).background(c)) }
                                        val rest = 1f - parts.sumOf { it.second.toDouble() }.toFloat() / total
                                        if (rest > 0.001f) Spacer(Modifier.weight(rest))
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    parts.forEach { (label, b, c) ->
                                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(10.dp).background(c, RoundedCornerShape(3.dp)))
                                            Spacer(Modifier.width(8.dp))
                                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                            Text(formatFileSize(b), style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatPill("${stats.appCount}", "Created by app", Modifier.weight(1f), Accents.violet)
                                StatPill("${stats.savedCount}", "Saved copies", Modifier.weight(1f), Accents.teal)
                                StatPill("${stats.folders.size}", "Folders", Modifier.weight(1f), Accents.amber)
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton({ tab = TAB_DUPLICATES }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Find duplicates")
                                }
                                OutlinedButton({ nav.open("folder_browser") }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Browse folders")
                                }
                            }
                        }
                        item { Text("By folder", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                        val max = stats.folders.firstOrNull()?.bytes?.coerceAtLeast(1) ?: 1
                        items(stats.folders.take(25), key = { it.folder }) { f ->
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp), tint = Accents.amber)
                                        Spacer(Modifier.width(8.dp))
                                        Text(f.folder, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Spacer(Modifier.width(8.dp))
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(formatFileSize(f.bytes), style = MaterialTheme.typography.labelLarge)
                                            Text("${f.count} PDF${if (f.count == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(progress = { f.bytes.toFloat() / max }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                                }
                            }
                        }
                    }

                    TAB_DUPLICATES -> {
                        val g = liveGroups
                        if (g == null) item {
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                    Text("Comparing files…", style = MaterialTheme.typography.titleMedium)
                                    Text("Files with the same size are checked byte by byte.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(12.dp))
                                    val (d, t) = progress
                                    if (t > 0) LinearProgressIndicator(progress = { d.toFloat() / t }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if (t > 0) Text("$d of $t", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        } else if (g.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(64.dp), tint = Accents.green)
                                Spacer(Modifier.height(12.dp))
                                Text("No duplicates", style = MaterialTheme.typography.titleMedium)
                                Text("Every PDF on your phone is unique.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton({ scanKey++ }) { Text("Scan again") }
                            }
                        } else {
                            item {
                                val extraFiles = g.flatMap { extras(it) }
                                Surface(shape = RoundedCornerShape(22.dp), color = Accents.container(Accents.orange)) {
                                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                                        Text("${formatFileSize(g.sumOf { it.reclaimable })} can be freed", style = MaterialTheme.typography.titleLarge, color = Accents.onContainer(Accents.orange))
                                        Text("${g.size} file${if (g.size == 1) "" else "s"} with ${extraFiles.size} extra cop${if (extraFiles.size == 1) "y" else "ies"}. The marked copy of each is kept.",
                                            style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.height(12.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Button({ deleter.request(extraFiles) }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Accents.orange)) {
                                                Icon(Icons.Rounded.CleaningServices, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove all extras")
                                            }
                                            TextButton({ scanKey++ }) { Text("Rescan") }
                                        }
                                    }
                                }
                            }
                            itemsIndexed(g, key = { _, grp -> groupKey(grp) }) { _, grp ->
                                val key = groupKey(grp)
                                val kept = keep[key] ?: grp.files.first().uri
                                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(44.dp, 56.dp).clip(RoundedCornerShape(6.dp))) { PdfThumbnail(grp.files.first().uri, Modifier.fillMaxSize(), widthPx = 160) }
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(grp.files.first().name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${grp.files.size} identical copies · ${formatFileSize(grp.size)} each", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        grp.files.forEach { f ->
                                            val isKept = f.uri == kept
                                            Row(
                                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { keep[key] = f.uri }.padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(isKept, { keep[key] = f.uri })
                                                Column(Modifier.weight(1f)) {
                                                    Text(f.folder.ifBlank { f.name }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    Text((if (isKept) "Keep · " else "Remove · ") + formatDate(f.dateModified), style = MaterialTheme.typography.labelSmall,
                                                        color = if (isKept) Accents.green else MaterialTheme.colorScheme.error)
                                                }
                                                IconButton({ preview = f }) { Icon(Icons.Rounded.Visibility, "Preview", Modifier.size(20.dp)) }
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton({ deleter.request(extras(grp)) }) {
                                                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                                Text("Delete ${grp.files.size - 1} cop${if (grp.files.size == 2) "y" else "ies"}")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        val largest = all.sortedByDescending { it.size }.take(40)
                        item { Text("${largest.size} largest PDFs", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                        items(largest, key = { it.uri.toString() }) { f ->
                            PdfListItem(
                                f, selected = false, selectionMode = false,
                                menu = FileMenu(
                                    open = { nav.openUri(f.uri) }, preview = { preview = f }, share = { shareUris(context, listOf(f.uri)) },
                                    properties = { nav.openTool("properties", f.uri) }, delete = { deleter.request(listOf(f)) }
                                ),
                                onClick = { nav.openUri(f.uri) }, onLongClick = { preview = f }
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
            onRename = null, onDelete = { preview = null; deleter.request(listOf(item)) },
            onProperties = { preview = null; nav.openTool("properties", item.uri) }
        )
    }
}
