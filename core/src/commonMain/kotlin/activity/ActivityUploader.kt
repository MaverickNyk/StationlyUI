package com.stationly.core.activity

import com.stationly.core.platform.Platform
import com.stationly.core.service.SduiApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drains the local activity queue to the backend.
 *
 * ## Why the schedule is "nightly, plus a fallback", and not just nightly
 * Neither platform will promise a wake at a particular hour. iOS decides when —
 * or whether — to run a background task, learning from when the user actually
 * opens the app; a phone in Low Power Mode gets none at all. Android's
 * WorkManager is more reliable but still deferred and still killed by some OEM
 * battery managers.
 *
 * So "nightly" is the intent and never the guarantee, and a device that simply
 * never gets a background wake must still be able to report. [flushIfStale] is
 * that path: on foreground, if the oldest queued event is older than
 * [STALE_AFTER_MS], upload then. On a healthy device it does nothing, because
 * the nightly run already emptied the queue.
 *
 * ## What happens to a batch the server will not take
 * Three outcomes, and conflating any two of them loses data or loops forever:
 *  - accepted → delete locally,
 *  - rejected as malformed (400) → ALSO delete, because the same bytes will be
 *    refused again and a poisoned batch at the head of a FIFO queue blocks
 *    every event behind it,
 *  - anything else (offline, 401, 429, 5xx) → keep and try later.
 *
 * ## Re-sending is safe
 * Every event carries a client-generated id and the server appends with a set
 * union, so the dangerous case — batch stored, response lost, batch resent —
 * costs one duplicate request and stores nothing twice.
 */
object ActivityUploader {

    /**
     * Events per request. The server caps at 500; staying under it means a
     * device that has been offline for a week pages through its backlog
     * instead of having the whole thing rejected for being too large.
     */
    private const val BATCH_SIZE = 200

    /** Most requests one flush will make, so a huge backlog cannot run forever
     *  inside a background task with a few seconds of budget. */
    private const val MAX_BATCHES_PER_FLUSH = 3

    /**
     * How old the oldest queued event may get before a foreground flush steps
     * in for the background task that evidently is not running.
     *
     * Longer than a day on purpose. Shorter would fire on a device whose
     * nightly run is simply due in a few hours, spending a foreground network
     * call to do work that was already scheduled.
     */
    private const val STALE_AFTER_MS = 26L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    private val storage get() = Platform.sqlStorage

    /**
     * Upload everything queued for the signed-in user.
     *
     * Returns whether anything was uploaded, which iOS reports back as its
     * background-task result — a task that reports "no data" too often gets
     * scheduled less generously.
     *
     * A signed-out device does nothing and keeps its queue: the events are
     * still real and are uploaded under whoever signs in next.
     */
    suspend fun flush(api: SduiApiService, deviceId: String, platform: String, appVersion: String?): Boolean =
        withContext(Dispatchers.Default) {
            val uid = Platform.storageManager.loadString(ActivityLog.UID_KEY)
            if (uid.isNullOrBlank()) return@withContext false

            // One flush at a time. Two are genuinely possible — a background
            // task firing while a foreground flush is mid-request — and while
            // the upload is idempotent, both would read the same rows and send
            // the same batch twice for nothing. `tryLock` rather than `lock`
            // because the second caller wants to be skipped, not queued behind
            // a request it would then repeat.
            if (!inFlight.tryLock()) return@withContext false
            try {
                var uploaded = false
                repeat(MAX_BATCHES_PER_FLUSH) {
                    // ── The lock is taken TWICE, and released across the request ──
                    //
                    // It used to wrap the whole read-send-delete cycle, which
                    // put a network round-trip inside the same lock every
                    // `ActivityLog.record()` needs: recording an event stalled
                    // for as long as an upload took, up to its timeout, on a
                    // path whose entire promise is that it never makes a caller
                    // wait.
                    //
                    // Releasing it is safe because of what the other holders
                    // do. An enqueue only ADDS rows, which cannot be in a batch
                    // already read. A trim deletes the OLDEST rows — which is
                    // this batch — but the delete below is by id and therefore
                    // idempotent, so the worst case is rows already gone.
                    val rows = ActivityLog.mutex.withLock { storage.activityBatch(uid, BATCH_SIZE) }
                    if (rows.isEmpty()) return@withContext uploaded

                    val outcome = runCatching {
                        api.uploadActivity(
                            ActivityBatchRequest(
                                deviceId = deviceId,
                                platform = platform,
                                appVersion = appVersion,
                                events = rows.map {
                                    ActivityEvent(id = it.id, name = it.name, t = it.t, props = decodeProps(it.props))
                                },
                            )
                        )
                    }.getOrElse { ActivityUploadOutcome.Retry }

                    // Retry keeps the batch and stops the loop — a second
                    // request would fail for the same reason.
                    if (outcome == ActivityUploadOutcome.Retry) return@withContext uploaded

                    // Rejected deletes too — see the class note. Losing a
                    // malformed batch is the lesser failure against a queue
                    // that never drains again.
                    ActivityLog.mutex.withLock { storage.deleteActivityEvents(rows.map { it.id }) }
                    if (outcome == ActivityUploadOutcome.Accepted) uploaded = true

                    // A short read means the queue is drained.
                    if (rows.size < BATCH_SIZE) return@withContext uploaded
                }
                uploaded
            } finally {
                inFlight.unlock()
            }
        }

    /** Guards against two flushes running at once — see [flush]. */
    private val inFlight = Mutex()

    /**
     * Upload only if the queue has gone stale — the foreground safety net.
     *
     * Cheap enough to call on every foreground: on a device whose background
     * task is running it reads one integer out of SQLite and returns.
     */
    suspend fun flushIfStale(api: SduiApiService, deviceId: String, platform: String, appVersion: String?): Boolean =
        withContext(Dispatchers.Default) {
            val oldest = runCatching { storage.oldestActivityEventAt() }.getOrNull() ?: return@withContext false
            val age = Clock.System.now().toEpochMilliseconds() - oldest
            if (age < STALE_AFTER_MS) return@withContext false
            flush(api, deviceId, platform, appVersion)
        }

    /**
     * Decode the stored props blob.
     *
     * Tolerant on purpose: a row whose JSON cannot be parsed uploads with EMPTY
     * props rather than being dropped. The event's name and timestamp are the
     * part that carries most of the meaning, and a decode failure here would
     * otherwise put an undeletable row at the head of the queue.
     */
    private fun decodeProps(raw: String): Map<String, String> = runCatching {
        (json.parseToJsonElement(raw) as? JsonObject)
            ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.jsonPrimitive.content }
            .orEmpty()
    }.getOrElse { emptyMap() }
}
