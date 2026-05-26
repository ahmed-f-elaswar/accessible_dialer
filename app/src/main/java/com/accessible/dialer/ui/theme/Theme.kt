package com.accessible.dialer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.settings.SettingsRepository.ThemeMode

// High contrast palette chosen for readability and WCAG AA contrast on the primary surfaces.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    secondary = Color(0xFF1B873B),
    onSecondary = Color.White,
    error = Color(0xFFB3261E),
    onError = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF101418),
    surface = Color(0xFFF6F8FB),
    onSurface = Color(0xFF101418),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    secondary = Color(0xFF7DDF8E),
    onSecondary = Color(0xFF003915),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF141A22),
    onSurface = Color(0xFFE6EAF0),
)

@Composable
fun AccessibleDialerTheme(
    themeMode: ThemeMode = ThemeMode.System,
    textScale: SettingsRepository.TextScale = SettingsRepository.TextScale.Default,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (darkTheme) DarkColors else LightColors

    // Override the density's fontScale so every sp-sized Text inside the theme honors
    // the user-picked accessibility scale on top of the OS scale.
    val base = LocalDensity.current
    val scaled = Density(density = base.density, fontScale = base.fontScale * textScale.factor)
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            content = content,
        )
    }
}
