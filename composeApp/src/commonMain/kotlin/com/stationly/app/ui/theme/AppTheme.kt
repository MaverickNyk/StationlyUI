package com.stationly.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import com.stationly.core.platform.Platform

/**
 * User's app-wide theme preference. Compose-Multiplatform port of the Android
 * `ui/theme/AppTheme.kt`. Persisted via the shared [Platform.storageManager]
 * (NSUserDefaults on iOS) so it survives process death.
 *
 *   LIGHT  — force light scheme
 *   DARK   — force dark scheme (the app's historical signage look)
 *   SYSTEM — follow the device dark-mode setting (default)
 *
 * The departure board / widget always render dark dot-matrix regardless —
 * that's signage, not chrome.
 */
enum class AppTheme(val storedAs: String, val displayName: String) {
    LIGHT  ("light",  "Light"),
    DARK   ("dark",   "Dark"),
    SYSTEM ("system", "System");

    companion object {
        fun fromStored(value: String?): AppTheme =
            entries.firstOrNull { it.storedAs == value } ?: SYSTEM
    }
}

/**
 * Read/write helper for the theme preference. Suspend because the shared
 * storage manager is async on iOS (unlike Android SharedPrefs). Read once at
 * theme-host start; written by the in-app toggle.
 */
object AppSettings {
    private const val KEY_THEME = "app_theme"

    suspend fun getTheme(): AppTheme =
        AppTheme.fromStored(Platform.storageManager.loadString(KEY_THEME))

    suspend fun setTheme(theme: AppTheme) {
        Platform.storageManager.saveString(KEY_THEME, theme.storedAs)
    }
}

/**
 * Current theme + a setter so any screen (Profile, etc.) can flip it without
 * prop-drilling. The setter persists and updates the host-level state so the
 * whole tree recomposes with the new scheme.
 */
@Immutable
data class AppThemeState(
    val theme: AppTheme,
    val onChange: (AppTheme) -> Unit,
)

/** Provided by [StationlyThemeHost]; default throws if you forgot to wrap. */
val LocalAppTheme = compositionLocalOf<AppThemeState> {
    error("LocalAppTheme not provided — wrap with StationlyThemeHost")
}
