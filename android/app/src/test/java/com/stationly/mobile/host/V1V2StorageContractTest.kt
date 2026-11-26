package com.stationly.mobile.host

import com.stationly.app.platform.DeviceIdentity
import com.stationly.app.platform.NotificationPermissionStore
import com.stationly.app.platform.ModeIconStore
import com.stationly.app.platform.modeIconFileName
import com.stationly.mobile.util.ModeIconCache
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared UI reaches the shipped app's data by writing to the same *file
 * names*, and nothing but this test says so.
 *
 * `:composeApp` cannot import `:android:app` — the dependency runs the other
 * way — so the two sides agree by convention rather than by calling the same
 * code. Two implementations, one directory. This is the only module that can
 * see both.
 *
 * ## What the cutover changed here
 * AV2-3.5 deleted v1's UI, so the *notification* half of this test went with
 * it: `NotificationPermissionEffect` was a composable on v1's summary screen and
 * has no successor to compare against. Its spelled-out assertions stay, and they
 * are the half that mattered — the flag on disk was written by v1 installs that
 * are still out there, so `NotificationPermissionStore` has to keep reading the
 * name v1 used whether or not v1's code still exists to be compared with.
 *
 * `ModeIconCache` is the one live two-sided contract left: the home-screen
 * widget still reads its PNGs, so v1's cache and the shared UI's have to agree
 * about a directory and a filename until EPIC-05 retires one of them.
 * `DeviceIdProvider` was the other, and AV2-4.4 deleted it — its assertion
 * below is now one-sided, against the names on disk rather than against a second
 * implementation.
 *
 * The failures this catches are all silent:
 *
 * - Change `DeviceIdentity`'s preferences file and every v1 user opening the
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
 */
class V1V2StorageContractTest {

    @Test
    fun `the device id is read from where v1 left it`() {
        // v1's half of this comparison — `DeviceIdProvider` — was deleted in
        // AV2-4.4, so this is now one-sided, the same shape as the notification
        // assertion below and for the same reason. The data did not go anywhere:
        // every install that has ever run this app has an id under these names,
        // and the backend keys its `sessions` map by it.
        //
        // The two objects agreed, which is why deleting one was safe. Keeping
        // both was not: each could MINT an id, on a path (logout) that runs
        // while the app is being torn down, and a second id means a session no
        // logout can ever release — a ghost holding station subscriptions for a
        // device that does not exist. iOS lost two days to that exact shape.
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
    fun `the notification prompt is remembered where v1 left it`() {
        // v1's half of this comparison — `NotificationPermissionEffectKt`'s
        // private consts — died with v1's summary screen at AV2-3.5. What did
        // NOT die is the data: every install that has already answered the
        // POST_NOTIFICATIONS prompt has these keys on disk under these names.
        //
        // Change them and every decided user reads back as NOT_DETERMINED. The
        // shared effect then calls `requestNotificationAuthorization()`, and
        // Android — which never re-shows a dialog it has already shown —
        // returns the standing answer with no UI at all. Nothing appears;
        // nothing is logged. Meanwhile a user who had DENIED stops seeing the
        // banner explaining why no alerts arrive, because their state now reads
        // as undecided.
        //
        // So this is now a one-sided assertion against recorded history, which
        // is the correct shape once one of the two implementations is gone.
        val store = NotificationPermissionStore::class.java
        assertEquals("StationlyPrefs", constant(store, "PREFS"))
        assertEquals("post_notifications_asked", constant(store, "KEY_ASKED"))
        assertEquals("post_notifications_granted", constant(store, "KEY_LAST_GRANTED"))
    }

    /**
     * A `const val` on a Kotlin object, read back off the class.
     *
     * Reflection rather than parsing the source: it survives reformatting and a
     * rewrite, and it fails loudly if the field is renamed — which is itself the
     * change worth being told about.
     */
    private fun constant(owner: Class<*>, name: String): String =
        owner.getDeclaredField(name).apply { isAccessible = true }.get(null) as String
}
