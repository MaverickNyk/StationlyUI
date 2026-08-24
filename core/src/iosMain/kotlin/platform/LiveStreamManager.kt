package com.stationly.core.platform

import com.stationly.core.config.AppConfig
import com.stationly.core.model.PredictionsPayload
import com.stationly.core.model.LineStatus
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.FormatDeparturesUseCase
import com.stationly.core.usecase.ProcessPredictionsUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/**
 * Persistent connection to the live departure stream
 * (`wss://.../api/v1/stream` — see stationly-backend's
 * `docs/LIVE_STREAM_HANDOVER.md`).
 *
 * Foreground-only by design: [notifyForeground] connects (idempotent) and
 * subscribes every saved selection; [notifyBackground] closes cleanly — no
 * background-mode entitlements are used. While foregrounded, any read-loop
 * termination (exception or a server-initiated close) schedules a reconnect
 * with exponential backoff (1s→30s, jittered). That is deliberately the
 * *only* reconnect trigger: there is no separate frame-staleness watchdog,
 * because a legitimately quiet station can go minutes without a frame and
 * that must never be mistaken for a dead socket. An explicit app-level
 * heartbeat (WS ping every 15s) keeps a truly dead peer surfacing quickly as
 * a send failure instead of hanging silently.
 *
 * Every inbound `snapshot`/`update` is handed off to the exact same
 * [ProcessPredictionsUseCase] pipeline FCM pushes already use — so SQLite
 * writes, the widget refresh and [com.stationly.core.util.FreshDataNotifier]
 * behave identically regardless of which transport produced the data.
 */
object LiveStreamManager {

    /**
     * How long [ensureStation]/[ensureLine] wait for a snapshot.
     *
     * A cached snapshot comes back in well under a second; the only slow case
     * is a genuinely cold station needing a TfL prefetch. 6s keeps the
     * pull-to-refresh spinner honest — past that, the caller's existing
     * catch-and-degrade path beats making the user stare at a spinner.
     */
    private const val ENSURE_TIMEOUT_MS = 6_000L

    /** WS ping interval — see the heartbeat note in the class doc. */
    private const val HEARTBEAT_MS = 15_000L

    /** Reconnect backoff: [RECONNECT_BASE_MS] doubling up to [RECONNECT_MAX_MS], plus jitter. */
    private const val RECONNECT_BASE_MS = 1_000L
    private const val RECONNECT_MAX_MS = 30_000L
    private const val RECONNECT_JITTER_MS = 500L

    // Matches NetworkModule.json exactly — the REST path already needed
    // isLenient/coerceInputValues for these payload shapes (e.g. an
    // unquoted lastUpdatedTime), and the stream sends the same shapes.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val client: HttpClient by lazy {
        HttpClient(Darwin) {
            install(WebSockets)
        }
    }

    // Mirrors PushPayloadBridge's construction in Platform.ios.kt — same
    // pipeline, different transport feeding it.
    private val processUseCase: ProcessPredictionsUseCase by lazy {
        ProcessPredictionsUseCase(
            departureRepository = DepartureRepository(
                NetworkModule.tflApi,
                Platform.storageManager,
                Platform.sqlStorage,
                SyncPredictionsUseCase(Platform.sqlStorage)
            ),
            widgetManager = Platform.widgetManager,
            storageManager = Platform.storageManager,
            formatDeparturesUseCase = FormatDeparturesUseCase(),
            sqlStorage = Platform.sqlStorage
        )
    }

    private val stateMutex = Mutex()
    private var session: DefaultClientWebSocketSession? = null
    private var socketJob: Job? = null
    private var isForeground = false
    private var generation = 0
    private var backoffMs = RECONNECT_BASE_MS
    // True only between a server `ready` frame and the socket dying. Gates
    // whether pull-to-refresh can reuse the live connection.
    private var isReady = false

    private val subscribedStations = mutableSetOf<String>()
    private val subscribedLines = mutableSetOf<String>()
    private val pendingStations = mutableMapOf<String, MutableList<CompletableDeferred<PredictionsPayload>>>()
    private val pendingLines = mutableMapOf<String, MutableList<CompletableDeferred<LineStatus>>>()

    // ── Public lifecycle ──

    fun notifyForeground() {
        scope.launch {
            stateMutex.withLock { isForeground = true }
            openIfNeeded()
        }
    }

    fun notifyBackground() {
        scope.launch {
            val gen = stateMutex.withLock {
                isForeground = false
                ++generation
            }
            closeCurrentSession(gen)
        }
    }

    /**
     * Pull-to-refresh. A live socket is NOT torn down: the server replays a
     * cached `snapshot` for every id on each subscribe frame, so forcing a
     * resubscribe repaints the board in well under a second. Reconnecting
     * instead would cost a TLS handshake + auth round-trip before the first
     * byte of data — which is exactly the delay this avoids.
     *
     * A dead or never-established socket still gets the full force-reconnect,
     * since that is the only thing that can fix it.
     */
    fun notifyPullToRefresh() {
        scope.launch {
            val healthy = stateMutex.withLock {
                isForeground = true
                session != null && isReady
            }
            if (healthy) {
                PushTrace.log("stream:refresh reusing live socket")
                val (st, ln) = stateMutex.withLock {
                    subscribedStations.toList() to subscribedLines.toList()
                }
                requestSubscribe(stations = st, lines = ln, force = true)
            } else {
                val gen = stateMutex.withLock { ++generation }
                closeCurrentSession(gen)
                stateMutex.withLock { backoffMs = RECONNECT_BASE_MS }
                PushTrace.log("stream:reconnect trigger=pullToRefresh(dead)")
                openIfNeeded()
            }
        }
    }

    // ── Used by StreamBackedTflApiService (the fetchInitialData path) ──

    suspend fun ensureStation(naptanId: String): PredictionsPayload {
        val deferred = CompletableDeferred<PredictionsPayload>()
        stateMutex.withLock { pendingStations.getOrPut(naptanId) { mutableListOf() }.add(deferred) }
        try {
            return withTimeout(ENSURE_TIMEOUT_MS) {
                requestSubscribe(stations = listOf(naptanId), force = true)
                deferred.await()
            }
        } finally {
            // A CompletableDeferred has no parent job, so a timeout does NOT
            // discard it — without this the entry outlives the call and every
            // subsequent timeout on the same id appends another one. See
            // [discardPending].
            discardPending(pendingStations, naptanId, deferred)
        }
    }

    /**
     * Takes no `mode`: the stream subscribes by line id alone. The caller
     * still checks mode for null, because that is what distinguishes "give me
     * this specific line" from the all-lines query that stays on REST.
     */
    suspend fun ensureLine(line: String): List<LineStatus> {
        val key = line.lowercase()
        val deferred = CompletableDeferred<LineStatus>()
        stateMutex.withLock { pendingLines.getOrPut(key) { mutableListOf() }.add(deferred) }
        try {
            return withTimeout(ENSURE_TIMEOUT_MS) {
                requestSubscribe(lines = listOf(key), force = true)
                listOf(deferred.await())
            }
        } finally {
            discardPending(pendingLines, key, deferred)
        }
    }

    /**
     * Drop one awaiter from a pending map, removing the key entirely once the
     * last awaiter for it is gone so the map can't accumulate empty lists.
     * Safe to call after a normal resolve (the key is already absent).
     */
    private suspend fun <T> discardPending(
        pending: MutableMap<String, MutableList<CompletableDeferred<T>>>,
        key: String,
        deferred: CompletableDeferred<T>,
    ) = stateMutex.withLock {
        pending[key]?.let { awaiters ->
            awaiters.remove(deferred)
            if (awaiters.isEmpty()) pending.remove(key)
        }
        Unit
    }

    // ── Used by IosNotificationManager's Station_*/LineStatus_* topic bridge ──

    fun subscribeTopics(stations: List<String>, lines: List<String>) {
        scope.launch { requestSubscribe(stations, lines) }
    }

    fun unsubscribeTopics(stations: List<String>, lines: List<String>) {
        scope.launch {
            val lowerLines = lines.map { it.lowercase() }
            stateMutex.withLock {
                subscribedStations -= stations.toSet()
                subscribedLines -= lowerLines.toSet()
            }
            sendFrame(buildJsonObject {
                put("action", "unsubscribe")
                if (stations.isNotEmpty()) putJsonArray("stations") { stations.forEach { add(it) } }
                if (lowerLines.isNotEmpty()) putJsonArray("lines") { lowerLines.forEach { add(it) } }
            })
        }
    }

    /**
     * Drop every subscription this socket holds — the sign-out path.
     *
     * Reads the current sets rather than taking a list, because the caller
     * (`IosNotificationManager.clearAllTopics`) no longer has one: it used to
     * derive it from the FCM topic ledger, and that ledger is gone along with
     * FirebaseMessaging. This manager already knows what it is subscribed to,
     * which makes it the right owner of the question anyway.
     */
    fun unsubscribeAll() {
        scope.launch {
            val (stations, lines) = stateMutex.withLock {
                subscribedStations.toList() to subscribedLines.toList()
            }
            if (stations.isEmpty() && lines.isEmpty()) return@launch
            stateMutex.withLock {
                subscribedStations.clear()
                subscribedLines.clear()
            }
            sendFrame(buildJsonObject {
                put("action", "unsubscribe")
                if (stations.isNotEmpty()) putJsonArray("stations") { stations.forEach { add(it) } }
                if (lines.isNotEmpty()) putJsonArray("lines") { lines.forEach { add(it) } }
            })
        }
    }

    // ── Internals ──

    /**
     * @param force re-send the subscribe frame even for ids this socket is
     *        already subscribed to. The server replays a `snapshot` from cache
     *        on every subscribe, including repeats (stationStreamServer.ts
     *        iterates all `subscribed` ids, not just newly-added ones) — so a
     *        forced resubscribe is how we pull an immediate fresh board
     *        without touching the connection. Without it, an already-tracked
     *        id sends nothing and any [ensureStation] await hangs until its
     *        timeout.
     */
    private suspend fun requestSubscribe(
        stations: List<String> = emptyList(),
        lines: List<String> = emptyList(),
        force: Boolean = false,
    ) {
        val lowerLines = lines.map { it.lowercase() }
        val (newStations, newLines) = stateMutex.withLock {
            // Deliberate side effect: a subscribe implies someone is actively
            // using the board, and `runConnection` bails immediately unless
            // this is set — so without it, a subscribe arriving before the
            // first scenePhase callback would open nothing at all.
            isForeground = true
            stations.filter { subscribedStations.add(it) || force } to
                lowerLines.filter { subscribedLines.add(it) || force }
        }
        openIfNeeded()
        if (newStations.isEmpty() && newLines.isEmpty()) return
        PushTrace.log("stream:subscribe stations=$newStations lines=$newLines force=$force")
        sendFrame(buildJsonObject {
            put("action", "subscribe")
            if (newStations.isNotEmpty()) putJsonArray("stations") { newStations.forEach { add(it) } }
            if (newLines.isNotEmpty()) putJsonArray("lines") { newLines.forEach { add(it) } }
        })
    }

    /**
     * Start the connection loop unless one is already running.
     *
     * ⚠️ The test and the launch MUST stay inside a single `withLock`. Split
     * across two acquisitions, two callers can both observe "no live job" and
     * both launch a `runConnection` — the second overwrites [socketJob], so
     * the first becomes an orphan that no [closeCurrentSession] can ever
     * cancel and that reconnects on its own forever. That is easy to hit in
     * practice: a cold start fires [notifyForeground] and the board's first
     * `ensureStation` (via [requestSubscribe]) within milliseconds of each
     * other, on different coroutines.
     */
    private suspend fun openIfNeeded() {
        stateMutex.withLock {
            if (socketJob?.isActive == true) return
            val gen = generation
            socketJob = scope.launch { runConnection(gen) }
        }
    }

    private suspend fun closeCurrentSession(gen: Int) {
        val s = stateMutex.withLock { session }
        try {
            s?.close(CloseReason(CloseReason.Codes.NORMAL, "backgrounded"))
        } catch (_: Exception) {
        }
        stateMutex.withLock {
            if (generation == gen) {
                session = null
                isReady = false
                socketJob?.cancel()
                socketJob = null
            }
        }
    }

    private suspend fun runConnection(gen: Int) {
        while (true) {
            val stillForeground = stateMutex.withLock { isForeground && generation == gen }
            if (!stillForeground) return

            try {
                val wsSession = client.webSocketSession(wsUrl())
                val staleOnConnect = stateMutex.withLock {
                    if (generation != gen) true else {
                        session = wsSession
                        backoffMs = RECONNECT_BASE_MS
                        false
                    }
                }
                if (staleOnConnect) {
                    try { wsSession.close() } catch (_: Exception) {}
                    return
                }

                // Resolved per connection through `IosAuthTokenAuthority`, not
                // read from `firebase_auth_token` — the same authority every
                // HTTP request goes through, and for the same reason. A socket
                // opened after the app has been idle for an hour used to
                // authenticate with a dead token; the backend accepts the frame
                // and the stream then carries nothing the user is entitled to,
                // silently, until the next reconnect. Reconnects are exactly
                // when this happens, since the socket closes on background.
                //
                // No change was needed here beyond this note: this line already
                // called `Platform.getAuthToken()`, so teaching that function to
                // refresh fixed the WebSocket at the same time as the REST path.
                val token = Platform.getAuthToken()
                wsSession.send(Frame.Text(buildJsonObject {
                    put("action", "auth")
                    put("token", token ?: "")
                }.toString()))

                // Resubscribe anything already tracked, plus every saved
                // selection — a fresh app-open (or reconnect) gets the whole
                // board subscribed immediately, not lazily on first fetch.
                val allSelections = Platform.sqlStorage.getAllSelections()
                val toSubscribeStations: List<String>
                val toSubscribeLines: List<String>
                stateMutex.withLock {
                    subscribedStations += allSelections.map { it.station }
                    subscribedLines += allSelections.map { it.line.lowercase() }
                    toSubscribeStations = subscribedStations.toList()
                    toSubscribeLines = subscribedLines.toList()
                }
                if (toSubscribeStations.isNotEmpty() || toSubscribeLines.isNotEmpty()) {
                    PushTrace.log("stream:subscribe(connect) stations=${toSubscribeStations.size} lines=${toSubscribeLines.size}")
                    wsSession.send(Frame.Text(buildJsonObject {
                        put("action", "subscribe")
                        if (toSubscribeStations.isNotEmpty()) putJsonArray("stations") { toSubscribeStations.forEach { add(it) } }
                        if (toSubscribeLines.isNotEmpty()) putJsonArray("lines") { toSubscribeLines.forEach { add(it) } }
                    }.toString()))
                }

                // App-level heartbeat: Ktor's client-side auto-ping isn't
                // available on this plugin version, so liveness is driven
                // explicitly here. A failed send (dead peer, network drop)
                // closes the session, which ends the `incoming` loop below
                // and falls through to the reconnect path uniformly.
                coroutineScope {
                    val heartbeat = launch {
                        while (true) {
                            delay(HEARTBEAT_MS)
                            try {
                                wsSession.send(Frame.Ping(ByteArray(0)))
                            } catch (e: Exception) {
                                PushTrace.log("stream:heartbeatFailed ${e.message?.take(80)}")
                                try { wsSession.close() } catch (_: Exception) {}
                                break
                            }
                        }
                    }
                    for (frame in wsSession.incoming) {
                        if (frame is Frame.Text) handleFrame(frame.readText())
                    }
                    heartbeat.cancel()
                }
            } catch (e: Exception) {
                PushTrace.log("stream:EXCEPTION ${e::class.simpleName}: ${e.message?.take(160)}")
            }

            stateMutex.withLock { isReady = false }
            val stillTracked = stateMutex.withLock { generation == gen && isForeground }
            if (!stillTracked) return

            val wait = stateMutex.withLock {
                val cur = backoffMs
                backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
                cur
            }
            PushTrace.log("stream:reconnect backoff=${wait}ms")
            delay(wait + (0..RECONNECT_JITTER_MS).random())
        }
    }

    private fun wsUrl(): String {
        val base = AppConfig.apiBaseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$base/api/v1/stream"
    }

    private suspend fun sendFrame(obj: JsonObject) {
        val s = stateMutex.withLock { session } ?: return
        try {
            s.send(Frame.Text(obj.toString()))
        } catch (_: Exception) {
        }
    }

    private suspend fun handleFrame(text: String) {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return
        }
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "snapshot", "update" -> {
                val stationId = root["station"]?.jsonPrimitive?.contentOrNull
                val lineId = root["line"]?.jsonPrimitive?.contentOrNull
                val payload = root["payload"]
                if (stationId != null && payload != null) {
                    try {
                        val fcm = json.decodeFromJsonElement(PredictionsPayload.serializer(), payload)
                        processUseCase.processStationUpdate(stationId, fcm)
                        PushTrace.log("stream:update station=$stationId")
                        resolveStation(stationId, fcm)
                    } catch (e: Exception) {
                        PushTrace.log("stream:decodeError station=$stationId ${e.message?.take(120)}")
                    }
                } else if (lineId != null && payload != null) {
                    try {
                        val status = json.decodeFromJsonElement(LineStatus.serializer(), payload)
                        processUseCase.processLineStatusUpdate(status)
                        PushTrace.log("stream:update line=$lineId")
                        resolveLine(lineId.lowercase(), status)
                    } catch (e: Exception) {
                        PushTrace.log("stream:decodeError line=$lineId ${e.message?.take(120)}")
                    }
                }
            }

            "error" -> {
                val code = root["code"]?.jsonPrimitive?.contentOrNull
                val errStations = (root["stations"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val errLines = (root["lines"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                PushTrace.log("stream:error code=$code stations=$errStations lines=$errLines")
                // Only unknown_station/unknown_line are permanent (server
                // drops the subscription) — fail those awaits now instead of
                // making the caller wait out the full timeout. The transient
                // codes (prefetch_throttled/prefetch_failed) resolve on their
                // own via a later Syncer push, so their awaits are left
                // pending until the timeout, matching a transient REST retry.
                if (code == "unknown_station") {
                    errStations.forEach { failStation(it, IllegalStateException("unknown_station")) }
                    stateMutex.withLock { subscribedStations -= errStations.toSet() }
                }
                if (code == "unknown_line") {
                    val lower = errLines.map { it.lowercase() }
                    lower.forEach { failLine(it, IllegalStateException("unknown_line")) }
                    // Prune symmetrically with unknown_station above. Without
                    // this, every reconnect re-sent a subscribe for a line the
                    // server had already rejected as permanently unknown.
                    stateMutex.withLock { subscribedLines -= lower.toSet() }
                }
            }

            "ready" -> {
                stateMutex.withLock { isReady = true }
                PushTrace.log("stream:ready")
            }
        }
    }

    private suspend fun resolveStation(id: String, payload: PredictionsPayload) {
        val list = stateMutex.withLock { pendingStations.remove(id) } ?: return
        list.forEach { it.complete(payload) }
    }

    private suspend fun failStation(id: String, e: Exception) {
        val list = stateMutex.withLock { pendingStations.remove(id) } ?: return
        list.forEach { it.completeExceptionally(e) }
    }

    private suspend fun resolveLine(id: String, status: LineStatus) {
        val list = stateMutex.withLock { pendingLines.remove(id) } ?: return
        list.forEach { it.complete(status) }
    }

    private suspend fun failLine(id: String, e: Exception) {
        val list = stateMutex.withLock { pendingLines.remove(id) } ?: return
        list.forEach { it.completeExceptionally(e) }
    }
}
