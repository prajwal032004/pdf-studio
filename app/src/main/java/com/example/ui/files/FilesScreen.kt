package com.example.ui.files

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.core.RecentStore
import com.example.ui.common.LocalNav
import com.example.ui.common.rememberFileSaver
import com.example.ui.home.BottomNavigationBar
import com.example.ui.tools.screens.Incoming
import com.example.utils.DevicePdf
import com.example.utils.FileUtils
import com.example.utils.PdfIndex
import kotlinx.coroutines.launch
import java.io.File

private const val TAB_ALL = 0
private const val TAB_APP = 1
private const val TAB_RECENT = 2

private const val SORT_NEWEST = 0
private const val SORT_NAME = 1
private const val SORT_LARGEST = 2
private const val SORT_OLDEST = 3

private val sortLabels = listOf("Newest first", "Name (A–Z)", "Largest first", "Oldest first")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("app_prefs", 0) }
    val saver = rememberFileSaver()

    val indexed by PdfIndex.files.collectAsState()
    val loading by PdfIndex.loading.collectAsState()
    var hasPermission by remember { mutableStateOf(FileUtils.hasStoragePermission(context)) }
    var recents by remember { mutableStateOf(RecentStore.list(context)) }

    var tab by rememberSaveable { mutableIntStateOf(prefs.getInt("files_tab", if (FileUtils.hasStoragePermission(context)) TAB_ALL else TAB_APP)) }
    var grid by rememberSaveable { mutableStateOf(prefs.getBoolean("is_grid_view", false)) }
    var sort by rememberSaveable { mutableIntStateOf(prefs.getInt("files_sort", SORT_NEWEST)) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Uri>() }
    val selectionMode = selected.isNotEmpty()
    var preview by remember { mutableStateOf<DevicePdf?>(null) }
    var renaming by remember { mutableStateOf<DevicePdf?>(null) }
    val deleter = rememberFileDeleter { uris -> selected.removeAll(uris); recents = RecentStore.list(context) }

    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasPermission = FileUtils.hasStoragePermission(context)
            recents = RecentStore.list(context)
            PdfIndex.refresh(context)
        }
    }

    val manageStorage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasPermission = FileUtils.hasStoragePermission(context)
        if (hasPermission) { tab = TAB_ALL; scope.launch { PdfIndex.refresh(context) } }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = FileUtils.hasStoragePermission(context)
        if (hasPermission) { tab = TAB_ALL; scope.launch { PdfIndex.refresh(context) } }
    }
    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                manageStorage.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
            }.onFailure { manageStorage.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else legacyPermission.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            nav.openUri(uri)
        }
    }

    val all = indexed.orEmpty()
    val recentItems = remember(recents, all) {
        val byUri = all.associateBy { it.uri }
        recents.map { r -> byUri[r.uri] ?: DevicePdf(r.uri, r.name, r.size, r.time) }
    }
    val source = when (tab) {
        TAB_APP -> all.filter { it.fromApp }
        TAB_RECENT -> recentItems
        else -> all
    }
    val shown = remember(source, query, sort, tab) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) source else source.filter { it.name.contains(q, true) || it.folder.contains(q, true) }
        if (tab == TAB_RECENT) filtered else when (sort) {
            SORT_NAME -> filtered.sortedBy { it.name.lowercase() }
            SORT_LARGEST -> filtered.sortedByDescending { it.size }
            SORT_OLDEST -> filtered.sortedBy { it.dateModified }
            else -> filtered.sortedByDescending { it.dateModified }
        }
    }
    val sections = tab != TAB_RECENT && (sort == SORT_NEWEST || sort == SORT_OLDEST) && query.isBlank()

    BackHandler(enabled = selectionMode) { selected.clear() }
    BackHandler(enabled = !selectionMode && searchOpen) { searchOpen = false; query = "" }

    fun menuFor(item: DevicePdf) = FileMenu(
        open = { nav.openUri(item.uri) },
        preview = { preview = item },
        share = { shareUris(context, listOf(item.uri)) },
        properties = { nav.openTool("properties", item.uri) },
        saveToDownloads = if (item.isAppFile && item.path != null) ({ saver.toDownloads(listOf(File(item.path)), null) }) else null,
        rename = if (tab != TAB_RECENT || item.path != null) ({ renaming = item }) else null,
        delete = if (tab != TAB_RECENT || item.path != null) ({ deleter.request(listOf(item)) }) else null,
        removeFromRecents = if (tab == TAB_RECENT) ({ RecentStore.remove(context, item.uri); recents = RecentStore.list(context) }) else null
    )
    fun onItemClick(item: DevicePdf) {
        if (selectionMode) { if (item.uri in selected) selected.remove(item.uri) else selected.add(item.uri) }
        else nav.openUri(item.uri)
    }
    fun onItemLongClick(item: DevicePdf) { if (item.uri !in selected) selected.add(item.uri) }

    Scaffold(
        bottomBar = { BottomNavigationBar("files") },
        floatingActionButton = {
            if (!selectionMode) ExtendedFloatingActionButton(
                onClick = { openDocument.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Rounded.FileOpen, null) }, text = { Text("Open file") }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // ---------------- Header
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.statusBarsPadding()) {
                    if (selectionMode) {
                        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ selected.clear() }) { Icon(Icons.Rounded.Close, "Cancel selection") }
                            Text("${selected.size} selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            IconButton({
                                val ids = shown.map { it.uri }
                                if (selected.size == ids.size) selected.clear() else { selected.clear(); selected.addAll(ids) }
                            }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                            IconButton({ shareUris(context, selected.toList()) }) { Icon(Icons.Rounded.Share, "Share") }
                            if (selected.size >= 2) IconButton({
                                Incoming.uris = selected.toList(); selected.clear(); nav.openTool("merge")
                            }) { Icon(Icons.Rounded.MergeType, "Merge selected") }
                            if (tab != TAB_RECENT) IconButton({
                                val sel = selected.toSet()
                                deleter.request(shown.filter { it.uri in sel })
                            }) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.error) }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Files", style = MaterialTheme.typography.headlineMedium)
                                Text(
                                    if (indexed == null) "Looking for PDFs…" else "${all.size} PDFs · ${formatFileSize(all.sumOf { it.size })}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton({ searchOpen = !searchOpen; if (!searchOpen) query = "" }) { Icon(if (searchOpen) Icons.Rounded.SearchOff else Icons.Rounded.Search, "Search") }
                            Box {
                                IconButton({ sortMenu = true }, enabled = tab != TAB_RECENT) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                                DropdownMenu(sortMenu, { sortMenu = false }) {
                                    sortLabels.forEachIndexed { i, label ->
                                        DropdownMenuItem(
                                            { Text(label) }, { sort = i; sortMenu = false; prefs.edit().putInt("files_sort", i).apply() },
                                            trailingIcon = { if (sort == i) Icon(Icons.Rounded.Check, null) }
                                        )
                                    }
                                }
                            }
                            IconButton({ grid = !grid; prefs.edit().putBoolean("is_grid_view", grid).apply() }) {
                                Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "List view" else "Grid view")
                            }
                            IconButton({ nav.open("folder_browser") }) { Icon(Icons.Rounded.FolderOpen, "Browse folders") }
                        }
                    }
                    AnimatedVisibility(searchOpen && !selectionMode) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                        OutlinedTextField(
                            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
                            placeholder = { Text("Search by name or folder") }, singleLine = true, shape = RoundedCornerShape(18.dp),
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } }
                        )
                    }
                    PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                        listOf(
                            "All" to all.size,
                            "Saved by app" to all.count { it.fromApp },
                            "Recent" to recents.size
                        ).forEachIndexed { i, (label, n) ->
                            Tab(
                                selected = tab == i,
                                onClick = { tab = i; selected.clear(); prefs.edit().putInt("files_tab", i).apply() },
                                text = { Text(if (indexed == null && i != TAB_RECENT) label else "$label ($n)", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                    PathBar(tab, context)
                }
            }

            // ---------------- Content
            if (tab == TAB_ALL && !hasPermission) PermissionBanner { requestPermission() }
            PullToRefreshBox(
                isRefreshing = loading && indexed != null,
                onRefresh = { scope.launch { PdfIndex.refresh(context); recents = RecentStore.list(context) } },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    indexed == null && tab != TAB_RECENT -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Finding PDFs on your phone…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    shown.isEmpty() -> EmptyFiles(tab, query.isNotBlank())
                    grid -> LazyVerticalGrid(
                        GridCells.Adaptive(150.dp), Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (sections) {
                            shown.groupBy { dateSection(it.dateModified) }.forEach { (label, group) ->
                                item(key = "h_$label", span = { GridItemSpan(maxLineSpan) }) { SectionLabel(label, group.size) }
                                items(group, key = { it.uri.toString() }) { f ->
                                    PdfGridItem(f, f.uri in selected, selectionMode, menuFor(f), { onItemClick(f) }, { onItemLongClick(f) })
                                }
                            }
                        } else items(shown, key = { it.uri.toString() }) { f ->
                            PdfGridItem(f, f.uri in selected, selectionMode, menuFor(f), { onItemClick(f) }, { onItemLongClick(f) })
                        }
                    }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        LazyColumn(
                            Modifier.fillMaxHeight().widthIn(max = 900.dp),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (sections) {
                                shown.groupBy { dateSection(it.dateModified) }.forEach { (label, group) ->
                                    stickyHeader(key = "h_$label") { SectionLabel(label, group.size) }
                                    items(group, key = { it.uri.toString() }) { f ->
                                        PdfListItem(f, f.uri in selected, selectionMode, menuFor(f), { onItemClick(f) }, { onItemLongClick(f) }, showPath = tab != TAB_APP)
                                    }
                                }
                            } else items(shown, key = { it.uri.toString() }) { f ->
                                PdfListItem(f, f.uri in selected, selectionMode, menuFor(f), { onItemClick(f) }, { onItemLongClick(f) }, showPath = tab != TAB_APP)
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { item ->
        QuickPreviewSheet(
            item, onDismiss = { preview = null },
            onOpen = { preview = null; nav.openUri(item.uri) },
            onRename = if (item.path != null) ({ preview = null; renaming = item }) else null,
            onDelete = if (item.path != null || tab != TAB_RECENT) ({ preview = null; deleter.request(listOf(item)) }) else null,
            onProperties = { preview = null; nav.openTool("properties", item.uri) }
        )
    }
    renaming?.let { item ->
        RenameDialog(item, onDismiss = { renaming = null }) { name ->
            renaming = null
            android.widget.Toast.makeText(context, "Renamed to $name", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/** Shows exactly where the files in the current tab live on the phone. */
@Composable
private fun PathBar(tab: Int, context: android.content.Context) {
    val paths = when (tab) {
        TAB_APP -> listOf(
            Icons.Rounded.AutoAwesome to FileUtils.appFolderLabel(context),
            Icons.Rounded.Download to "Internal storage/Download/PDF Studio"
        )
        TAB_RECENT -> listOf(Icons.Rounded.History to "Opened or created recently in PDF Studio")
        else -> listOf(Icons.Rounded.PhoneAndroid to "Internal storage · every folder with PDFs")
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        paths.forEach { (icon, path) ->
            Row(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FolderSpecial, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("See every PDF on your phone", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Allow file access to list, rename and delete PDFs in all folders.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(8.dp))
            Button(onGrant, shape = RoundedCornerShape(12.dp)) { Text("Allow") }
        }
    }
}

@Composable
private fun EmptyFiles(tab: Int, searching: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            if (searching) Icons.Rounded.SearchOff else if (tab == TAB_RECENT) Icons.Rounded.History else Icons.Rounded.FolderOff, null,
            Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                searching -> "No matching files"
                tab == TAB_APP -> "Nothing saved yet"
                tab == TAB_RECENT -> "No recent files"
                else -> "No PDFs found"
            },
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                searching -> "Try a different name or folder."
                tab == TAB_APP -> "PDFs you create with any tool, and copies you save to Downloads, appear here."
                tab == TAB_RECENT -> "PDFs you open in PDF Studio will show up here."
                else -> "Pull down to refresh, or tap Open file to pick one."
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
    }
}
