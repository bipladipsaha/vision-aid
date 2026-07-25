package com.visionaid.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * VisionAid AI dark-first high-contrast theme.
 *
 * Design decisions:
 * - Dark-only scheme: reduces glare for low-vision users and saves
 *   battery on OLED screens (the phone lives in the user's pocket)
 * - No light theme: blind and low-vision users benefit from consistent
 *   high-contrast dark surfaces
 * - All accent colors exceed 4.5:1 contrast ratio against the background
 */

private val VisionAidColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = VisionDarkBackground,
    primaryContainer = VisionDarkSurfaceVariant,
    onPrimaryContainer = VisionTextPrimary,

    secondary = PiConnectedBlue,
    onSecondary = VisionDarkBackground,
    secondaryContainer = VisionDarkSurfaceVariant,
    onSecondaryContainer = VisionTextPrimary,

    tertiary = SafeGreen,
    onTertiary = VisionDarkBackground,

    error = DangerRed,
    onError = VisionDarkBackground,
    errorContainer = VisionDarkSurfaceVariant,
    onErrorContainer = DangerRed,

    background = VisionDarkBackground,
    onBackground = VisionTextPrimary,

    surface = VisionDarkSurface,
    onSurface = VisionTextPrimary,
    surfaceVariant = VisionDarkSurfaceVariant,
    onSurfaceVariant = VisionTextSecondary,

    outline = VisionTextDisabled,
    outlineVariant = VisionDarkSurfaceVariant,
)

private val NeoLightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = NeoPrimary,
    onPrimary = Color.White,
    primaryContainer = NeoPrimaryContainer,
    onPrimaryContainer = NeoOnPrimaryContainer,

    secondary = NeoSecondary,
    onSecondary = Color.White,
    secondaryContainer = NeoSurfaceContainerLowest,
    onSecondaryContainer = NeoOnSurface,

    background = NeoBackground,
    onBackground = NeoOnSurface,

    surface = NeoSurface,
    onSurface = NeoOnSurface,
    surfaceVariant = NeoSurfaceContainer,
    onSurfaceVariant = NeoOnSurfaceVariant,

    outline = Color(0xFF727972),
    outlineVariant = Color(0xFFC1C8C1)
)

@Composable
fun VisionAidTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = NeoLightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Dark icons on light background
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VisionAidTypography,
        content = content
    )
}
