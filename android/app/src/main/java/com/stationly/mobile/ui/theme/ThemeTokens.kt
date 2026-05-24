package com.stationly.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App-wide theme tokens. One source of truth for every theme-aware colour
 * in the app (excluding the dot-matrix departure board, which is intentionally
 * locked to its TfL signage palette regardless of theme).
 *
 * **Source order at runtime** (see [ThemeRepository]):
 *   1. Hardcoded [DefaultLightTokens] / [DefaultDarkTokens] in this file —
 *      always the ground truth; app boots with these on first install or
 *      when SharedPrefs is wiped.
 *   2. SharedPrefs cache of the last successful SDUI sync — overlays the
 *      defaults. Survives uninstall-to-reinstall via Android Auto Backup,
 *      survives offline forever.
 *   3. Background SDUI fetch on every app launch — writes its result to
 *      the SharedPrefs cache. Does NOT re-apply mid-session (avoids the
 *      jarring "colours just changed mid-scroll" effect); the new tokens
 *      take effect on the next cold launch.
 *
 * **Why a fixed list of fields rather than a Map<String, Color>?**
 * - Type safety: a typo'd key becomes a compile error, not a silent fallback.
 * - IDE autocomplete + refactor support.
 * - Cheap to add a new field (one place to declare, one place to default,
 *   one place to deserialise from JSON in [ThemeRepository.merge]).
 *
 * **Why M3 colorScheme isn't enough on its own?**
 * Compose's `MaterialTheme.colorScheme` only has slots for primary,
 * surface, error etc. — no slot for semantic "due" red, "live" green,
 * "TfL roundel" red, or the constant dot-matrix amber. [ThemeTokens]
 * is the superset; [toColorScheme] projects the parts M3 cares about.
 */
@Immutable
data class ThemeTokens(
    // ── Canvas & surfaces ────────────────────────────────────────────────
    val canvas: Color,
    val card: Color,
    val cardElevated: Color,
    val scrim: Color,

    // ── Text on canvas / cards ───────────────────────────────────────────
    val textPrimary: Color,
    val textMuted: Color,
    val textSubtle: Color,

    // ── Borders ──────────────────────────────────────────────────────────
    val borderSubtle: Color,
    val borderStrong: Color,

    // ── Brand amber family ───────────────────────────────────────────────
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,

    // ── Semantic states ──────────────────────────────────────────────────
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val due: Color,
    val live: Color,

    // ── Theme-independent constants ──────────────────────────────────────
    // These two are part of every [ThemeTokens] instance but always carry
    // the same value across light/dark — they live here so SDUI can still
    // override them in one place.
    val brandSignage: Color,   // TfL amber INSIDE the dot-matrix board
    val roundelRed: Color,     // Stationly logo / roundel mark
)

/* ──────────────────────────────────────────────────────────────────────────
 * DEFAULTS — the ground truth, baked into the app binary.
 * Values match `stationly-backend/src/services/themeService.ts` defaults.
 * If you change one side, change the other in the same PR.
 * ────────────────────────────────────────────────────────────────────────── */

/** Stationly's signature dark theme — bright TfL amber on near-black. */
val DefaultDarkTokens = ThemeTokens(
    canvas             = Color(0xFF0A0A0A),
    card               = Color(0xFF161616),
    cardElevated       = Color(0xFF222222),
    scrim              = Color(0xCC000000),

    textPrimary        = Color(0xFFFFFFFF),
    textMuted          = Color(0xB3FFFFFF),
    textSubtle         = Color(0x73FFFFFF),

    borderSubtle       = Color(0x1FFFFFFF),
    borderStrong       = Color(0x59FFFFFF),

    primary            = Color(0xFFFFC819),
    onPrimary          = Color(0xFF000000),
    primaryContainer   = Color(0x26FFC819),
    onPrimaryContainer = Color(0xFFFFC819),

    success            = Color(0xFF4ADE80),
    warning            = Color(0xFFFFC819),
    error              = Color(0xFFEF4444),
    info               = Color(0xFF4A90D9),
    due                = Color(0xFFFF5252),
    live               = Color(0xFF4ADE80),

    brandSignage       = Color(0xFFFFC819),
    roundelRed         = Color(0xFFDD2C33),
)

/** Warm cream + deep amber. Picked for AAA contrast on text-as-primary. */
val DefaultLightTokens = ThemeTokens(
    canvas             = Color(0xFFFAF7F0),
    card               = Color(0xFFFFFFFF),
    cardElevated       = Color(0xFFF0EAE0),
    scrim              = Color(0xCC000000),

    textPrimary        = Color(0xFF1A1A1A),
    textMuted          = Color(0xFF5A5247),
    textSubtle         = Color(0x995A5247),

    borderSubtle       = Color(0xFFD8D0C0),
    borderStrong       = Color(0x591A1A1A),

    primary            = Color(0xFF8B5A0E),
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFFAE6C2),
    onPrimaryContainer = Color(0xFF5A3B00),

    success            = Color(0xFF16A34A),
    warning            = Color(0xFFB45309),
    error              = Color(0xFFB42318),
    info               = Color(0xFF1E40AF),
    due                = Color(0xFFDC2626),
    live               = Color(0xFF16A34A),

    brandSignage       = Color(0xFFFFC819),   // CONSTANT — only used on dark dot-matrix surface
    roundelRed         = Color(0xFFDD2C33),
)

/**
 * Read by every composable that needs theme-aware colour without going
 * through MaterialTheme. Default points at dark tokens so previews /
 * misconfigured contexts still render sensibly.
 */
val LocalThemeTokens = compositionLocalOf { DefaultDarkTokens }
