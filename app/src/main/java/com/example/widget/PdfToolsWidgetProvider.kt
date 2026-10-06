package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.core.RecentStore
import com.example.core.Workspace

/** The main "PDF Studio" widget. Adapts between small (2×2), medium (4×2) and large (4×3+) layouts. */
open class PdfToolsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) update(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        update(context, appWidgetManager, appWidgetId)
    }

    companion object {
        private val tileIds = mapOf(
            R.id.t_open to "open", R.id.t_scan to "scan", R.id.t_merge to "merge", R.id.t_compress to "compress",
            R.id.t_sign to "sign", R.id.t_ocr to "ocr", R.id.t_images to "images_to_pdf", R.id.t_all to "_all"
        )

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            (mgr.getAppWidgetIds(ComponentName(context, PdfToolsWidgetProvider::class.java)) +
                mgr.getAppWidgetIds(ComponentName(context, PdfDashboardWidgetProvider::class.java))).forEach { update(context, mgr, it) }
        }

        private fun build(context: Context, layout: Int, widgetId: Int): RemoteViews {
            val v = RemoteViews(context.packageName, layout)
            v.setTextViewText(R.id.w_greeting, WidgetActions.greeting())
            tileIds.forEach { (viewId, tool) ->
                v.setOnClickPendingIntent(viewId, WidgetActions.toolIntent(context, tool, widgetId * 100 + viewId % 97))
            }
            v.setOnClickPendingIntent(R.id.w_profile, WidgetActions.homeIntent(context, widgetId * 100 + 1))
            v.setOnClickPendingIntent(R.id.w_search, WidgetActions.toolIntent(context, "_all", widgetId * 100 + 2))
            if (layout == R.layout.widget_home_large) {
                val last = RecentStore.list(context).firstOrNull()
                if (last != null) {
                    v.setTextViewText(R.id.w_last_name, last.name)
                    v.setTextViewText(R.id.w_last_meta, "Continue • ${last.pages} pages • ${Workspace.formatSize(last.size)} • ${WidgetActions.relativeTime(last.time)}")
                    val open = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = WidgetActions.openFileUri(last.uri)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    v.setOnClickPendingIntent(R.id.w_last, PendingIntent.getActivity(context, widgetId * 100 + 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                } else {
                    v.setTextViewText(R.id.w_last_name, "No recent files yet")
                    v.setTextViewText(R.id.w_last_meta, "Open or create a PDF to see it here")
                    v.setOnClickPendingIntent(R.id.w_last, WidgetActions.toolIntent(context, "open", widgetId * 100 + 3))
                }
            }
            return v
        }

        fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(
                    mapOf(
                        SizeF(110f, 110f) to build(context, R.layout.widget_home_small, id),
                        SizeF(250f, 110f) to build(context, R.layout.widget_home, id),
                        SizeF(250f, 230f) to build(context, R.layout.widget_home_large, id)
                    )
                )
            } else {
                val o = mgr.getAppWidgetOptions(id)
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
                val layout = when {
                    w < 200 -> R.layout.widget_home_small
                    h >= 220 -> R.layout.widget_home_large
                    else -> R.layout.widget_home
                }
                build(context, layout, id)
            }
            mgr.updateAppWidget(id, views)
        }
    }
}

/** Same widget, listed separately in the picker at dashboard size (4×3). */
class PdfDashboardWidgetProvider : PdfToolsWidgetProvider()
