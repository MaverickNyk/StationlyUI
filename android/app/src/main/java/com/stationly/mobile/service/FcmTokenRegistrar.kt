package com.stationly.mobile.service

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.stationly.core.service.SduiApiServiceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Pushes this device's FCM registration token to the backend so that
 * `uid`-targeted admin notifications can be resolved against it.
 *
 * Two entry points:
 *   - `registerIfAuthenticated(token)` — called from
 *     [FcmMessagingService.onNewToken] whenever FCM rotates the
 *     token. Idempotent: re-registering the same token from the
 *     same user is a cheap no-op on the server.
 *   - `ensureRegistered()` — called from [StationlyApplication] on
 *     every cold launch. Fetches the current FCM token via
 *     [FirebaseMessaging.getToken] and registers it if a user is
 *     signed in. Catches the case where the user signed in BEFORE
 *     the FCM token was first issued; without this the token would
 *     never make it to the backend until next rotation.
 *
 * State tracking: we persist a `last_registered_token` value in
 * SharedPrefs so the cold-launch path can skip the network call when
 * nothing has changed. The server is idempotent anyway, but skipping
 * saves a round-trip + auth header generation.
 *
 * Failure handling: registration failures are logged but never crash
 * the app. The token registry is a soft requirement — if it's missing,
 * `uid`-targeted notifications just resolve to zero tokens (a no-op on
 * the server), they don't block the auto-status-change flow which uses
 * topics instead.
 *
 * Why a Firebase-backed helper rather than a use case in commonMain:
 *   - FirebaseAuth + FirebaseMessaging are Android-only APIs. The KMP
 *     `SduiApiService` HAS the register call, but the orchestration
 *     (current token? authed? skip if unchanged?) is platform-specific.
 *   - Keeping this in the Android module avoids polluting commonMain
 *     with Firebase plumbing.
 */
object FcmTokenRegistrar {

    private const val TAG = "FcmTokenRegistrar"
    private const val PREFS = "StationlyPrefs"
    private const val KEY_LAST_REGISTERED_TOKEN = "fcm_last_registered_token"
    private const val KEY_LAST_REGISTERED_UID   = "fcm_last_registered_uid"

    /**
     * Best-effort register. Returns immediately if no user is signed in
     * or if the same (token, uid) pair was already registered in this
     * install. Otherwise POSTs to `/user/fcm/register` on a background
     * coroutine.
     */
    fun registerIfAuthenticated(context: Context, token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.d(TAG, "No authenticated user — skipping FCM token register")
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_REGISTERED_TOKEN, null) == token &&
            prefs.getString(KEY_LAST_REGISTERED_UID, null) == uid) {
            // Nothing changed since last register. Server's set(merge)
            // would no-op anyway but we save a round-trip.
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = SduiApiServiceFactory.create()
                val appVersion = runCatching {
                    context.packageManager
                        .getPackageInfo(context.packageName, 0).versionName
                }.getOrNull()
                val ok = api.registerFcmToken(token = token, platform = "android", appVersion = appVersion)
                if (ok) {
                    prefs.edit()
                        .putString(KEY_LAST_REGISTERED_TOKEN, token)
                        .putString(KEY_LAST_REGISTERED_UID, uid)
                        .apply()
                    Log.d(TAG, "FCM token registered for uid ${uid.take(6)}…")
                } else {
                    Log.w(TAG, "FCM token register returned non-OK; will retry next launch")
                }
            } catch (e: Exception) {
                // Network blip, expired auth token, server 5xx — all
                // transient. We'll try again on the next cold launch via
                // [ensureRegistered] without the user noticing.
                Log.w(TAG, "FCM token register failed (will retry next launch): ${e.message}")
            }
        }
    }

    /**
     * Cold-launch entry point. Looks up the device's current FCM token,
     * delays briefly so any sign-in flow can complete, then forwards
     * to [registerIfAuthenticated]. Cheap if nothing changed.
     */
    fun ensureRegistered(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            // Small delay so a freshly-opened app that's about to
            // auto-sign-in has time to populate FirebaseAuth.currentUser
            // before we read it. ~300ms is the empirical threshold; if
            // auth takes longer, the next cold launch picks it up.
            delay(300)
            val token = runCatching {
                FirebaseMessaging.getInstance().token.await()
            }.getOrNull() ?: return@launch
            registerIfAuthenticated(context, token)
        }
    }

    /**
     * Tell the server to forget this device's token. Called from the
     * logout path so a future admin notification targeted at the old
     * user doesn't reach this device.
     */
    fun unregister(context: Context, token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SduiApiServiceFactory.create().unregisterFcmToken(token)
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .remove(KEY_LAST_REGISTERED_TOKEN)
                    .remove(KEY_LAST_REGISTERED_UID)
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "FCM token unregister failed: ${e.message}")
            }
        }
    }
}
