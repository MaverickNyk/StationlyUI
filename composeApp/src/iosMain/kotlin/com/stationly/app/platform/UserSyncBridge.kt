package com.stationly.app

import com.stationly.core.model.push.UserSyncReason
import com.stationly.core.platform.AppGroupKeys
import com.stationly.core.platform.IosAppGroup
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.repository.UserSyncRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

/**
 * Cross-device account sync on iOS — the counterpart to Android's
 * `UserSyncCoordinator`.
 *
 * ## Why iOS needs this at all
 * When a user changes something on one device — adds a station, edits their
 * profile, deletes the account — the backend pings every device that account is
 * signed in on so they reconcile immediately rather than at their next cold
 * launch. Android has had this since the `user_sync` FCM push existed. iOS
 * never did: the signal was FCM-shaped, and even before FirebaseMessaging was
 * removed no iOS code consumed it. So an iPhone kept showing yesterday's boards
 * after the user changed them on their Android phone, indefinitely.
 *
 * The signal now arrives over APNs using the shared envelope vocabulary
 * (`PushEnvelope`), and lands here.
 *
 * ## This is NOT the departures pipeline
 * Worth stating plainly, because the two are easy to conflate: the
 * `Station_*` / `LineStatus_*` topics that drive Android's widget fire every
 * minute or ten, and they stay Android-only precisely because iOS meters widget
 * reloads. `user.sync` fires when a HUMAN changes something, which is rare
 * enough to be safe on every platform. Freshness of departures on iOS comes
 * from the refresh policy; freshness of the ACCOUNT comes from here.
 */
object UserSyncBridge {

    /**
     * Serialised so two signals — or a signal racing the foreground resync —
     * cannot run the station diff concurrently. Android holds the same
     * invariant with its own mutex, and for the same reason: the diff reads
     * local state, decides on removals, and then applies them.
     */
    private val mutex = Mutex()

    private val selectionRepository by lazy {
        SelectionRepository(Platform.storageManager, Platform.sqlStorage)
    }

    private val departureRepository by lazy {
        DepartureRepository(
            NetworkModule.tflApi,
            Platform.storageManager,
            Platform.sqlStorage,
            SyncPredictionsUseCase(Platform.sqlStorage),
        )
    }

    private val lifecycle by lazy {
        StationLifecycleUseCase(
            selectionRepository = selectionRepository,
            departureRepository = departureRepository,
            notificationManager = Platform.notificationManager,
            widgetManager = Platform.widgetManager,
            sqlStorage = Platform.sqlStorage,
            storageManager = Platform.storageManager,
        )
    }

    private val userSyncRepository by lazy {
        UserSyncRepository(NetworkModule.sduiApi, Platform.sqlStorage, Platform.storageManager)
    }

    /**
     * The signed-in account, read from the App Group.
     *
     * Swift's `AuthBridge` writes it there on every auth transition, which
     * makes it the one place both languages agree on who is signed in. Reading
     * Firebase from Kotlin is not an option — the SDK is Swift-side only.
     */
    private fun currentUid(): String? =
        NSUserDefaults(suiteName = IosAppGroup.ID)
            .stringForKey(AppGroupKeys.FIREBASE_USER_UID)
            ?.takeIf { it.isNotBlank() }

    /**
     * Act on a `user.sync` signal.
     *
     * [pushUid] is the account the push was minted for. It is checked against
     * the signed-in user and the push is DROPPED on a mismatch — a device token
     * outlives a session, so a push can land on a phone that has since signed in
     * as somebody else. Acting on it would reconcile the wrong account, and for
     * `reason = "deleted"` would log out a user whose account is perfectly
     * fine. Android performs the identical check; this is not iOS being
     * cautious, it is the two platforms agreeing.
     *
     * Returns whether anything was actually applied, which the caller reports to
     * iOS as its background-fetch result.
     */
    suspend fun handle(reason: String?, pushUid: String?): Boolean = withContext(Dispatchers.Default) {
        val currentUid = currentUid()
        if (currentUid.isNullOrEmpty()) return@withContext false
        if (!pushUid.isNullOrEmpty() && pushUid != currentUid) return@withContext false

        mutex.withLock {
            when (reason) {
                // Deliberately NOT handled here. Forcing a sign-out touches the
                // keychain, the Firebase session and the UI, none of which this
                // object owns — and a half-applied logout is worse than a late
                // one. Reported so the caller can drive the platform's own
                // logout path, exactly as Android routes it to `forceLogout`.
                UserSyncReason.DELETED -> false

                // "stations", "profile", or absent — all of which mean the same
                // thing to a client: the cloud profile is the truth, diff
                // against it. Reconcile handles both the station list and the
                // profile fields, so splitting the cases would only risk them
                // drifting.
                else -> runCatching {
                    userSyncRepository.reconcile(currentUid, lifecycle)
                    true
                }.getOrElse { false }
            }
        }
    }

    /** Whether a signal asks for a forced sign-out — the one case the caller
     *  must drive itself. See [handle]. */
    fun isAccountDeleted(reason: String?): Boolean = reason == UserSyncReason.DELETED
}
