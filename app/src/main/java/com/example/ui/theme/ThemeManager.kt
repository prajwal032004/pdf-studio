package com.example.ui.theme

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf

enum class AppColorTheme {
    DYNAMIC, BLUE, RED, GREEN, ORANGE, PURPLE, MONOCHROME
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

val LocalAppColorTheme = staticCompositionLocalOf<MutableState<AppColorTheme>> {
    error("No AppColorTheme provided")
}

val LocalThemeMode = staticCompositionLocalOf<MutableState<ThemeMode>> {
    error("No ThemeMode provided")
}

object ThemePreferences {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME = "app_theme"
    private const val KEY_MODE = "theme_mode"

    fun getTheme(context: Context): AppColorTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeName = prefs.getString(KEY_THEME, AppColorTheme.DYNAMIC.name) ?: AppColorTheme.DYNAMIC.name
        return try {
            AppColorTheme.valueOf(themeName)
        } catch (e: Exception) {
            AppColorTheme.DYNAMIC
        }
    }

    fun saveTheme(context: Context, theme: AppColorTheme) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun getMode(context: Context): ThemeMode = runCatching {
        ThemeMode.valueOf(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MODE, null) ?: "SYSTEM")
    }.getOrDefault(ThemeMode.SYSTEM)

    fun saveMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
    }
}
