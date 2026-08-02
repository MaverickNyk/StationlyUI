package com.stationly.app.util

import com.stationly.app.platform.DeviceIdentity
import com.stationly.core.platform.Platform
import com.stationly.core.service.NetworkModule

/**
 * Pushes this device's FCM registration token to the backend so that
 * `uid`-targeted notifications can be resolved against it. Port of Android
 * `service/FcmTokenRegistrar.kt`.
 *
 * **Why this exists:** until 2026-07-25 iOS registered the token in exactly
 * ONE place — the explicit login flow in `LoginViewModel`. That leaves two
 * holes Android doesn't have:
 *
 *  1. **Keychain-restored sessions never register.** Firebase's iOS session
 *     survives app deletion in the keychain, so a reinstalled app opens
 *     straight into Summary without passing through login — and the backend
 *     never learns the new install's token.
 *  2. **Token rotation never propagates.** FCM rotates tokens on its own
 *     schedule, and invalidates every existing token if the Firebase app
 *     registration is deleted and recreated. The backend keeps sending to the
 *     dead ones and every push fails with
 *     `messaging/registration-token-not-registered`.
 *
 * Both were observed live: the backend held three stale tokens and delivered
 * zero pushes while the device sat there holding a perfectly valid one.
 *
 * Idempotent and cheap: the last successfully-registered `(token, uid)` pair
 * is cached, so the common case ("same token, same user") costs one string
 * comparison and no network. The server's `set(merge)` would no-op anyway —
 * this just saves the round-trip and the auth-header generation.
 *
 * Best-effort by design (Android invariant #2, "FCM token register is
 * opportunistic, not blocking"): every failure is swallowed and retried on the
 * next call. A missing token registry only means uid-targeted pushes resolve
 * to zero tokens; the topic-driven board/widget updates are unaffected.
 */
object FcmTokenRegistrar {

    private const val KEY_LAST_TOKEN = "fcm_last_registered_token"
    private const val KEY_LAST_UID   = "fcm_last_registered_uid"

    /**
     * Register the current token if it (or the signed-in user) has changed
     * since the last success. No-op when signed out or when the Swift
     * AppDelegate hasn't received a token yet — the next call picks it up.
     *
     * @param uid the signed-in user, defaulting to whatever is in storage.
     *        Login passes it explicitly: `firebase_user_uid` is written by the
     *        Swift AuthBridge on its own auth-state callback, so reading it
     *        from storage mid-login is a race the caller doesn't need to take.
     */
    suspend fun ensureRegistered(uid: String? = null) {
        try {
            val userId = uid ?: Platform.storageManager.loadString("firebase_user_uid") ?: return
            val token = Platform.notificationManager.registerDevice()
            if (token.isBlank()) return

            val lastToken = Platform.storageManager.loadString(KEY_LAST_TOKEN)
            val lastUid   = Platform.storageManager.loadString(KEY_LAST_UID)
            if (lastToken == token && lastUid == userId) return

            val ok = NetworkModule.sduiApi.registerFcmToken(
                token      = token,
                platform   = Platform.getPlatformName().lowercase(),
                appVersion = DeviceIdentity.deviceInfo().appVersion,
            )
            if (ok) {
                Platform.storageManager.saveString(KEY_LAST_TOKEN, token)
                Platform.storageManager.saveString(KEY_LAST_UID, userId)
            }
        } catch (_: Exception) {
            // Network blip, expired auth token, backend 5xx — all transient.
            // Retried on the next foreground without the user noticing.
        }
    }

    /**
     * Forget the cached pair so the next [ensureRegistered] re-POSTs. Called
     * on sign-out: the next user on this device must not inherit the previous
     * user's token registration.
     */
    suspend fun clearCache() {
        try {
            Platform.storageManager.saveString(KEY_LAST_TOKEN, "")
            Platform.storageManager.saveString(KEY_LAST_UID, "")
        } catch (_: Exception) {}
    }
}
