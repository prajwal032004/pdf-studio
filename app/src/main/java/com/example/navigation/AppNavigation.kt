package com.example.navigation

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.core.Workspace
import com.example.tools.settings.SettingsScreen
import com.example.tools.viewer.PdfViewerScreen
import com.example.ui.common.AppNav
import com.example.ui.common.LocalNav
import com.example.ui.common.LocalWideLayout
import com.example.ui.common.ToolTopBar
import com.example.ui.files.FilesScreen
import com.example.ui.files.FolderBrowserScreen
import com.example.ui.files.StorageScreen
import com.example.ui.home.HomeScreen
import com.example.ui.tools.AllToolsScreen
import com.example.ui.tools.ToolHost
import com.example.ui.tools.screens.Incoming
import java.io.File

/** Where the app should go right after launch (from a widget, share sheet or "Open with"). */
sealed class StartRequest {
    data class Viewer(val uri: Uri, val external: Boolean) : StartRequest()
    data class Tool(val id: String, val uri: Uri? = null) : StartRequest()
    data object Tools : StartRequest()
    data object IncomingPdfs : StartRequest()
}

/** The four top-level destinations shown in the bottom bar / navigation rail. */
val TabRoutes = listOf("home", "tools", "files", "settings")

private fun routeOf(e: NavBackStackEntry?) = e?.destination?.route?.substringBefore('?')

private class NavImpl(private val nav: NavHostController, private val context: android.content.Context) : AppNav {
    private var lastPush = 0L

    /** Ignores a second push that arrives within a moment of the first (double taps, fast repeats). */
    private fun push(route: String, builder: androidx.navigation.NavOptionsBuilder.() -> Unit = {}) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPush < 450) return
        lastPush = now
        nav.navigate(route, builder)
    }

    override fun back() {
        if (nav.previousBackStackEntry != null) nav.popBackStack()
        else nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } }
    }
    override fun openUri(uri: Uri) = push("viewer?uri=${Uri.encode(uri.toString())}")
    override fun openFile(file: File) = openUri(Workspace.uriFor(context, file))
    override fun openTool(id: String, uri: Uri?) {
        if (id == "_all") { tab("tools"); return }
        push("tool/$id" + (uri?.let { "?uri=${Uri.encode(it.toString())}" } ?: ""))
    }
    override fun openLink(url: String) = push("webview?url=${Uri.encode(url)}")
    override fun home() { if (!nav.popBackStack("home", false)) nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } } }
    override fun tab(route: String) {
        val target = when (route) { "tools_search" -> "tools?search=true"; else -> route }
        nav.navigate(target) {
            // Tabs never pile up: each one sits directly on Home, and returning restores its scroll and state.
            popUpTo("home") { saveState = route != "tools_search" }
            launchSingleTop = true
            restoreState = route != "tools_search"
        }
    }
    override fun openCategory(category: String) = push("tools?cat=$category") { launchSingleTop = true }
    override fun open(route: String) = push(route) { launchSingleTop = true }
}

private fun isTabSwitch(s: AnimatedContentTransitionScope<NavBackStackEntry>) =
    routeOf(s.initialState) in TabRoutes && routeOf(s.targetState) in TabRoutes

@Composable
fun AppNavigation(start: StartRequest? = null) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val appNav = remember(navController) { NavImpl(navController, context) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    LaunchedEffect(start) {
        when (start) {
            is StartRequest.Viewer -> navController.navigate("viewer?uri=${Uri.encode(start.uri.toString())}&ext=${start.external}")
            is StartRequest.Tool -> appNav.openTool(start.id, start.uri)
            StartRequest.Tools -> appNav.tab("tools")
            StartRequest.IncomingPdfs -> navController.navigate("incoming")
            null -> {}
        }
    }

    CompositionLocalProvider(LocalNav provides appNav, LocalWideLayout provides wide) {
        val entry by navController.currentBackStackEntryAsState()
        val current = routeOf(entry)
        Row(Modifier.fillMaxSize()) {
            if (wide && current in TabRoutes) AppNavigationRail(current ?: "home") { appNav.tab(it) }
            NavHost(
                navController = navController, startDestination = "home",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                enterTransition = {
                    if (isTabSwitch(this)) fadeIn(tween(180)) else fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }
                },
                exitTransition = { if (isTabSwitch(this)) fadeOut(tween(120)) else fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = {
                    if (isTabSwitch(this)) fadeOut(tween(120)) else fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 12 }
                }
            ) {
                composable("home") { HomeScreen() }
                composable(
                    "tools?cat={cat}&search={search}",
                    arguments = listOf(
                        navArgument("cat") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("search") { type = NavType.BoolType; defaultValue = false }
                    )
                ) { e -> AllToolsScreen(e.arguments?.getString("cat"), e.arguments?.getBoolean("search") == true) }
                composable("files") { FilesScreen() }
                composable("folder_browser") { FolderBrowserScreen() }
                composable(
                    "storage?tab={tab}",
                    arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })
                ) { e -> StorageScreen(e.arguments?.getInt("tab") ?: 0) }
                composable("settings") { SettingsScreen(onOpenLink = { appNav.openLink(it) }) }
                composable("webview?url={url}") { e ->
                    e.arguments?.getString("url")?.let { com.example.tools.webview.WebViewScreen(url = Uri.decode(it)) }
                }
                composable(
                    "viewer?uri={uri}&ext={ext}",
                    arguments = listOf(navArgument("uri") { type = NavType.StringType }, navArgument("ext") { type = NavType.BoolType; defaultValue = false })
                ) { e ->
                    val uri = Uri.parse(Uri.decode(e.arguments?.getString("uri") ?: return@composable))
                    val external = e.arguments?.getBoolean("ext") == true
                    // Opened from another app: back returns to that app instead of PDF Studio's home.
                    val activity = LocalContext.current as? Activity
                    BackHandler(enabled = external && routeOf(navController.previousBackStackEntry) == "home") { activity?.finish() }
                    PdfViewerScreen(uri = uri, fromExternal = external)
                }
                composable(
                    "tool/{id}?uri={uri}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null })
                ) { e ->
                    val id = e.arguments?.getString("id") ?: "all"
                    val uri = e.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
                    ToolHost(id, uri)
                }
                composable("incoming") { IncomingPdfsScreen() }
            }
        }
    }
}

@Composable
private fun AppNavigationRail(current: String, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    NavigationRail(containerColor = cs.surfaceContainer, header = {
        Spacer(Modifier.height(8.dp))
        Icon(Icons.Rounded.PictureAsPdf, null, tint = cs.primary, modifier = Modifier.size(32.dp))
    }) {
        Spacer(Modifier.weight(1f))
        listOf(
            Triple("home", "Home", Icons.Rounded.Home), Triple("tools", "Tools", Icons.Rounded.Apps),
            Triple("files", "Files", Icons.Rounded.Folder), Triple("settings", "Settings", Icons.Rounded.Settings)
        ).forEach { (route, label, icon) ->
            NavigationRailItem(
                selected = current == route,
                onClick = { if (current != route) onSelect(route) },
                icon = { Icon(icon, label) },
                label = { Text(label) }
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Several PDFs were shared into the app — offer what makes sense for a set of files. */
@Composable
private fun IncomingPdfsScreen() {
    val nav = LocalNav.current
    val uris = remember { Incoming.uris }
    Scaffold(topBar = { ToolTopBar(null, "${uris.size} PDFs received", "Choose what to do with them") }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                Triple("Merge into one PDF", Icons.Rounded.MergeType, "merge"),
                Triple("Batch process (compress, watermark, OCR…)", Icons.Rounded.DynamicFeed, "batch"),
                Triple("Open the first one", Icons.Rounded.MenuBook, "open_first"),
                Triple("Browse all tools", Icons.Rounded.Apps, "_all")
            ).forEach { (label, icon, id) ->
                ElevatedCard(onClick = {
                    when (id) {
                        "open_first" -> uris.firstOrNull()?.let { Incoming.uris = emptyList(); nav.openUri(it) }
                        else -> nav.openTool(id)
                    }
                }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
