package com.example.core

import android.content.Context
import android.net.Uri
import com.example.widget.WidgetUpdater
import org.json.JSONArray
import org.json.JSONObject

data class RecentItem(val uri: Uri, val name: String, val time: Long, val pages: Int, val size: Long, val tool: String? = null)

/** Recently opened and created files, shown on Home and in the Recent Files widget. */
object RecentStore {
    private const val PREFS = "recent_store"
    private const val KEY = "items"
    private const val MAX = 30

    fun list(context: Context): List<RecentItem> = runCatching {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecentItem(Uri.parse(o.getString("u")), o.getString("n"), o.getLong("t"), o.optInt("p"), o.optLong("s"), o.optString("tool").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    fun add(context: Context, item: RecentItem) {
        val items = listOf(item) + list(context).filter { it.uri != item.uri }
        val arr = JSONArray()
        items.take(MAX).forEach {
            arr.put(JSONObject().put("u", it.uri.toString()).put("n", it.name).put("t", it.time).put("p", it.pages).put("s", it.size).put("tool", it.tool ?: ""))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        WidgetUpdater.refreshRecent(context)
    }

    fun remove(context: Context, uri: Uri) {
        val arr = JSONArray()
        list(context).filter { it.uri != uri }.forEach {
            arr.put(JSONObject().put("u", it.uri.toString()).put("n", it.name).put("t", it.time).put("p", it.pages).put("s", it.size).put("tool", it.tool ?: ""))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        WidgetUpdater.refreshRecent(context)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        WidgetUpdater.refreshRecent(context)
    }

    /** Records a file produced by a tool (always readable later, since it lives in app storage). */
    fun addOutput(context: Context, file: java.io.File, tool: String) {
        if (file.extension.lowercase() != "pdf") return
        add(context, RecentItem(Workspace.uriFor(context, file), file.name, System.currentTimeMillis(), PageOps.pageCount(file), file.length(), tool))
    }
}
