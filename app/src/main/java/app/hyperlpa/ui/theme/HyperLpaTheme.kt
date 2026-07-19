package app.hyperlpa.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.ThemeAccent
import app.hyperlpa.data.settings.ThemeMode
import app.hyperlpa.data.settings.ThemePalette
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.platformDynamicColors

val LocalBlurEnabled = staticCompositionLocalOf { true }
val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun HyperLpaTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorMode = when {
        !settings.useMonet && settings.themeMode == ThemeMode.LIGHT -> ColorSchemeMode.Light
        !settings.useMonet && settings.themeMode == ThemeMode.DARK -> ColorSchemeMode.Dark
        !settings.useMonet -> ColorSchemeMode.System
        settings.themeMode == ThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        settings.themeMode == ThemeMode.DARK -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }
    val dynamicSeed = if (settings.useMonet && settings.accent == ThemeAccent.SYSTEM) {
        platformDynamicColors(isDark).primary
    } else {
        null
    }
    val keyColor = when {
        !settings.useMonet -> null
        settings.accent == ThemeAccent.SYSTEM -> dynamicSeed
        else -> settings.accent.seedColor
    }
    val controller = remember(colorMode, keyColor, settings.palette) {
        ThemeController(
            colorSchemeMode = colorMode,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = settings.palette.toMiuixPalette(),
        )
    }
    val generatedColors = controller.currentColors()
    val colors = remember(generatedColors, isDark, settings.useMonet, settings.pureBlack) {
        if (settings.useMonet && isDark && settings.pureBlack) {
            generatedColors.copy(background = Color.Black, surface = Color.Black)
        } else {
            generatedColors
        }
    }

    MiuixTheme(colors = colors) {
        val platformDensity = LocalDensity.current
        val scaledDensity = remember(platformDensity, settings.densityScale) {
            Density(
                density = platformDensity.density * settings.densityScale.coerceIn(0.8f, 1.1f),
                fontScale = platformDensity.fontScale,
            )
        }
        SystemBarsEffect(isDark = isDark)
        CompositionLocalProvider(
            LocalDensity provides scaledDensity,
            LocalPlatformDensity provides platformDensity,
            LocalBlurEnabled provides settings.blurEnabled,
            LocalDarkTheme provides isDark,
            LocalContentColor provides MiuixTheme.colorScheme.onSurface,
            content = content,
        )
    }
}

@Composable
private fun SystemBarsEffect(isDark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val ThemeAccent.seedColor: Color
    get() = when (this) {
        ThemeAccent.SYSTEM,
        ThemeAccent.BLUE,
        -> Color(0xFF3482FF)
        ThemeAccent.PURPLE -> Color(0xFF6750A4)
        ThemeAccent.PINK -> Color(0xFFB0006D)
        ThemeAccent.RED -> Color(0xFFBA1A1A)
        ThemeAccent.ORANGE -> Color(0xFFB65D00)
        ThemeAccent.YELLOW -> Color(0xFF7D5700)
        ThemeAccent.GREEN -> Color(0xFF006D3B)
        ThemeAccent.TEAL -> Color(0xFF006A6A)
    }

private fun ThemePalette.toMiuixPalette(): ThemePaletteStyle = when (this) {
    ThemePalette.TONAL_SPOT -> ThemePaletteStyle.TonalSpot
    ThemePalette.NEUTRAL -> ThemePaletteStyle.Neutral
    ThemePalette.VIBRANT -> ThemePaletteStyle.Vibrant
    ThemePalette.EXPRESSIVE -> ThemePaletteStyle.Expressive
    ThemePalette.RAINBOW -> ThemePaletteStyle.Rainbow
    ThemePalette.FRUIT_SALAD -> ThemePaletteStyle.FruitSalad
    ThemePalette.MONOCHROME -> ThemePaletteStyle.Monochrome
    ThemePalette.FIDELITY -> ThemePaletteStyle.Fidelity
    ThemePalette.CONTENT -> ThemePaletteStyle.Content
}
