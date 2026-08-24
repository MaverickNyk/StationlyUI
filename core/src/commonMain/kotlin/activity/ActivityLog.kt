package com.stationly.core.activity

import com.stationly.core.platform.Platform
import com.stationly.core.repository.SqlStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Where the app records what the user did — local first, uploaded later.
 *
 * ## Recording must never be something a caller has to think about
 * Every method here is fire-and-forget and swallows its own failures. A call
 * site is a `record(...)` in the middle of a button handler, and the one thing
 * it must not do is change how that button behaves — a telemetry write that
 * can throw, block, or fail a user's action is worse than no telemetry.
 * `Result` is deliberately not returned anywhere: there is nothing a caller
 * could usefully do with it.
 *
 * ## Local first, and on a schedule
 * Events go to SQLite immediately and leave the device on a schedule (nightly,
 * with a foreground fallback — see [ActivityUploader]). That is what makes the
 * cost bearable: a user who opens the app forty times a day is one upload, not
 * forty, and none of those opens is waiting on a network round-trip.
 *
 * The queue survives login and logout by construction — `clearAllData` names
 * its tables and this is not one of them — which is the only way the events
 * around an auth change can be recorded at all.
 *
 * ## Common to both platforms on purpose
 * Nothing here is iOS-specific. Android wires the same [record] calls into its
 * own call sites and schedules the same [ActivityUploader] from WorkManager;
 * the queue, the batching, the cap and the retry rules are shared so the two
 * platforms cannot drift into reporting subtly different things.
 */
object ActivityLog {

    /**
     * Hard cap on the queue.
     *
     * A queue this long means uploads have been failing for weeks, and the
     * alternative to a cap is a database that grows without bound on a device
     * that can never drain it. Overflow drops the OLDEST rows: by the time it
     * matters, recent activity is the part still worth having.
     */
    private const val MAX_QUEUED_EVENTS = 5_000

    /** How much headroom to reclaim when the cap is hit, so trimming is rare. */
    private const val TRIM_TO = 4_000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val PROPS_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

    /**
     * Serialises enqueues against each other AND against the uploader's drain.
     *
     * Without it the cap check races: two events recorded in the same frame
     * both read a count below the limit and both insert, and — worse — a trim
     * running while a batch is in flight can delete rows the uploader has
     * already sent but not yet acknowledged, which is the one way this design
     * can lose an event it accepted.
     */
    internal val mutex = Mutex()

    /**
     * Detached, because recording is fire-and-forget and the caller's scope is
     * usually a ViewModel that may be cleared by the very navigation the event
     * is describing. A `board.added` cancelled by the screen closing is exactly
     * the event we came for.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val storage: SqlStorage get() = Platform.sqlStorage

    /**
     * Record one event. Returns immediately; the write happens off-thread.
     *
     * [props] values are stringified because the wire format is flat strings —
     * see [ActivityEvent.props] for why there is no typed payload per event.
     */
    fun record(name: String, props: Map<String, String> = emptyMap()) {
        val event = ActivityEvent(
            id = newEventId(),
            name = name,
            t = Clock.System.now().toEpochMilliseconds(),
            props = props,
        )
        scope.launch { persist(event) }
    }

    /** [record] with the common single-property shape spelled out. */
    fun record(name: String, key: String, value: String) = record(name, mapOf(key to value))

    /**
     * Enqueue and await the write.
     *
     * For the handful of call sites that are about to end the process or the
     * session — a logout, a scene going to background — where a detached
     * coroutine is not guaranteed to survive long enough to run.
     *
     * @param uid files the event against this account instead of whoever storage
     *   says is signed in. Only for an event ABOUT a teardown, which by
     *   definition cannot read the uid itself — see [persist].
     */
    suspend fun recordBlocking(
        name: String,
        props: Map<String, String> = emptyMap(),
        uid: String? = null,
    ) {
        persist(
            ActivityEvent(
                id = newEventId(),
                name = name,
                t = Clock.System.now().toEpochMilliseconds(),
                props = props,
            ),
            uidOverride = uid,
        )
    }

    private suspend fun persist(
        event: ActivityEvent,
        uidOverride: String? = null,
    ) = withContext(Dispatchers.Default) {
        runCatching {
            mutex.withLock {
                // The uid is stamped HERE, not at upload time: an event belongs
                // to whoever was signed in when it happened. A queue that spans
                // a logout would otherwise file yesterday's activity under
                // today's account.
                //
                // ## Why the override exists
                // For one event this read is guaranteed to be too late.
                // `auth.forced_logout` describes a session ending, and the
                // teardown that ends it wipes this very key — so the row landed
                // with an EMPTY uid, measured twice on device. That is not a
                // cosmetic loss: `ActivityUploader.flush` selects `WHERE uid = ?`,
                // so a blank row matches nobody, never uploads, and is eventually
                // trimmed. The one event whose purpose is to be readable
                // afterwards was the one guaranteed to be thrown away.
                //
                // `NetworkModule.forceLogout` therefore passes the uid from the
                // `sub` claim of the token the server just rejected, which no
                // teardown can erase.
                val uid = uidOverride?.takeIf { it.isNotBlank() }
                    ?: Platform.storageManager.loadString(UID_KEY).orEmpty()
                storage.enqueueActivityEvent(
                    id = event.id,
                    uid = uid,
                    name = event.name,
                    t = event.t,
                    props = json.encodeToString(PROPS_SERIALIZER, event.props),
                )
                if (storage.activityCount() > MAX_QUEUED_EVENTS) {
                    storage.trimActivityEvents((MAX_QUEUED_EVENTS - TRIM_TO).coerceAtLeast(1))
                }
            }
        }
    }

    /**
     * A unique id per event, without a UUID dependency in commonMain.
     *
     * Uniqueness is what makes an upload idempotent (see [ActivityEvent.id]),
     * and it only has to hold within ONE device's queue — the server namespaces
     * by uid and device. The case it must survive is several events recorded in
     * the same millisecond, which one tap fanning out to three call sites
     * genuinely produces.
     *
     * ## Why there is no counter
     * This used to mix in `++counter`, which looked like the strongest part of
     * the id and was the only unsound one: [record] is called from arbitrary
     * threads, so the increment was a data race, and two callers could read the
     * same value and mint the SAME id. A duplicate id is worse than no id —
     * the server appends with a set union, so the second event would be
     * silently swallowed as a replay of the first.
     *
     * 96 bits of randomness has no such failure mode and needs no shared state.
     * At the volumes here (a few hundred events a day) a collision is far less
     * likely than the database being corrupted underneath it, and the enqueue
     * is `INSERT OR IGNORE`, so even then it costs one event rather than
     * anything worse.
     */
    private fun newEventId(): String {
        val now = Clock.System.now().toEpochMilliseconds()
        val hi = kotlin.random.Random.nextLong()
        val lo = kotlin.random.Random.nextInt()
        return "${now.toString(36)}-${hi.toULong().toString(36)}-${lo.toUInt().toString(36)}"
    }

    internal const val UID_KEY = "firebase_user_uid"
}
