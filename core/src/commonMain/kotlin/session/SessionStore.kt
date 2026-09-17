package com.stationly.core.session

import com.stationly.core.platform.Platform
import com.stationly.core.platform.StorageManager

/**
 * THE answer to "who is signed in".
 *
 * ## What this replaces
 * Seven implementations, and the string `firebase_user_uid` written out as a
 * literal or a separate constant in **twelve places across four modules and two
 * languages**. They did not agree with each other:
 * `IosPlatformAuthProvider.isLoggedIn()` answered from the TOKEN while
 * `UserSyncBridge.currentUid()` answered from the UID, which is exactly the torn
 * state `discardTornIdentity` existed to clean up afterwards. When one race
 * wiped eight identity keys and left the token behind, four of them said signed
 * in and three said signed out **in the same process**.
 *
 * The point of this object is not tidiness. It makes that state
 * unrepresentable rather than merely repairable.
 *
 * ## The domain is named per key, never inherited
 * The previous fifth declaration lived on `AppGroupKeys` — an object whose name
 * says App Group and half of whose contents are in `UserDefaults.standard`.
 * Reading the uid from the group suite once silently disabled cross-device sync
 * entirely, and it failed in the safe-looking direction: a reconcile that never
 * runs looks exactly like a reconcile with nothing to do.
 *
 * So every key below states its own domain in [Key.durable], and callers never
 * choose. [DURABLE] survives a sign-out; [SESSION] does not.
 *
 * ## Not a cache
 * Reads go to storage every time. The values are small, the reads are rare
 * (login, logout, a reconcile), and a cached copy is a ninth answer to the
 * question this object exists to have one answer to.
 */
object SessionStore {

    /**
     * One identity key, and where it lives.
     *
     * @param durable true when the value must OUTLIVE a sign-out. Logout wipes
     *   the app's ordinary defaults domain, which is right for anything naming
     *   the user and wrong for the things that make the app look the way they
     *   left it.
     */
    enum class Key(val storageKey: String, val durable: Boolean) {
        /** The signed-in account. Wiped by sign-out — a stale uid must never outlive a session. */
        UID("firebase_user_uid", durable = false),

        /**
         * The cached Firebase ID token.
         *
         * ⚠️ A CACHE, not an authority. Firebase ID tokens live about an hour,
         * and this key is written opportunistically. Anything that needs a
         * bearer for an outbound request must mint one through the platform's
         * token authority instead — reading this directly is what made
         * `DevicePushCoordinator` send dead bearers, and later skip registration
         * entirely, so a device never got its push tokens back after a sign-out.
         */
        AUTH_TOKEN("firebase_auth_token", durable = false),

        EMAIL("firebase_user_email", durable = false),
        DISPLAY_NAME("firebase_user_display_name", durable = false),
        PHOTO_URL("firebase_user_photo_url", durable = false),
        SIGNIN_PROVIDER("signin_provider", durable = false),
        MEMBER_SINCE("member_since", durable = false),
    }

    private val storage: StorageManager get() = Platform.storageManager

    suspend fun get(key: Key): String? {
        val raw = if (key.durable) storage.loadDurable(key.storageKey) else storage.loadString(key.storageKey)
        return raw?.takeIf { it.isNotBlank() }
    }

    suspend fun set(key: Key, value: String?) {
        val v = value.orEmpty()
        if (key.durable) storage.saveDurable(key.storageKey, v) else storage.saveString(key.storageKey, v)
    }

    /** The signed-in account id, or null. The one question, asked one way. */
    suspend fun uid(): String? = get(Key.UID)

    /**
     * Whether a session exists, judged on the UID alone.
     *
     * Deliberately NOT "the token is non-blank", which is how one of the seven
     * implementations answered it. A token can outlive the identity keys and an
     * identity can exist while the token is momentarily absent; only one of the
     * two names the account, and that is the thing every caller actually wants.
     */
    suspend fun hasSession(): Boolean = uid() != null

    /**
     * Namespace a per-account preference key.
     *
     * Anything kept per user goes through this rather than spelling the uid into
     * a string at the call site. Two accounts on one device otherwise share a
     * key, and the second user inherits the first user's settings — silently,
     * and only on a device that has had two people signed in, which is exactly
     * the case nobody tests.
     */
    fun scoped(uid: String, key: String): String = "$key:$uid"
}
