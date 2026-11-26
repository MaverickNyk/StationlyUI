package com.stationly.mobile.service

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * `stationly_all` — the one topic that is not a board.
 *
 * It is how an `audience: {type:"all"}` admin push reaches every install with
 * **zero Firestore reads**: FCM fans the message out internally, so the backend
 * sends one message rather than one per device. That is the entire reason it
 * exists, and it is why it is subscribed per INSTALL rather than per account.
 *
 * ## Why it is not in the topic ledger
 * The ledger is the set of topics a reconcile may add or remove, and it is
 * derived from the user's boards. This one belongs to the install, has no board
 * behind it, and must survive every board change, sign-out and account deletion.
 * Keeping it out of the ledger is what makes "unsubscribe everything the ledger
 * knows about" a safe thing to do at logout. `TopicLedger.isBoardTopic` is the
 * second guard, for the day somebody puts it in there anyway.
 *
 * ## The guard, and the one event that invalidates it
 * Subscribing is idempotent on FCM's side, but the SharedPreferences boolean
 * avoids the call entirely on every cold launch after the first. The cost of
 * that is a flag that says "done" forever — and a **token rotation** silently
 * makes it false, because topic subscriptions belong to the token, not to the
 * app. Before [resubscribe] existed, a rotated token dropped the broadcast
 * channel permanently on a device that went on believing it had it. Nothing to
 * see: no error, no missing screen, just an install that stops hearing from us.
 */
object BroadcastTopic {

    const val NAME = "stationly_all"

    private const val PREFS = "StationlyPrefs"
    private const val GUARD_KEY = "subscribed_all_topic"
    private const val TAG = "BroadcastTopic"

    /** Cold-launch path: subscribe once per install, then never call FCM again. */
    fun ensureSubscribed(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(GUARD_KEY, false)) return
        FirebaseMessaging.getInstance()
            .subscribeToTopic(NAME)
            .addOnSuccessListener { prefs.edit().putBoolean(GUARD_KEY, true).apply() }
            .addOnFailureListener { Log.w(TAG, "Subscribe to $NAME failed; will retry next launch", it) }
    }

    /**
     * Token-rotation path: subscribe unconditionally, because the guard is about
     * the install and the thing that just changed is the token.
     *
     * On failure the guard is LOWERED rather than left alone — [ensureSubscribed]
     * only acts when it is false, so lowering it is the retry, and it is the only
     * one this path gets.
     */
    suspend fun resubscribe(context: Context) {
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(NAME).await()
            Log.d(TAG, "re-subscribed $NAME after token rotation")
        } catch (e: Exception) {
            Log.e(TAG, "Re-subscribe to $NAME after token rotation failed", e)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(GUARD_KEY, false).apply()
        }
    }
}
