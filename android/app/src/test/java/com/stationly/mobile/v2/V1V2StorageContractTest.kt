package com.stationly.mobile.v2

import com.stationly.app.platform.DeviceIdentity
import com.stationly.app.platform.NotificationPermissionStore
import com.stationly.app.platform.ModeIconStore
import com.stationly.app.platform.modeIconFileName
import com.stationly.mobile.service.DeviceIdProvider
import com.stationly.mobile.util.ModeIconCache
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1 and v2 share storage, and nothing but this test says so.
 *
 * `:composeApp` cannot import `:android:app` — the dependency runs the other
 * way — so the shared UI's `actual`s reach the shipped app's data by writing to
 * the same *file names*, not by calling the same code. Two implementations, one
 * directory, agreeing by convention. This is the only module that can see both,
 * and it can only see both on the staging flavour, which is where the shared UI
 * lives.
 *
 * The failures this catches are all silent:
 *
 * - Change `DeviceIdProvider`'s preferences file and every v1 user opening the
 *   shared UI is issued a **new device id**. The backend keys its `sessions` map
 *   by that id and releases a station's subscription only when the last device
 *   signs out, so the old session becomes a ghost that logout can never clear —
 *   holding subscriptions for a device that no longer exists. iOS lost two days
 *   to this exact shape.
 * - Change `ModeIconCache`'s directory or its filename sanitisation and the two
 *   caches quietly stop finding each other's PNGs. The shared UI re-downloads
 *   everything into a second set of files; the home-screen widget carries on
 *   reading the first and renders untinted fallbacks.
 * - Change where the notification "we asked" flag lives and every user who has
 *   already answered reads back as NOT_DETERMINED. The shared effect then calls
 *   `requestNotificationAuthorization()`, and Android — which never re-shows a
 *   dialog it has already shown — returns the standing answer with no UI at
 *   all. Nothing appears; nothing is logged. Meanwhile a user who had DENIED
 *   stops seeing the banner explaining why no alerts arrive, because their
 *   state now reads as undecided.
 *
 * None of these shows up as an error, on either side. All of them show up here.
 *
 * AV2-3.5 deletes the v1 halves, and with them this test.
 */
class V1V2StorageContractTest {

    @Test
    fun `both device identities read the same preferences file and key`() {
        assertEquals(
            "the SharedPreferences file name drifted between v1 and the shared UI",
            constant(DeviceIdProvider::class.java, "PREFS"),
            constant(DeviceIdentity::class.java, "PREFS"),
        )
        assertEquals(
            "the device-id key drifted between v1 and the shared UI",
            constant(DeviceIdProvider::class.java, "KEY"),
            constant(DeviceIdentity::class.java, "KEY"),
        )
        // Spelled out as well as compared, so a change to BOTH sides at once —
        // which would keep them agreeing with each other while abandoning every
        // id already on disk — still fails.
        assertEquals("StationlyDevice", constant(DeviceIdentity::class.java, "PREFS"))
        assertEquals("device_id", constant(DeviceIdentity::class.java, "KEY"))
    }

    @Test
    fun `both mode icon caches use the same directory and side files`() {
        assertEquals(
            "the mode-icon directory drifted between v1 and the shared UI",
            constant(ModeIconCache::class.java, "DIR"),
            constant(ModeIconStore::class.java, "DIR"),
        )
        assertEquals(
            constant(ModeIconCache::class.java, "TINTS_FILE"),
            constant(ModeIconStore::class.java, "TINTS_FILE"),
        )
        assertEquals(
            constant(ModeIconCache::class.java, "VERSION_FILE"),
            constant(ModeIconStore::class.java, "VERSION_FILE"),
        )
        assertEquals("mode_icons", constant(ModeIconStore::class.java, "DIR"))
    }

    @Test
    fun `both mode icon caches sanitise a mode name to the same file name`() {
        // ModeIconCache.safeName is private, so it is reached the same way as
        // the constants. Comparing behaviour rather than source text: this stays
        // true through a rewrite of either side, and false the moment they
        // disagree about a single character.
        val v1 = ModeIconCache::class.java.getDeclaredMethod("safeName", String::class.java)
            .apply { isAccessible = true }

        listOf(
            "tube", "Tube", "TUBE",
            "national-rail", "elizabeth-line", "dlr", "overground", "bus", "tram",
            "National Rail", "cable/car", "Thames Clipper (RB1)", "",
        ).forEach { mode ->
            assertEquals(
                "v1 and the shared UI would look for different files for: \"$mode\"",
                v1.invoke(ModeIconCache, mode) as String,
                modeIconFileName(mode),
            )
        }
    }

    @Test
    fun `both sides remember the notification prompt in the same place`() {
        // v1 keeps these as private top-level consts, so the owner is the file
        // facade class rather than a type this module can name in source.
        val v1 = Class.forName("com.stationly.mobile.ui.common.NotificationPermissionEffectKt")
        val v2 = NotificationPermissionStore::class.java

        assertEquals(
            "the preferences file for the notification flag drifted",
            constant(v1, "PREFS"),
            constant(v2, "PREFS"),
        )
        assertEquals(
            "the \"we asked\" key drifted — every decided user reads as NOT_DETERMINED",
            constant(v1, "KEY_ASKED"),
            constant(v2, "KEY_ASKED"),
        )
        assertEquals(
            "the \"last granted\" key drifted",
            constant(v1, "KEY_LAST_GRANTED"),
            constant(v2, "KEY_LAST_GRANTED"),
        )
        // Spelled out too, so changing BOTH sides at once — which keeps them
        // agreeing with each other while abandoning every flag already on disk
        // — still fails.
        assertEquals("StationlyPrefs", constant(v2, "PREFS"))
        assertEquals("post_notifications_asked", constant(v2, "KEY_ASKED"))
        assertEquals("post_notifications_granted", constant(v2, "KEY_LAST_GRANTED"))
    }

    /**
     * A `private const val` on a Kotlin object, read back off the class.
     *
     * Reflection rather than parsing the source: it survives reformatting and a
     * rewrite, and it fails loudly if the field is renamed — which is itself the
     * change worth being told about.
     */
    private fun constant(owner: Class<*>, name: String): String =
        owner.getDeclaredField(name).apply { isAccessible = true }.get(null) as String
}
