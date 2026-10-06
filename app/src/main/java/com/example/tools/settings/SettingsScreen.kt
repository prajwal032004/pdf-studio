package com.example.tools.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.AppInfo
import com.example.BuildConfig
import com.example.R
import com.example.core.RecentStore
import com.example.core.Workspace
import com.example.ui.common.SegmentedChoice
import com.example.ui.common.SwitchRow
import com.example.ui.common.readableWidth
import com.example.ui.home.BottomNavigationBar
import com.example.ui.theme.AppColorTheme
import com.example.ui.theme.LocalAppColorTheme
import com.example.ui.theme.LocalThemeMode
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemePreferences
import com.example.ui.theme.seedFor
import com.example.utils.FileUtils
import com.example.widget.PdfDashboardWidgetProvider
import com.example.widget.PdfToolsWidgetProvider
import com.example.widget.QuickToolWidgetProvider
import com.example.widget.RecentFilesWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class WidgetOption(val title: String, val size: String, val preview: Int, val provider: Class<*>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenLink: (String) -> Unit
) {
    val context = LocalContext.current
    val nav = com.example.ui.common.LocalNav.current
    val indexed by com.example.utils.PdfIndex.files.collectAsState()
    LaunchedEffect(Unit) { com.example.utils.PdfIndex.refresh(context) }
    var readPaged by remember { mutableStateOf(com.example.core.ViewerPrefs.paged(context)) }
    var readResume by remember { mutableStateOf(com.example.core.ViewerPrefs.resume(context)) }
    var readKeepOn by remember { mutableStateOf(com.example.core.ViewerPrefs.keepScreenOn(context)) }
    val currentTheme = LocalAppColorTheme.current
    val mode = LocalThemeMode.current
    var storageSizeStr by remember { mutableStateOf("Calculating…") }
    var freeSpaceStr by remember { mutableStateOf("Calculating…") }
    var cacheStr by remember { mutableStateOf("…") }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    suspend fun measure() {
        val size = withContext(Dispatchers.IO) { FileUtils.getAppFilesDir(context).listFiles()?.sumOf { it.length() } ?: 0L }
        val cache = withContext(Dispatchers.IO) { context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        val free = withContext(Dispatchers.IO) { android.os.StatFs(android.os.Environment.getDataDirectory().path).let { it.availableBlocksLong * it.blockSizeLong } }
        storageSizeStr = Workspace.formatSize(size); cacheStr = Workspace.formatSize(cache); freeSpaceStr = Workspace.formatSize(free)
    }
    LaunchedEffect(Unit) { measure() }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = { LargeTopAppBar(title = { Text("Settings", fontWeight = FontWeight.Bold) }, scrollBehavior = scrollBehavior) },
        bottomBar = { BottomNavigationBar("settings") },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).readableWidth()) {
            // ---------------- Appearance
            SectionTitle("Appearance")
            SettingsCard {
                CardHeader(Icons.Rounded.ColorLens, "Theme colour")
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AppColorTheme.entries.forEach { theme ->
                        val sel = currentTheme.value == theme
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (theme == AppColorTheme.DYNAMIC) MaterialTheme.colorScheme.primary else seedFor(theme))
                                .clickable { currentTheme.value = theme; ThemePreferences.saveTheme(context, theme) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (sel) Icon(Icons.Rounded.Check, "Selected", tint = Color.White)
                            else if (theme == AppColorTheme.DYNAMIC) Icon(Icons.Rounded.Wallpaper, "Dynamic", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Text(if (currentTheme.value == AppColorTheme.DYNAMIC) "Dynamic: follows your wallpaper (Android 12+)" else currentTheme.value.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(16.dp))
                CardHeader(Icons.Rounded.DarkMode, "Dark mode")
                Spacer(Modifier.height(10.dp))
                SegmentedChoice(listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), mode.value, {
                    mode.value = it; ThemePreferences.saveMode(context, it)
                })
            }

            // ---------------- Widgets
            SectionTitle("Home screen widgets")
            SettingsCard {
                CardHeader(Icons.Rounded.Widgets, "Add a widget")
                Text("Beautiful widgets for quick access — tap one to add it to your home screen, or long-press the home screen → Widgets → PDF Studio.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                val widgets = listOf(
                    WidgetOption("PDF Studio", "4 × 2 · resizable", R.drawable.preview_widget_home, PdfToolsWidgetProvider::class.java),
                    WidgetOption("Dashboard", "4 × 3", R.drawable.preview_widget_home_large, PdfDashboardWidgetProvider::class.java),
                    WidgetOption("Recent PDFs", "4 × 3 · scrollable", R.drawable.preview_widget_recent, RecentFilesWidgetProvider::class.java),
                    WidgetOption("Quick Tool", "1 × 1 · choose tool", R.drawable.preview_widget_quick, QuickToolWidgetProvider::class.java)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(widgets) { w ->
                        Column(Modifier.width(220.dp).clip(RoundedCornerShape(20.dp)).clickable {
                            val mgr = AppWidgetManager.getInstance(context)
                            if (Build.VERSION.SDK_INT >= 26 && mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(context, w.provider), null, null)
                            else Toast.makeText(context, "Long-press your home screen → Widgets → PDF Studio", Toast.LENGTH_LONG).show()
                        }) {
                            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                                Image(painterResource(w.preview), w.title, contentScale = ContentScale.Fit, modifier = Modifier.padding(10.dp))
                            }
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(w.title, style = MaterialTheme.typography.titleSmall)
                                    Text(w.size, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Rounded.AddCircle, "Add", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // ---------------- Reading
            SectionTitle("Reading")
            SettingsCard {
                CardHeader(Icons.Rounded.MenuBook, "PDF viewer")
                Spacer(Modifier.height(10.dp))
                SegmentedChoice(listOf(false to "Continuous scroll", true to "Single page"), readPaged, {
                    readPaged = it; com.example.core.ViewerPrefs.setPaged(context, it)
                })
                Spacer(Modifier.height(6.dp))
                SwitchRow("Resume where I left off", "Reopen each PDF on the last page you read", readResume) {
                    readResume = it; com.example.core.ViewerPrefs.setResume(context, it)
                }
                SwitchRow("Keep screen on while reading", null, readKeepOn) {
                    readKeepOn = it; com.example.core.ViewerPrefs.setKeepScreenOn(context, it)
                }
                TextButton({ com.example.core.ViewerPrefs.clearPositions(context); Toast.makeText(context, "Reading positions cleared", Toast.LENGTH_SHORT).show() }) {
                    Text("Forget reading positions")
                }
            }

            // ---------------- PDFs on this phone
            SectionTitle("PDFs on this phone")
            SettingsCard {
                val all = indexed
                if (all == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Scanning…")
                    }
                } else {
                    val stats = com.example.core.FileOps.stats(context, all)
                    StorageRow(Icons.Rounded.PictureAsPdf, "${stats.count} PDFs", "Across ${stats.folders.size} folders", Workspace.formatSize(stats.bytes), MaterialTheme.colorScheme.primary)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                    stats.folders.take(3).forEach { f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(f.folder, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${f.count} · ${Workspace.formatSize(f.bytes)}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (!FileUtils.hasStoragePermission(context)) Text(
                        "Only PDFs PDF Studio can see are counted — allow file access in the Files tab for the full picture.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    FilledTonalButton({ nav.open("storage?tab=1") }, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Find duplicates")
                    }
                    OutlinedButton({ nav.open("storage?tab=0") }, shape = RoundedCornerShape(12.dp)) { Text("Storage details") }
                }
            }

            // ---------------- Storage
            SectionTitle("App storage")
            SettingsCard {
                StorageRow(Icons.Rounded.Folder, "Created by PDF Studio", FileUtils.appFolderLabel(context), storageSizeStr, MaterialTheme.colorScheme.primary)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                val saved = indexed.orEmpty().filter { it.savedByApp }
                StorageRow(Icons.Rounded.Download, "Saved to Downloads", "Internal storage/Download/PDF Studio · ${saved.size} files", Workspace.formatSize(saved.sumOf { it.size }), MaterialTheme.colorScheme.secondary)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                StorageRow(Icons.Rounded.CleaningServices, "Temporary files", "Working copies & previews", cacheStr, MaterialTheme.colorScheme.tertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton({
                        scope.launch {
                            withContext(Dispatchers.IO) { Workspace.clearWork(context); context.cacheDir.listFiles()?.filter { it.isFile && (it.name.startsWith("tmp_") || it.name.startsWith("cam_") || it.name.endsWith(".pdf")) }?.forEach { it.delete() } }
                            measure(); Toast.makeText(context, "Temporary files cleared", Toast.LENGTH_SHORT).show()
                        }
                    }, shape = RoundedCornerShape(12.dp)) { Text("Clear temporary files") }
                    OutlinedButton({ RecentStore.clear(context); Toast.makeText(context, "Recent list cleared", Toast.LENGTH_SHORT).show() }, shape = RoundedCornerShape(12.dp)) { Text("Clear recents") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                StorageRow(Icons.Rounded.Storage, "Device free space", "Available for new PDFs", freeSpaceStr, MaterialTheme.colorScheme.secondary)
            }

            // ---------------- Privacy
            SectionTitle("Privacy")
            SettingsCard {
                CardHeader(Icons.Rounded.Shield, "Offline by design")
                Text("Every tool runs on this device — including OCR, barcode reading and signature checks. PDF Studio never uploads your documents.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }

            // ---------------- Credits
            SectionTitle("PDF Studio")
            SettingsCard {
                CardHeader(Icons.Rounded.Code, "Open source on GitHub")
                Text("Made by ${AppInfo.DEVELOPER}. Free and MIT-licensed — star the repo, report a bug or contribute.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ uriHandler.openUri(AppInfo.REPO) }, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.Star, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Star on GitHub")
                    }
                    OutlinedButton({ uriHandler.openUri(AppInfo.DEVELOPER_GITHUB) }, shape = RoundedCornerShape(12.dp)) { Text("Developer") }
                }
                Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).padding(top = 12.dp), fontWeight = FontWeight.Bold)
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
    ) { Column(Modifier.padding(20.dp), content = content) }
}

@Composable
private fun CardHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StorageRow(icon: ImageVector, title: String, subtitle: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color.copy(alpha = 0.15f)) { Icon(icon, null, Modifier.padding(10.dp), tint = color) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
    }
}
