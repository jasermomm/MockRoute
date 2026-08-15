package com.jasermomm.mockroute.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.jasermomm.mockroute.data.Accent
import com.jasermomm.mockroute.data.AppSettings
import com.jasermomm.mockroute.data.ThemeMode

fun Accent.color(): Color = when (this) {
    Accent.BLUE -> Color(0xFF2458D3)
    Accent.PURPLE -> Color(0xFF7450C8)
    Accent.GREEN -> Color(0xFF197A4B)
    Accent.ORANGE -> Color(0xFFB85A00)
    Accent.RED -> Color(0xFFB3261E)
    Accent.TEAL -> Color(0xFF00796B)
    Accent.PINK -> Color(0xFFA83B72)
    Accent.NEUTRAL -> Color(0xFF5F6368)
}

fun Accent.hex(): String = "#%06X".format(color().toArgb() and 0xFFFFFF)

@Composable
fun MockRouteTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = settings.accent.color().lightenForDark(),
            secondary = settings.accent.color().lightenForDark(),
            surface = Color(0xFF111318),
            surfaceContainer = Color(0xFF1A1C21),
        )
        else -> lightColorScheme(
            primary = settings.accent.color(),
            secondary = settings.accent.color(),
            surface = Color(0xFFF9F9FD),
            surfaceContainer = Color(0xFFF0F1F7),
        )
    }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

private fun Color.lightenForDark(): Color = Color(
    red = (red * 0.55f + 0.45f).coerceAtMost(1f),
    green = (green * 0.55f + 0.45f).coerceAtMost(1f),
    blue = (blue * 0.55f + 0.45f).coerceAtMost(1f),
    alpha = 1f,
)
