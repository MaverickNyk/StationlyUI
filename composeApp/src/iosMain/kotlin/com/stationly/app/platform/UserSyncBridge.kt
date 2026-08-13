package com.stationly.app

import com.stationly.app.sync.UserStateSync
import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
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
import kotlinx.datetime.Clock
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
     * The signed-in account, read from the app's STANDARD defaults.
     *
     * Swift's `AuthBridge.persistUserIdentity` writes it there on every auth
     * transition; reading Firebase from Kotlin is not an option, since the SDK
     * is Swift-side only.
     *
     * ## This used to open the App Group suite, and that made the whole file dead
     * The previous version read `NSUserDefaults(suiteName = IosAppGroup.ID)` and
     * claimed the App Group was "the one place both languages agree on who is
     * signed in". Nothing has ever written the uid there — `AuthBridge` writes
     * every identity key to `UserDefaults.standard` — so this returned null on
     * every call, `handle` bailed on its first line, and **cross-device sync
     * silently did nothing on iOS**. Confirmed on device: a `user.sync` push
     * arrived, was traced, was routed correctly, and reconciled nothing; a dump
     * of the App Group container had no uid key at all.
     *
     * It fails in the safe direction, which is exactly why it survived: a
     * reconcile that never runs looks like a reconcile with nothing to do.
     *
     * Standard defaults rather than teaching Swift to mirror the key, because
     * every other Kotlin reader of the uid already uses them —
     * `IosPlatformAuthProvider`, `UserStateRepository`, `ActivityLog` — and a
     * second copy is a second thing to keep in step. (`AppGroupKeys` is a
     * shared NAME registry; several of its constants are already read from the
     * standard domain.) It is also the correct domain for this fact: logout
     * wipes it, so a stale uid cannot outlive a session.
     *
     * ⚠️ The widget EXTENSION is a different process and cannot see these. If
     * something in the extension ever needs the uid, mirror it into the App
     * Group there and then — do not move this read back.
     */
    private fun currentUid(): String? =
        NSUserDefaults.standardUserDefaults
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

                // "boards", "preferences", "profile", "stations", or absent —
                // all of which mean the same thing to a client: the cloud is
                // the truth, diff against it. One reconcile handles every case,
                // so splitting them would only risk the branches drifting; the
                // reason exists so the SERVER can say what changed, not so this
                // has to do less work.
                //
                // Against the v2 `boards` list, never `stations`: the legacy
                // array is Android's, and Android replaces it wholesale on every
                // board setup — so diffing against it would make an iPhone
                // delete its own boards the first time the account's Android
                // device saved one.
                else -> runCatching {
                    val profile = userSyncRepository.reconcileBoards(currentUid, lifecycle)
                    // Two cases where `reconcileBoards` deliberately left local
                    // state alone rather than diffing, and both end the same
                    // way — this device claims the account:
                    //
                    //  - the account has NO board list yet, so what was served
                    //    is derived from Android's array rather than a record of
                    //    anything the user did here;
                    //  - it has one, but nothing in it is usable, i.e. it was
                    //    written in a shape this build no longer reads.
                    //
                    // Without the second case the device is stuck: it keeps its
                    // boards (the bail is what protects them) but never
                    // publishes them, so the account never converges and the
                    // user's next device restores the old shape. Pushing once
                    // resolves it — the write makes the list usable, and this
                    // branch stops firing.
                    if (profile.boardsUpdatedAt == 0L || profile.boards.none { it.isUsable }) {
                        UserStateSync.boardsChanged()
                    }
                    // Boards only. A settings change on another device does
                    // NOT arrive here and is not meant to: appearance is
                    // device-local, so there is nothing on the wire to
                    // reconcile. See UserSettings.
                    ActivityLog.record(ActivityEvents.SYNC_RECONCILED, "reason", reason ?: "none")
                    true
                }.getOrElse { false }
            }
        }
    }

    /**
     * Reconcile against the account on foreground, if it has been long enough.
     *
     * ## The push cannot be the only path, and was
     * [handle] was reachable from exactly one place — the APNs handler. So a
     * board deleted on another device reached this one only if its push arrived,
     * and a silent push is best-effort by design: iOS will not deliver one to an
     * app the user force-quit, and it may drop or defer it under load. A missed
     * push therefore meant stale forever — not until next launch, but until some
     * LATER push happened to land, because nothing else ever asked.
     *
     * That is not theoretical. A `deleted` push was observed not arriving on this
     * very device, which is what surfaced the whole class of bug.
     *
     * Android has had a foreground resync since it had cross-device sync at all
     * (`UserSyncCoordinator.reconcile(force = false)`); this is the counterpart,
     * and the mutex it shares with [handle] is why the two cannot run the diff at
     * the same time.
     *
     * ## Debounced, because foregrounding is not rare
     * An app-switch to check a message and come straight back must not cost a
     * profile read each way. [FOREGROUND_MIN_INTERVAL_MS] is the floor; a push
     * that arrives inside that window is still applied immediately, because it
     * goes through [handle] and does not consult this at all.
     */
    suspend fun reconcileOnForeground(): Boolean = withContext(Dispatchers.Default) {
        val uid = currentUid()
        if (uid.isNullOrEmpty()) return@withContext false
        mutex.withLock {
            // ── The debounce check and its stamp are ONE step, under the lock ──
            //
            // They used to sit outside it. Two foregrounds close together — a
            // Control Centre pull-down and the return from it, which is a real
            // pair of `didBecomeActive` callbacks — both read the old stamp,
            // both passed the check, both wrote it, and both then queued on this
            // mutex. The lock serialised them but did not DEDUPE them, so the
            // debounce that exists to stop a second profile read let two through
            // back to back, the second acting on a premise the first had already
            // invalidated.
            //
            // `lastForegroundSyncAt` is also plain shared mutable state read from
            // `Dispatchers.Default`; reading and writing it under the same lock
            // that guards the reconcile gives it the happens-before it never had.
            val now = Clock.System.now().toEpochMilliseconds()
            if (now - lastForegroundSyncAt < FOREGROUND_MIN_INTERVAL_MS) return@withLock false
            lastForegroundSyncAt = now
            runCatching {
                // Returns whether anything ACTUALLY changed, not merely whether
                // the reconcile ran.
                //
                // NOT for the widget's benefit — a foreground rebuild is free
                // (WidgetKit exempts reloads while the app is on screen, and
                // `RefreshScheduleStore` honours that), so the caller does it
                // unconditionally. This is for the things that are not free: the
                // device-registration write, and the activity event, neither of
                // which should fire on a foreground where nothing moved.
                //
                // `reconcileBoards` hands back the profile rather than a verdict,
                // so the comparison is made here, against the flat rows it
                // actually mutates.
                val before = Platform.sqlStorage.getAllSelections().map { it.boardKey }.toSet()
                userSyncRepository.reconcileBoards(uid, lifecycle)
                val after = Platform.sqlStorage.getAllSelections().map { it.boardKey }.toSet()
                val changed = before != after
                if (changed) {
                    ActivityLog.record(ActivityEvents.SYNC_RECONCILED, "reason", "foreground")
                }
                changed
            }.getOrElse {
                // Let the next foreground try again rather than treating a failed
                // attempt as a completed one.
                lastForegroundSyncAt = 0
                false
            }
        }
    }

    private var lastForegroundSyncAt: Long = 0

    /**
     * Floor between foreground reconciles.
     *
     * Two minutes covers the app-switch case — glance at a message, come back —
     * without letting a device go long enough that a missed push is noticeable.
     * The user is looking at the board when this runs, so the freshness it buys
     * is the freshness they can see.
     */
    private const val FOREGROUND_MIN_INTERVAL_MS = 2L * 60 * 1000

    /** Whether a signal asks for a forced sign-out — the one case the caller
     *  must drive itself. See [handle]. */
    fun isAccountDeleted(reason: String?): Boolean = reason == UserSyncReason.DELETED

    /**
     * Tear down everything a deleted account leaves behind on this device.
     *
     * ## Why this exists
     * `AuthBridge.signOutForAccountDeletion` claimed its mechanics were
     * "identical" to a deliberate sign-out. They were not: it called Swift's
     * `logout()`, which signs out of Firebase, signs out of Google and clears the
     * identity keys — and stops there. Everything the KOTLIN side owns was left
     * standing, because on the deliberate path that work lives in
     * `ProfileViewModel.signOut` and nothing was calling the equivalent here.
     *
     * The result was a device with no credentials and a complete working app:
     * boards still in SQLite, widget still live, topics still subscribed,
     * settings still loaded. It looks like a healthy session because everything
     * that makes it look healthy is local.
     *
     * ## Must run BEFORE the Firebase sign-out
     * The uid is read from storage that `clearUserInfo()` wipes, and it is what
     * namespaces the durable settings being erased. Called after, this would find
     * no uid and silently erase nothing.
     *
     * ## Erased, not merely reset
     * [UserStateSync.forgetAccount] rather than `resetForNewSession`: a signed-out
     * account keeps its arrangement because the same person is expected back, and
     * a DELETED one has nobody to come back. Leaving per-uid rows for an account
     * that cannot exist again is the one case where keeping them is wrong.
     */
    suspend fun tearDownDeletedAccount(): Boolean = withContext(Dispatchers.Default) {
        val uid = currentUid()
        // Raised BEFORE the teardown, and into DURABLE storage — `cleanupAll()`
        // wipes the app's own defaults, so a flag written to those would be
        // erased by the very sequence it is meant to survive. The login screen
        // reads it once and clears it, so the user is told why they are looking
        // at a sign-in page instead of their boards. Android does the same thing
        // through its own `FLAG_ACCOUNT_REMOVED`.
        runCatching {
            Platform.storageManager.saveDurable(UserStateSync.ACCOUNT_REMOVED_FLAG, "1")
        }
        runCatching { lifecycle.cleanupAll() }
            .onFailure { return@withContext false }
        if (!uid.isNullOrBlank()) {
            runCatching { UserStateSync.forgetAccount(uid) }
        }
        true
    }

    // The "your account was removed" key is NOT redeclared here. It lives on
    // `UserStateSync.ACCOUNT_REMOVED_FLAG`, in common code, and both the writer
    // above and the login screen that consumes it already reference it there.
    //
    // This file used to carry a second `const val` with the same literal and a
    // doc comment explaining that it existed so the key could not be spelled two
    // different ways — while being the second spelling. Nothing read it.

    /**
     * The signed-in uid, for the ONE caller that has to check it itself.
     *
     * [handle] drops a push minted for another account on its second line, but
     * the `deleted` branch is driven by the platform (it touches the keychain and
     * the UI, which this object does not own) and so returns before reaching it.
     * That left the single most destructive signal — end this session — as the
     * only one applied without asking who it was for. Exposed rather than
     * duplicated in Swift so both readings of "who is signed in" stay one
     * implementation; see [currentUid] for why it is the standard domain.
     */
    fun currentUidOrNull(): String? = currentUid()
}
