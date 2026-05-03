package com.stationly.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StationlyDarkColorScheme = darkColorScheme(
    primary            = TflAmber,
    onPrimary          = Color(0xFF1A1400),
    primaryContainer   = Color(0xFF2A2000),
    onPrimaryContainer = TflAmber,
    secondary          = TflAmberDim,
    onSecondary        = Color.Black,
    secondaryContainer = Color(0xFF1A1A00),
    onSecondaryContainer = TextSecondary,
    tertiary           = SuccessGreen,
    onTertiary         = Color.Black,
    background         = AppBackground,
    onBackground       = TextPrimary,
    surface            = AppSurface,
    onSurface          = TextPrimary,
    surfaceVariant     = Color(0xFF2A2A2A),
    onSurfaceVariant   = TextSecondary,
    error              = ErrorColor,
    onError            = Color.White,
    errorContainer     = Color(0xFF3D0018),
    onErrorContainer   = ErrorColor,
    outline            = Color(0xFF3A3A3A),
    outlineVariant     = Color(0xFF2A2A2A),
    scrim              = Color.Black.copy(alpha = 0.7f),
    inverseSurface     = Color(0xFFEEEEEE),
    inverseOnSurface   = Color.Black,
    inversePrimary     = Color(0xFF5A3E00),
)

@Composable
fun StationlyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StationlyDarkColorScheme,
        content = content
    )
}
