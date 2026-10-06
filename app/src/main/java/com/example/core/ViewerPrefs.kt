package com.example.core

import android.content.Context

/** Reading preferences shared by the viewer and Settings. */
object ViewerPrefs {
    const val THEME_DAY = 0
    const val THEME_NIGHT = 1
    const val THEME_SEPIA = 2

    private fun prefs(c: Context) = c.getSharedPreferences("viewer_prefs", Context.MODE_PRIVATE)

    /** Single page with horizontal swipes instead of one continuous vertical scroll. */
    fun paged(c: Context) = prefs(c).getBoolean("paged", false)
    fun setPaged(c: Context, v: Boolean) = prefs(c).edit().putBoolean("paged", v).apply()

    fun theme(c: Context) = prefs(c).getInt("theme", THEME_DAY)
    fun setTheme(c: Context, v: Int) = prefs(c).edit().putInt("theme", v).apply()

    fun keepScreenOn(c: Context) = prefs(c).getBoolean("keep_on", false)
    fun setKeepScreenOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("keep_on", v).apply()

    fun resume(c: Context) = prefs(c).getBoolean("resume", true)
    fun setResume(c: Context, v: Boolean) = prefs(c).edit().putBoolean("resume", v).apply()

    private fun posKey(name: String, size: Long) = "pos_" + "$name|$size".hashCode()

    fun lastPage(c: Context, name: String, size: Long) = prefs(c).getInt(posKey(name, size), 0)
    fun saveLastPage(c: Context, name: String, size: Long, page: Int) = prefs(c).edit().putInt(posKey(name, size), page).apply()

    fun clearPositions(c: Context) {
        val p = prefs(c)
        p.edit().apply { p.all.keys.filter { it.startsWith("pos_") }.forEach { remove(it) } }.apply()
    }
}
