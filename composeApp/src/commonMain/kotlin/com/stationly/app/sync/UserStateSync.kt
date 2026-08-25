package com.stationly.app.sync

import com.stationly.app.ui.dream.ClockStyle
import com.stationly.app.ui.dream.DreamLayout
import com.stationly.app.ui.dream.DreamSettings
import com.stationly.app.ui.dream.DreamTheme
import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.UserProfileResponse
import com.stationly.core.model.user.Board
import com.stationly.core.model.user.WidgetPlacement
import com.stationly.app.platform.DeviceIdentity
import com.stationly.core.platform.Platform
import com.stationly.core.session.SessionStore
import com.stationly.core.repository.UserSettings
import com.stationly.core.repository.UserStateRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.StationLifecycleUseCase
import kotlinx.datetime.Clock

/**
 * Binds the app's board list to the account it belongs to.
 *
 * ## Boards go to the backend; appearance never does
 * The split is drawn by consequence — see [UserSettings]. A board list decides
 * whether TfL is polled for a station at all, so losing one loses departures and
 * it is worth a write. Expanded/collapsed, rows-per-platform, pin, order and
 * layout decide how a card looks, change on every touch, and are worth nothing
 * to any device but this one. They are kept per account on the device and
 * restored when the same person signs back in.
 *
 * ## iOS only, deliberately
 * Everything here writes the board list, which Android does not read yet. Wiring
 * Android to this would put both platforms on one list and reintroduce the
 * cross-platform wipe the split exists to fix — Android's own save path calls
 * `cleanupAll()` first, so the "full list" it posts is always a single board.
 * Android moves over by adopting this, not by both writing at once.
 */
object UserStateSync {

    /**
     * One-shot "your account was removed" notice, in DURABLE storage.
     *
     * Durable because the teardown that raises it wipes the app's own defaults —
     * a flag written to those would be erased by the very sequence it exists to
     * outlive. Declared here, in common code, so the platform that RAISES it and
     * the login screen that CONSUMES it cannot spell it differently; a key
     * duplicated as a literal drifts and then fails by showing nothing at all,
     * which is exactly the silence this is meant to break.
     */
    const val ACCOUNT_REMOVED_FLAG = "account_removed_notice"

    val repository: UserStateRepository by lazy {
        UserStateRepository(
            NetworkModule.sduiApi,
            Platform.sqlStorage,
            // Handed down because `core` cannot reach `DeviceIdentity` — it needs
            // the iOS App Group and Android's own prefs file, so it lives here.
            // It is what lets the server's `user.sync` fan-out skip this device
            // after this device is the one that made the change.
            DeviceIdentity.deviceId(),
        )
    }

    /**
     * Retained as a no-op call site, deliberately.
     *
     * There is nothing left to wire: settings became device-local, so a settings
     * write is a disk write and stops there. What used to live here was a hook
     * turning every toggle into a debounced backend push.
     *
     * The function stays because `ActivityBridge.start()` — the app-start hook on
     * iOS — calls it, and an app-start seam for account state is worth keeping
     * even while it is empty. It no longer guards itself with a `wired` flag, and
     * it no longer holds a [CoroutineScope]: that scope was created at class-init
     * with a [SupervisorJob], never used by anything, and never cancelled — a
     * live root object kept alive for a hook that had already been deleted.
     */
    fun start() = Unit

    /**
     * Note that the user's boards changed; the push is debounced.
     *
     * @param emptiedByUser whether this change leaves the account with no boards
     *   at all — the permission the server needs before it will replace a stored
     *   list with an empty one. See [UserStateRepository.boardsChanged].
     */
    fun boardsChanged(emptiedByUser: Boolean = false) = repository.boardsChanged(emptiedByUser)

    /**
     * Send anything pending immediately.
     *
     * For backgrounding and logout — the two moments where the debounce timer
     * may not survive to fire. Awaited by its callers so a logout can be sure
     * the user's last change left the device before the session did.
     */
    suspend fun flushNow() = repository.flushNow()

    /**
     * Drop everything held for the session that is ending.
     *
     * Called at logout and again immediately before a login restore. Both,
     * because they are different failure modes: at logout it stops the next user
     * inheriting settings, and before a restore it stops a user who signs back
     * into the SAME account on a device that never fully quit from carrying
     * stale board ages into their first push.
     *
     * The stores here are process-wide objects and the app is long-lived, so
     * "the session ended" is never signalled by the state simply going away.
     */
    suspend fun resetForNewSession() {
        // Point the per-account stores at whoever is signed in NOW, before
        // anything reads them. Called on both edges — logout and the login
        // restore — because both are session boundaries and a store left bound
        // to the previous account hands their arrangement to the next person.
        DreamSettings.bindAccount(SessionStore.uid())

        // Drops the in-memory copy and re-reads for whoever is signed in now.
        // It does NOT wipe the disk: that is what makes signing back in on this
        // device restore the arrangement rather than reset it.
        UserSettings.reset()
        repository.reset()
        clearDreamSettings()
    }

    /**
     * Tear down a session whose account is GONE, not merely signed out.
     *
     * The difference from [resetForNewSession] is the disk. Everything device-
     * local is kept per uid precisely so the same person gets their app back —
     * but a deleted account has no same person to come back, and rows namespaced
     * to a uid that no longer exists can never be reached again by anything.
     *
     * ## Takes the uid rather than resolving it
     * Not because the sign-out has already cleared it — it has NOT.
     * `AuthBridge.signOutForAccountDeletion` runs this teardown FIRST and only
     * then `logout()`, precisely so the uid is still readable, and that order is
     * commented as load-bearing on the Swift side.
     *
     * The real reason is that [UserSettings] may not know the uid: it resolves
     * one only when something has caused it to load, so deleting an account
     * without ever opening a settings screen leaves it on the `anon` namespace,
     * and wiping that would be both wrong and silent. The caller always has the
     * uid; this store might not.
     */
    suspend fun forgetAccount(uid: String) {
        UserSettings.forgetAccount(uid)
        repository.reset()
        clearDreamSettings()
    }

    /**
     * The screensaver's settings live in a store that deliberately SURVIVES the
     * logout wipe, so unlike the others they are still here after `cleanupAll()`
     * — and would show the previous user's screensaver to the next one. Cleared
     * explicitly: every key this store holds is a preference belonging to whoever
     * was signed in.
     */
    private fun clearDreamSettings() {
        DreamSettings.applyRemote {
            DreamSettings.setLayout(DreamLayout.CLOCK_AND_BOARD)
            DreamSettings.setTheme(DreamTheme.SYSTEM)
            DreamSettings.setClockStyle(ClockStyle.DIGITAL)
            DreamSettings.setStationId(null)
        }
    }

    // ── Restore ─────────────────────────────────────────────────────────────

    /**
     * Restore the boards and their filters.
     *
     * Arrangement is NOT restored from here — it never left the device. The
     * settings store is re-read for this account by `resetForNewSession`, so a
     * board coming back finds the configuration it had when the user signed out.
     *
     * Used by the login path, where local state has just been wiped and there is
     * nothing to diff against — so this SETS UP rather than reconciling. The
     * mid-session equivalent is `UserSyncRepository.reconcileBoards`, which
     * diffs because the user may be looking at a board while it runs.
     */
    suspend fun restoreBoards(profile: UserProfileResponse, lifecycle: StationLifecycleUseCase) {
        // `isUsable` drops boards that say nothing — a truncated payload, or a
        // response from a backend that predates this shape. Without it they
        // would suppress the legacy fallback below and restore nothing at all.
        val usable = profile.boards.filter { it.isUsable }
        val boards = usable.ifEmpty {
            // Fall back to the LEGACY list when the response carries no boards
            // but does carry stations.
            //
            // A current backend always derives `boards` from `stations`, so the
            // two can only disagree this way when the backend PREDATES the
            // field — the ordinary state of affairs for any client that ships
            // ahead of a deploy, including this one on the day it lands. Without
            // this the login restore silently does nothing: the selections reach
            // SQLite (core writes them from `stations`) but no board is ever set
            // up, so none subscribes, fetches, or reaches the widget. The user
            // sees a home screen of boards that never populate.
            Board.fromSelections(
                profile.stations.map { station ->
                    UserSelection(
                        mode = station.mode,
                        line = station.line,
                        station = station.id,
                        parentStationId = station.parentStationId.orEmpty(),
                        stationName = station.name,
                        direction = station.direction,
                        destinations = emptyList(),
                        destinationIds = emptyList(),
                    )
                },
            )
        }
        if (boards.isEmpty()) return

        // The arrangement is already loaded — `resetForNewSession` re-read it for
        // this account before the restore began — so a board coming back finds
        // the settings it had, without a second fetch and without a frame drawn
        // at the defaults.
        UserSettings.ensureLoaded()

        // Oldest first, so the restored home screen is in the order the user
        // built it.
        //
        // `config.sortKey` is in the comparator for symmetry with `buildBoards`,
        // where it IS live — but on THIS path it can never decide anything.
        // `Board.config` is `@Transient`, so every board deserialised from the
        // profile carries a default `BoardConfig`, every `sortKey` is
        // `Int.MAX_VALUE`, and the sort falls entirely to `addedAt`. (An earlier
        // version of this comment claimed position overrode it here. It cannot:
        // drag order is device-local and does not travel — see
        // USER_STATE_AND_ACTIVITY.md §2b.)
        //
        // Which is correct rather than merely harmless: this ordering only
        // decides SETUP order. The home screen renders from `UserSettings`,
        // which does hold the positions on a device that has them.
        //
        // Set up per (line, direction): that is the level a topic subscription
        // and a prediction table live at, so one station with four lines is four
        // calls, not one.
        boards.sortedWith(compareBy({ it.config.sortKey }, { it.addedAt })).forEach { board ->
            board.toSelections().forEach { selection ->
                runCatching { lifecycle.setupStation(selection, isFirstTime = false) }
            }
        }
        ActivityLog.record(ActivityEvents.SYNC_RESTORED, "boards", boards.size.toString())

        // Restored from the LEGACY array, which means the account has no board
        // list of its own — or has one this build cannot read. Claim it, so the
        // user's next device restores what they are looking at now rather than
        // going round the same fallback. One push: the write makes the list
        // usable, so this cannot repeat.
        if (usable.isEmpty()) repository.boardsChanged()
    }

    // ── Widget placement ────────────────────────────────────────────────────

    /**
     * Record which boards the widget host says are on the home screen.
     *
     * Takes board ids paired with the widget sizes showing them. Device-local
     * and never pushed — see [WidgetPlacement] — so this deliberately does not
     * mark anything dirty.
     *
     * Callers must not invoke this when the probe FAILED: an empty map means
     * "the user has no widgets", and reporting a transient failure that way
     * would read as them removing every widget at once.
     */
    fun widgetsObserved(byBoard: Map<String, List<String>>) {
        val now = Clock.System.now().toEpochMilliseconds()
        UserSettings.widgetsObserved(
            byBoard.mapValues { (_, families) ->
                WidgetPlacement(placed = true, families = families.distinct(), observedAt = now)
            },
        )
    }
}
