package com.stationly.app.ui.common

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
import com.stationly.app.ui.theme.AppTheme
import com.stationly.app.ui.theme.LocalAppTheme

/**
 * Top-bar / floating theme toggle. Single tap cycles Light → Dark → System →
 * Light. Reads/writes via [LocalAppTheme] so the change persists (via
 * AppSettings → NSUserDefaults) and the whole app recomposes with the new
 * colour scheme. Compose-Multiplatform port of the Android
 * `ui/common/ThemeToggleButton.kt`, kept identical so the daily-use shortcut
 * behaves the same on both platforms.
 *
 * Icon morphs with a small crossfade+scale to acknowledge the tap visually:
 *   LIGHT  → sun
 *   DARK   → moon
 *   SYSTEM → brightness-auto
 *
 * The departure board / widget always render dark dot-matrix regardless —
 * that's signage, not chrome.
 */
@Composable
fun ThemeToggleButton(
    modifier: Modifier = Modifier,
    /** When true: very small + low alpha, suitable for a discreet bottom-edge
     *  placement on the home screen. When false (default): top-bar size. */
    compact: Boolean = false,
) {
    val themeState = LocalAppTheme.current

    val boxSize    = if (compact) 32.dp else 36.dp
    val iconSize   = 18.dp
    val hitboxSize = if (compact) 40.dp else 48.dp
    val bgAlpha    = 0.05f
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
                // Filled glyphs read clearer at small sizes than outlines. The
                // trio sun / moon / auto-brightness is the industry-standard
                // theme-toggle iconography (iOS, macOS, Material, GitHub, …).
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
