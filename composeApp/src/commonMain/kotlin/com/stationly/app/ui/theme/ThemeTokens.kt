package com.stationly.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App-wide theme tokens — the iOS/Compose-Multiplatform port of the Android
 * `ui/theme/ThemeTokens.kt`. One source of truth for every theme-aware colour
 * that doesn't map cleanly onto a Material 3 colorScheme slot (semantic
 * "live" green, "due" red, brand marks, etc.).
 *
 * NOTE: this is the additive foundation for parity with the redesigned Android
 * app. Today the iOS app still boots dark-only ([DefaultDarkTokens]); the SDUI
 * theme sync + light/system preference (Android `ThemeRepository` / `Theme.kt`)
 * is a later phase. Keeping the full token set here now means the screens we
 * port can already read `LocalThemeTokens.current.*` exactly like Android.
 *
 * Field set kept in lockstep with:
 *   - android/.../ui/theme/ThemeTokens.kt
 *   - stationly-backend/src/services/themeService.ts (ThemeBucket)
 *   - core/.../model/sdui/SduiAppModels.kt (SduiThemeTokens wire model)
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
    val brandSignage: Color,   // TfL amber INSIDE the dot-matrix board
    val roundelRed: Color,     // Stationly logo / roundel mark
)

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

    brandSignage       = Color(0xFFFFC819),
    roundelRed         = Color(0xFFDD2C33),
)

/**
 * Read by every composable that needs theme-aware colour without going
 * through MaterialTheme. Default points at dark tokens so previews /
 * misconfigured contexts still render sensibly.
 */
val LocalThemeTokens = compositionLocalOf { DefaultDarkTokens }

/* ──────────────────────────────────────────────────────────────────────────
 * MERGE — applies a partial SDUI override map onto a baseline [ThemeTokens].
 * Each key is a token name; values are `#RRGGBB` / `#AARRGGBB` hex strings.
 * Unknown keys are ignored. Mirrors android/.../ThemeRepository.kt#merge.
 * ────────────────────────────────────────────────────────────────────────── */

internal fun ThemeTokens.merge(
    overrides: Map<String, String>,
    constants: Map<String, String>,
): ThemeTokens {
    if (overrides.isEmpty() && constants.isEmpty()) return this
    fun pick(key: String, fallback: Color): Color = overrides[key]?.let(::parseHex) ?: fallback
    fun pickConst(key: String, fallback: Color): Color = constants[key]?.let(::parseHex) ?: fallback
    return copy(
        canvas             = pick("canvas",             canvas),
        card               = pick("card",               card),
        cardElevated       = pick("cardElevated",       cardElevated),
        scrim              = pick("scrim",              scrim),
        textPrimary        = pick("textPrimary",        textPrimary),
        textMuted          = pick("textMuted",          textMuted),
        textSubtle         = pick("textSubtle",         textSubtle),
        borderSubtle       = pick("borderSubtle",       borderSubtle),
        borderStrong       = pick("borderStrong",       borderStrong),
        primary            = pick("primary",            primary),
        onPrimary          = pick("onPrimary",          onPrimary),
        primaryContainer   = pick("primaryContainer",   primaryContainer),
        onPrimaryContainer = pick("onPrimaryContainer", onPrimaryContainer),
        success            = pick("success",            success),
        warning            = pick("warning",            warning),
        error              = pick("error",              error),
        info               = pick("info",               info),
        due                = pick("due",                due),
        live               = pick("live",               live),
        brandSignage       = pickConst("brandSignage",  brandSignage),
        roundelRed         = pickConst("roundelRed",    roundelRed),
    )
}

/** Parses `#RRGGBB`, `#AARRGGBB`, `RRGGBB`, `AARRGGBB`. Null on garbage. */
private fun parseHex(hex: String): Color? = runCatching {
    val cleaned = hex.removePrefix("#")
    val withAlpha = if (cleaned.length == 6) "FF$cleaned" else cleaned
    if (withAlpha.length != 8) return@runCatching null
    Color(withAlpha.toLong(16))
}.getOrNull()
