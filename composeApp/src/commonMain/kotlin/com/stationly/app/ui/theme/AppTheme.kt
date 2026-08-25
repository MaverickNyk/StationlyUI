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

    /**
     * DURABLE, and deliberately NOT namespaced by account.
     *
     * **Durable**, because sign-out wipes the app's ordinary defaults domain.
     * That is right for anything naming the user and wrong for this: "dark mode"
     * says nothing about who anybody is, and a user who signed out and back in
     * had their theme silently reset to system every time.
     *
     * **Not scoped by uid, and that is a correction rather than an omission.**
     * Scoping it was tried and broke the feature outright. The theme is read at
     * THEME-HOST START — the earliest thing the UI does — which is before the
     * uid has been restored. So the scoped read looked up `app_theme:` with no
     * account, found nothing, and fell back to system. The setting was written
     * correctly and never read back.
     *
     * The design says to namespace the DREAM settings by uid and to move this
     * one to durable storage; those are different instructions for a reason.
     * A per-account theme would need the read deferred until after auth
     * restores, which means a visible flash of the wrong theme on every launch —
     * a worse bug than two accounts on one device sharing a colour scheme.
     *
     * The read falls back to the ordinary domain so a value written before this
     * became durable survives the upgrade instead of resetting once.
     */
    suspend fun getTheme(): AppTheme =
        AppTheme.fromStored(
            Platform.storageManager.loadDurable(KEY_THEME)
                ?: Platform.storageManager.loadString(KEY_THEME),
        )

    suspend fun setTheme(theme: AppTheme) {
        Platform.storageManager.saveDurable(KEY_THEME, theme.storedAs)
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
