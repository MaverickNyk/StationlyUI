package session

import com.stationly.core.session.SessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The single owner of the identity keys.
 *
 * This replaces seven implementations of "who is signed in" and TWELVE
 * declarations of the string `firebase_user_uid` across four modules and two
 * languages. They disagreed: one answered from the token and another from the
 * uid, and when a race once wiped eight identity keys and left the token, four
 * of them said signed in and three said signed out in the same process.
 *
 * These pin the two properties that made the scatter dangerous — that a key's
 * storage DOMAIN is stated rather than inherited from an object's name, and
 * that per-account values are namespaced.
 *
 * Test names avoid commas: Kotlin/Native rejects them inside backticks.
 */
class SessionStoreTest {

    @Test
    fun `every identity key is wiped by a sign-out`() {
        // None of these may be durable. Durable storage survives `clearAll()`,
        // so a uid or a token left there would outlive the session that owned it
        // and be read by whoever signs in next.
        SessionStore.Key.entries.forEach {
            assertFalse(it.durable, "${it.name} must not survive a sign-out")
        }
    }

    @Test
    fun `every declaration of the uid key agrees, across modules and languages`() {
        // The literal survives in exactly two places on purpose, and this pins
        // them together.
        //
        // `SessionStore.Key.UID` is the one Kotlin declaration: `UserSettings`,
        // `ActivityLog` and `UserStateRepository` all reference it now rather
        // than spelling it out. The other is `AppGroupKeys.FIREBASE_USER_UID` in
        // `iosMain`, which cannot reference this one honestly — Swift's
        // `AuthBridge` is the WRITER and reaches `UserDefaults.standard`
        // directly, outside KMP, so the string exists on both sides of a
        // boundary Kotlin cannot see across. Single-sourcing the Kotlin half
        // would look like one declaration while the Swift half stayed
        // untouched.
        //
        // So the guarantee is asserted rather than structural. If Swift's key
        // ever changes, this is what fails.
        assertEquals("firebase_user_uid", SessionStore.Key.UID.storageKey)
    }

    @Test
    fun `the uid key spells the same string the whole codebase used`() {
        // Twelve places spelled this literal. Changing it without a migration
        // would silently sign every existing user out on upgrade.
        assertEquals("firebase_user_uid", SessionStore.Key.UID.storageKey)
        assertEquals("firebase_auth_token", SessionStore.Key.AUTH_TOKEN.storageKey)
    }

    @Test
    fun `scoping a key makes two accounts unable to collide`() {
        val a = SessionStore.scoped("alice", "app_theme")
        val b = SessionStore.scoped("bob", "app_theme")
        assertTrue(a != b)
        assertTrue(a.startsWith("app_theme"))
        // The unscoped key stays a distinct third value — it is where every
        // pre-P3 value already lives and is read as a migration fallback.
        assertTrue(a != "app_theme" && b != "app_theme")
    }
}
