package com.stationly.mobile.service

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.repository.UserSyncRepository
import com.stationly.core.service.SduiApiServiceFactory
import com.stationly.core.service.TflApiServiceFactory
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import com.stationly.mobile.util.FreshDataNotifier
import com.stationly.mobile.widget.DepartureWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Cross-device session sync (feature #5).
 *
 * Two entry points keep this device's local state in lockstep with the
 * cloud profile so two devices signed into the same account never drift:
 *
 *  1. [handleUserSync] — invoked by [FcmMessagingService] when a silent
 *     `user_sync` data push arrives (the backend sends one whenever the
 *     user's stations / name / account change on any device).
 *  2. [reconcile] — also called on app foreground (debounced) as a
 *     fallback for when the device was offline when the push was sent.
 *
 * For `reason == "deleted"` we force-log-out: the account no longer
 * exists, so showing its board would be a ghost session.
 *
 * All work runs on a detached app-level scope — these are triggered from
 * a background service / lifecycle callback with no ViewModel to host them.
 */
object UserSyncCoordinator {

    private const val STORAGE_PREFS = "StationlyPrefs"
    // Deliberately a SEPARATE prefs file: FirebaseAuthManager.logout()
    // clears all of "StationlyPrefs", which would erase a notice flag
    // stored there before the login screen could read it.
    private const val FLAGS_PREFS = "StationlySyncFlags"
    private const val FLAG_ACCOUNT_REMOVED = "pending_account_removed"

    // Foreground reconcile is only a FALLBACK for a missed `user_sync` push, so
    // it's deliberately infrequent: each run costs one Firestore read of the
    // user doc (GET /user/sync/profile), and that scales with users × app-opens.
    // 15 min is plenty because:
    //   • cold start always reconciles (lastReconcileAt starts at 0),
    //   • a resume after >15 min backgrounded — the case where a push may have
    //     been dropped (Doze/OEM kill) — still reconciles,
    //   • rapid glance-away/return resumes are suppressed (nothing was missed),
    //   • real-time pushes pass force=true and bypass this entirely.
    private const val RECONCILE_DEBOUNCE_MS = 15 * 60_000L  // 15 min

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Serialize reconciles so two pushes (or push + foreground) can't run the
    // station diff / FCM-subscribe concurrently and race each other.
    private val reconcileMutex = Mutex()

    @Volatile private var lastReconcileAt = 0L

    /**
     * Route an incoming `user_sync` FCM push by reason.
     *
     * [pushUid] is the account the push was minted for. We ignore the push if
     * it doesn't match the currently signed-in user — an FCM token can linger
     * on a device that has since signed in as someone else (or out), and acting
     * on it would reconcile/log-out the wrong account.
     */
    fun handleUserSync(context: Context, reason: String?, pushUid: String?) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (pushUid != null && currentUid != null && pushUid != currentUid) {
            Log.w("UserSync", "Ignoring user_sync for $pushUid — current user is $currentUid")
            return
        }
        Log.d("UserSync", "user_sync push received, reason=$reason")
        when (reason) {
            "deleted" -> {
                // Only force-logout if the deleted account is the one signed in
                // here (or the push omitted a uid — legacy/safety).
                if (pushUid == null || pushUid == currentUid) forceLogout(context)
            }
            else -> reconcile(context, force = true)   // "stations" / "profile" / null
        }
    }

    /**
     * Diff local state against the cloud profile and apply the minimal
     * set of changes. [force] bypasses the foreground debounce (used for
     * push-triggered syncs, which are precise and should always run).
     */
    fun reconcile(context: Context, force: Boolean = false) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val uid = user.uid

        val now = System.currentTimeMillis()
        if (!force && now - lastReconcileAt < RECONCILE_DEBOUNCE_MS) return
        lastReconcileAt = now

        scope.launch {
            reconcileMutex.withLock {
                try {
                    // Ensure a valid Firebase ID token is in hand BEFORE hitting the
                    // auth-gated /user endpoints. At cold-start onResume the cached
                    // token is often missing/expired; awaiting a refresh here avoids
                    // firing a tokenless request that 401s (and then mis-parses the
                    // error body). If we still can't get one, bail and retry on the
                    // next foreground rather than touching local state.
                    val token = try { user.getIdToken(false).await()?.token } catch (_: Exception) { null }
                    if (token.isNullOrBlank()) {
                        Log.w("UserSync", "Skipping reconcile — no auth token yet (will retry)")
                        lastReconcileAt = 0L // clear debounce so the next foreground retries
                        return@withLock
                    }

                    val sdui = SduiApiServiceFactory.create()
                    val repo = UserSyncRepository(sdui, Platform.sqlStorage, Platform.storageManager)
                    val profile = repo.reconcile(uid, buildLifecycle())

                    // Surface a display-name change made on another device.
                    try { FirebaseAuth.getInstance().currentUser?.reload()?.await() } catch (_: Exception) {}

                    // Nudge the home board to re-read selections from SQL
                    // (SummaryViewModel listens for the "selections" key), then
                    // redraw the widget + dream.
                    context.getSharedPreferences(STORAGE_PREFS, Context.MODE_PRIVATE)
                        .edit().putString("selections", now.toString()).apply()
                    FreshDataNotifier.notifyAll(context)
                    DepartureWidgetProvider.updateFromStorage(context)

                    Log.d("UserSync", "Reconcile complete: ${profile.stations.size} station(s)")
                } catch (e: com.stationly.core.service.UserNotFoundException) {
                    // The account was deleted (here or on another device) and we
                    // missed / never got the `user_sync` deleted push. Treat the
                    // 404 as the authoritative signal and evict this device too.
                    Log.w("UserSync", "Account no longer exists — forcing logout", e)
                    forceLogout(context)
                } catch (e: Exception) {
                    Log.w("UserSync", "Reconcile failed (will retry on next foreground)", e)
                }
            }
        }
    }

    /**
     * Account was deleted elsewhere — wipe and bounce to login. Sets a
     * one-shot flag (in a prefs file that survives the logout wipe) so the
     * login screen can show a brief "account removed" notice.
     */
    private fun forceLogout(context: Context) {
        scope.launch {
            try {
                context.getSharedPreferences(FLAGS_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(FLAG_ACCOUNT_REMOVED, true).apply()
                // Full local teardown + Firebase sign-out. MainActivity's
                // auth-state observer evicts to the login screen the moment
                // currentUser becomes null.
                FirebaseAuthManager(context).logout()
            } catch (e: Exception) {
                Log.w("UserSync", "Force logout failed; falling back to bare sign-out", e)
                try { FirebaseAuth.getInstance().signOut() } catch (_: Exception) {}
            }
        }
    }

    /** Read-and-clear the "account removed" notice flag (called from MainActivity). */
    fun consumeAccountRemovedFlag(context: Context): Boolean {
        val prefs = context.getSharedPreferences(FLAGS_PREFS, Context.MODE_PRIVATE)
        val flag = prefs.getBoolean(FLAG_ACCOUNT_REMOVED, false)
        if (flag) prefs.edit().putBoolean(FLAG_ACCOUNT_REMOVED, false).apply()
        return flag
    }

    private fun buildLifecycle(): StationLifecycleUseCase {
        val apiService = TflApiServiceFactory.create()
        val syncPredictions = SyncPredictionsUseCase(Platform.sqlStorage)
        val selectionRepo = SelectionRepository(Platform.storageManager, Platform.sqlStorage)
        val departureRepo = DepartureRepository(apiService, Platform.storageManager, Platform.sqlStorage, syncPredictions)
        return StationLifecycleUseCase(
            selectionRepository = selectionRepo,
            departureRepository = departureRepo,
            notificationManager = Platform.notificationManager,
            widgetManager = Platform.widgetManager,
            sqlStorage = Platform.sqlStorage,
            storageManager = Platform.storageManager,
        )
    }
}
