package com.stationly.mobile.service

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.stationly.core.session.AuthIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tell the rest of the app who is signed in.
 *
 * ## The bug this exists to end
 * The shared UI reads the identity out of storage — the avatar's monogram, the
 * profile screen's name, and the device-session upsert that tells the backend
 * which account this phone belongs to. Every one of those keys was written by
 * exactly one thing: `AuthBridge.swift`. On iOS.
 *
 * Android held the same values the entire time. `auth.currentUser` has the
 * display name, the email, the photo and the provider, and
 * `AndroidPlatformAuthProvider` even exposes all four. Nothing ever copied them
 * anywhere the shared code could see, so on Android:
 *
 *  - the home screen's avatar showed **"?"**, on every device, forever — not
 *    intermittently, which is what the "?" was designed to mean, and
 *  - `registerDeviceSession` posted the device session with an **empty email
 *    and no display name**, so the backend's per-device records were anonymous.
 *
 * Neither is a rendering fault and neither is fixable in the UI. The identity
 * was simply never published.
 *
 * ## Why a listener and not a write at sign-in
 * Sign-in is not the only way an account arrives. Firebase restores the session
 * itself on cold start, `updateProfile` changes the display name mid-session,
 * `reload()` pulls a changed photo, and a token refresh can revise provider
 * data. A listener catches the restore and every later revision; a write bolted
 * onto the login screen catches one of them and goes stale after that. iOS
 * learned this the same way and its AuthBridge listener is the model here.
 *
 * ## Order matters on sign-out
 * The clear runs on the `currentUser == null` edge, and it clears **exactly**
 * the keys a sign-in writes ([AuthIdentity.clearedEntries], asserted against
 * [AuthIdentity.entriesFor] in `AuthIdentityTest`). A key left behind is the
 * previous account's name greeting the next person to sign in on a shared
 * phone — silent, and only on a device that has had two people on it.
 */
object AuthIdentityPublisher {

    /**
     * `Unconfined`, deliberately, and it is about ORDERING rather than speed.
     *
     * Android's `saveString` is a `SharedPreferences.edit().apply()` wearing a
     * `suspend` modifier: no dispatcher switch, no disk wait on the calling
     * thread, nothing that can actually suspend. Unconfined therefore runs the
     * whole publish INLINE on the auth-state callback, so the identity is
     * readable the moment Firebase says the user changed.
     *
     * That matters because `UserStateSync.resetForNewSession()` re-reads the
     * uid to re-point the per-account stores, and it runs on both session
     * edges. Dispatching to `IO` left a window — small, and comfortably won in
     * practice, since several network calls sit between sign-in and that reset
     * — in which a sign-out followed by a different sign-in could re-point
     * those stores at the PREVIOUS account. That is the one hazard the uid
     * namespacing exists to prevent, and "comfortably won in practice" is how
     * this codebase has acquired most of its bugs.
     *
     * Not `runBlocking` on the callback thread, which would do the same job
     * today and become an ANR the day one of these writes genuinely suspends
     * (a DataStore migration, say). Unconfined degrades to asynchronous in that
     * case instead of freezing the main thread.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Volatile
    private var listener: FirebaseAuth.AuthStateListener? = null

    /**
     * Start mirroring `FirebaseAuth`'s user into the shared session store.
     *
     * Idempotent — called from `StationlyApplication.onCreate`, which runs once
     * per process, but a second call would otherwise double every write. Firebase
     * delivers the current state to a new listener immediately, so registering
     * here also covers the restored-session case with no separate cold-start
     * read.
     */
    fun start() {
        if (listener != null) return
        val auth = FirebaseAuth.getInstance()
        val l = FirebaseAuth.AuthStateListener { publish(it.currentUser) }
        listener = l
        auth.addAuthStateListener(l)
    }

    /** Re-publish now. For the paths that change the profile without an auth event. */
    fun refresh() = publish(FirebaseAuth.getInstance().currentUser)

    private fun publish(user: FirebaseUser?) {
        scope.launch {
            runCatching {
                if (user == null) {
                    AuthIdentity.clearedEntries().forEach { (key, value) ->
                        com.stationly.core.session.SessionStore.set(key, value)
                    }
                } else {
                    AuthIdentity.publish(
                        uid = user.uid,
                        email = user.email,
                        displayName = user.displayName,
                        photoUrl = user.photoUrl?.toString(),
                        provider = AuthIdentity.providerLabelFor(
                            user.providerData.map { it.providerId }
                        ),
                    )
                }
            }
        }
    }
}
