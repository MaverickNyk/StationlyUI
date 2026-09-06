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
 * Read-only view of the app theme, for the surfaces that are still on v1's
 * Compose tree — which since AV2-3.5 means the **Daydream** and nothing else.
 *
 * ## Two files, one setting, and the precedence that keeps them agreeing
 * The shared UI owns the theme picker now
 * (`composeApp/.../ui/theme/AppTheme.kt`), and it writes through
 * `Platform.storageManager.saveDurable` — which on Android is
 * `stationly_durable_prefs`, NOT the `StationlyPrefs` this file used to read.
 * It is durable because signing out wipes the ordinary defaults domain, and
 * "dark mode" says nothing about who anybody is; iOS reset every user's theme
 * on every sign-out until that moved.
 *
 * So this reads durable FIRST and `StationlyPrefs` second, which is the exact
 * precedence the shared `AppSettings.getTheme` uses, for the exact same two
 * reasons:
 *
 *  - **Durable first**, or the screensaver keeps rendering the theme the user
 *    last chose in v1 while the app renders the one they chose today. Silent,
 *    and only visible on a surface nobody opens deliberately.
 *  - **`StationlyPrefs` second**, so a choice made in v1 and never touched
 *    since still counts. That fallback is the whole of AV2-3.5 task (e): a user
 *    upgrading from `versionCode 2` keeps their theme because the shared reader
 *    finds the value v1 wrote, at the key and in the file v1 wrote it to. There
 *    is no translation step, and adding one would be a second thing to get
 *    wrong. `V1ThemeCarryOverTest` holds both halves of that.
 *
 * There is deliberately no `setTheme` here any more: two writers is how the two
 * files start disagreeing. The shared UI writes; this reads.
 *
 * NOTE: this is NOT the dream's `DreamSettings` — that one uses a separate
 * `StationlyDreamPrefs` file so it isn't wiped on logout.
 */
object AppSettings {
    /** Written by v1, and by nothing since the cutover. Still the fallback. */
    internal const val LEGACY_FILE = "StationlyPrefs"

    /** Written by the shared UI's `Platform.storageManager.saveDurable`. */
    internal const val DURABLE_FILE = "stationly_durable_prefs"

    internal const val KEY_THEME = "app_theme"

    fun getTheme(context: Context): AppTheme = AppTheme.fromStored(
        context.getSharedPreferences(DURABLE_FILE, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)
            ?: context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
                .getString(KEY_THEME, null)
    )
}

/**
 * The resolved theme, for the v1 Compose tree that still reads it.
 *
 * It carried an `onChange` setter until AV2-3.5, so any v1 screen could flip
 * the theme without prop-drilling. Every one of those screens is gone and the
 * picker lives in the shared UI now, so this is read-only — see [AppSettings]
 * for why a second writer is the thing to avoid rather than the thing to keep.
 */
@Immutable
data class AppThemeState(
    val theme: AppTheme,
)

/**
 * Provided by [StationlyThemeHost], which since the cutover has exactly one
 * caller: `DreamSettingsActivity`. Default throws — if you see "not provided",
 * you forgot to wrap your preview / test composable in
 * `StationlyThemeHost { ... }`.
 */
val LocalAppTheme = compositionLocalOf<AppThemeState> {
    error("LocalAppTheme not provided — wrap with StationlyThemeHost")
}
