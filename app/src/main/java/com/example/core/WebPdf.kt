package com.example.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Offline HTML rendering via the device WebView (no network access is enabled). */
object WebPdf {

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(context: Context, allowJs: Boolean): WebView = WebView(context).apply {
        settings.javaScriptEnabled = allowJs
        settings.allowFileAccess = true
        settings.blockNetworkLoads = true
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = false
        setBackgroundColor(Color.WHITE)
    }

    private suspend fun load(web: WebView, html: String?, fileUrl: String?, baseUrl: String?) =
        suspendCancellableCoroutine<Unit> { cont ->
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            if (fileUrl != null) web.loadUrl(fileUrl)
            else web.loadDataWithBaseURL(baseUrl, html ?: "", "text/html", "UTF-8", null)
        }

    /**
     * Captures the page as high-resolution images sliced into [size] pages.
     * Works without any dialog; text becomes part of the image.
     */
    suspend fun capture(
        context: Context, html: String?, fileUrl: String?, name: String, size: PageSize,
        allowJs: Boolean, progress: Progress = NoProgress
    ): File {
        val pageRect = size.rect(false)
        val widthPx = 1240 // ≈150 dpi across A4
        val (bitmaps, _) = withContext(Dispatchers.Main) {
            val web = newWebView(context, allowJs)
            web.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            web.layout(0, 0, widthPx, 2000)
            progress(0.1f, "Loading page")
            load(web, html, fileUrl, "file:///android_asset/")
            delay(600) // allow late layout (fonts, images from local files)
            web.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val contentH = maxOf(web.measuredHeight, (web.contentHeight * web.resources.displayMetrics.density).toInt(), 200)
            web.layout(0, 0, widthPx, contentH)
            val sliceH = (widthPx * pageRect.height / pageRect.width).toInt()
            val slices = mutableListOf<Bitmap>()
            var y = 0
            val total = ((contentH + sliceH - 1) / sliceH).coerceAtMost(200)
            while (y < contentH && slices.size < 200) {
                progress(0.2f + 0.5f * slices.size / total, "Capturing page ${slices.size + 1}")
                val bmp = Bitmap.createBitmap(widthPx, sliceH, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.WHITE)
                c.translate(0f, -y.toFloat())
                web.draw(c)
                slices += bmp
                y += sliceH
            }
            web.destroy()
            slices to contentH
        }
        return withContext(Dispatchers.IO) {
            PDDocument().use { doc ->
                bitmaps.forEachIndexed { i, bmp ->
                    progress(0.7f + 0.3f * i / bitmaps.size, "Writing page ${i + 1}")
                    val page = PDPage(PDRectangle(pageRect.width, pageRect.height))
                    doc.addPage(page)
                    val img = JPEGFactory.createFromImage(doc, bmp, 0.88f)
                    PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, pageRect.width, pageRect.height) }
                    bmp.recycle()
                }
                doc.documentInformation.title = name
                val f = Workspace.newOutput(context, name.ifBlank { "Webpage" }, Workspace.stamp())
                doc.save(f); f
            }
        }
    }

    /** Opens the system print dialog, where "Save as PDF" produces a vector PDF with selectable text. */
    fun printToPdf(activityContext: Context, html: String?, fileUrl: String?, jobName: String, allowJs: Boolean) {
        val web = newWebView(activityContext, allowJs)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val pm = activityContext.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    pm.print(
                        jobName, view.createPrintDocumentAdapter(jobName),
                        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
                    )
                }, 300)
            }
        }
        if (fileUrl != null) web.loadUrl(fileUrl) else web.loadDataWithBaseURL(null, html ?: "", "text/html", "UTF-8", null)
    }
}
