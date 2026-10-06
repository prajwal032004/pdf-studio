package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.example.R

object WidgetUpdater {
    /** Tells all relevant widgets that recent files changed. */
    fun refreshRecent(context: Context) {
        runCatching {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RecentFilesWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                @Suppress("DEPRECATION")
                mgr.notifyAppWidgetViewDataChanged(ids, R.id.recent_list)
                ids.forEach { RecentFilesWidgetProvider.update(context, mgr, it) }
            }
            PdfToolsWidgetProvider.updateAll(context)
        }
    }
}
