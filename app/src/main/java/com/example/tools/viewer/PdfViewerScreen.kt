package com.example.tools.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.core.*
import com.example.tools.viewer.ViewerExtras.recordOpen
import com.example.ui.common.*
import com.example.ui.tools.ToolCatalog
import com.example.ui.tools.ToolCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

object ViewerExtras {
    fun recordOpen(context: Context, pdf: PickedPdf) {
        RecentStore.add(context, RecentItem(pdf.uri, pdf.name, System.currentTimeMillis(), pdf.pageCount, pdf.size))
    }
}

// ---------------------------------------------------------------- Reading themes

private val nightFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
)))

// Maps white paper to warm cream and black ink to soft brown.
private val sepiaFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    (244f - 91f) / 255f, 0f, 0f, 0f, 91f,
    0f, (236f - 70f) / 255f, 0f, 0f, 70f,
    0f, 0f, (216f - 54f) / 255f, 0f, 54f,
    0f, 0f, 0f, 1f, 0f
)))

private fun filterFor(theme: Int) = when (theme) {
    ViewerPrefs.THEME_NIGHT -> nightFilter
    ViewerPrefs.THEME_SEPIA -> sepiaFilter
    else -> null
}

private fun paperFor(theme: Int) = when (theme) {
    ViewerPrefs.THEME_NIGHT -> Color.Black
    ViewerPrefs.THEME_SEPIA -> Color(0xFFF4ECD8)
    else -> Color.White
}

private fun themeLabel(theme: Int) = when (theme) {
    ViewerPrefs.THEME_NIGHT -> "Night"
    ViewerPrefs.THEME_SEPIA -> "Sepia"
    else -> "Day"
}

private fun themeIcon(theme: Int): ImageVector = when (theme) {
    ViewerPrefs.THEME_NIGHT -> Icons.Rounded.DarkMode
    ViewerPrefs.THEME_SEPIA -> Icons.Rounded.AutoStories
    else -> Icons.Rounded.LightMode
}

// ---------------------------------------------------------------- Zoom

/** Pinch / double-tap zoom shared by both reading modes. Read only inside draw or gesture code to avoid recompositions. */
@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)

    fun reset() { scale = 1f; offsetX = 0f; offsetY = 0f }

    private fun maxX(size: IntSize) = size.width * (scale - 1f) / 2f
    private fun maxY(size: IntSize) = size.height * (scale - 1f) / 2f

    /** Zooms by [factor] keeping the content under [focus] in place. */
    fun zoomBy(factor: Float, focus: Offset, size: IntSize, list: LazyListState?) {
        val old = scale
        val new = (old * factor).coerceIn(1f, 5f)
        val z = new / old
        if (z == 1f) return
        val cx = size.width / 2f
        val cy = size.height / 2f
        scale = new
        offsetX = (offsetX * z + (focus.x - cx) * (1 - z)).coerceIn(-maxX(size), maxX(size))
        if (list != null) list.dispatchRawDelta((focus.y - cy) * (z - 1) / new)
        else offsetY = (offsetY * z + (focus.y - cy) * (1 - z)).coerceIn(-maxY(size), maxY(size))
    }

    fun panBy(pan: Offset, size: IntSize, list: LazyListState?, horizontalOnly: Boolean = false) {
        offsetX = (offsetX + pan.x).coerceIn(-maxX(size), maxX(size))
        if (horizontalOnly) return
        if (list != null) list.dispatchRawDelta(-pan.y / scale)
        else offsetY = (offsetY + pan.y).coerceIn(-maxY(size), maxY(size))
    }
}

/**
 * Two fingers zoom and pan; one finger scrolls the list as usual (and pans sideways when zoomed);
 * tap toggles the toolbars; double-tap zooms to 2.5× at the tapped spot, or back to fit.
 */
private fun Modifier.viewerGestures(zoom: ZoomState, list: LazyListState?, scope: CoroutineScope, onTap: () -> Unit): Modifier = this
    .pointerInput(zoom, list) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { pos ->
                scope.launch {
                    val target = if (zoom.scale > 1.2f) 1f else 2.5f
                    var last = zoom.scale
                    animate(last, target, animationSpec = tween(260)) { v, _ ->
                        zoom.zoomBy(v / last, pos, size, list)
                        last = v
                    }
                    if (target == 1f) zoom.reset()
                }
            }
        )
    }
    .pointerInput(zoom, list) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2) {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid.isSpecified) zoom.zoomBy(event.calculateZoom(), centroid, size, list)
                    zoom.panBy(event.calculatePan(), size, list)
                    event.changes.forEach { it.consume() }
                } else if (zoom.scale > 1.01f) {
                    if (list != null) {
                        // The list keeps vertical scrolling; sideways movement pans the zoomed page.
                        zoom.panBy(event.calculatePan(), size, list, horizontalOnly = true)
                    } else {
                        zoom.panBy(event.calculatePan(), size, null)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            if (zoom.scale < 1.03f) zoom.reset()
        }
    }
    .graphicsLayer {
        scaleX = zoom.scale
        scaleY = zoom.scale
        translationX = zoom.offsetX
        translationY = zoom.offsetY
    }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun pdfName(name: String) = if (name.endsWith(".pdf", true)) name else "$name.pdf"

// ---------------------------------------------------------------- Screen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PdfViewerScreen(uri: Uri, fromExternal: Boolean = false) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val back = rememberBackAction()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val saver = rememberFileSaver()

    var pdf by remember { mutableStateOf<PickedPdf?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var locked by remember { mutableStateOf(false) }
    val picker = rememberPdfPicker(onCancel = { locked = true }) { p -> locked = false; failed = null; pdf = p; recordOpen(context, p) }
    LaunchedEffect(uri) {
        try {
            val p = Workspace.pick(context, uri)
            pdf = p
            recordOpen(context, p)
        } catch (e: PasswordRequiredException) {
            picker.load(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: "This file couldn't be opened"
        }
    }
    val renderer = rememberRenderer(pdf?.file)
    val sizesReady by produceState(false, renderer) {
        value = false
        renderer?.preloadSizes()
        value = renderer != null
    }
    val count = renderer?.pageCount ?: 0

    var paged by remember { mutableStateOf(ViewerPrefs.paged(context)) }
    var theme by remember { mutableIntStateOf(ViewerPrefs.theme(context)) }
    var keepOn by remember { mutableStateOf(ViewerPrefs.keepScreenOn(context)) }
    var chrome by remember { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TextHit>>(emptyList()) }
    var hitIndex by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    var pageText by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var openSheetShown by rememberSaveable { mutableStateOf(false) }

    val list = rememberLazyListState()
    val pager = rememberPagerState { count }
    val zoom = remember { ZoomState() }
    var page by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(false) }

    fun goTo(target: Int, smooth: Boolean = false) {
        if (count == 0) return
        val t = target.coerceIn(0, count - 1)
        scope.launch {
            if (paged) { zoom.reset(); if (smooth) pager.animateScrollToPage(t) else pager.scrollToPage(t) }
            else if (smooth) list.animateScrollToItem(t) else list.scrollToItem(t)
        }
    }

    // Restore the last reading position once, then keep `page` in sync with whichever mode is showing.
    LaunchedEffect(paged, sizesReady) {
        if (!sizesReady || count == 0) return@LaunchedEffect
        if (!resumed) {
            val p = pdf
            val last = if (p != null && ViewerPrefs.resume(context)) ViewerPrefs.lastPage(context, p.name, p.size) else 0
            resumed = true
            if (last in 1 until count) {
                page = last
                scope.launch {
                    val r = snackbar.showSnackbar("Resumed on page ${last + 1}", "Start over", duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) goTo(0)
                }
            }
        }
        val target = page.coerceIn(0, count - 1)
        if (paged) {
            pager.scrollToPage(target)
            snapshotFlow { pager.currentPage }.collect { page = it }
        } else {
            list.scrollToItem(target)
            snapshotFlow {
                val info = list.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.firstOrNull { it.offset <= center && it.offset + it.size > center }?.index ?: list.firstVisibleItemIndex
            }.collect { page = it }
        }
    }
    LaunchedEffect(pdf) {
        val p = pdf ?: return@LaunchedEffect
        snapshotFlow { if (resumed) page else -1 }.collectLatest {
            if (it >= 0) { delay(600); ViewerPrefs.saveLastPage(context, p.name, p.size, it) }
        }
    }
    LaunchedEffect(paged) { if (paged) snapshotFlow { pager.currentPage }.collect { zoom.reset() } }

    LaunchedEffect(pdf) { if (pdf != null && fromExternal && !openSheetShown) { openSheetShown = true; delay(350); showTools = true } }
    LaunchedEffect(query, pdf) {
        val f = pdf?.file ?: return@LaunchedEffect
        if (query.length < 2) { hits = emptyList(); searching = false; return@LaunchedEffect }
        delay(300); searching = true
        hits = runCatching { TextOps.search(f, query) }.getOrDefault(emptyList())
        hitIndex = 0; searching = false
        hits.firstOrNull()?.let { goTo(it.page, smooth = true) }
    }
    val hitsByPage = remember(hits) { hits.groupBy { it.page } }
    val activeHit = hits.getOrNull(hitIndex)

    // Keep the screen awake while reading, if asked.
    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    // Hiding the toolbars also hides the system bars for distraction-free reading.
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(chrome, window) {
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        if (ctl != null) {
            if (chrome) ctl.show(WindowInsetsCompat.Type.systemBars())
            else {
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    BackHandler(enabled = searchOpen) { searchOpen = false; query = ""; hits = emptyList() }

    // A document kept unencrypted for reading must not leak out unprotected: share/save the original bytes.
    suspend fun exportable(p: PickedPdf): File = if (p.wasEncrypted) Workspace.copyToWork(context, p.uri) else p.file

    val statusTop = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    val topPad = statusTop + 64.dp + if (searchOpen) 76.dp else 0.dp
    val bottomPad = navBottom + 80.dp
    val screenBg = when (theme) {
        ViewerPrefs.THEME_NIGHT -> Color(0xFF0E0E10)
        ViewerPrefs.THEME_SEPIA -> Color(0xFFE6DCC4)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().background(screenBg)) {
        val r = renderer
        when {
            failed != null -> CenterMessage(Icons.Rounded.ErrorOutline, "Couldn't open this PDF", failed!!, MaterialTheme.colorScheme.error) {
                Button({ nav.openTool("repair") }) { Icon(Icons.Rounded.Healing, null); Spacer(Modifier.width(8.dp)); Text("Try Repair PDF") }
            }
            locked && pdf == null -> CenterMessage(Icons.Rounded.Lock, "Password protected", "This PDF needs its password before it can be shown.", MaterialTheme.colorScheme.primary) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(back) { Text("Go back") }
                    Button({ locked = false; picker.load(uri) }) { Icon(Icons.Rounded.LockOpen, null); Spacer(Modifier.width(8.dp)); Text("Enter password") }
                }
            }
            r == null || !sizesReady -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Spacer(Modifier.height(14.dp))
                Text(if (pdf == null) "Opening…" else "Preparing ${pdf?.pageCount ?: ""} pages…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            paged -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val basePx = with(density) { (maxWidth - 16.dp).roundToPx() }.coerceIn(320, 1600)
                val hiRes by remember { derivedStateOf { zoom.scale > 1.6f } }
                val renderPx = if (hiRes) (basePx * 2).coerceAtMost(2800) else basePx
                val canSwipe by remember { derivedStateOf { zoom.scale <= 1.01f } }
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize().viewerGestures(zoom, null, scope) { chrome = !chrome },
                    contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
                    pageSpacing = 16.dp,
                    userScrollEnabled = canSwipe,
                    beyondViewportPageCount = 1,
                    key = { it }
                ) { i ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        ViewerPage(r, i, renderPx, theme, hitsByPage[i].orEmpty(), activeHit, Modifier)
                    }
                }
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val pageWidth = minOf(maxWidth - 16.dp, 920.dp)
                val basePx = with(density) { pageWidth.roundToPx() }.coerceIn(320, 1600)
                val hiRes by remember { derivedStateOf { zoom.scale > 1.6f } }
                val renderPx = if (hiRes) (basePx * 2).coerceAtMost(2800) else basePx
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().viewerGestures(zoom, list, scope) { chrome = !chrome },
                    contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(count, key = { it }) { i ->
                        ViewerPage(r, i, renderPx, theme, hitsByPage[i].orEmpty(), activeHit, Modifier.width(pageWidth))
                    }
                }
            }
        }

        // ---------------- Top bar
        AnimatedVisibility(
            chrome || pdf == null, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(pdf?.name ?: "Opening…", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            pdf?.let {
                                Text(
                                    "Page ${page + 1} of ${it.pageCount} · ${Workspace.formatSize(it.size)}" + if (it.wasEncrypted) " · unlocked" else "",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                                )
                            }
                        }
                        if (pdf != null) {
                            IconButton({ searchOpen = !searchOpen; if (!searchOpen) { query = ""; hits = emptyList() } }) {
                                Icon(if (searchOpen) Icons.Rounded.SearchOff else Icons.Rounded.Search, "Search")
                            }
                            IconButton({
                                theme = (theme + 1) % 3
                                ViewerPrefs.setTheme(context, theme)
                                Toast.makeText(context, "${themeLabel(theme)} reading", Toast.LENGTH_SHORT).show()
                            }) { Icon(themeIcon(theme), "Reading theme: ${themeLabel(theme)}") }
                            Box {
                                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                                val p = pdf
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text("Go to page…") }, { menu = false; showGoto = true }, leadingIcon = { Icon(Icons.Rounded.Pin, null) })
                                    DropdownMenuItem({ Text("Outline / bookmarks") }, { menu = false; showOutline = true }, leadingIcon = { Icon(Icons.Rounded.Bookmarks, null) })
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        { Text(if (paged) "Continuous scroll" else "Single page (swipe)") },
                                        { menu = false; paged = !paged; zoom.reset(); ViewerPrefs.setPaged(context, paged) },
                                        leadingIcon = { Icon(if (paged) Icons.Rounded.ViewDay else Icons.Rounded.ViewCarousel, null) }
                                    )
                                    DropdownMenuItem(
                                        { Text("Keep screen on") },
                                        { keepOn = !keepOn; ViewerPrefs.setKeepScreenOn(context, keepOn) },
                                        leadingIcon = { Icon(Icons.Rounded.LightMode, null) },
                                        trailingIcon = { Checkbox(keepOn, { keepOn = it; ViewerPrefs.setKeepScreenOn(context, it) }) }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem({ Text("Select text on this page") }, {
                                        menu = false
                                        if (p != null) scope.launch { pageText = withContext(Dispatchers.IO) { runCatching { ConvertOps.extractText(p.file, listOf(page)) }.getOrDefault("") } }
                                    }, leadingIcon = { Icon(Icons.Rounded.TextFields, null) })
                                    DropdownMenuItem({ Text("Copy all text") }, {
                                        menu = false
                                        if (p != null) scope.launch {
                                            val t = withContext(Dispatchers.IO) { runCatching { ConvertOps.extractText(p.file) }.getOrDefault("") }
                                            if (t.isBlank()) Toast.makeText(context, "No text layer — try OCR", Toast.LENGTH_LONG).show()
                                            else com.example.ui.tools.screens.copy(context, t)
                                        }
                                    }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                                    HorizontalDivider()
                                    DropdownMenuItem({ Text("Save to Downloads") }, {
                                        menu = false
                                        p?.let { pp -> scope.launch { saver.toDownloads(listOf(exportable(pp)), pdfName(pp.name)) } }
                                    }, leadingIcon = { Icon(Icons.Rounded.Download, null) })
                                    DropdownMenuItem({ Text("Save a copy as…") }, {
                                        menu = false
                                        p?.let { pp -> scope.launch { saver.saveAs(exportable(pp), pdfName(pp.name)) } }
                                    }, leadingIcon = { Icon(Icons.Rounded.SaveAs, null) })
                                    DropdownMenuItem({ Text("Print") }, { menu = false; p?.let { PrintHelper.printFile(context, it.file, it.baseName) } }, leadingIcon = { Icon(Icons.Rounded.Print, null) })
                                    DropdownMenuItem({ Text("Share") }, {
                                        menu = false
                                        p?.let { pp -> scope.launch { Workspace.share(context, listOf(exportable(pp))) } }
                                    }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                                    DropdownMenuItem({ Text("Open with another app") }, {
                                        menu = false
                                        p?.let { pp -> scope.launch { Workspace.openExternally(context, exportable(pp)) } }
                                    }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) })
                                    DropdownMenuItem({ Text("Properties") }, { menu = false; p?.let { nav.openTool("properties", uri) } }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
                                }
                            }
                        }
                    }
                    AnimatedVisibility(searchOpen) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                query, { query = it }, Modifier.weight(1f).focusRequester(focus), placeholder = { Text("Find in document") }, singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                leadingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Search, null) },
                                trailingIcon = {
                                    if (query.length >= 2 && !searching) Text(
                                        if (hits.isEmpty()) "0" else "${hitIndex + 1}/${hits.size}",
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp)
                                    )
                                }
                            )
                            IconButton({ if (hits.isNotEmpty()) { hitIndex = (hitIndex - 1 + hits.size) % hits.size; goTo(hits[hitIndex].page, true) } }, enabled = hits.isNotEmpty()) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous match") }
                            IconButton({ if (hits.isNotEmpty()) { hitIndex = (hitIndex + 1) % hits.size; goTo(hits[hitIndex].page, true) } }, enabled = hits.isNotEmpty()) { Icon(Icons.Rounded.KeyboardArrowDown, "Next match") }
                        }
                    }
                }
            }
        }

        // ---------------- Bottom bar
        AnimatedVisibility(
            chrome && pdf != null && renderer != null, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ViewerAction(Icons.Rounded.Draw, "Edit") { nav.openTool("annotate", uri) }
                    ViewerAction(Icons.Rounded.HistoryEdu, "Sign") { nav.openTool("sign", uri) }
                    ViewerAction(Icons.Rounded.GridView, "Pages") { showPages = true }
                    ViewerAction(Icons.Rounded.Download, "Save") { pdf?.let { pp -> scope.launch { saver.toDownloads(listOf(exportable(pp)), pdfName(pp.name)) } } }
                    ViewerAction(Icons.Rounded.AutoAwesome, "Tools", highlight = true) { showTools = true }
                }
            }
        }

        if (!paged && renderer != null && sizesReady && count > 3) {
            PageScrubber(
                count = count, page = page, show = chrome || list.isScrollInProgress,
                top = topPad, bottom = bottomPad, onSeek = { goTo(it) },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
        if (paged && renderer != null && sizesReady && !chrome) {
            Surface(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp), shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f)
            ) {
                Text("${page + 1} / $count", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
        ZoomBadge(zoom, Modifier.align(Alignment.Center))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad))
    }

    val p = pdf
    if (showTools && p != null) OpenWithSheet(p, renderer, onDismiss = { showTools = false }) { t -> showTools = false; nav.openTool(t, uri) }
    if (showPages && renderer != null) PagesSheet(renderer, page, { showPages = false }) { i -> showPages = false; goTo(i) }
    if (showOutline && p != null) OutlineSheet(p, { showOutline = false }) { i -> showOutline = false; goTo(i) }
    if (showGoto && p != null) {
        var t by remember { mutableStateOf("${page + 1}") }
        val n = t.toIntOrNull()
        val valid = n != null && n in 1..p.pageCount
        AlertDialog(
            onDismissRequest = { showGoto = false }, title = { Text("Go to page") },
            text = { NumberField("Page (1–${p.pageCount})", t, { t = it }, Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ showGoto = false; n?.let { goTo(it - 1) } }, enabled = valid) { Text("Go") } },
            dismissButton = { TextButton({ showGoto = false }) { Text("Cancel") } }
        )
    }
    pageText?.let { txt ->
        AlertDialog(
            onDismissRequest = { pageText = null }, title = { Text("Page ${page + 1} text") },
            text = {
                Box(Modifier.heightIn(max = 420.dp)) {
                    SelectionContainer { Text(txt.ifBlank { "No text found on this page. Scanned page? Run OCR to make it selectable." }, Modifier.verticalScroll(rememberScrollState())) }
                }
            },
            confirmButton = { TextButton({ com.example.ui.tools.screens.copy(context, txt); pageText = null }, enabled = txt.isNotBlank()) { Text("Copy all") } },
            dismissButton = { TextButton({ pageText = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun CenterMessage(icon: ImageVector, title: String, body: String, tint: Color, actions: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(84.dp).background(tint.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(40.dp), tint = tint)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        actions()
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, highlight: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (highlight) cs.primaryContainer else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (highlight) cs.onPrimaryContainer else cs.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (highlight) cs.onPrimaryContainer else cs.onSurfaceVariant)
    }
}

@Composable
private fun ViewerPage(r: PageRenderer, index: Int, widthPx: Int, theme: Int, hits: List<TextHit>, active: TextHit?, modifier: Modifier) {
    // Keep showing the previous bitmap while a sharper one renders after zooming in.
    var bmp by remember(r, index) { mutableStateOf<Bitmap?>(ThumbCache.peek(r, index, widthPx)) }
    LaunchedEffect(r, index, widthPx) {
        val cached = ThumbCache.peek(r, index, widthPx)
        if (cached != null) bmp = cached else ThumbCache.get(r, index, widthPx)?.let { bmp = it }
    }
    val ratio = r.cachedSize(index)?.let { it.first.toFloat() / it.second } ?: 0.707f
    Box(modifier.aspectRatio(ratio).shadow(2.dp, RectangleShape).background(paperFor(theme))) {
        val b = bmp
        if (b != null) {
            val image = remember(b) { b.asImageBitmap() }
            Image(image, "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = filterFor(theme))
        } else {
            Text(
                "${index + 1}", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineMedium,
                color = if (theme == ViewerPrefs.THEME_NIGHT) Color.DarkGray else Color.LightGray
            )
        }
        if (hits.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
            hits.forEach { h ->
                h.rects.forEach { rc ->
                    drawRect(
                        if (h === active) Color(0xAAFF9800) else Color(0x66FFEB3B),
                        Offset(rc.left * size.width, rc.top * size.height), Size(rc.width * size.width, rc.height * size.height)
                    )
                }
            }
        }
    }
}

/** Shows the zoom level briefly while it changes. */
@Composable
private fun ZoomBadge(zoom: ZoomState, modifier: Modifier) {
    val pct by remember { derivedStateOf { (zoom.scale * 100).roundToInt() / 10 * 10 } }
    var visible by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(pct) {
        if (first) { first = false; return@LaunchedEffect }
        visible = true
        delay(900)
        visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)) {
            Text("$pct%", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

/** Draggable handle on the right edge for jumping through long documents. */
@Composable
private fun PageScrubber(count: Int, page: Int, show: Boolean, top: Dp, bottom: Dp, onSeek: (Int) -> Unit, modifier: Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragPage by remember { mutableIntStateOf(page) }
    val alpha by animateFloatAsState(if (show || dragging) 1f else 0f, tween(if (show || dragging) 150 else 600), label = "scrubber")
    if (alpha <= 0.01f) return
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxHeight().width(120.dp).padding(top = top + 8.dp, bottom = bottom + 8.dp).graphicsLayer { this.alpha = alpha }) {
        val thumbH = 44.dp
        val density = LocalDensity.current
        val trackPx = with(density) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
        val shown = if (dragging) dragPage else page
        val frac = shown.toFloat() / (count - 1).coerceAtLeast(1)
        val y = with(density) { (trackPx * frac).toDp() }
        var dragY by remember { mutableFloatStateOf(0f) }
        Surface(
            Modifier.align(Alignment.TopEnd).offset(y = y).padding(end = 4.dp).size(width = 30.dp, height = thumbH)
                .pointerInput(count, trackPx) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; dragPage = page; dragY = trackPx * frac },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false }
                    ) { change, amount ->
                        change.consume()
                        dragY = (dragY + amount).coerceIn(0f, trackPx)
                        val p = (dragY / trackPx * (count - 1)).roundToInt()
                        if (p != dragPage) { dragPage = p; onSeek(p) }
                    }
                },
            shape = RoundedCornerShape(15.dp), color = cs.inverseSurface.copy(alpha = 0.88f), shadowElevation = 3.dp
        ) {
            Icon(Icons.Rounded.UnfoldMore, "Drag to scroll pages", Modifier.padding(5.dp), tint = cs.inverseOnSurface)
        }
        if (dragging) Surface(
            Modifier.align(Alignment.TopEnd).offset(y = y + 6.dp).padding(end = 42.dp), shape = RoundedCornerShape(12.dp), color = cs.primary, shadowElevation = 4.dp
        ) {
            Text("${shown + 1} / $count", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = cs.onPrimary)
        }
    }
}

/** The "Open with PDF Studio" sheet: file preview on top, every tool grouped below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenWithSheet(pdf: PickedPdf, renderer: PageRenderer?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (renderer != null) LazyRow(Modifier.width(150.dp).height(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(minOf(renderer.pageCount, 6)) { i -> PageImage(renderer, i, Modifier.height(106.dp).shadow(3.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)), widthPx = 240) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(pdf.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${pdf.pageCount} pages · ${Workspace.formatSize(pdf.size)}" + if (pdf.wasEncrypted) " · unlocked" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip({ onDismiss() }, { Text("Read") }, leadingIcon = { Icon(Icons.Rounded.MenuBook, null, Modifier.size(16.dp)) })
                        AssistChip({ onPick("annotate") }, { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Draw, null, Modifier.size(16.dp)) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("What do you want to do?") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp)
            )
            val tools = ToolCatalog.forOpenPdf.filter { q.isBlank() || it.title.contains(q, true) || it.keywords.contains(q, true) }
            LazyVerticalGrid(
                GridCells.Adaptive(88.dp), Modifier.fillMaxWidth().heightIn(max = 560.dp),
                state = rememberLazyGridState(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (q.isBlank()) ToolCategory.entries.forEach { c ->
                    val inCat = tools.filter { it.category == c }
                    if (inCat.isEmpty()) return@forEach
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text(c.title, style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp))
                    }
                    items(inCat.size) { i -> ToolTile(inCat[i], Modifier.fillMaxWidth()) { onPick(inCat[i].id) } }
                } else items(tools.size) { i -> ToolTile(tools[i], Modifier.fillMaxWidth()) { onPick(tools[i].id) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PagesSheet(r: PageRenderer, current: Int, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    ModalBottomSheet(onDismiss) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pages", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("${r.pageCount} total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyVerticalGrid(
            GridCells.Adaptive(96.dp), Modifier.fillMaxWidth().heightIn(max = 600.dp), state = grid, contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(r.pageCount, key = { it }) { i -> PageThumbCard(r, i, i == current, onClick = { onJump(i) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(pdf: PickedPdf, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    var items by remember { mutableStateOf<List<BookmarkEntry>?>(null) }
    LaunchedEffect(pdf) { items = withContext(Dispatchers.IO) { runCatching { OrganizeOps.readBookmarks(pdf.file) }.getOrDefault(emptyList()) } }
    val nav = LocalNav.current
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text("Outline", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            val l = items
            when {
                l == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                l.isEmpty() -> {
                    Text("This PDF has no bookmarks.", style = MaterialTheme.typography.bodyMedium)
                    TextButton({ onDismiss(); nav.openTool("bookmarks", pdf.uri) }) { Text("Add bookmarks") }
                }
                else -> l.forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = b.page >= 0) { onJump(b.page) }
                            .padding(start = (b.level * 16).dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
                    ) {
                        Text(b.title, Modifier.weight(1f), style = if (b.level == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                        if (b.page >= 0) Text("${b.page + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
