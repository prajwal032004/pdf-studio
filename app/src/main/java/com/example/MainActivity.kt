package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.core.Workspace
import com.example.navigation.AppNavigation
import com.example.navigation.StartRequest
import com.example.ui.theme.AppTheme
import com.example.ui.tools.screens.Incoming

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = if (savedInstanceState == null) parse(intent) else null
        setContent {
            AppTheme {
                AppNavigation(start)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }

    private fun kindOf(uri: Uri, declared: String?): String {
        val mime = declared?.takeIf { it != "*/*" } ?: runCatching { Workspace.mimeOf(this, uri) }.getOrNull() ?: ""
        val name = runCatching { Workspace.displayName(this, uri).lowercase() }.getOrDefault("")
        return when {
            mime == "application/pdf" || name.endsWith(".pdf") -> "pdf"
            mime.startsWith("image/") -> "image"
            mime.contains("markdown") || name.endsWith(".md") || name.endsWith(".markdown") -> "markdown"
            mime == "text/html" || name.endsWith(".html") || name.endsWith(".htm") -> "html"
            mime.startsWith("text/") -> "text"
            else -> "pdf"
        }
    }

    private fun parse(intent: Intent?): StartRequest? {
        intent ?: return null
        val data = intent.data
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                data ?: return null
                if (data.scheme == "pdfstudio") return when (data.host) {
                    "tools" -> data.pathSegments.firstOrNull()?.let { if (it == "_all") StartRequest.Tools else StartRequest.Tool(it, data.getQueryParameter("uri")?.let(Uri::parse)) }
                    "open" -> data.getQueryParameter("uri")?.let { StartRequest.Viewer(Uri.parse(it), external = false) }
                    else -> null
                }
                runCatching { contentResolver.takePersistableUriPermission(data, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                return when (kindOf(data, intent.type)) {
                    "pdf" -> StartRequest.Viewer(data, external = true)
                    "image" -> { Incoming.uris = listOf(data); StartRequest.Tool("images_to_pdf") }
                    "markdown" -> StartRequest.Tool("markdown_to_pdf", data)
                    "html" -> StartRequest.Tool("html_to_pdf", data)
                    else -> StartRequest.Tool("text_to_pdf", data)
                }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = streams(intent)
                if (uris.isEmpty()) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                    Incoming.text = text
                    return StartRequest.Tool(if (text.trimStart().startsWith("<")) "html_to_pdf" else if (text.contains("# ") || text.contains("**")) "markdown_to_pdf" else "text_to_pdf")
                }
                val kinds = uris.map { kindOf(it, if (uris.size == 1) intent.type else null) }
                return when {
                    kinds.all { it == "image" } -> { Incoming.uris = uris; StartRequest.Tool("images_to_pdf") }
                    uris.size == 1 && kinds[0] == "pdf" -> StartRequest.Viewer(uris[0], external = true)
                    uris.size == 1 -> StartRequest.Tool("${kinds[0]}_to_pdf", uris[0])
                    else -> { Incoming.uris = uris.filterIndexed { i, _ -> kinds[i] == "pdf" }; StartRequest.IncomingPdfs }
                }
            }
        }
        return null
    }
}
