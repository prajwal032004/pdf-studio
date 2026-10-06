package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun tone(c: Color, towards: Color, amount: Float) = lerp(c, towards, amount)

/** Builds a full, opaque tonal scheme from one seed so containers never look washed out. */
private fun lightScheme(seed: Color): ColorScheme {
    val white = Color.White; val black = Color(0xFF101014)
    val secondary = tone(seed, Color(0xFF6B6B7B), 0.55f)
    val tertiary = tone(seed, Color(0xFF00897B), 0.6f)
    return lightColorScheme(
        primary = seed,
        onPrimary = white,
        primaryContainer = tone(seed, white, 0.82f),
        onPrimaryContainer = tone(seed, black, 0.6f),
        secondary = secondary,
        onSecondary = white,
        secondaryContainer = tone(secondary, white, 0.84f),
        onSecondaryContainer = tone(secondary, black, 0.65f),
        tertiary = tertiary,
        onTertiary = white,
        tertiaryContainer = tone(tertiary, white, 0.82f),
        onTertiaryContainer = tone(tertiary, black, 0.65f),
        background = tone(seed, white, 0.965f),
        onBackground = Color(0xFF1B1B21),
        surface = tone(seed, white, 0.985f),
        onSurface = Color(0xFF1B1B21),
        surfaceVariant = tone(seed, white, 0.9f),
        onSurfaceVariant = Color(0xFF47464F),
        surfaceContainerLowest = white,
        surfaceContainerLow = tone(seed, white, 0.97f),
        surfaceContainer = tone(seed, white, 0.945f),
        surfaceContainerHigh = tone(seed, white, 0.92f),
        surfaceContainerHighest = tone(seed, white, 0.895f),
        outline = Color(0xFF787680),
        outlineVariant = tone(seed, Color(0xFFC8C5D0), 0.8f),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002)
    )
}

private fun darkScheme(seed: Color): ColorScheme {
    val bg = Color(0xFF121218)
    val light = tone(seed, Color.White, 0.35f)
    val secondary = tone(light, Color(0xFFC8C6D8), 0.5f)
    val tertiary = tone(light, Color(0xFF80CBC4), 0.55f)
    return darkColorScheme(
        primary = light,
        onPrimary = tone(seed, Color.Black, 0.7f),
        primaryContainer = tone(seed, bg, 0.55f),
        onPrimaryContainer = tone(seed, Color.White, 0.8f),
        secondary = secondary,
        onSecondary = tone(secondary, Color.Black, 0.75f),
        secondaryContainer = tone(secondary, bg, 0.7f),
        onSecondaryContainer = tone(secondary, Color.White, 0.8f),
        tertiary = tertiary,
        onTertiary = tone(tertiary, Color.Black, 0.75f),
        tertiaryContainer = tone(tertiary, bg, 0.68f),
        onTertiaryContainer = tone(tertiary, Color.White, 0.8f),
        background = bg,
        onBackground = Color(0xFFE5E1EA),
        surface = tone(seed, bg, 0.94f),
        onSurface = Color(0xFFE5E1EA),
        surfaceVariant = tone(seed, Color(0xFF2A2A33), 0.85f),
        onSurfaceVariant = Color(0xFFC9C5D0),
        surfaceContainerLowest = Color(0xFF0D0D12),
        surfaceContainerLow = tone(seed, Color(0xFF1A1A21), 0.93f),
        surfaceContainer = tone(seed, Color(0xFF1F1F26), 0.92f),
        surfaceContainerHigh = tone(seed, Color(0xFF292930), 0.92f),
        surfaceContainerHighest = tone(seed, Color(0xFF34343B), 0.92f),
        outline = Color(0xFF928F9A),
        outlineVariant = Color(0xFF47464F)
    )
}

fun seedFor(theme: AppColorTheme): Color = when (theme) {
    AppColorTheme.DYNAMIC -> Color(0xFF6750A4)
    AppColorTheme.BLUE -> Color(0xFF2962FF)
    AppColorTheme.RED -> Color(0xFFD32F2F)
    AppColorTheme.GREEN -> Color(0xFF00897B)
    AppColorTheme.ORANGE -> Color(0xFFEF6C00)
    AppColorTheme.PURPLE -> Color(0xFF8E24AA)
    AppColorTheme.MONOCHROME -> Color(0xFF37474F)
}

@Composable
fun AppTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val themeState = remember { mutableStateOf(ThemePreferences.getTheme(context)) }
    val modeState = remember { mutableStateOf(ThemePreferences.getMode(context)) }
    val darkTheme = when (modeState.value) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (themeState.value == AppColorTheme.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val seed = seedFor(themeState.value)
        if (darkTheme) darkScheme(seed) else lightScheme(seed)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAppColorTheme provides themeState, LocalThemeMode provides modeState) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}

/** Brand accents used for tool categories; stable across themes for recognisability. */
object Accents {
    val red = Color(0xFFE53935)
    val orange = Color(0xFFFB8C00)
    val amber = Color(0xFFFFB300)
    val green = Color(0xFF43A047)
    val teal = Color(0xFF00897B)
    val cyan = Color(0xFF00ACC1)
    val blue = Color(0xFF1E88E5)
    val indigo = Color(0xFF3949AB)
    val violet = Color(0xFF7E57C2)
    val pink = Color(0xFFD81B60)
    val slate = Color(0xFF546E7A)

    @Composable
    fun container(accent: Color): Color {
        val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
        return if (dark) accent.copy(alpha = 0.22f).compositeOver(MaterialTheme.colorScheme.surface)
        else accent.copy(alpha = 0.13f).compositeOver(Color.White)
    }

    @Composable
    fun onContainer(accent: Color): Color {
        val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
        return if (dark) lerp(accent, Color.White, 0.35f) else lerp(accent, Color.Black, 0.2f)
    }
}
