package com.stationly.core.repository

import com.stationly.core.model.*
import com.stationly.core.service.TflApiService
import com.stationly.core.platform.StorageManager
import com.stationly.core.usecase.SyncPredictionsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Departure Repository
 * 
 * Manages real-time departure data, line status, and predictions.
 * This is the core of the widget functionality.
 * 
 * Mirrors the real-time functionality from MindTheTimeAndroid but
 * extracted as a reusable, platform-agnostic repository.
 */
class DepartureRepository(
    private val apiService: TflApiService,
    private val storageManager: StorageManager,
    private val sqlStorage: SqlStorage,
    private val syncPredictionsUseCase: SyncPredictionsUseCase
) {
    
    // In-memory cache for real-time data
    private val _predictions = MutableStateFlow<List<PredictionItem>>(emptyList())
    val predictions: StateFlow<List<PredictionItem>> = _predictions.asStateFlow()
    
    private val _lineStatus = MutableStateFlow<LineStatus?>(null)
    val lineStatus: StateFlow<LineStatus?> = _lineStatus.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Per-station mutex so a single station never has two concurrent
     * `fetchInitialData` calls in flight. Different stations can still
     * fetch in parallel (independent mutex per key).
     *
     * Why: the FCM listener, the home pull-to-refresh, and the widget
     * refresh button can ALL trigger a fetch for the same station at
     * overlapping moments — they'd all write to SQL concurrently. SQLite
     * itself is serialised so there's no corruption, but the "winning"
     * write determines what surfaces see, and the in-flight `_lineStatus`
     * / `_isLoading` flow updates race each other. One-at-a-time per
     * station eliminates that flapping without blocking parallelism
     * across stations.
     */
    private val fetchMutexes = mutableMapOf<String, Mutex>()
    private val fetchMutexesLock = Mutex()

    private suspend fun mutexFor(stationId: String): Mutex =
        fetchMutexesLock.withLock {
            fetchMutexes.getOrPut(stationId.lowercase()) { Mutex() }
        }

    /**
     * Fetch line status and predictions for ONE selection.
     *
     * The single-selection door onto [refreshBoards], which owns the fetching,
     * the deduplication and the per-station locking. It had its own copy of all
     * of that until the fan-out existed; keeping both meant two implementations
     * of "refresh a board" that had already begun to differ in their error
     * handling, and the shipping Android app runs this one.
     *
     * What it adds on top is the cache fallback: a station being ADDED has no
     * rows yet and a failed first fetch would leave the board with nothing, so
     * a cached status is better than none. The fan-out has no equivalent because
     * its callers are refreshing boards that are already on screen.
     */
    suspend fun fetchInitialData(selection: UserSelection) {
        refreshBoards(listOf(selection))
        // Publish the best status this line has, whatever just happened.
        // [refreshBoards] writes a freshly fetched status to SQL before
        // returning, so this reads the NEW one when the fetch worked and the
        // cached one when it did not — which is what the hand-written version
        // did with an outer catch, without needing to know which case it is in.
        sqlStorage.getLineStatus(selection.mode, selection.line)?.let { _lineStatus.value = it }
    }

    /** What a fan-out refresh managed to do. */
    data class RefreshResult(val requested: Int, val succeeded: Int) {
        /**
         * Distinguishes "the network is gone" from "one stop is having a bad
         * time". Only the first should raise the offline banner — a single
         * failed stop already shows as a board that did not move.
         */
        val allFailed: Boolean get() = requested > 0 && succeeded == 0
    }

    /**
     * Refresh MANY boards at once: concurrently, deduplicated, and reporting
     * each one the moment it lands.
     *
     * ## What this replaces and why it was slow
     * Callers used to loop [fetchInitialData] over every selection. That is
     * serial, and it asks for the same thing several times over:
     *
     *  - **One request per SELECTION, not per stop.** A rail station tracked in
     *    both directions is two selections sharing one naptan, and the
     *    predictions endpoint answers with every line and direction calling
     *    there — so the second call fetches a payload identical to the first.
     *  - **One line-status request per selection.** Seven stations across five
     *    lines asked fourteen times for five answers.
     *
     * Deduplicating turns ~2N requests into (unique stops + unique lines), and
     * running them concurrently turns the remainder into roughly ONE round trip
     * of wall time instead of a sum. On a phone tracking seven stations that is
     * the difference between a pull-to-refresh measured in seconds and one
     * measured in a round trip.
     *
     * ## Incremental by design
     * [onUpdated] fires per selection as soon as THAT stop's payload is in
     * SQLite, so a board can repaint while its neighbours are still in flight.
     * Waiting for the slowest stop before showing any of them would throw away
     * most of what the concurrency just bought.
     *
     * ## Failure is per stop
     * One naptan timing out must not discard the six that answered, so each job
     * keeps its own exceptions. The caller gets counts and decides: see
     * [RefreshResult.allFailed] for the only case that means "offline".
     *
     * The per-station [mutexFor] is still held across each stop's fetch AND its
     * writes, which is what it was always for — concurrency here is ACROSS
     * stops, never within one.
     */
    suspend fun refreshBoards(
        selections: List<UserSelection>,
        onUpdated: suspend (UserSelection) -> Unit = {},
    ): RefreshResult = coroutineScope {
        if (selections.isEmpty()) return@coroutineScope RefreshResult(0, 0)

        _isLoading.value = true
        try {
            // ── Unique lines ──
            // Keyed on (mode, line) because that is what the status endpoint and
            // the SQL table are keyed on; two directions of one line are one
            // status.
            val statusJobs = selections
                .map { it.mode.lowercase() to it.line.lowercase() }
                .distinct()
                .map { (mode, line) ->
                    async {
                        guarded {
                            apiService.getLineStatuses(line, mode).firstOrNull()?.also { status ->
                                sqlStorage.saveLineStatus(status)
                                // Deliberately only the primary selection's line
                                // feeds the single-valued flow. It predates
                                // multi-station and last-writer-wins across
                                // several concurrent lines would make it
                                // arbitrary; consumers of a specific line read
                                // SQL, which is keyed properly.
                                val primary = selections.first()
                                if (primary.line.equals(line, true) &&
                                    primary.mode.equals(mode, true)
                                ) {
                                    _lineStatus.value = status
                                }
                            }
                        }
                    }
                }

            // ── Unique stops ──
            val stopJobs = selections
                .groupBy { it.station }
                .map { (naptan, atThisStop) ->
                    async {
                        val payload = guarded {
                            // A hard ceiling on ONE stop, so no transport-level
                            // stall can hold the caller's spinner open. Every
                            // layer below is already bounded; this is the
                            // backstop that does not depend on them being right,
                            // and it is per stop so a bad one cannot delay the
                            // rest of the board.
                            withTimeout(STOP_TIMEOUT_MS) {
                                mutexFor(naptan).withLock {
                                    val fetched = apiService.getPredictions(naptan)
                                    // Synced against EVERY selection reading
                                    // from this stop, inside the same lock the
                                    // fetch took — one payload, N boards, no
                                    // second request.
                                    atThisStop.forEach { selection ->
                                        guarded { syncPredictionsUseCase.execute(fetched, selection) }
                                    }
                                    fetched
                                }
                            }
                        }
                        // Announced whatever happened: a stop that failed still
                        // has cached rows worth re-reading, and a caller that
                        // only heard about successes could not clear a spinner.
                        atThisStop.forEach { onUpdated(it) }
                        payload != null
                    }
                }

            statusJobs.awaitAll()
            val succeeded = stopJobs.awaitAll().count { it }
            RefreshResult(requested = stopJobs.size, succeeded = succeeded)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Run [block], swallowing its failure but never its cancellation.
     *
     * `runCatching` would be wrong here: it catches [CancellationException] too,
     * which turns a cancelled refresh into one that silently carries on and
     * reports success.
     */
    private suspend fun <T> guarded(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: TimeoutCancellationException) {
            // OUR timeout expiring, not the caller cancelling us. It has to be
            // caught BEFORE the clause below, because it is a
            // CancellationException — rethrowing it would cancel this stop's
            // Deferred and, through `awaitAll`, fail the whole refresh over one
            // slow stop.
            println("DepartureRepository: timed out: ${e.message}")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("DepartureRepository: ${e::class.simpleName}: ${e.message}")
            null
        }

    private companion object {
        /**
         * Ceiling on one stop's refresh.
         *
         * Comfortably above the transports beneath it (the stream bounds itself
         * at 6s, the REST client at 10s) because it is not meant to pre-empt
         * them — it is the backstop for the case where one of them is wrong
         * about its own bound, which has happened.
         */
        const val STOP_TIMEOUT_MS = 15_000L
    }

    /**
     * Process incoming FCM payload
     * This is called when FCM message arrives
     */
    suspend fun processPredictionsPayload(payload: PredictionsPayload) {
        // Extract predictions from payload
        val allPredictions = payload.lines.values.flatMap { lineData ->
            lineData.dirs.values.flatMap { dirData ->
                dirData.preds
            }
        }
        
        // Update predictions
        _predictions.value = allPredictions
        
        // Note: In real app we would map PredictionItem to PredictionDisplay here
        // For simplicity, we'll implement that mapping when we do the unified formatters
    }
    
    /**
     * Clear departure data
     */
    suspend fun clear() {
        _predictions.value = emptyList()
        _lineStatus.value = null
        sqlStorage.clearAllPredictions()
        sqlStorage.clearLineStatuses()
    }
}