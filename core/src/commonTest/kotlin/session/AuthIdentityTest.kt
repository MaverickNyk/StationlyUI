package session

import com.stationly.core.session.AuthIdentity
import com.stationly.core.session.SessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who the app thinks you are, and the one place that decides it.
 *
 * ## The bug this was written for
 * The home screen's avatar showed **"?"** on every Android device, always. The
 * shared UI reads the identity from storage keys — `firebase_user_display_name`,
 * `firebase_user_email`, `firebase_user_photo_url` — and the only thing that had
 * ever written them was iOS's `AuthBridge.swift`. Android had the values the
 * whole time, on `FirebaseAuth.currentUser`, and never published them.
 *
 * It was not only the avatar. `SummaryViewModel.registerDeviceSession` reads the
 * same keys to upsert this device's session, so every Android device registered
 * itself with a blank email and no display name, and the account's device list
 * had nothing to show for it.
 *
 * These pin the rules, not the plumbing: what a monogram is, and which keys an
 * identity is made of. `AndroidAuthIdentityPublisher` is the writer, and it
 * cannot be unit-tested without FirebaseAuth — but it cannot get the CONTENT
 * wrong without failing this file.
 *
 * Test names avoid commas: Kotlin/Native rejects them inside backticks.
 */
class AuthIdentityTest {

    // ── the monogram ────────────────────────────────────────────────────────

    @Test
    fun `a display name gives its first letter`() {
        assertEquals("N", AuthIdentity.monogram("Nikhil Kumar"))
        assertEquals("S", AuthIdentity.monogram("stationly"))
    }

    @Test
    fun `an email address gives its first letter too`() {
        // The fallback when a provider gives no display name — email sign-up,
        // and Google accounts that have never set one.
        assertEquals("A", AuthIdentity.monogram("alice@example.com"))
    }

    /**
     * The FIRST LETTER, not the first character. A name that starts with a digit
     * or a symbol would otherwise put "1" or "@" in the avatar, which reads as a
     * rendering bug rather than as a monogram.
     */
    @Test
    fun `leading digits and punctuation are skipped`() {
        assertEquals("B", AuthIdentity.monogram("123 Bus Rider"))
        assertEquals("K", AuthIdentity.monogram("  _kumar"))
    }

    @Test
    fun `it is upper case whatever was stored`() {
        assertEquals("N", AuthIdentity.monogram("nikhil"))
    }

    /**
     * "?" is the honest answer to "I do not know yet", and it is what the avatar
     * showed on Android for every session. It must stay reachable — a cold start
     * races the identity write — but it must mean "not loaded", never "loaded
     * and empty".
     */
    @Test
    fun `nothing to go on falls back to the question mark`() {
        assertEquals("?", AuthIdentity.monogram(null))
        assertEquals("?", AuthIdentity.monogram(""))
        assertEquals("?", AuthIdentity.monogram("   "))
        assertEquals("?", AuthIdentity.monogram("123"))
    }

    // ── what an identity is made of ─────────────────────────────────────────

    @Test
    fun `a signed-in identity publishes every key the shared UI reads`() {
        val entries = AuthIdentity.entriesFor(
            uid = "abc123",
            email = "alice@example.com",
            displayName = "Alice",
            photoUrl = "https://example.com/a.png",
            provider = "google.com",
        ).toMap()

        assertEquals("abc123", entries[SessionStore.Key.UID])
        assertEquals("alice@example.com", entries[SessionStore.Key.EMAIL])
        assertEquals("Alice", entries[SessionStore.Key.DISPLAY_NAME])
        assertEquals("https://example.com/a.png", entries[SessionStore.Key.PHOTO_URL])
        assertEquals("google.com", entries[SessionStore.Key.SIGNIN_PROVIDER])
    }

    /**
     * A provider that gives no name or photo is normal — email sign-up gives
     * neither. The keys are still WRITTEN, as blanks, because a stale value from
     * a previous account is worse than an empty one: `SessionStore.get` already
     * reads blank as absent.
     */
    @Test
    fun `absent fields are published as blanks rather than skipped`() {
        val entries = AuthIdentity.entriesFor(
            uid = "abc123",
            email = "alice@example.com",
            displayName = null,
            photoUrl = null,
            provider = "password",
        ).toMap()

        assertTrue(entries.containsKey(SessionStore.Key.DISPLAY_NAME))
        assertTrue(entries.containsKey(SessionStore.Key.PHOTO_URL))
        assertEquals("", entries[SessionStore.Key.DISPLAY_NAME])
        assertEquals("", entries[SessionStore.Key.PHOTO_URL])
    }

    /**
     * The set is the same on the way out, which is what stops one account's name
     * greeting the next person to sign in on this device.
     */
    @Test
    fun `signing out clears exactly the keys signing in wrote`() {
        val signedIn = AuthIdentity.entriesFor(
            uid = "abc123",
            email = "alice@example.com",
            displayName = "Alice",
            photoUrl = null,
            provider = "google.com",
        ).map { it.first }.toSet()

        assertEquals(signedIn, AuthIdentity.clearedEntries().map { it.first }.toSet())
        assertTrue(AuthIdentity.clearedEntries().all { it.second == null })
    }

    // ── the provider label ───────────────────────────────────────────────────

    @Test
    fun `firebase provider ids become the labels the profile screen shows`() {
        assertEquals("Google", AuthIdentity.providerLabel("google.com"))
        assertEquals("Apple", AuthIdentity.providerLabel("apple.com"))
        assertEquals("Email", AuthIdentity.providerLabel("password"))
    }

    @Test
    fun `an unknown or absent provider is Email, which is the one you can always sign in with`() {
        assertEquals("Email", AuthIdentity.providerLabel(null))
        assertEquals("Email", AuthIdentity.providerLabel(""))
        assertEquals("Email", AuthIdentity.providerLabel("facebook.com"))
    }

    @Test
    fun `the label round-trips back to the id the backend wants`() {
        // registerDeviceSession maps the STORED label back to a raw id before
        // it posts. The two directions were written 900 lines and one language
        // apart; this is the assertion that they agree.
        listOf("google.com", "apple.com", "password").forEach { raw ->
            val label = AuthIdentity.providerLabel(raw)
            assertEquals(
                if (raw == "password") "email" else raw,
                AuthIdentity.providerIdFor(label),
                "round trip of $raw via $label",
            )
        }
    }
}
