package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.MainActivity
import com.example.R
import com.example.core.RecentItem
import com.example.core.RecentStore
import com.example.core.Workspace

/** Scrollable list of recently opened/created PDFs. */
class RecentFilesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) update(context, appWidgetManager, id)
    }

    companion object {
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RecentFilesWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                @Suppress("DEPRECATION")
                mgr.notifyAppWidgetViewDataChanged(ids, R.id.recent_list)
                ids.forEach { update(context, mgr, it) }
            }
            PdfToolsWidgetProvider.updateAll(context)
        }

        fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(context.packageName, R.layout.widget_recent)
            val svc = Intent(context, RecentWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("pdfstudio://widget/recent/$id")
            }
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.recent_list, svc)
            v.setEmptyView(R.id.recent_list, R.id.recent_empty)
            // Template for item clicks; each row fills in its own data URI.
            val template = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            v.setPendingIntentTemplate(R.id.recent_list, PendingIntent.getActivity(context, id * 10 + 7, template, PendingIntent.FLAG_UPDATE_CURRENT or mutable))
            v.setOnClickPendingIntent(R.id.w_open, WidgetActions.toolIntent(context, "open", id * 10 + 1))
            v.setOnClickPendingIntent(R.id.w_scan, WidgetActions.toolIntent(context, "scan", id * 10 + 2))
            v.setOnClickPendingIntent(R.id.w_profile, WidgetActions.homeIntent(context, id * 10 + 3))
            mgr.updateAppWidget(id, v)
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(id, R.id.recent_list)
        }
    }
}

class RecentWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var items: List<RecentItem> = emptyList()
        override fun onCreate() { items = RecentStore.list(context) }
        override fun onDataSetChanged() { items = RecentStore.list(context).take(12) }
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getViewAt(position: Int): RemoteViews {
            val item = items.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_recent_item)
            return RemoteViews(context.packageName, R.layout.widget_recent_item).apply {
                setTextViewText(R.id.item_name, item.name)
                val meta = buildList {
                    if (item.pages > 0) add("${item.pages} pages")
                    if (item.size > 0) add(Workspace.formatSize(item.size))
                    add(WidgetActions.relativeTime(item.time))
                }.joinToString(" • ")
                setTextViewText(R.id.item_meta, meta)
                setOnClickFillInIntent(R.id.item_root, Intent().apply { data = WidgetActions.openFileUri(item.uri) })
            }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
