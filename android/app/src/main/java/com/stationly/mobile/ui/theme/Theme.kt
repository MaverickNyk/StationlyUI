package com.stationly.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Theme entry points for the Stationly app.
 *
 * Architecture (see [ThemeTokens] docstring for full detail):
 *
 *   [StationlyThemeHost]      ← DreamSettingsActivity wraps everything in this
 *        │
 *        ├─ reads AppTheme preference (Light / Dark / System)
 *        ├─ reads cached SduiThemeTokens from SharedPrefs
 *        ├─ kicks off ThemeRepository.refreshInBackground() (writes to cache,
 *        │   does NOT re-apply mid-session — colour flips on next launch)
 *        ├─ merges defaults ⊕ cached overrides → final [ThemeTokens]
 *        └─ provides:
 *              - LocalAppTheme       (the picker state)
 *              - LocalThemeTokens    (every theme-aware colour, incl. non-M3 ones)
 *              - MaterialTheme.colorScheme (subset of tokens M3 cares about)
 *
 * The first 18 lines of every screen can read either MaterialTheme.colorScheme.*
 * (for code that's already M3-native) or LocalThemeTokens.current.* (for the
 * semantic tokens that don't map to M3 — `due`, `live`, `brandSignage`, etc.).
 *
 * Brand approach: amber is primary in both themes (TfL signage in dark, deep
 * amber in light). Cards in light theme are pure white to POP off the cream
 * canvas; in dark, slightly lighter than canvas to rise from it. The
 * dot-matrix departure board is on its OWN locked dark surface — never
 * themed (signage doesn't flip).
 */

/**
 * Resolves the user's [AppTheme] choice into [ThemeTokens] and wraps content
 * in MaterialTheme + LocalThemeTokens. SYSTEM defers to Compose's
 * [isSystemInDarkTheme] — flipping system dark mode while the app is open
 * re-renders correctly because Compose observes that signal.
 */
@Composable
fun StationlyTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    tokens: ThemeTokens? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (theme) {
        AppTheme.DARK   -> true
        AppTheme.LIGHT  -> false
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    val resolvedTokens = tokens ?: if (darkTheme) DefaultDarkTokens else DefaultLightTokens

    CompositionLocalProvider(LocalThemeTokens provides resolvedTokens) {
        MaterialTheme(
            colorScheme = resolvedTokens.toColorScheme(darkTheme),
            typography  = MaterialTheme.typography,
            content     = content,
        )
    }
}

/**
 * Theme host for the surfaces still on v1's Compose tree. Since AV2-3.5 that
 * is `DreamSettingsActivity` and nothing else — the app itself is
 * `:composeApp`'s `StationlyThemeHost`, which is a different function in a
 * different module with the same name and the same job.
 *
 * Composes:
 *   - The persisted [AppTheme] preference, read once. It is READ-only here now:
 *     the picker is in the shared UI, and `AppSettings` explains why there must
 *     not be a second writer.
 *   - The SharedPrefs cache of SDUI theme overrides (read synchronously
 *     on first composition — cheap, ~1 KB JSON).
 *   - A background-coroutine network refresh (fire-and-forget per launch).
 */
@Composable
fun StationlyThemeHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val theme = remember { AppSettings.getTheme(context) }

    // Read SDUI overrides once per composition. The merge happens below
    // against the active dark/light defaults so flipping the theme picker
    // re-applies overrides to the OPPOSITE palette without an extra read.
    val overrides = remember { ThemeRepository.loadCachedOverrides(context) }

    // One-shot background refresh per process. Failures are silent; the
    // cached/default palette keeps working. Writes go to SharedPrefs and
    // are picked up on the NEXT launch (not mid-session).
    LaunchedEffect(Unit) { ThemeRepository.refreshInBackground(context) }

    val darkTheme = when (theme) {
        AppTheme.DARK   -> true
        AppTheme.LIGHT  -> false
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    val base = if (darkTheme) DefaultDarkTokens else DefaultLightTokens
    val activeOverrides = if (darkTheme) overrides.dark else overrides.light
    val mergedTokens = base.merge(activeOverrides, overrides.constants)

    CompositionLocalProvider(LocalAppTheme provides AppThemeState(theme)) {
        StationlyTheme(theme = theme, tokens = mergedTokens, content = content)
    }
}

/**
 * Project [ThemeTokens] onto Material 3's `ColorScheme`. Keeps the M3
 * naming so every screen that already uses `MaterialTheme.colorScheme.*`
 * continues to work without a rewrite. Tokens not represented in M3
 * (`due`, `live`, `brandSignage`, `roundelRed`) are accessed via
 * `LocalThemeTokens.current.*` instead.
 */
internal fun ThemeTokens.toColorScheme(darkTheme: Boolean): ColorScheme {
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary            = primary,
        onPrimary          = onPrimary,
        primaryContainer   = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary          = primary,            // we don't define a separate secondary brand
        onSecondary        = onPrimary,
        tertiary           = roundelRed,         // logo red as M3's tertiary slot
        onTertiary         = onPrimary,
        background         = canvas,
        onBackground       = textPrimary,
        surface            = card,
        onSurface          = textPrimary,
        surfaceVariant     = cardElevated,
        onSurfaceVariant   = textMuted,
        outline            = borderSubtle,
        outlineVariant     = borderSubtle,
        scrim              = scrim,
        error              = error,
        onError            = onPrimary,
    )
}
