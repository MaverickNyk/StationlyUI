package com.stationly.mobile.host

import com.stationly.core.platform.AndroidStorageManager
import com.stationly.mobile.ui.theme.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import com.stationly.app.ui.theme.AppTheme as SharedAppTheme
import com.stationly.mobile.ui.theme.AppTheme as V1AppTheme

/**
 * A user upgrading from `versionCode 2` keeps their theme, and the screensaver
 * keeps showing the same one as the app.
 *
 * AV2-3.5's acceptance criterion is "a user upgrading keeps their theme", and
 * the story called for a one-shot translation on first v2 launch. **No
 * translation was written, because none is needed** — and this test is the
 * reason that is a finding rather than a gap. The shared reader already looks in
 * the ordinary prefs domain when the durable one is empty:
 *
 *     loadDurable("app_theme") ?: loadString("app_theme")
 *
 * and on Android `loadString` reads `StationlyPrefs`, which is exactly the file
 * and exactly the key v1 wrote to. The fallback was added for an iOS reason
 * (a value written before the setting became durable) and it happens to be the
 * whole of Android's upgrade path. A migration on top of it would be a second
 * mechanism doing the same job, with its own first-launch ordering to get wrong.
 *
 * That leaves three ways for the carry-over to break silently, all of them a
 * rename rather than a bug, and all of them here:
 *
 *  1. The two `AppTheme` enums stop agreeing about what a stored string means.
 *     `"dark"` becoming `"DARK"` on one side resets every upgrading user to
 *     system, and `fromStored` swallows it — that is what its fallback is for.
 *  2. The key changes on one side.
 *  3. A prefs FILE changes. Renaming `StationlyPrefs` does not migrate the
 *     app's history; it abandons it.
 *
 * The fourth risk is the one the dream carries: the shared UI writes the theme
 * to the DURABLE file, and `dream/` still reads through v1's `AppSettings`. If
 * that reader stopped preferring durable, the screensaver would quietly render
 * whatever theme the user last picked in v1 — on a surface nobody opens
 * deliberately, so nobody would report it.
 */
class V1ThemeCarryOverTest {

    @Test
    fun `both AppTheme enums store the same strings for the same choices`() {
        assertEquals(
            "an upgrading user's stored value is read by the SHARED enum; if the " +
                "two disagree, fromStored falls back to SYSTEM and the choice is lost",
            V1AppTheme.entries.associate { it.name to it.storedAs },
            SharedAppTheme.entries.associate { it.name to it.storedAs },
        )
        // Spelled out as well as compared, so changing BOTH sides at once —
        // which keeps them agreeing with each other while abandoning every
        // value already on disk — still fails.
        assertEquals("light", V1AppTheme.LIGHT.storedAs)
        assertEquals("dark", V1AppTheme.DARK.storedAs)
        assertEquals("system", V1AppTheme.SYSTEM.storedAs)
    }

    @Test
    fun `both enums fall back to SYSTEM on nothing, and on nonsense`() {
        // The fallback is why a drift above is silent rather than a crash.
        assertEquals(V1AppTheme.SYSTEM, V1AppTheme.fromStored(null))
        assertEquals(SharedAppTheme.SYSTEM, SharedAppTheme.fromStored(null))
        assertEquals(V1AppTheme.SYSTEM, V1AppTheme.fromStored("DARK"))
        assertEquals(SharedAppTheme.SYSTEM, SharedAppTheme.fromStored("DARK"))
    }

    @Test
    fun `both sides name the theme with the same key`() {
        assertEquals(
            "the theme key drifted; v1's value would never be read back",
            constant(Class.forName("com.stationly.app.ui.theme.AppSettings"), "KEY_THEME"),
            AppSettings.KEY_THEME,
        )
        assertEquals("app_theme", AppSettings.KEY_THEME)
    }

    @Test
    fun `the dream reads the two files the shared storage writes`() {
        assertEquals(
            "the durable prefs file drifted — the app and the screensaver would " +
                "show different themes",
            constant(AndroidStorageManager::class.java, "DURABLE_PREFS"),
            AppSettings.DURABLE_FILE,
        )
        assertEquals(
            "the legacy prefs file drifted — every v1 user's theme is abandoned",
            constant(AndroidStorageManager::class.java, "PREFS"),
            AppSettings.LEGACY_FILE,
        )
        assertEquals("stationly_durable_prefs", AppSettings.DURABLE_FILE)
        assertEquals("StationlyPrefs", AppSettings.LEGACY_FILE)
    }

    /**
     * A `const val` read back off the class, as `V1V2StorageContractTest` does.
     *
     * Reflection rather than parsing the source: it survives reformatting and a
     * rewrite, and it fails loudly if the field is renamed — which is itself the
     * change worth being told about. Needed here because `:core`'s constants are
     * `internal` to a different module.
     */
    private fun constant(owner: Class<*>, name: String): String =
        owner.getDeclaredField(name).apply { isAccessible = true }.get(null) as String
}
