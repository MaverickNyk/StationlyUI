package com.stationly.core.repository

import com.stationly.core.model.sdui.UserProfileResponse
import com.stationly.core.model.user.Board
import com.stationly.core.platform.Platform
import com.stationly.core.service.SduiApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * The one place the app's board list is pushed to and pulled from the cloud.
 *
 * Boards ONLY. Appearance — expanded, view, rows, pin, order, layout — never
 * leaves the device; see [UserSettings] for the split and why it is drawn by
 * consequence rather than convenience. This class exists because a board list
 * has consequence: the backend derives the subscription registry from it, and a
 * station missing from that registry is never polled for departures.
 *
 * Deliberately separate from [UserSyncRepository], which owns the LEGACY
 * `stations` path Android still uses: mixing them is what let one platform's
 * write erase the other's boards, and keeping the repositories apart makes it
 * obvious at every call site which contract is in play.
 *
 * ## The board list is assembled here, from two places
 * Selections live in SQLite because that is what the fetch pipeline runs on;
 * configuration lives in [UserSettings] because that is what the settings
 * screens edit. A [Board] is the join of the two, and this is the only place
 * that performs it — so there is exactly one definition of what the user's
 * account looks like, and no way for a caller to push half of one.
 *
 * ## Writes are debounced, and still are
 * Board changes are rare — a few per session at most — so the debounce is no
 * longer load-bearing for the write quota the way it was when appearance rode
 * along here. It stays because a single edit is still a BURST: adding a station
 * with four lines ticks four boxes and saves once, and re-picking a filter
 * rewrites several rows. One write [DEBOUNCE_MS] after the last change collapses
 * those without making the user wait.
 *
 * The tradeoff is that an app killed inside the debounce window loses that
 * write. It is recoverable — the setting is still correct locally and the next
 * change re-sends everything, since both payloads are full replacements rather
 * than deltas — and [flushNow] covers the moments where losing it is not
 * acceptable (backgrounding, logout).
 *
 * ## Nothing is sent unless something changed
 * [flushNow] used to push unconditionally, and it is called on EVERY
 * backgrounding and once a night. So an app opened and closed twenty times a day
 * spent twenty writes on a board list nobody had touched — plus a `user.sync`
 * fan-out per write, which every device on the account answers with a profile
 * read. That is the same write amplification appearance-sync was moved off the
 * wire to avoid, relocated rather than removed.
 *
 * [BoardPushGate] now decides: it counts user changes against the last one the
 * server accepted, so a flush with nothing pending is free. It also carries the
 * permission that lets an EMPTY list be stored, which a full-replace endpoint
 * cannot infer — see the gate for both rules and the tests that pin them.
 *
 * This also gives the retry the docs always claimed: a push that fails offline
 * leaves the gate pending, so the nightly flush resends it. It used to "recover"
 * only because the next flush pushed regardless of whether anything was pending.
 *
 * ## The payload is whole-state, never a delta
 * The board list is sent in full. A delta protocol
 * would need a merge on the server, an ordering guarantee between requests, and
 * a recovery path for a delta applied to the wrong base. A full replacement has
 * none of those and cannot half-apply; the cost is a slightly larger request
 * that this sends at most once every few seconds.
 */
class UserStateRepository(
    private val api: SduiApiService,
    private val sqlStorage: SqlStorage,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serialises the actual pushes so two flushes cannot interleave. */
    private val mutex = Mutex()

    private var boardsJob: Job? = null

    /**
     * Whether there is anything to send, and whether it may be empty.
     *
     * See [BoardPushGate] — the rules live there so they can be tested, which
     * this class cannot be (it reaches the network and `Platform`).
     */
    private val gate = BoardPushGate()

    /**
     * When each board was first saved, keyed by board id.
     *
     * Kept so `addedAt` is STABLE across re-syncs. Recomputing it as "now" on
     * every push would make every board look newly added on every write, and the
     * restore order shuffle on each login. Seeded from the cloud profile at
     * [fetch], and from `now` only for a board genuinely created here.
     */
    private val addedAt = mutableMapOf<String, Long>()

    // ── Reads ───────────────────────────────────────────────────────────────

    /**
     * The whole account, in one request.
     *
     * Deliberately one call: the profile and the board list live on the same
     * document, so splitting them would double the cost of the most frequent
     * authenticated read in the app for no benefit. This is what the login
     * loader waits on.
     */
    suspend fun fetch(uid: String): UserProfileResponse {
        val profile = api.getUserProfile(uid)
        // Remember what the server thinks each board's age is, so our next write
        // preserves it instead of restamping everything to now.
        //
        // Under the same mutex the push uses: this runs on the login path while
        // a debounced push can be in flight on another thread, and `addedAt` is
        // a plain map — unsynchronised concurrent mutation is a data race, not
        // merely a stale read.
        mutex.withLock { profile.boards.forEach { addedAt[it.id] = it.addedAt } }
        return profile
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /**
     * Note that the boards changed — a board added or removed, or a filter
     * edited. The push follows after the debounce.
     *
     * Per-board SETTINGS are deliberately not on this list any more: they are
     * device-local, so changing one is a disk write that stops there.
     *
     * Reads the current state at PUSH time rather than taking it as an argument,
     * so a burst of edits sends the final state once instead of each
     * intermediate list in turn — and so a caller can never post a partial list
     * by accident. That mistake has a history here: the legacy path posted only
     * the boards it had just added, and because the endpoint is a full replace,
     * it silently deleted every other board on the account.
     *
     * @param emptiedByUser true when the change being reported leaves the user
     *   with no boards at all. It is the only thing that tells the server a
     *   deliberate "I deleted my last board" apart from a client whose local
     *   database is momentarily empty — see [BoardPushGate]. Call sites pass the
     *   live answer rather than a literal, so a delete that leaves other boards
     *   behind never hands out the permission.
     */
    fun boardsChanged(emptiedByUser: Boolean = false) {
        gate.changed(emptiedByUser)
        boardsJob?.cancel()
        boardsJob = scope.launch {
            delay(DEBOUNCE_MS)
            runCatching { pushBoards() }
        }
    }

    /**
     * Send anything pending right now, skipping the debounce.
     *
     * For the moments where the process may not survive the wait: the app going
     * to background, and logout. Awaited by its callers, so a logout can be sure
     * the user's last change left the device before the session did.
     *
     * A no-op when nothing is pending, which is the overwhelmingly common case —
     * see the class note. It is called on every backgrounding, and most times the
     * app is backgrounded the user did not touch their boards.
     */
    suspend fun flushNow() {
        boardsJob?.cancel()
        runCatching { pushBoards() }
    }

    /**
     * Drop everything held for the session that is ending.
     *
     * Cancels pending pushes as well as clearing [addedAt], and the cancellation
     * is the important half: a debounced write queued by the OUTGOING user would
     * otherwise fire moments after the next one signs in and post the wrong
     * account's boards under their credentials.
     */
    suspend fun reset() {
        boardsJob?.cancel()
        mutex.withLock {
            addedAt.clear()
            gate.reset()
        }
    }

    /**
     * The user's boards exactly as they would be pushed.
     *
     * Public so the widget directory and anything else that needs the account's
     * real shape reads the same join everything else does, rather than
     * re-deriving it and drifting.
     *
     * Locked, because building the list WRITES: [addedAt] is a plain map and
     * `getOrPut` inserts a row for any board first seen here. [fetch] already
     * takes this lock for exactly that reason and says why — an unsynchronised
     * concurrent mutation is a data race, not merely a stale read. This method
     * being public and unlocked left the invitation in the doc comment above
     * while leaving the hazard in place: any caller taking it up would have been
     * racing a debounced push or a login fetch.
     */
    suspend fun currentBoards(): List<Board> = mutex.withLock { buildBoards() }

    /**
     * [currentBoards] for callers that ALREADY hold [mutex].
     *
     * `kotlinx.coroutines.sync.Mutex` is not reentrant, so `pushBoards` — which
     * runs entirely under the lock — must not call the public wrapper. It would
     * deadlock, permanently and silently: the push coroutine suspends forever,
     * the gate is never acknowledged, and every later flush queues behind it.
     */
    private suspend fun buildBoards(): List<Board> {
        UserSettings.ensureLoaded()
        val now = Clock.System.now().toEpochMilliseconds()
        return Board.fromSelections(
            selections = sqlStorage.getAllSelections(),
            config = UserSettings::configOf,
            addedAt = { id -> addedAt.getOrPut(id) { now } },
        )
            // Sorted so the array's order and `config.position` always agree,
            // and a reader that honours either gets the same home screen.
            .sortedWith(compareBy({ it.config.sortKey }, { it.addedAt }))
    }

    private suspend fun pushBoards() = mutex.withLock {
        // Captured before anything else, so an edit racing this request is
        // counted against the NEXT push rather than cleared by this one's
        // acknowledgement.
        val target = gate.pending() ?: return@withLock
        if (Platform.storageManager.loadString(UID_KEY).isNullOrBlank()) return@withLock
        // The unlocked variant — this block already holds [mutex]. See [buildBoards].
        val boards = buildBoards()
        // `updatedAt` is this device's clock, and the server drops a write older
        // than the one it holds. Device clocks are not synchronised, so this
        // approximates "later" rather than guaranteeing it — acceptable because
        // the loser of a race is a board list that gets re-pushed on the next
        // change, and never a board that silently disappears.
        val response = api.syncBoards(
            boards = boards,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            allowEmpty = gate.allowEmpty(),
        )
        // Acknowledged on `success`, not on `applied`. A declined write (stale,
        // or an empty list the server refused) is a 200 it understood, and
        // resending it would be refused for the same reason; a transport failure
        // never reaches here — the call throws — leaving the gate pending, so
        // the nightly flush retries it.
        if (response.success) gate.accepted(target, listWasEmpty = boards.isEmpty())
        Unit
    }

    companion object {
        /**
         * Long enough to collapse a slider drag or a drag-reorder into one
         * write, short enough that a user who changes a setting and immediately
         * switches apps has usually already synced.
         */
        const val DEBOUNCE_MS = 2_500L
        private const val UID_KEY = "firebase_user_uid"
    }
}
