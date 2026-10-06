package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.example.R

/** 1×1 shortcut to a single tool. The tool is chosen in [QuickToolConfigActivity]. */
open class QuickToolWidgetProvider : AppWidgetProvider() {

    /** Fixed tool for the legacy Merge/Split/Reorder widgets; null means "use the configured tool". */
    open val fixedTool: String? = null

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) render(context, appWidgetManager, id, fixedTool ?: QuickToolPrefs.get(context, id))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { QuickToolPrefs.remove(context, it) }
    }

    companion object {
        fun render(context: Context, mgr: AppWidgetManager, id: Int, toolId: String) {
            val t = WidgetActions.tool(toolId)
            val v = RemoteViews(context.packageName, R.layout.widget_single_action)
            v.setImageViewResource(R.id.iv_icon, t.icon)
            v.setInt(R.id.tile_bg, "setBackgroundResource", t.tile)
            v.setTextViewText(R.id.tv_label, t.label)
            v.setOnClickPendingIntent(R.id.btn_action, WidgetActions.toolIntent(context, t.id, id * 10 + 5))
            mgr.updateAppWidget(id, v)
        }
    }
}

class MergeWidgetProvider : QuickToolWidgetProvider() { override val fixedTool = "merge" }
class SplitWidgetProvider : QuickToolWidgetProvider() { override val fixedTool = "split" }
class ReorderWidgetProvider : QuickToolWidgetProvider() { override val fixedTool = "reorder" }

object QuickToolPrefs {
    private const val P = "quick_tool_widgets"
    fun get(context: Context, id: Int) = context.getSharedPreferences(P, Context.MODE_PRIVATE).getString("w$id", "scan") ?: "scan"
    fun set(context: Context, id: Int, tool: String) = context.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString("w$id", tool).apply()
    fun remove(context: Context, id: Int) = context.getSharedPreferences(P, Context.MODE_PRIVATE).edit().remove("w$id").apply()
}
