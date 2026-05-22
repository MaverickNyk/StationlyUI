package com.stationly.mobile.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * User's app-wide theme preference. Persisted in SharedPrefs so it survives
 * process death, read before Compose starts so we don't flash the wrong
 * theme during cold launch.
 *
 *   LIGHT  — force light scheme
 *   DARK   — force dark scheme (matches the app's historical signage look)
 *   SYSTEM — follow the device's current dark-mode setting (default)
 *
 * The departure board / widget always render in their dark dot-matrix
 * style regardless of this choice — that's signage, not chrome. See
 * `dream/CLAUDE.md` invariant #10 for the same principle in the dream.
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
 * Read/write helper for app-level settings. Lives in the main
 * "StationlyPrefs" file so it's adjacent to other core preferences.
 *
 * NOTE: this is NOT the dream's `DreamSettings` — that one uses a separate
 * `StationlyDreamPrefs` file so it isn't wiped on logout. App theme is
 * a UI preference that doesn't depend on the user's account.
 */
object AppSettings {
    private const val FILE      = "StationlyPrefs"
    private const val KEY_THEME = "app_theme"

    fun getTheme(context: Context): AppTheme =
        AppTheme.fromStored(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY_THEME, null)
        )

    fun setTheme(context: Context, theme: AppTheme) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, theme.storedAs).apply()
    }
}

/**
 * Holds both the current theme and a setter so any screen (Profile,
 * Settings, etc.) can update it without prop-drilling through the nav
 * graph. The setter persists to SharedPrefs AND updates the MainActivity-
 * level state so the whole tree recomposes with the new colour scheme.
 */
@Immutable
data class AppThemeState(
    val theme: AppTheme,
    val onChange: (AppTheme) -> Unit,
)

/**
 * Provided by [com.stationly.mobile.MainActivity] via [StationlyThemeHost].
 * Default throws — if you see "not provided", you forgot to wrap your
 * preview / test composable in `StationlyThemeHost { ... }`.
 */
val LocalAppTheme = compositionLocalOf<AppThemeState> {
    error("LocalAppTheme not provided — wrap with StationlyThemeHost")
}
