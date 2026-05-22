package com.stationly.mobile.dream

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Theme colours for the dream's canvas + text that sits on the canvas.
 *
 * Deliberately small: this is NOT a full Material colour scheme. The
 * departure board card draws itself in its dot-matrix dark style at all
 * times, and TfL line colours stay constant regardless of theme.
 *
 * What lives here:
 *   - [canvas]      outer background behind everything
 *   - [onCanvas]    base colour for text and faint shapes; composables
 *                   apply their own alpha for muted variants.
 *   - [brandAccent] theme-aware Stationly/TfL amber. Bright amber on dark
 *                   reads as signage; on light, a deep amber/bronze keeps
 *                   the brand cue without losing contrast. Use this for
 *                   the digital clock, station name headline, temperature
 *                   chip, and the analog dial's hour markers — anywhere
 *                   the dark theme would pick up `tfl_amber`.
 */
@Immutable
data class DreamColors(
    val canvas: Color,
    val onCanvas: Color,
    val brandAccent: Color,
    /** Semantic danger / disruption colour. Used by disruption status
     *  text and "Due" ETA so the alert reads as bad-news, not brand-cue. */
    val danger: Color,
    /** Semantic live-data green pulse. Constant across both dream themes
     *  by design — "live" means the same thing whatever the canvas. */
    val live: Color,
)

/** Stationly's signature near-black canvas with white-on-canvas + bright
 *  amber for signage-style accents. */
val DarkDreamColors = DreamColors(
    canvas      = Color(0xFF0A0A0A),
    onCanvas    = Color.White,
    brandAccent = Color(0xFFFFC819),  // TfL amber (matches @color/tfl_amber)
    danger      = Color(0xFFFF5252),  // bright red on near-black canvas
    live        = Color(0xFF4ADE80),  // semantic green, constant
)

/**
 * Warm off-white canvas with near-black text. The off-white avoids a
 * harsh pure-white wash on AMOLED panels and matches the cosy "docked
 * on the bedside" use case the dream is built for. Brand accent drops to
 * a deep amber/bronze that still reads as the same hue family but has
 * enough contrast against the warm light canvas to be legible.
 */
val LightDreamColors = DreamColors(
    // Kept in sync with `ui/theme/Theme.kt`'s LightColorScheme — same
    // canvas, same brand-amber accent so the dream and app feel
    // continuous when the cluster dream is overlaid on a light app theme.
    canvas      = Color(0xFFFAF7F0),
    onCanvas    = Color(0xFF1A1A1A),
    brandAccent = Color(0xFF8B5A0E),
    danger      = Color(0xFFB42318),  // deep red, reads cleanly on cream
    live        = Color(0xFF16A34A),  // mid-green, matches light app theme
)

/**
 * Threads the resolved theme through the composition. Any composable that
 * draws on the dream's canvas should read [LocalDreamColors.current]
 * instead of hardcoding `Color.White` / `Color(0xFF0A0A0A)`. Default
 * keeps existing call sites correct even if the host forgets to provide.
 */
val LocalDreamColors = compositionLocalOf { DarkDreamColors }
