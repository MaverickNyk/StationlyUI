package com.stationly.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.stationly.core.model.sdui.SduiThemeTokens
import kotlinx.coroutines.launch

/**
 * Theme entry points — Compose-Multiplatform port of android/.../ui/theme/Theme.kt.
 *
 *   [StationlyThemeHost]  ← App.kt wraps everything in this
 *        ├─ reads the AppTheme preference (Light / Dark / System)
 *        ├─ reads cached SduiThemeTokens from shared storage
 *        ├─ kicks off ThemeRepository.refreshInBackground() (writes the cache;
 *        │   does NOT re-apply mid-session — colour flips on next launch)
 *        ├─ merges defaults ⊕ cached overrides → final ThemeTokens
 *        └─ provides LocalAppTheme, LocalThemeTokens, MaterialTheme.colorScheme
 *
 * SYSTEM defers to [isSystemInDarkTheme]; flipping system dark mode while the
 * app is open re-renders correctly because Compose observes that signal.
 *
 * Unlike Android (synchronous SharedPrefs read before first frame), the shared
 * storage manager is suspend, so the preference + overrides load in a
 * LaunchedEffect and apply on the following recomposition. The first frame uses
 * system-default tokens; this is the closest iOS analog to Android's
 * "applied on next launch" cache model.
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

@Composable
fun StationlyThemeHost(content: @Composable () -> Unit) {
    var theme by remember { mutableStateOf(AppTheme.SYSTEM) }
    var overrides by remember { mutableStateOf(SduiThemeTokens()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        theme = AppSettings.getTheme()
        overrides = ThemeRepository.loadCachedOverrides()
        ThemeRepository.refreshInBackground()
    }

    val darkTheme = when (theme) {
        AppTheme.DARK   -> true
        AppTheme.LIGHT  -> false
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    val base = if (darkTheme) DefaultDarkTokens else DefaultLightTokens
    val activeOverrides = if (darkTheme) overrides.dark else overrides.light
    val mergedTokens = base.merge(activeOverrides, overrides.constants)

    val themeState = AppThemeState(
        theme = theme,
        onChange = { new ->
            scope.launch { AppSettings.setTheme(new) }
            theme = new
        },
    )
    CompositionLocalProvider(LocalAppTheme provides themeState) {
        StationlyTheme(theme = theme, tokens = mergedTokens, content = content)
    }
}

/**
 * Project [ThemeTokens] onto Material 3's `ColorScheme` so every screen that
 * reads `MaterialTheme.colorScheme.*` keeps working. Non-M3 tokens (`due`,
 * `live`, `brandSignage`, `roundelRed`) are read via `LocalThemeTokens.current`.
 */
internal fun ThemeTokens.toColorScheme(darkTheme: Boolean): ColorScheme {
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary            = primary,
        onPrimary          = onPrimary,
        primaryContainer   = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary          = primary,
        onSecondary        = onPrimary,
        tertiary           = roundelRed,
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
