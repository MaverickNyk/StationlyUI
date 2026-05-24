package com.stationly.mobile.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.stationly.mobile.ui.theme.AppTheme
import com.stationly.mobile.ui.theme.LocalAppTheme

/**
 * Top-bar theme toggle. Single tap cycles Light → Dark → System → Light.
 * Reads/writes via [LocalAppTheme] so the change persists and the whole
 * app recomposes with the new colour scheme.
 *
 * Icon morphs with a small crossfade+scale to acknowledge the tap visually:
 *   LIGHT  → sun
 *   DARK   → moon
 *   SYSTEM → brightness-auto
 *
 * Lives on the Summary top bar by default; the same button can be dropped
 * into any other top bar later — there's nothing screen-specific here.
 *
 * The full picker (with swatches and labels) still lives in Profile →
 * Appearance for first-time discovery. This is the daily-use shortcut.
 */
@Composable
fun ThemeToggleButton(
    modifier: Modifier = Modifier,
    /** When true: very small + low alpha, suitable for a discreet bottom-edge
     *  placement on the home screen. When false (default): top-bar size. */
    compact: Boolean = false,
) {
    val themeState = LocalAppTheme.current

    // Compact mode is still smaller than the top-bar variant, but sized
    // large enough that the sun/moon/auto glyphs are immediately
    // legible at a glance — the previous 12dp filled icons were too
    // small for the symbol to register.
    val boxSize    = if (compact) 32.dp else 36.dp
    val iconSize   = if (compact) 18.dp else 18.dp
    val hitboxSize = if (compact) 40.dp else 48.dp
    val bgAlpha    = if (compact) 0.05f else 0.05f
    val tintAlpha  = if (compact) 0.60f else 0.70f

    IconButton(
        onClick = {
            val next = when (themeState.theme) {
                AppTheme.LIGHT  -> AppTheme.DARK
                AppTheme.DARK   -> AppTheme.SYSTEM
                AppTheme.SYSTEM -> AppTheme.LIGHT
            }
            themeState.onChange(next)
        },
        modifier = modifier.size(hitboxSize),
    ) {
        Box(
            modifier = Modifier
                .size(boxSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = bgAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = themeState.theme,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.7f, animationSpec = tween(180))) togetherWith
                        (fadeOut(tween(120)) + scaleOut(targetScale = 0.7f, animationSpec = tween(120)))
                },
                label = "theme_icon_morph",
            ) { theme ->
                // Filled glyphs read clearer at small sizes than outlines.
                // The trio sun / moon / auto-brightness is the
                // industry-standard theme-toggle iconography (used by
                // iOS, macOS, Material Design, GitHub, etc.).
                val (icon, label) = when (theme) {
                    AppTheme.LIGHT  -> Icons.Filled.LightMode      to "Switch to dark theme"
                    AppTheme.DARK   -> Icons.Filled.DarkMode       to "Switch to system theme"
                    AppTheme.SYSTEM -> Icons.Filled.BrightnessAuto to "Switch to light theme"
                }
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = tintAlpha),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}
