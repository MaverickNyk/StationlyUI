package com.stationly.mobile.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A widget belongs to the account that placed it.
 *
 * ## The bug, found by AV2-9.7
 * `widget_prefs` is not namespaced by uid, unlike `UserSettings`, and no
 * sign-out path clears it: `cleanupAll` and `FirebaseAuthManager.logout` wipe
 * SQL, topics, widget CONTENT and `StationlyPrefs`, and none of them touches
 * the widget's own file.
 *
 * So a binding survives a logout. If the next account happens to track the same
 * hub, the widget silently re-attaches and comes back live in the PREVIOUS
 * person's configuration: their nav mode, their platform page, their pin. TfL
 * naptans are public and two Londoners both tracking King's Cross is ordinary
 * rather than exotic, so this is not a corner.
 *
 * Worse on the forced path: `signOutFromAuthExpiry` on Android is
 * `auth.signOut()` and a log line, with no teardown at all, so an expired
 * session leaves the previous account's board on the home screen.
 *
 * ## Why the obvious fix is wrong
 * Clearing `widget_prefs` on sign-out un-configures every widget for the COMMON
 * case, which is somebody signing back in as themselves. The widget would come
 * back blank and they would have to set it up again for no reason they can see.
 *
 * So the binding is STAMPED rather than cleared. Signing back in as yourself
 * matches the stamp and restores everything; another account does not match and
 * gets the honest empty state.
 */
class WidgetOwnershipTest {

    @Test
    fun `the account that placed it owns it`() {
        assertTrue(WidgetBindingStore.isOwnedBy(owner = "uidA", current = "uidA"))
    }

    /** **The bug.** A different account must not inherit the widget. */
    @Test
    fun `another account does not`() {
        assertFalse(WidgetBindingStore.isOwnedBy(owner = "uidA", current = "uidB"))
    }

    /**
     * A widget placed before this stamp existed has no owner. It belongs to
     * whoever is signed in now, because the alternative is blanking every
     * widget on every phone that upgrades. It gets stamped on first read, so
     * the NEXT account change is caught properly.
     */
    @Test
    fun `a widget from before the stamp belongs to whoever is here`() {
        assertTrue(WidgetBindingStore.isOwnedBy(owner = null, current = "uidA"))
        assertTrue(WidgetBindingStore.isOwnedBy(owner = "", current = "uidA"))
    }

    /**
     * Signed out, or mid-transition where the uid has not landed yet, is NOT a
     * mismatch. Rejecting on an absent current uid would unbind every widget
     * during the window between a launch and the identity being published,
     * which is a race the user would see as their widgets blinking empty.
     *
     * Only reject when both are known and they differ.
     */
    @Test
    fun `an unknown current account is not a mismatch`() {
        assertTrue(WidgetBindingStore.isOwnedBy(owner = "uidA", current = null))
        assertTrue(WidgetBindingStore.isOwnedBy(owner = "uidA", current = ""))
        assertTrue(WidgetBindingStore.isOwnedBy(owner = null, current = null))
    }

    @Test
    fun `the owner key is per widget, beside the binding`() {
        assertEquals("owner_6", WidgetBindingStore.ownerKeyFor(6))
        assertEquals("owner_11", WidgetBindingStore.ownerKeyFor(11))
    }

    private fun assertEquals(a: String, b: String) = org.junit.Assert.assertEquals(a, b)
}
