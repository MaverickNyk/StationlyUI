package com.stationly.core.session

/**
 * What "who is signed in" is made of, and how it is shown.
 *
 * ## Why this exists
 * [SessionStore] owns the identity KEYS; this owns their CONTENT. The two rules
 * here were each living somewhere that could not see the other:
 *
 *  - the **monogram** was an inline expression in `SummaryViewModel`, so nothing
 *    named it, tested it, or could reuse it, and
 *  - the **set of keys an identity consists of** existed only as four
 *    consecutive `UserDefaults.set` calls inside `AuthBridge.swift` — which is
 *    iOS-only code, and therefore a definition Android could not reach.
 *
 * That second one was not academic. The shared UI reads these keys, Swift was
 * the only writer, and so every Android device showed **"?"** for its avatar,
 * forever, while holding the real name on `FirebaseAuth.currentUser` the whole
 * time. The same keys feed `registerDeviceSession`, so Android devices also
 * registered their backend session with a blank email and no display name.
 *
 * Stating the set here means a platform can publish an identity without
 * rediscovering which keys that involves, and `AuthIdentityTest` fails if the
 * two halves ever disagree about the answer.
 */
object AuthIdentity {

    /**
     * The letter an avatar falls back to when there is no photo.
     *
     * The first LETTER, not the first character: a name beginning with a digit
     * or punctuation would otherwise put "1" or "@" in the circle, which reads
     * as a rendering fault rather than as somebody's initial.
     *
     * "?" means **"not loaded yet"** and is deliberately reachable — a cold start
     * can out-run the identity write and the avatar has to draw something. It
     * must never come to mean "loaded, and empty".
     */
    fun monogram(displayNameOrEmail: String?): String =
        displayNameOrEmail?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "?"

    /**
     * Everything a signed-in session publishes.
     *
     * Absent fields are written as BLANKS rather than skipped. A provider that
     * gives no display name or photo is ordinary — email sign-up gives neither —
     * and leaving the key untouched would leave the PREVIOUS account's name in
     * place for the next person to sign in on this device. `SessionStore.get`
     * already reads blank as absent, so writing one costs nothing and closes
     * that.
     */
    fun entriesFor(
        uid: String,
        email: String?,
        displayName: String?,
        photoUrl: String?,
        provider: String?,
    ): List<Pair<SessionStore.Key, String?>> = listOf(
        SessionStore.Key.UID to uid,
        SessionStore.Key.EMAIL to email.orEmpty(),
        SessionStore.Key.DISPLAY_NAME to displayName.orEmpty(),
        SessionStore.Key.PHOTO_URL to photoUrl.orEmpty(),
        SessionStore.Key.SIGNIN_PROVIDER to provider.orEmpty(),
    )

    /**
     * The same keys, emptied.
     *
     * Exactly the set [entriesFor] writes, which is the property that stops one
     * account's name greeting the next person — and is asserted rather than
     * assumed, because the two lists are the kind that drift apart one field at
     * a time.
     */
    fun clearedEntries(): List<Pair<SessionStore.Key, String?>> =
        entriesFor(uid = "", email = null, displayName = null, photoUrl = null, provider = null)
            .map { it.first to null }

    /** Publish [entriesFor] through [SessionStore]. */
    suspend fun publish(
        uid: String,
        email: String?,
        displayName: String?,
        photoUrl: String?,
        provider: String?,
    ) {
        entriesFor(uid, email, displayName, photoUrl, provider)
            .forEach { (key, value) -> SessionStore.set(key, value) }
    }

    // ── the sign-in provider ─────────────────────────────────────────────────

    /**
     * Firebase's provider id, in the words a person reads.
     *
     * Anything unrecognised — including a null, which is what an account with
     * no provider data gives — is "Email". That is the one door every account
     * can go back through: an unknown provider labelled "Email" sends someone
     * to a screen where they can reset a password, while a blank or a raw
     * "facebook.com" sends them nowhere.
     */
    fun providerLabel(rawProviderId: String?): String = when (rawProviderId) {
        "google.com" -> "Google"
        "apple.com" -> "Apple"
        else -> "Email"
    }

    /**
     * The inverse, for the backend, which wants the raw id back.
     *
     * `SummaryViewModel.registerDeviceSession` was doing this with its own
     * inline `when` over the stored label. Two mappings in opposite directions
     * that must agree, written in different files and different languages, is
     * the shape that drifts — so they are adjacent here and
     * `AuthIdentityTest` walks the round trip.
     */
    fun providerIdFor(label: String?): String = when (label) {
        "Google" -> "google.com"
        "Apple" -> "apple.com"
        else -> "email"
    }
}
