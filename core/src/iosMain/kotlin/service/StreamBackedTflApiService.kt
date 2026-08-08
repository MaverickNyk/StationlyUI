package com.stationly.core.service

import com.stationly.core.model.*
import com.stationly.core.platform.LiveStreamManager
import com.stationly.core.platform.PushTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * iOS's [TflApiService]: predictions and line status — the two live,
 * repeatedly-fetched endpoints — are served by [LiveStreamManager] (the
 * WebSocket stream) with REST as a **hedge** behind it. Everything else
 * (mode/line lookup, station search, route info — one-shot dropdown data, not
 * live board data) delegates straight to [rest] unchanged.
 *
 * ## Why the stream alone was not enough
 * The socket is the right transport in steady state: it is already open, the
 * server pushes without being asked, and a subscribe costs one frame. It is the
 * WRONG transport at exactly the moments a user is watching. A cold socket has
 * to do a TCP connect, a TLS handshake, an HTTP upgrade, a subscribe and then
 * wait for the server's cached snapshot before [LiveStreamManager.ensureStation]
 * can return — and its ceiling is `ENSURE_TIMEOUT_MS`, six seconds. That is the
 * app's first launch, and it is every return from the background where the
 * socket was reaped. A plain REST GET is one round trip.
 *
 * ## Hedging, not racing
 * Firing both every time would halve the worst case and DOUBLE the request
 * volume — for the common case, where the socket is warm and answers in well
 * under the head start, the second request is pure waste.
 *
 * So the stream goes first alone and REST is only asked if the stream has not
 * answered within [HEDGE_DELAY_MS], or has already failed. A warm socket wins
 * inside that window and REST is never called at all; a cold or wedged one gets
 * overtaken. The cost is bounded by how often the socket is actually slow.
 *
 * ## Cancelling the loser does not unsubscribe
 * This is what makes hedging safe here rather than merely fast.
 * [LiveStreamManager.ensureStation] registers an awaiter, sends a subscribe and
 * waits; when it is cancelled its `finally` discards only the AWAITER. The
 * subscription itself lives on the socket's own state and survives. So when
 * REST wins the race, the station is still subscribed and the stream keeps
 * pushing live updates for it — REST buys the first answer, the socket keeps
 * doing the job it is good at.
 *
 * ## One write path, unchanged
 * Both transports return the same [PredictionsPayload] through the same interface, and
 * the caller feeds it to the same `SyncPredictionsUseCase`. Inbound stream
 * FRAMES still go through `ProcessPredictionsUseCase` exactly as before. Nothing
 * here adds a second way for data to reach SQLite.
 */
class StreamBackedTflApiService(private val rest: TflApiService) : TflApiService {

    override suspend fun getModes(): List<TransportMode> = rest.getModes()
    override suspend fun getLines(mode: String): List<LineInfo> = rest.getLines(mode)
    override suspend fun searchStations(searchKey: String): List<StationBrief> = rest.searchStations(searchKey)
    override suspend fun getRoute(lineId: String): LineRouteResponse = rest.getRoute(lineId)

    override suspend fun getLineStatuses(lineId: String?, mode: String?): List<LineStatus> {
        // Both null-checks matter: a query missing either half is the
        // "all lines for a mode" / "everything" shape, which the stream has no
        // subscription for and REST still serves.
        if (lineId == null || mode == null) return rest.getLineStatuses(lineId, mode)
        return hedged(
            what = "line:$lineId",
            primary = { LiveStreamManager.ensureLine(lineId) },
            fallback = { rest.getLineStatuses(lineId, mode) },
        )
    }

    override suspend fun getPredictions(naptanId: String): PredictionsPayload = hedged(
        what = "station:$naptanId",
        primary = { LiveStreamManager.ensureStation(naptanId) },
        fallback = { rest.getPredictions(naptanId) },
    )

    /**
     * Run [primary]; if it has not succeeded within [HEDGE_DELAY_MS] — or has
     * already failed — run [fallback] alongside it and take whichever answers
     * first. Throws only if BOTH fail.
     *
     * Deliberately built out of [CompletableDeferred] and a counter rather than
     * `select`: the outcome wanted here is "the first SUCCESS", and a select
     * over two `onAwait` clauses reports the first to SETTLE, so a fast failure
     * from one side would beat a good answer from the other.
     */
    private suspend fun <T> hedged(
        what: String,
        primary: suspend () -> T,
        fallback: suspend () -> T,
    ): T = coroutineScope {
        val winner = CompletableDeferred<T>()
        // Completes whatever the outcome, so the hedge can start the moment the
        // primary FAILS instead of serving out a head start nobody is using.
        val primarySettled = CompletableDeferred<Unit>()

        val lock = Mutex()
        var lastError: Throwable? = null

        suspend fun attempt(block: suspend () -> T, viaRest: Boolean) {
            try {
                val value = block()
                // First one home wins; a later `complete` is a no-op, so the
                // loser finishing normally cannot overwrite the answer already
                // handed to the caller.
                if (winner.complete(value) && viaRest) {
                    PushTrace.log("stream:hedge REST won $what")
                }
            } catch (e: TimeoutCancellationException) {
                // ── The bug this catch exists for ──
                // `LiveStreamManager.ensureStation` bounds itself with
                // `withTimeout`, and that throws TimeoutCancellationException,
                // which IS a CancellationException. Letting it fall into the
                // clause below re-threw it as "the hedge was cancelled", so a
                // timed-out socket did not count as a failed attempt — and if
                // REST then failed too, nothing ever completed `winner` and the
                // caller waited for ever, holding this station's fetch mutex
                // with it. A stream timeout is an ATTEMPT failing, not this
                // coroutine being cancelled.
                lock.withLock { lastError = e }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lock.withLock { lastError = e }
            }
        }

        val primaryJob = launch {
            try {
                attempt(primary, viaRest = false)
            } finally {
                primarySettled.complete(Unit)
            }
        }
        val fallbackJob = launch {
            // Head start for the socket. Cut short if the primary settles
            // early — a success means we are about to return and never ask,
            // a failure means waiting the rest of the window helps nobody.
            withTimeoutOrNull(HEDGE_DELAY_MS) { primarySettled.await() }
            if (winner.isCompleted) return@launch
            attempt(fallback, viaRest = true)
        }

        // ── The guard that makes hanging impossible ──
        //
        // Both attempts having FINISHED with nobody having won is the only
        // state in which `winner` would never complete, and it is reached by
        // more routes than a failure counter can enumerate (a throw, a timeout,
        // one job cancelled on its own). Watching for "both jobs are done" is
        // the same conclusion drawn from the jobs themselves, so no new failure
        // mode can slip past it.
        val watchdog = launch {
            primaryJob.join()
            fallbackJob.join()
            if (!winner.isCompleted) {
                winner.completeExceptionally(
                    lock.withLock { lastError }
                        ?: IllegalStateException("no transport answered for $what")
                )
            }
        }

        try {
            winner.await()
        } finally {
            // The loser is cancelled rather than left running: it would hold a
            // connection and, for the stream side, an awaiter. Cancelling a
            // finished job is a no-op, so the winner is unaffected.
            primaryJob.cancel()
            fallbackJob.cancel()
            watchdog.cancel()
        }
    }

    private companion object {
        /**
         * How long the socket gets on its own before REST is asked too.
         *
         * A warm subscribe round trip is well inside this, so the steady state
         * costs nothing extra. It is short enough that a cold open — which has
         * a handshake and an upgrade to get through first — is overtaken rather
         * than waited out.
         */
        const val HEDGE_DELAY_MS = 250L
    }
}
