package com.example.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.AppInfo
import com.example.core.RecentItem
import com.example.core.RecentStore
import com.example.core.Workspace
import com.example.ui.common.LocalNav
import com.example.ui.common.ToolTile
import com.example.ui.common.readableWidth
import com.example.ui.theme.Accents
import com.example.ui.tools.ToolCatalog
import com.example.ui.tools.ToolCategory
import com.example.widget.WidgetActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    var recent by remember { mutableStateOf(RecentStore.list(context)) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { recent = RecentStore.list(context) } }
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello" }
    }

    Scaffold(bottomBar = { BottomNavigationBar("home") }, containerColor = cs.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).readableWidth(900.dp)) {
            // ---------------- Hero
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                    .background(Brush.linearGradient(listOf(cs.primary, lerpColor(cs.primary, Accents.pink, 0.45f), lerpColor(cs.primary, Accents.orange, 0.35f))))
            ) {
                // soft decorative circles
                Box(Modifier.offset(x = 240.dp, y = (-40).dp).size(220.dp).background(Color.White.copy(alpha = 0.08f), CircleShape))
                Box(Modifier.offset(x = (-60).dp, y = 120.dp).size(160.dp).background(Color.White.copy(alpha = 0.06f), CircleShape))
                Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PictureAsPdf, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("PDF Studio", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).clickable { uriHandler.openUri(AppInfo.DEVELOPER_GITHUB) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Made by ", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                                Text(AppInfo.DEVELOPER, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(3.dp))
                                Icon(Icons.Rounded.OpenInNew, "Open GitHub", tint = Color.White, modifier = Modifier.size(12.dp))
                            }
                        }
                        IconButton(
                            { nav.tab("settings") },
                            Modifier.size(46.dp).background(Color.White.copy(alpha = 0.18f), CircleShape)
                        ) { Icon(Icons.Rounded.Settings, "Settings", tint = Color.White) }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("$greeting 👋", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
                    Text("What would you like to do\nwith your PDFs today?", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        onClick = { nav.tab("tools_search") }, shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = 0.95f),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, null, tint = Color(0xFF5B5470))
                            Spacer(Modifier.width(10.dp))
                            Text("Search ${ToolCatalog.all.size} tools…", color = Color(0xFF5B5470), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Box(Modifier.background(Color(0xFFEDE7F6), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text("Offline", style = MaterialTheme.typography.labelSmall, color = Color(0xFF5E35B1))
                            }
                        }
                    }
                }
            }

            // ---------------- Quick actions bento
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    BigAction("Open PDF", "View, search & annotate", Icons.Rounded.FileOpen, Accents.blue, Modifier.weight(1.15f).height(168.dp)) { nav.openTool("open") }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SmallAction("Scan", Icons.Rounded.DocumentScanner, Accents.teal, Modifier.fillMaxWidth().height(78.dp)) { nav.openTool("scan") }
                        SmallAction("Images → PDF", Icons.Rounded.Collections, Accents.orange, Modifier.fillMaxWidth().height(78.dp)) { nav.openTool("images_to_pdf") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallAction("Merge", Icons.Rounded.MergeType, Accents.violet, Modifier.weight(1f).height(92.dp), stacked = true) { nav.openTool("merge") }
                    SmallAction("Compress", Icons.Rounded.Compress, Accents.green, Modifier.weight(1f).height(92.dp), stacked = true) { nav.openTool("compress") }
                    SmallAction("Sign", Icons.Rounded.HistoryEdu, Accents.pink, Modifier.weight(1f).height(92.dp), stacked = true) { nav.openTool("sign") }
                }
            }

            // ---------------- Popular tools
            SectionHeader("Popular tools", "See all") { nav.tab("tools") }
            val popular = listOf("annotate", "split", "ocr", "protect", "watermark", "pdf_to_images", "reorder", "redact", "fill_form", "page_numbers", "crop", "unlock")
                .mapNotNull { ToolCatalog.get(it) }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(popular) { t -> ToolTile(t, Modifier.width(88.dp)) { nav.openTool(t.id) } }
            }

            // ---------------- Categories
            SectionHeader("Browse by category", null) {}
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolCategory.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { c -> CategoryCard(c, Modifier.weight(1f)) { nav.openCategory(c.name) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // ---------------- Recent
            SectionHeader("Recent files", "Files") { nav.tab("files") }
            if (recent.isEmpty()) {
                Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerLow,
                    border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.5f))) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.History, null, Modifier.size(40.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        Text("Nothing here yet", style = MaterialTheme.typography.titleSmall)
                        Text("PDFs you open or create will appear here and in the Recent PDFs widget.", style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.take(6).forEach { r -> RecentRow(r, onRemove = { RecentStore.remove(context, r.uri); recent = RecentStore.list(context) }) { nav.openUri(r.uri) } }
            }

            // ---------------- Footer
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(16.dp), tint = Accents.green)
                Spacer(Modifier.width(6.dp))
                Text("100% offline · your files never leave this device", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onAction) { Text(action); Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp)) }
    }
}

@Composable
private fun BigAction(title: String, subtitle: String, icon: ImageVector, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(28.dp), color = Color.Transparent, shadowElevation = 6.dp) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent, lerpColor(accent, Color(0xFF1A237E), 0.35f))))) {
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 30.dp, y = 30.dp).size(120.dp).background(Color.White.copy(alpha = 0.1f), CircleShape))
            Column(Modifier.padding(18.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(48.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }
}

@Composable
private fun SmallAction(title: String, icon: ImageVector, accent: Color, modifier: Modifier, stacked: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        if (stacked) Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(40.dp).background(Accents.container(accent), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Accents.onContainer(accent), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        } else Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Accents.container(accent), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Accents.onContainer(accent), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CategoryCard(c: ToolCategory, modifier: Modifier, onClick: () -> Unit) {
    val count = ToolCatalog.inCategory(c).size
    Surface(onClick = onClick, modifier = modifier.height(84.dp), shape = RoundedCornerShape(22.dp), color = Accents.container(c.accent)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(c.accent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(c.icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, color = Accents.onContainer(c.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$count tools", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecentRow(r: RecentItem, onRemove: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp, 56.dp).shadow(2.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                com.example.ui.components.PdfThumbnail(r.uri, Modifier.fillMaxSize(), widthPx = 160, version = r.size)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(if (r.pages > 0) "${r.pages} pages" else null, if (r.size > 0) Workspace.formatSize(r.size) else null, WidgetActions.relativeTime(r.time),
                        r.tool?.let { ToolCatalog.get(it)?.title }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Remove from recents") }, { menu = false; onRemove() }, leadingIcon = { Icon(Icons.Rounded.Close, null) })
                }
            }
        }
    }
}

@Composable
fun BottomNavigationBar(
    currentRoute: String = "home",
    onHomeClick: () -> Unit = {},
    onFilesClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    // Wide screens use the navigation rail instead.
    if (com.example.ui.common.LocalWideLayout.current) return
    val nav = LocalNav.current
    val cs = MaterialTheme.colorScheme
    NavigationBar(containerColor = cs.surfaceContainer, tonalElevation = 0.dp) {
        listOf(
            Triple("home", "Home", Icons.Rounded.Home), Triple("tools", "Tools", Icons.Rounded.Apps),
            Triple("files", "Files", Icons.Rounded.Folder), Triple("settings", "Settings", Icons.Rounded.Settings)
        ).forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = currentRoute == route,
                onClick = { if (currentRoute != route) nav.tab(route) },
                icon = { Icon(icon, label) },
                label = { Text(label) }
            )
        }
    }
}
