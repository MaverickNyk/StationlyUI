package com.stationly.mobile.host

import com.stationly.core.platform.AndroidStorageManager
import com.stationly.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

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
 * ## What AV2-6.2 changed here
 * This test used to compare TWO implementations: v1's `AppSettings` enum, key
 * and prefs files against the shared ones. v1's theme package is deleted — the
 * screensaver was its last reader, and the screensaver is the shared
 * `StationlyThemeHost` now, so the app and the dream cannot disagree about a
 * theme because there is one reader.
 *
 * So this is one-sided now, against the names on disk rather than against a
 * second implementation — the same shape `V1V2StorageContractTest` uses, and for
 * the same reason. **The data did not go anywhere.** Every v1 install has a
 * theme under these names, and the ways for the carry-over to break are all
 * renames rather than bugs:
 *
 *  1. The stored strings change meaning. `"dark"` becoming `"DARK"` resets every
 *     upgrading user to system, and `fromStored` swallows it — that is what its
 *     fallback is for.
 *  2. The key changes.
 *  3. A prefs FILE changes. Renaming `StationlyPrefs` does not migrate the app's
 *     history; it abandons it.
 */
class V1ThemeCarryOverTest {

    @Test
    fun `the stored theme strings are the ones v1 wrote`() {
        // Spelled out rather than compared, now that there is one enum. A
        // rename here reads every upgrading user's value as nonsense, and
        // `fromStored` turns that into SYSTEM without complaining.
        assertEquals("light", AppTheme.LIGHT.storedAs)
        assertEquals("dark", AppTheme.DARK.storedAs)
        assertEquals("system", AppTheme.SYSTEM.storedAs)
    }

    @Test
    fun `an unreadable value falls back to SYSTEM rather than throwing`() {
        // The fallback is why a drift above is silent rather than a crash, which
        // is exactly why the assertions above have to be explicit.
        assertEquals(AppTheme.SYSTEM, AppTheme.fromStored(null))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromStored("DARK"))
    }

    @Test
    fun `the theme is named by the key v1 used`() {
        assertEquals(
            "app_theme",
            constant(Class.forName("com.stationly.app.ui.theme.AppSettings"), "KEY_THEME"),
        )
    }

    @Test
    fun `the durable-then-legacy fallback reads the two files v1 and v2 write`() {
        // `loadDurable` reads the first, `loadString` the second. The second is
        // where every v1 install's theme still is, so the fallback IS the
        // upgrade path — see the class KDoc.
        assertEquals(
            "the durable prefs file drifted — settings that must survive a logout would not",
            "stationly_durable_prefs",
            constant(AndroidStorageManager::class.java, "DURABLE_PREFS"),
        )
        assertEquals(
            "the legacy prefs file drifted — every v1 user's theme is abandoned",
            "StationlyPrefs",
            constant(AndroidStorageManager::class.java, "PREFS"),
        )
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
