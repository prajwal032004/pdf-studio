package com.example

import android.app.Application
import android.webkit.WebView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfStudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Needed by every PDFBox entry point (activity, widgets, share targets).
        PDFBoxResourceLoader.init(applicationContext)
        // Lets HTML → PDF capture the full page height, not just the viewport.
        runCatching { WebView.enableSlowWholeDocumentDraw() }
        // Working copies from earlier sessions are never reused; reclaim their space quietly.
        val startedAt = System.currentTimeMillis()
        Thread { runCatching { com.example.core.Workspace.trimWork(this, startedAt - 60_000) } }.apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
