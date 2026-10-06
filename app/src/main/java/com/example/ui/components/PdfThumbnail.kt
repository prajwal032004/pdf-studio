package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.ThumbCache
import com.example.ui.theme.Accents

/**
 * First-page preview of a PDF. Cached in memory and rendered a few at a time, so long
 * lists scroll smoothly and scrolling back shows thumbnails instantly.
 * [version] (e.g. the modified time) makes a changed file re-render.
 */
@Composable
fun PdfThumbnail(uri: Uri, modifier: Modifier = Modifier, widthPx: Int = 240, version: Long = 0, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    var bitmap by remember(uri, widthPx, version) { mutableStateOf<Bitmap?>(ThumbCache.peekUri(uri, widthPx, version)) }
    var failed by remember(uri, widthPx, version) { mutableStateOf(false) }

    LaunchedEffect(uri, widthPx, version) {
        if (bitmap == null) {
            val b = ThumbCache.forUri(context.applicationContext, uri, widthPx, version)
            if (b != null) bitmap = b else failed = true
        }
    }

    Crossfade(bitmap, modifier, label = "thumb") { b ->
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().background(Color.White), contentScale = contentScale, alignment = Alignment.TopCenter)
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(
                    if (failed) Icons.Rounded.Lock else Icons.Rounded.PictureAsPdf, contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.onSurfaceVariant else Accents.red, modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
