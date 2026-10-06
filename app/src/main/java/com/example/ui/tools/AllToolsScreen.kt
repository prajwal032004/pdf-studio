package com.example.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.example.ui.common.LocalNav
import com.example.ui.common.ToolTile
import com.example.ui.home.BottomNavigationBar
import com.example.ui.theme.Accents
import kotlinx.coroutines.launch

@Composable
fun AllToolsScreen(initialCategory: String? = null, focusSearch: Boolean = false) {
    val nav = LocalNav.current
    var query by remember { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val results = ToolCatalog.search(query)

    // Index of each category header inside the grid, for chip jumps.
    val headerIndex = remember {
        var idx = 0
        ToolCategory.entries.associateWith { c -> idx.also { idx += 1 + ToolCatalog.inCategory(c).size } }
    }
    LaunchedEffect(initialCategory) {
        val c = ToolCategory.entries.firstOrNull { it.name == initialCategory } ?: return@LaunchedEffect
        grid.scrollToItem(headerIndex[c] ?: 0)
    }
    LaunchedEffect(focusSearch) { if (focusSearch) runCatching { focus.requestFocus() } }

    Scaffold(bottomBar = { BottomNavigationBar("tools") }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 6.dp)) {
                    Text("All tools", style = MaterialTheme.typography.headlineMedium)
                    Text("${ToolCatalog.all.size} offline tools · nothing leaves your phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text("Search tools — try \"compress\", \"sign\", \"ocr\"") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                        singleLine = true, shape = RoundedCornerShape(18.dp)
                    )
                    if (query.isBlank()) LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ToolCategory.entries) { c ->
                            AssistChip(
                                onClick = { scope.launch { grid.animateScrollToItem(headerIndex[c] ?: 0) } },
                                label = { Text(c.title) },
                                leadingIcon = { Icon(c.icon, null, Modifier.size(16.dp), tint = c.accent) }
                            )
                        }
                    }
                }
            }
            LazyVerticalGrid(
                GridCells.Adaptive(92.dp), Modifier.fillMaxSize(), state = grid,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (query.isNotBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(if (results.isEmpty()) "No tools match \"$query\"" else "${results.size} result(s)",
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
                    }
                    items(results.size, key = { results[it].id }) { i -> ToolTile(results[i], Modifier.fillMaxWidth()) { nav.openTool(results[i].id) } }
                } else ToolCategory.entries.forEach { c ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h_${c.name}") {
                        Row(Modifier.padding(start = 6.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).background(Accents.container(c.accent), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                Icon(c.icon, null, Modifier.size(18.dp), tint = Accents.onContainer(c.accent))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(c.title, style = MaterialTheme.typography.titleMedium)
                                Text(c.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    val list = ToolCatalog.inCategory(c)
                    items(list.size, key = { list[it].id }) { i ->
                        Box {
                            ToolTile(list[i], Modifier.fillMaxWidth()) { nav.openTool(list[i].id) }
                            if (list[i].isNew) Badge(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 10.dp), containerColor = Accents.pink) { Text("NEW") }
                        }
                    }
                }
            }
        }
    }
}
