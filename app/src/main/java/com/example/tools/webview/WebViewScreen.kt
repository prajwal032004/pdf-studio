package com.example.tools.webview

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.common.rememberBackAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(url: String) {
    val context = LocalContext.current
    val leave = rememberBackAction()
    var loading by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("Portfolio") }
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val bg = MaterialTheme.colorScheme.background.toArgb()

    // Back walks the page history first, then leaves the screen.
    BackHandler(enabled = canGoBack) { webView?.goBack() }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(Uri.parse(url).host ?: url, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(leave) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton({ webView?.reload() }) { Icon(Icons.Rounded.Refresh, "Reload") }
                    IconButton({
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView?.url ?: url))) }
                    }) { Icon(Icons.Rounded.OpenInBrowser, "Open in browser") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(bg)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(v: WebView?, u: String?, favicon: android.graphics.Bitmap?) { loading = true }
                            override fun onPageFinished(v: WebView?, u: String?) {
                                loading = false
                                canGoBack = v?.canGoBack() == true
                                v?.title?.takeIf { it.isNotBlank() }?.let { title = it }
                            }
                            override fun doUpdateVisitedHistory(v: WebView?, u: String?, isReload: Boolean) {
                                canGoBack = v?.canGoBack() == true
                            }
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        loadUrl(url)
                        webView = this
                    }
                }
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
