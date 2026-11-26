package repository

import com.stationly.core.repository.UserSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every Android user's board arrangement is in the `anon` namespace, and it has
 * to survive the day the uid starts resolving.
 *
 * ## How it got there
 * `UserSettings` namespaces its keys by uid, reading it from
 * `SessionStore.Key.UID` — `firebase_user_uid`. On iOS `AuthBridge.swift`
 * writes that key. On Android, until 2026-09-12, **nothing did**. So `load()`
 * resolved `NO_USER` on every Android launch and every arrangement anyone ever
 * made — card order, collapsed state, departures per platform, pins, the home
 * layout — was written to `board_configs_v2::anon` and `home_layout_v2::anon`.
 *
 * It worked, silently and consistently, because one namespace used forever is
 * indistinguishable from the right one.
 *
 * Publishing the identity fixed the avatar and the device session, and in the
 * same stroke made `load()` resolve a real uid for the first time — pointing it
 * at a namespace that has never existed. Without this, upgrading would hand
 * every Android user a home screen with their boards in default order, nothing
 * pinned, and their layout reset. Observed on the Pixel: the real arrangement
 * sat in `::anon` while a fresh empty bucket appeared beside it under the uid.
 *
 * ## The rule, and the one it must not become
 * Adopt `anon` for an account that has none of its own, ONCE, and then the
 * anon namespace is gone. It must not become "a signed-in account with no
 * settings inherits whatever is in anon", because on a shared phone the second
 * person to sign in would then inherit the first person's arrangement — the
 * exact thing the namespacing exists to prevent.
 */
class AnonAdoptionTest {

    private val uid = "KsMe1BKEfUVxc7wlsuerGIoJv0y2"
    private val stored = """{"940GZZDLBNK":{"rowsPerPlatform":5}}"""

    /** **The upgrade this exists for.** */
    @Test
    fun `an account with nothing of its own takes over the anon namespace`() {
        assertEquals(stored, UserSettings.adoptionSource(uid, perUid = null, anon = stored))
    }

    /**
     * The second person on a shared phone. Their own namespace is absent too,
     * but anon was consumed by the first sign-in and is gone — so there is
     * nothing to inherit, which is the whole point.
     */
    @Test
    fun `once anon is consumed there is nothing left to inherit`() {
        assertNull(UserSettings.adoptionSource("someone-else", perUid = null, anon = null))
    }

    /**
     * An account that already has its own settings is never overwritten, even
     * if an anon namespace somehow still exists. Adoption is a one-way rescue
     * of orphaned values, not a merge.
     */
    @Test
    fun `an account that already has settings is left alone`() {
        assertNull(UserSettings.adoptionSource(uid, perUid = """{"x":{}}""", anon = stored))
    }

    /**
     * An account whose settings are legitimately EMPTY — everything at its
     * default, so every row pruned — still counts as having its own. `"{}"` is
     * an answer; absent is the absence of one, and only the second adopts.
     */
    @Test
    fun `an empty-but-present namespace is an answer, not a gap`() {
        assertNull(UserSettings.adoptionSource(uid, perUid = "{}", anon = stored))
    }

    /**
     * Signed out, we ARE anon: reading and writing it directly is correct, and
     * adopting it into itself would be a no-op at best.
     */
    @Test
    fun `signed out adopts nothing`() {
        assertNull(UserSettings.adoptionSource(UserSettings.NO_USER, perUid = null, anon = stored))
        assertNull(UserSettings.adoptionSource("", perUid = null, anon = stored))
    }

    /** A blank anon value is not a value. */
    @Test
    fun `a blank anon namespace is nothing to adopt`() {
        assertNull(UserSettings.adoptionSource(uid, perUid = null, anon = "   "))
    }
}
