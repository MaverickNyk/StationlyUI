package com.stationly.app.ui.summary

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
import com.stationly.core.util.FreshDataNotifier
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.delay
import com.stationly.app.ui.util.StationPrefs
import com.stationly.app.ui.util.StationPrefsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.stationly.app.platform.performHaptic
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.getConnectivityFlow
import com.stationly.app.platform.NotificationAuthState
import com.stationly.app.platform.hasHomeScreenWidget
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

    private val _showWidgetPromo = MutableStateFlow(false)
    val showWidgetPromo: StateFlow<Boolean> = _showWidgetPromo.asStateFlow()

    private val _showDreamPromo = MutableStateFlow(false)
    val showDreamPromo: StateFlow<Boolean> = _showDreamPromo.asStateFlow()

    private val _showNotificationDeniedBanner = MutableStateFlow(false)
    val showNotificationDeniedBanner: StateFlow<Boolean> = _showNotificationDeniedBanner.asStateFlow()

    /**
     * Per-station card preferences (pin, hide hero).
     *
     * Passed straight through from [StationPrefsRepository] rather than mirrored
     * into a flow of this VM's own: the station settings screen writes the same
     * preferences from a different destination, and a mirror would go stale the
     * moment it did.
     */
    val stationPrefs: StateFlow<Map<String, StationPrefs>> = StationPrefsRepository.prefs

    init {
        viewModelScope.launch { StationPrefsRepository.ensureLoaded() }
        viewModelScope.launch { fetchAnnouncement() }
        viewModelScope.launch { fetchHomeConfig() }
        viewModelScope.launch {
            loadUserInitial()
            registerDeviceSession()
            // Android does this from StationlyApplication.onCreate; Summary is
            // the iOS equivalent "first authenticated surface". Without it a
            // keychain-restored session (or a rotated token) never reaches the
            // backend and every uid-targeted push fails NotRegistered.
            com.stationly.app.util.FcmTokenRegistrar.ensureRegistered()
        }
        checkWidgetPromo()
        checkDreamPromo()
        checkNotificationDeniedBanner()
        viewModelScope.launch {
            getConnectivityFlow().collect { online ->
                _uiState.value = _uiState.value.copy(isOnline = online)
            }
        }
        viewModelScope.launch {
            selectionRepository.initialize()
            selectionRepository.selections.value.firstOrNull()?.let { refreshDataIfStale(it) }
        }
        viewModelScope.launch {
            selectionRepository.selections.collect { newSelections ->
                _selections.value = newSelections
                maybeWarmModeIcons(newSelections)
                newSelections.forEach { selection ->
                    loadPredictions(selection)
                    loadLineStatus(selection)
                }
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
        // ProcessFcmPayloadUseCase emits after writing predictions to SQLite, so
        // we reload the board immediately rather than waiting on the 30 s poll.
        viewModelScope.launch {
            FreshDataNotifier.events.collect {
                _selections.value.forEach {
                    loadPredictions(it)
                    // A LineStatus_* push only touches the status table — reload
                    // the board's status strip too, like Android's home VM does
                    // on its line_status_data prefs ping.
                    loadLineStatus(it)
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
                val dbPreds = GlobalBoardProcessor.processPredictions(rawPreds)
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                // Android keeps "now" — unchanged behaviour. iOS uses the real
                // last-sync time so the "ago" timer reflects genuine backend
                // freshness instead of resetting on every periodic SQL re-read
                // below, which would otherwise make a live stream look exactly
                // like 30s polling from the UI's point of view.
                val predsTimestamp = when {
                    dbPreds.isEmpty() -> 0L
                    Platform.getPlatformName() == "iOS" ->
                        Platform.sqlStorage.getLastUpdatedTimestamp(
                            selection.station, selection.line, selection.direction
                        ) ?: now
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

    private suspend fun refreshDataIfStale(selection: UserSelection) {
        val existingPreds = Platform.sqlStorage.getPredictions(
            selection.station, selection.line, selection.direction
        )
        val existingStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
        val lastUpdatedByStation = _stationUpdates.value[selection.boardKey] ?: 0L
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val isStale = now - lastUpdatedByStation > 60_000

        if (existingPreds.isEmpty() || existingStatus == null || isStale) {
            departureRepository.fetchInitialData(selection)
            loadPredictions(selection)
            loadLineStatus(selection)
        }
    }

    fun refreshAll() {
        // Android keeps its haptic here — unchanged behaviour. iOS moved it to
        // the moment the pull crosses the trigger threshold (see
        // `SummaryScreen`), which is where the platform puts it; firing here as
        // well double-buzzed when the user released quickly.
        if (Platform.getPlatformName() != "iOS") performHaptic(HapticType.TAP)
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        // No-op on Android. On iOS this forces a resubscribe on the live
        // stream (the server replays a cached snapshot per subscribe), and
        // reconnects only if the socket is actually dead — see
        // core.platform.LiveStream.notifyPullToRefresh.
        com.stationly.core.platform.LiveStream.notifyPullToRefresh()
        viewModelScope.launch {
            try {
                _selections.value.forEach { selection ->
                    departureRepository.fetchInitialData(selection)
                    loadPredictions(selection)
                    loadLineStatus(selection)
                }
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                _uiState.value = _uiState.value.copy(isRefreshing = false, lastUpdated = now)
                performHaptic(HapticType.SUCCESS)
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
            val uid = Platform.storageManager.loadString("firebase_user_uid") ?: return
            sduiApi.syncProfile(
                com.stationly.core.model.sdui.SyncProfileRequest(
                    uid            = uid,
                    email          = Platform.storageManager.loadString("firebase_user_email") ?: "",
                    displayName    = Platform.storageManager.loadString("firebase_user_display_name"),
                    photoURL       = Platform.storageManager.loadString("firebase_user_photo_url"),
                    signInProvider = when (Platform.storageManager.loadString("signin_provider")) {
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

    /**
     * Show the "add a home screen widget" promo to people who don't have one.
     *
     * Android reads `AppWidgetManager.getAppWidgetIds` and filters by host
     * category. iOS's `WidgetCenter` is Swift-only, so the Swift host probes
     * it into the App Group and [hasHomeScreenWidget] reads that — `null`
     * means the probe hasn't landed, in which case we decide nothing rather
     * than flash a promo at someone who already has the widget.
     */
    fun checkWidgetPromo() {
        _showWidgetPromo.value = hasHomeScreenWidget() == false
    }

    /**
     * X button — hides until the next foreground re-evaluates (same
     * non-persistent behaviour as Android's `dismissWidgetPromo`).
     *
     * Android also has `hideWidgetPromoForSession()` for its "Add" CTA. iOS
     * has no counterpart on purpose: there is no API to add a widget on the
     * user's behalf, so the card ships with `cta = null` permanently (see
     * `WidgetPromoCard`) and a session-hide method would be unreachable code.
     */
    fun dismissWidgetPromo() {
        _showWidgetPromo.value = false
    }

    /**
     * Show the screensaver promo until the user has actually run the dream.
     *
     * Android asks the system whether Stationly is the selected screensaver
     * (`Settings.Secure.screensaver_components` + the `screensaver_enabled`
     * flag). iOS has no system screensaver slot — the dream is an in-app
     * surface — so "has run it at least once" is the analogue, persisted in
     * the app-group dream prefs so it survives the logout wipe.
     */
    fun checkDreamPromo() {
        _showDreamPromo.value = !com.stationly.app.ui.dream.DreamSettings.hasEverStarted()
    }

    fun dismissDreamPromo() {
        _showDreamPromo.value = false
    }

    fun hideDreamPromoForSession() {
        _showDreamPromo.value = false
    }

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
            // Cheap when nothing changed (one string compare, no network) —
            // catches a token that rotated while we were backgrounded.
            com.stationly.app.util.FcmTokenRegistrar.ensureRegistered()
        }
        // Same ON_RESUME re-evaluation Android does: the user may have added a
        // widget, run the screensaver, or flipped notifications in Settings
        // while we were backgrounded.
        checkWidgetPromo()
        checkDreamPromo()
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
