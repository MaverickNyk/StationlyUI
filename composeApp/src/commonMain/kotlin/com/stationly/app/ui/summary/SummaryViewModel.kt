package com.stationly.app.ui.summary
import com.stationly.core.session.SessionStore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.FormatDeparturesUseCase
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import com.stationly.core.util.FreshData
import com.stationly.core.util.FreshDataNotifier
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.LineNameStore
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.delay
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.HomeLayout
import com.stationly.core.repository.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import com.stationly.app.platform.performHaptic
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.getConnectivityFlow
import com.stationly.app.platform.NotificationAuthState
import com.stationly.app.platform.notificationAuthState

class SummaryViewModel(
    private val selectionRepository: SelectionRepository = SelectionRepository(
        Platform.storageManager,
        Platform.sqlStorage
    ),
    private val departureRepository: DepartureRepository = DepartureRepository(
        NetworkModule.tflApi,
        Platform.storageManager,
        Platform.sqlStorage,
        SyncPredictionsUseCase(Platform.sqlStorage)
    )
) : ViewModel() {

    private val sduiApi = NetworkModule.sduiApi

    private val formatDeparturesUseCase = FormatDeparturesUseCase()
    private val stationLifecycleUseCase = StationLifecycleUseCase(
        selectionRepository = selectionRepository,
        departureRepository = departureRepository,
        notificationManager = Platform.notificationManager,
        widgetManager = Platform.widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = Platform.storageManager
    )

    private val _uiState = MutableStateFlow(SummaryUiState())
    val uiState: StateFlow<SummaryUiState> = _uiState.asStateFlow()

    private val _selections = MutableStateFlow<List<UserSelection>>(emptyList())
    val selections: StateFlow<List<UserSelection>> = _selections.asStateFlow()

    private val _predictions = MutableStateFlow<Map<String, List<PredictionDisplay>>>(emptyMap())
    val predictions: StateFlow<Map<String, List<PredictionDisplay>>> = _predictions.asStateFlow()

    /*
     * Every per-selection map below is keyed by [UserSelection.boardKey].
     *
     * They used to be keyed by `selection.station` alone, which was correct
     * only while the product was single-line: one station could appear at most
     * once. Now that a user can track several lines at the same station, a
     * station id no longer identifies a board — two selections at King's Cross
     * would land on the same key and the second one written would silently
     * erase the first's departures, SDUI payload and freshness timestamp.
     */

    private val _lineStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val lineStatuses: StateFlow<Map<String, String>> = _lineStatuses.asStateFlow()

    private val _failedLineStatusKeys = MutableStateFlow<Set<String>>(emptySet())
    val failedLineStatusKeys: StateFlow<Set<String>> = _failedLineStatusKeys.asStateFlow()

    private val _stationUpdates = MutableStateFlow<Map<String, Long>>(emptyMap())
    val stationUpdates: StateFlow<Map<String, Long>> = _stationUpdates.asStateFlow()

    private val _announcement = MutableStateFlow<SduiAppComponent.Announcement?>(null)
    val announcement: StateFlow<SduiAppComponent.Announcement?> = _announcement.asStateFlow()

    private val _homeConfig = MutableStateFlow<Map<String, String>>(emptyMap())
    val homeConfig: StateFlow<Map<String, String>> = _homeConfig.asStateFlow()

    private val _forceUpdate = MutableStateFlow(false)
    val forceUpdate: StateFlow<Boolean> = _forceUpdate.asStateFlow()

    private val _showNotificationDeniedBanner = MutableStateFlow(false)
    val showNotificationDeniedBanner: StateFlow<Boolean> = _showNotificationDeniedBanner.asStateFlow()

    /**
     * Every board's configuration — expanded, view, arrangement, position.
     *
     * Passed straight through from [UserSettings] rather than mirrored into a
     * flow of this VM's own: the board settings screen writes the same
     * configuration from a different destination, and a mirror would go stale
     * the moment it did.
     *
     * This flow also carries the ORDER, since a board's position is part of its
     * configuration — so a reorder made in home settings reaches the cards on
     * the same emission a rows-per-platform change does, and there is no second
     * list to observe or to keep in step.
     */
    val boardConfigs: StateFlow<Map<String, BoardConfig>> = UserSettings.configs

    /** Stacked list or one station per swipeable page — see [HomeLayout]. */
    val homeLayout: StateFlow<HomeLayout> = UserSettings.layout

    /**
     * Whether the two flows above are answering from disk yet.
     *
     * Both report a DEFAULT until [UserSettings.ensureLoaded] lands — an empty
     * map, `HomeLayout.LIST` — and a default is indistinguishable from a real
     * value. The home screen renders no board until this is true, so the first
     * frame it paints is the configured one rather than a guess it then has to
     * correct.
     */
    val prefsLoaded: StateFlow<Boolean> = UserSettings.loaded

    /**
     * Guards [refreshStaleBoards] — see the note there about cold start.
     *
     * ## This declaration MUST stay above `init`, and that is not a style rule
     * `viewModelScope` is `Main.immediate`, so a coroutine `init` launches runs
     * SYNCHRONOUSLY during construction — before any property declared below
     * `init` has been initialised. `init` launches `refreshStaleBoards`, which
     * reads this lock, so declaring it below cost a Kotlin/Native SIGSEGV on
     * every launch: not a null-pointer exception, an immediate signal 11 with a
     * crash report naming only `MetalRedrawer.draw`.
     *
     * The project has paid for this once already — see `BOARD_AND_DREAM_UI.md`
     * §14, which documents the identical crash on the station settings screen.
     */
    private val staleRefreshLock = Mutex()

    init {
        viewModelScope.launch { UserSettings.ensureLoaded() }
        // Before the first board is drawn, so a row that names its line uses
        // the backend's short form rather than falling back to the local table
        // for one render and then changing under the user. Cheap — one small
        // string read — and an empty result is a legal state, so this cannot
        // delay or fail the board. See LineNameStore.
        viewModelScope.launch { LineNameStore.ensureLoaded() }
        viewModelScope.launch { fetchAnnouncement() }
        viewModelScope.launch { fetchHomeConfig() }
        viewModelScope.launch {
            loadUserInitial()
            registerDeviceSession()
            // Android does this from StationlyApplication.onCreate; Summary is
            // the iOS equivalent "first authenticated surface". Without it a
            // keychain-restored session (or a rotated token) never reaches the
            // backend and every uid-targeted push fails NotRegistered.
        }
        checkNotificationDeniedBanner()
        viewModelScope.launch {
            getConnectivityFlow().collect { online ->
                _uiState.value = _uiState.value.copy(isOnline = online)
            }
        }
        viewModelScope.launch {
            selectionRepository.initialize()
            // EVERY stale board, not just the first — see refreshStaleBoards.
            // This is the cold-start path: the sooner it starts the more of it
            // overlaps with the screen composing, so it is deliberately the
            // first thing after the selections are known.
            refreshStaleBoards(selectionRepository.selections.value)
        }
        viewModelScope.launch {
            selectionRepository.selections.collect { newSelections ->
                _selections.value = newSelections
                maybeWarmModeIcons(newSelections)
                newSelections.forEach(::reloadBoard)
                if (newSelections.isNotEmpty() && _uiState.value.activeStationId == null) {
                    val primary = newSelections.first()
                    _uiState.value = _uiState.value.copy(
                        activeStationId = primary.station,
                        activeLineId = primary.line
                    )
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                _selections.value.forEach { loadPredictions(it) }
            }
        }
        // Instant refresh when an FCM push lands while the app is active:
        // ProcessPredictionsUseCase emits after writing predictions to SQLite, so
        // we reload the board immediately rather than waiting on the 30 s poll.
        viewModelScope.launch {
            FreshDataNotifier.events.collect { what ->
                // Only the boards the event actually touched. This is the
                // hottest path in the app on iOS — the live stream emits per
                // station every few seconds, and reloading everything meant a
                // SQL read plus a board re-derivation for all N selections on
                // every frame, nearly all of them re-reading unchanged rows.
                // Android has always been targeted (its prefs pings are keyed
                // `predictions_<station>_<line>`); this is that precision.
                when (what) {
                    is FreshData.Station -> _selections.value
                        .filter { it.station.equals(what.stationId, ignoreCase = true) }
                        .forEach { loadPredictions(it) }

                    // A LineStatus_* push only touches the status table — reload
                    // the board's status strip, like Android's home VM does on
                    // its line_status_data prefs ping.
                    is FreshData.Line -> _selections.value
                        .filter { it.line.equals(what.lineId, ignoreCase = true) }
                        .forEach { loadLineStatus(it) }

                    // The emitter could not name a scope, so nothing can be
                    // ruled out. Correct, and the reason to keep naming them.
                    FreshData.All -> _selections.value.forEach(::reloadBoard)
                }
            }
        }
    }

    // Safety-net warm-up, mirroring Android SummaryViewModel: a selection can
    // exist with no cached mode icon (cloud restore skips the Selection
    // screen, so loadModes() never fired). Fetch /modes once and populate the
    // App-Group icon cache, then re-push the widget so it picks the icon up.
    private var iconWarmupAttempted = false
    private fun maybeWarmModeIcons(selections: List<UserSelection>) {
        if (selections.isEmpty() || iconWarmupAttempted) return
        if (selections.none { !com.stationly.app.platform.ModeIconStore.hasIcon(it.mode) }) return
        iconWarmupAttempted = true
        viewModelScope.launch {
            try {
                val modes = sduiApi.getDropdownData("/modes")
                val entries = modes.map {
                    com.stationly.app.platform.ModeIconEntry(
                        modeName = it.id,
                        iconUrl  = it.iconUrl?.replace(
                            com.stationly.core.config.AppConfig.PROD_API_URL,
                            com.stationly.core.config.AppConfig.apiBaseUrl
                        ),
                        tintHex  = it.tintHex,
                    )
                }
                val iconVersion = modes.firstOrNull { !it.iconVersion.isNullOrBlank() }?.iconVersion
                if (entries.isNotEmpty()) {
                    com.stationly.app.platform.ModeIconStore.sync(entries, iconVersion)
                    _selections.value.firstOrNull()?.let { loadPredictions(it) }
                }
            } catch (_: Exception) {
                // Drawn roundel fallback stays until the next launch retries.
            }
        }
    }

    private fun loadPredictions(selection: UserSelection) {
        viewModelScope.launch {
            try {
                val rawPreds = Platform.sqlStorage.getPredictions(
                    selection.station, selection.line, selection.direction
                )
                // EVERY row SQL holds, not the three that fit on screen.
                //
                // This called `processPredictions(rawPreds)`, whose default cap
                // is 3 per platform — and that quietly broke the board's ability
                // to tick. `SyncPredictionsUseCase` stores 8 per platform
                // precisely so the countdown has trains behind the visible ones
                // to shift up as they depart (see MultiLineBoardProcessor.ROW_RESERVE);
                // trimming to 3 here threw the reserves away before the board
                // ever saw them, so a card went 3 rows → 2 → 1 → empty over three
                // minutes and stayed empty until the next push. The widget reads
                // SQL directly and kept all 8, so the two surfaces disagreed
                // within a minute of every update.
                //
                // The display cap belongs to the BOARD, where it is the user's
                // own `rowsPerPlatform` and is applied after the departed rows
                // have been shed. This call is now only a sort. (Android's own
                // ViewModel has always passed MAX_VALUE here; the Compose port
                // dropped it.)
                val dbPreds = GlobalBoardProcessor.processPredictions(
                    rawPreds, perPlatformCap = Int.MAX_VALUE
                )
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                // Android keeps "now" — unchanged behaviour. iOS uses the real
                // last-sync time so the "ago" timer reflects genuine backend
                // freshness instead of resetting on every periodic SQL re-read
                // below, which would otherwise make a live stream look exactly
                // like 30s polling from the UI's point of view.
                //
                // An empty board is a SYNCED board, not an unsynced one. The
                // old first branch was `dbPreds.isEmpty() -> 0L`, which short-
                // circuited before the lookup below and froze the "X ago" timer
                // on any stop with no upcoming departures — a quiet bus stop at
                // midday read "we last heard from the backend when the last bus
                // was due", which is the opposite of the truth. SyncStatusEntity
                // is upserted on EVERY sync regardless of row count (see the
                // table comment in StationlyDatabase.sq) precisely so the timer
                // can say when we last CHECKED. 0L now means only what it says:
                // this stop has never synced, so there is no age to show.
                val predsTimestamp = when {
                    Platform.getPlatformName() == "iOS" ->
                        Platform.sqlStorage.getLastUpdatedTimestamp(
                            selection.station, selection.line, selection.direction
                        ) ?: if (dbPreds.isEmpty()) 0L else now
                    dbPreds.isEmpty() -> 0L
                    else -> now
                }

                val currentMap = _predictions.value.toMutableMap()
                currentMap[selection.boardKey] = dbPreds
                _predictions.value = currentMap

                if (predsTimestamp > 0) {
                    val updates = _stationUpdates.value.toMutableMap()
                    updates[selection.boardKey] = predsTimestamp
                    _stationUpdates.value = updates

                    if (predsTimestamp > _uiState.value.lastUpdated) {
                        _uiState.value = _uiState.value.copy(lastUpdated = predsTimestamp)
                    }

                    // Real line status (severity : reason) so the widget footer
                    // matches the in-app board / Android widget instead of always
                    // defaulting to "Good Service".
                    val dbStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
                    val widgetStatus = dbStatus?.let { s ->
                        val reason = StationlyFormatters.formatStatusReason(s.reason ?: "").trim()
                        if (reason.isNotEmpty()) "${s.statusSeverityDescription}: $reason"
                        else s.statusSeverityDescription
                    }
                    Platform.widgetManager.updateWidget(
                        com.stationly.core.model.WidgetState(
                            stationName = selection.stationName,
                            lineName = selection.line,
                            predictions = dbPreds,
                            status = widgetStatus,
                            lastUpdated = predsTimestamp / 1000,
                            direction = selection.direction,
                            mode = selection.mode
                        )
                    )
                }
            } catch (_: Exception) {
                // Silently ignore — stale data remains visible
            }
        }
    }

    private fun loadLineStatus(selection: UserSelection) {
        val key = "${selection.mode}_${selection.line}".lowercase()
        viewModelScope.launch {
            try {
                val dbStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
                if (dbStatus != null) {
                    var cleanReason = StationlyFormatters.formatStatusReason(dbStatus.reason ?: "").trim()
                    if (cleanReason.isNotEmpty()) cleanReason = ": $cleanReason"
                    val formattedStatus = "${dbStatus.statusSeverityDescription}$cleanReason"
                    _lineStatuses.value = _lineStatuses.value.toMutableMap().also { it[key] = formattedStatus }
                    _failedLineStatusKeys.value = _failedLineStatusKeys.value - key
                }
            } catch (_: Exception) {
                _failedLineStatusKeys.value = _failedLineStatusKeys.value + key
            }
        }
    }

    /**
     * Whether this board's cached rows are too old to show without refetching.
     *
     * Split out from the fetch so the whole set can be judged first and then
     * fetched in ONE fan-out — see [refreshStaleBoards].
     */
    /**
     * Repaint one board from SQLite: its departures and its status strip.
     *
     * The pair was written out at four call sites (the selections collector,
     * the fresh-data collector, the cold-start/foreground refresh and
     * pull-to-refresh), which is four places to forget the second half — and
     * forgetting `loadLineStatus` gives a board with fresh trains under a stale
     * status strip, which looks like a backend bug rather than a missing line.
     */
    private fun reloadBoard(selection: UserSelection) {
        loadPredictions(selection)
        loadLineStatus(selection)
    }

    private suspend fun isStale(selection: UserSelection): Boolean {
        val existingPreds = Platform.sqlStorage.getPredictions(
            selection.station, selection.line, selection.direction
        )
        val existingStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
        val lastUpdatedByStation = _stationUpdates.value[selection.boardKey] ?: 0L
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        return existingPreds.isEmpty() || existingStatus == null ||
            now - lastUpdatedByStation > 60_000
    }

    /**
     * Bring every stale board up to date at once.
     *
     * ## This used to refresh exactly one station
     * Launch called `refreshDataIfStale(selections.firstOrNull())`. Everything
     * after the first board therefore painted from SQLite and waited for the
     * 30 s poll or a stream frame to become true — so on a cold start the
     * second station onwards showed departures that could be minutes old, with
     * nothing on screen saying so.
     *
     * Now the whole set is judged, and whatever is stale is fetched
     * concurrently and deduplicated. The cost is the same one round trip
     * whether the user tracks one station or seven.
     */
    private suspend fun refreshStaleBoards(selections: List<UserSelection>) {
        // Cold start fires this from `init` AND from the screen's first
        // ON_RESUME, within milliseconds of each other. Both would pass the
        // staleness test — neither has written anything yet — and every stop
        // would be fetched twice, the second run merely blocking on the first's
        // per-station lock before repeating its work.
        //
        // `tryLock` rather than `withLock` because the right answer to "a
        // refresh is already in flight" is to do nothing: it is fetching the
        // same stops, and its `onUpdated` will repaint the same boards.
        if (!staleRefreshLock.tryLock()) return
        try {
            val stale = selections.filter { isStale(it) }
            if (stale.isEmpty()) return
            departureRepository.refreshBoards(stale, ::reloadBoard)
        } finally {
            staleRefreshLock.unlock()
        }
    }

    fun refreshAll() {
        // Android keeps its haptic here — unchanged behaviour. iOS moved it to
        // the moment the pull crosses the trigger threshold (see
        // `SummaryScreen`), which is where the platform puts it; firing here as
        // well double-buzzed when the user released quickly.
        if (Platform.getPlatformName() != "iOS") performHaptic(HapticType.TAP)
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        // ── Deliberately NOT calling LiveStream.notifyPullToRefresh() ──
        //
        // It force-resubscribed every station and line at once, which was the
        // right thing when a pull did nothing else. The fan-out below now asks
        // for each stop explicitly, and `ensureStation` force-subscribes as part
        // of that — so keeping both meant every pull sent a blanket resubscribe
        // AND a per-station one, and the server replays a cached snapshot for
        // each, doubling the frames a refresh costs.
        //
        // The two things that call justified are both still covered: a dead
        // socket is reconnected by `openIfNeeded` inside `ensureStation`, and
        // the forced snapshot is exactly what `ensureStation` is asking for. It
        // also resubscribed stations the user no longer has on screen, which the
        // fan-out cannot do.
        viewModelScope.launch {
            try {
                // One concurrent, deduplicated fan-out instead of a serial loop
                // over every selection — see DepartureRepository.refreshBoards
                // for the arithmetic. Boards repaint as each stop lands rather
                // than all together at the end, so the first one is on screen
                // in a single round trip instead of the sum of all of them.
                val result = departureRepository.refreshBoards(_selections.value, ::reloadBoard)
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                if (result.allFailed) {
                    // Every stop failed, which is the network rather than a
                    // stop having a bad time. A partial failure deliberately
                    // does NOT raise the banner: the boards that did refresh
                    // are fresh, and the ones that did not already show it in
                    // their own "ago" timer.
                    _uiState.value = _uiState.value.copy(isRefreshing = false, isBackendOffline = true)
                    performHaptic(HapticType.ERROR)
                    scheduleAutoRetry()
                } else {
                    _uiState.value = _uiState.value.copy(isRefreshing = false, lastUpdated = now)
                    performHaptic(HapticType.SUCCESS)
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    isBackendOffline = true
                )
                performHaptic(HapticType.ERROR)
                scheduleAutoRetry()
            }
        }
    }

    private suspend fun loadUserInitial() {
        // Swift AuthBridge re-persists the identity keys at launch via the
        // auth-state listener — a cold start can race it and read nothing
        // (the intermittent "?" avatar). One delayed re-read covers the gap
        // instead of pinning "?" until the next foreground.
        var name = Platform.storageManager.loadString("firebase_user_display_name")
            ?: Platform.storageManager.loadString("firebase_user_email")
        // Poll briefly so the avatar resolves the instant the keys land, instead
        // of a single fixed 1.5 s re-read (mirrors ProfileViewModel's poll).
        var waited = 0L
        while (name == null && waited < 3000L) {
            delay(120L)
            waited += 120L
            name = Platform.storageManager.loadString("firebase_user_display_name")
                ?: Platform.storageManager.loadString("firebase_user_email")
        }
        val initial = name?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "?"
        // Firebase profile photo (Google sign-in) — written by AuthBridge.swift
        // to NSUserDefaults under "firebase_user_photo_url". Rendered as the
        // top-bar avatar; falls back to the monogram when absent.
        val photoUrl = Platform.storageManager.loadString("firebase_user_photo_url")
        _uiState.value = _uiState.value.copy(userInitial = initial, photoUrl = photoUrl)
    }

    /**
     * Re-register this device's backend session (`/user/sync/profile` with
     * deviceId + deviceInfo). LoginViewModel does this at explicit sign-in,
     * but an iOS keychain-restored session (Firebase survives app
     * delete/reinstall in the keychain) never passes through login — without
     * this the backend's per-device session record goes missing or stale.
     * Idempotent backend upsert, best-effort, runs after loadUserInitial so
     * the identity keys have settled.
     */
    private suspend fun registerDeviceSession() {
        try {
            // Through SessionStore, not raw literals. Every one of these was a
            // separate spelling of a key declared elsewhere, and this block read
            // five of them — so a rename anywhere else would have left this
            // silently posting a login with empty fields.
            val uid = SessionStore.uid() ?: return
            sduiApi.syncProfile(
                com.stationly.core.model.sdui.SyncProfileRequest(
                    uid            = uid,
                    email          = SessionStore.get(SessionStore.Key.EMAIL) ?: "",
                    displayName    = SessionStore.get(SessionStore.Key.DISPLAY_NAME),
                    photoURL       = SessionStore.get(SessionStore.Key.PHOTO_URL),
                    signInProvider = when (SessionStore.get(SessionStore.Key.SIGNIN_PROVIDER)) {
                        "Google" -> "google.com"
                        "Apple"  -> "apple.com"
                        else     -> "email"
                    },
                    deviceId       = com.stationly.app.platform.DeviceIdentity.deviceId(),
                    deviceInfo     = com.stationly.app.platform.DeviceIdentity.deviceInfo()
                )
            )
        } catch (_: Exception) {}
    }

    private suspend fun fetchAnnouncement() {
        try {
            val screen = NetworkModule.sduiApi.getHomeAnnouncement()
            val component = screen.components.filterIsInstance<SduiAppComponent.Announcement>().firstOrNull()
            if (component != null) {
                val dismissKey = component.dismissKey ?: component.id
                val dismissed = Platform.storageManager.loadString("dismissed_announcement_$dismissKey") == "true"
                if (!dismissed) _announcement.value = component
            }
        } catch (_: Exception) {}
    }

    private suspend fun fetchHomeConfig() {
        // Cache-first (Android HomeConfigStore parity): cold launches render
        // last-synced SDUI copy instantly instead of flashing the hardcoded
        // fallbacks until the network returns.
        if (_homeConfig.value.isEmpty()) {
            com.stationly.app.ui.util.HomeConfigCache.load()
                .takeIf { it.isNotEmpty() }
                ?.let { _homeConfig.value = it }
        }
        try {
            val config = NetworkModule.sduiApi.getHomeConfig().strings
            _homeConfig.value = config
            com.stationly.app.ui.util.HomeConfigCache.save(config)
            // SDUI force-update gate (Android parity). Was declared but never
            // set on iOS, so UpdateNudgeDialog could never appear however low
            // the installed version was.
            config["app.minVersion"]?.let { minVer ->
                _forceUpdate.value = com.stationly.app.ui.util.isVersionBelow(
                    com.stationly.app.platform.appVersionName(),
                    minVer,
                )
            }
        } catch (_: Exception) {}
    }

    // ── No "add a widget" or "set as screensaver" promo ──────────────────
    //
    // Both were ports of Android nudges that do not survive the crossing, and
    // they were removed on 2026-08-23 rather than kept for parity's sake.
    //
    // The widget promo asked for a gesture iOS gives the app no way to help
    // with: `isRequestPinAppWidgetSupported` is Android's, and there is no API
    // that adds a widget on the user's behalf, so the card had no CTA and could
    // only recite Home Screen instructions at someone who had not asked.
    //
    // The screensaver promo advertised a surface iOS has no system slot for.
    // On Android the dream is chosen in system Settings and the promo takes you
    // there; here it is an ordinary in-app screen, reachable from home settings
    // like every other setting. A banner for one row of the settings screen is
    // an advert, not a signpost.
    //
    // What remains on the home screen is the announcement banner and the
    // notification-denied banner — one is a message, the other is a permission
    // the user can grant. Both are acted on, not dismissed past.

    /**
     * Surface the "notifications are off" banner when the OS says the user
     * denied permission. Without it, line-status auto-alerts and admin pushes
     * silently no-op with no way for the user to find out.
     *
     * Android has to consult its own "we asked" SharedPrefs flag because
     * `checkSelfPermission` can't tell denial from never-prompted; iOS reports
     * `notDetermined` natively, so DENIED alone is the whole condition.
     * Re-runs on every foreground, so flipping the switch back on in Settings
     * clears the banner without another nudge.
     */
    fun checkNotificationDeniedBanner() {
        viewModelScope.launch {
            _showNotificationDeniedBanner.value =
                notificationAuthState() == NotificationAuthState.DENIED
        }
    }

    fun dismissNotificationDeniedBanner() {
        _showNotificationDeniedBanner.value = false
    }

    fun dismissAnnouncement() {
        val current = _announcement.value ?: return
        val key = current.dismissKey ?: current.id
        viewModelScope.launch {
            Platform.storageManager.saveString("dismissed_announcement_$key", "true")
            _announcement.value = null
        }
    }

    fun reloadSelectionsFromDb() {
        viewModelScope.launch {
            // Identity keys (name/photo) are written by Swift AuthBridge and can
            // change after this VM was created (sign-in completes, display name
            // edited on the Profile screen) — re-read them on every foreground.
            loadUserInitial()
            selectionRepository.initialize()
            // ── The foreground fetch ──
            //
            // Until this existed, coming back from another app showed whatever
            // was in SQLite when we left and nothing went to the network: the
            // 30 s loop only re-READS SQL, and `notifyForeground` merely reopens
            // the socket without asking it for anything. So the board sat on
            // stale rows until a stream frame happened to arrive — which, on a
            // socket that has just been reopened, is exactly when it is slowest.
            //
            // In its own coroutine so it starts NOW rather than queueing behind
            // the token check below, and only for boards that are actually
            // stale, so a five-second trip to the app switcher costs nothing.
            launch { refreshStaleBoards(selectionRepository.selections.value) }
            // Cheap when nothing changed (one string compare, no network) —
            // catches a token that rotated while we were backgrounded.
        }
        // Same ON_RESUME re-evaluation Android does: the user may have flipped
        // notifications in Settings while we were backgrounded.
        checkNotificationDeniedBanner()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(isBackendOffline = false)
    }

    fun retryLoad() {
        _uiState.value = _uiState.value.copy(isBackendOffline = false)
        refreshAll()
    }

    private fun scheduleAutoRetry() {
        viewModelScope.launch {
            delay(30_000)
            if (_uiState.value.isBackendOffline) retryLoad()
        }
    }
}
