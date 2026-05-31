package com.stationly.mobile.ui.summary

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.TflApiServiceFactory
import com.stationly.core.usecase.FormatDeparturesUseCase
import com.stationly.core.usecase.ProcessFcmPayloadUseCase
import com.stationly.core.platform.Platform
import com.stationly.core.platform.AndroidStorageManager
import com.stationly.core.platform.AndroidNotificationManager
import com.stationly.core.platform.AndroidWidgetManager
import com.stationly.mobile.util.HomeConfigStore
import com.stationly.mobile.util.ModeIconCache
import com.stationly.mobile.util.PREFS_NAME
import com.stationly.mobile.widget.DepartureWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SummaryViewModel - Android ViewModel for summary screen
 * 
 * Displays departure information for saved selections.
 * Uses KMP core use cases for data processing.
 */
class SummaryViewModel(application: Application) : AndroidViewModel(application) {
    
    private val context = application.applicationContext
    private val gson = Gson()
    
    // Platform implementations
    private val storageManager = AndroidStorageManager(context)
    private val notificationManager = AndroidNotificationManager(context)
    private val widgetManager = AndroidWidgetManager(context)
    
    // API Service
    private val apiService = TflApiServiceFactory.create()
    private val sduiService = com.stationly.core.service.SduiApiServiceFactory.create()
    
    // Repositories
    private val syncPredictionsUseCase = com.stationly.core.usecase.SyncPredictionsUseCase(Platform.sqlStorage)
    private val selectionRepository = SelectionRepository(storageManager, Platform.sqlStorage)
    private val departureRepository = DepartureRepository(apiService, storageManager, Platform.sqlStorage, syncPredictionsUseCase)
    
    // KMP Core Use Cases
    private val formatDeparturesUseCase = FormatDeparturesUseCase()
    private val stationLifecycleUseCase = com.stationly.core.usecase.StationLifecycleUseCase(
        selectionRepository = selectionRepository,
        departureRepository = departureRepository,
        notificationManager = notificationManager,
        widgetManager = widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = Platform.storageManager
    )

    private val processFcmPayloadUseCase = ProcessFcmPayloadUseCase(
        departureRepository,
        widgetManager,
        storageManager,
        formatDeparturesUseCase
    )
    
    // UI State
    private val _uiState = MutableStateFlow(SummaryUiState())
    val uiState: StateFlow<SummaryUiState> = _uiState.asStateFlow()
    
    // Saved selections
    private val _selections = MutableStateFlow<List<UserSelection>>(emptyList())
    val selections: StateFlow<List<UserSelection>> = _selections.asStateFlow()
    
    // Current predictions
    private val _predictions = MutableStateFlow<Map<String, List<PredictionDisplay>>>(emptyMap())
    val predictions: StateFlow<Map<String, List<PredictionDisplay>>> = _predictions.asStateFlow()
    
    // Line Statuses (Key: mode_line)
    private val _lineStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val lineStatuses: StateFlow<Map<String, String>> = _lineStatuses.asStateFlow()

    private val _failedLineStatusKeys = MutableStateFlow<Set<String>>(emptySet())
    val failedLineStatusKeys: StateFlow<Set<String>> = _failedLineStatusKeys.asStateFlow()
    
    // Per-station last updated timestamps
    private val _stationUpdates = MutableStateFlow<Map<String, Long>>(emptyMap())
    val stationUpdates: StateFlow<Map<String, Long>> = _stationUpdates.asStateFlow()
    
    // Per-station bound SDUI payloads (Perfectly synced with Widget)
    private val _sduiPayloads = MutableStateFlow<Map<String, com.stationly.core.model.sdui.SduiWidgetPayload?>>(emptyMap())
    val sduiPayloads: StateFlow<Map<String, com.stationly.core.model.sdui.SduiWidgetPayload?>> = _sduiPayloads.asStateFlow()

    // Home screen announcement from SDUI
    private val _announcement = MutableStateFlow<com.stationly.core.model.sdui.SduiAppComponent.Announcement?>(null)
    val announcement: StateFlow<com.stationly.core.model.sdui.SduiAppComponent.Announcement?> = _announcement.asStateFlow()

    // Server-controlled UI strings (labels, empty state text, explore labels, greetings)
    private val _homeConfig = MutableStateFlow<Map<String, String>>(emptyMap())
    val homeConfig: StateFlow<Map<String, String>> = _homeConfig.asStateFlow()

    // True when the installed version is below app.minVersion
    private val _forceUpdate = MutableStateFlow(false)
    val forceUpdate: StateFlow<Boolean> = _forceUpdate.asStateFlow()

    // Station ID currently being deleted, null when idle
    private val _isDeletingBoard = MutableStateFlow<String?>(null)
    val isDeletingBoard: StateFlow<String?> = _isDeletingBoard.asStateFlow()

    // True when widget hasn't been pinned yet and user hasn't dismissed the promo
    private val _showWidgetPromo = MutableStateFlow(false)
    val showWidgetPromo: StateFlow<Boolean> = _showWidgetPromo.asStateFlow()

    // True when Stationly is NOT the current screensaver. Re-evaluated on
    // every ON_RESUME so the banner disappears as soon as the user picks
    // it in system Settings.
    private val _showDreamPromo = MutableStateFlow(false)
    val showDreamPromo: StateFlow<Boolean> = _showDreamPromo.asStateFlow()

    // True when the user has been asked for POST_NOTIFICATIONS (so the
    // system permission prompt has fired at least once) and the result
    // was denial. Drives a soft banner that opens app notification
    // settings — without this nudge the user wouldn't know that line
    // status alerts are silently no-op'd by the dispatcher. Re-evaluated
    // on every ON_RESUME so the banner disappears as soon as the user
    // toggles notifications back on in Settings.
    private val _showNotificationDeniedBanner = MutableStateFlow(false)
    val showNotificationDeniedBanner: StateFlow<Boolean> = _showNotificationDeniedBanner.asStateFlow()

    // One-shot guard for the mode-icon warmup. Set after the first
    // `/modes` fetch attempt regardless of success — without it, a
    // failed download (anyMissing stays true) would re-hit the API on
    // every selectionRepository emit.
    private var iconWarmupAttempted = false
    
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("predictions_")) {
            // predictions_{stationId}_{lineId}
            val parts = key.split("_")
            if (parts.size >= 3) {
                val stationId = parts[1]
                val lineId = parts[2]
                viewModelScope.launch {
                    val selection = _selections.value.find { it.station == stationId && it.line == lineId }
                    selection?.let { loadPredictions(it) }
                }
            }
        } else if (key == "line_status_data") {
            viewModelScope.launch {
                _selections.value.forEach { 
                    loadPredictions(it)
                    loadLineStatus(it) 
                }
            }
        } else if (key == "selections") {
            // A cross-device reconcile (UserSyncCoordinator) or another
            // screen mutated the saved selections through a DIFFERENT
            // SelectionRepository instance, so our in-memory flow is stale.
            // Re-read from SQL to pick up the change live.
            reloadSelectionsFromDb()
        }
    }
    
    init {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        viewModelScope.launch { fetchAnnouncement() }
        viewModelScope.launch { fetchHomeConfig() }
        checkWidgetPromo()
        checkDreamPromo()
        checkNotificationDeniedBanner()

        viewModelScope.launch {
            selectionRepository.initialize()
            selectionRepository.selections.value.firstOrNull()?.let { refreshDataIfStale(it) }
        }

        viewModelScope.launch {
            selectionRepository.selections.collect { selections ->
                _selections.value = selections

                // Prune _lineStatuses + _failedLineStatusKeys to entries that
                // match a current selection. Without this, switching the
                // active board (or having a selection removed by sync from
                // another device) leaves a stale entry in the map — and the
                // Network section's disclaimer dialog ends up listing lines
                // the user isn't actually watching anymore.
                val validKeys = selections.map { "${it.mode}_${it.line}".lowercase() }.toSet()
                _lineStatuses.value = _lineStatuses.value.filterKeys { it in validKeys }
                _failedLineStatusKeys.value = _failedLineStatusKeys.value.intersect(validKeys)

                // Warm the mode-icon cache for the modes the user is actually
                // using. Only triggers if at least one of those modes has no
                // cached icon yet — typical case: user installs, restores a
                // selection from the cloud without entering Selection, so
                // loadModes() never fires. The widget + dream's station-row
                // roundel reads from this cache.
                val anyMissing = selections.any { sel ->
                    ModeIconCache.cachedFile(context, sel.mode) == null
                }
                if (anyMissing && selections.isNotEmpty() && !iconWarmupAttempted) {
                    iconWarmupAttempted = true
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val modes = sduiService.getDropdownData("/modes")
                            val entries = modes.map { opt ->
                                ModeIconCache.ModeSyncEntry(
                                    modeName = opt.id,
                                    iconUrl  = opt.iconUrl,
                                    tintHex  = opt.tintHex,
                                )
                            }
                            val iconVersion = modes.firstOrNull { !it.iconVersion.isNullOrBlank() }?.iconVersion
                            if (entries.isNotEmpty()) {
                                ModeIconCache.sync(context, entries, iconVersion)
                                // Re-push the widget so it picks up the
                                // newly-cached icon without waiting for the
                                // next FCM tick / minute alarm.
                                DepartureWidgetProvider.updateFromStorage(context)
                            }
                        } catch (e: Exception) {
                            Log.w("SummaryVM", "Mode icon warmup failed", e)
                        }
                    }
                }

                selections.forEach { selection ->
                    loadPredictions(selection)
                    loadLineStatus(selection)
                }
                if (selections.isNotEmpty() && _uiState.value.activeStationId == null) {
                    val primary = selections.first()
                    _uiState.value = _uiState.value.copy(
                        activeStationId = primary.station,
                        activeLineId = primary.line
                    )
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun loadPredictions(selection: UserSelection) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val rawPreds = Platform.sqlStorage.getPredictions(selection.station, selection.line)
                // Keep the FULL per-platform buffer (up to 8 rows, the storage
                // cap set by SyncPredictionsUseCase). Earlier this defaulted
                // to perPlatformCap = 3 — which meant the home Board's
                // cached list only held the visible 3 rows, with no buffer
                // to slide up when the first row passed grace. Result: when
                // a Due train departed, the board shrunk to 2 rows and stayed
                // that way until the next FCM, instead of pulling in the next
                // upcoming train. The display cap of 3 is applied at render
                // time by `prepareLegacyRows` — the storage layer must not
                // pre-cap.
                val dbPreds = com.stationly.core.util.GlobalBoardProcessor
                    .processPredictions(rawPreds, perPlatformCap = Int.MAX_VALUE)
                
                // Honest "last updated" time — the SQL row's persistence
                // timestamp (set when the FCM payload or REST sync was
                // saved). Read regardless of whether `dbPreds` is empty
                // RIGHT NOW: empty predictions doesn't mean we never had
                // any. A station whose last train departed an hour ago
                // still has a real SQL timestamp from the FCM that
                // delivered that train — and the home Board's chronometer
                // + BoardFallbackState rely on that value to distinguish
                // "service ended" / "live updates paused" from "fresh
                // install, no data yet". 0L is now only returned when
                // SQL genuinely has no timestamp for this station/line.
                val predsTimestamp = Platform.sqlStorage
                    .getPredictionsTimestamp(selection.station, selection.line)
                    ?: 0L
                val currentMap = _predictions.value.toMutableMap()
                currentMap[selection.station] = dbPreds
                _predictions.value = currentMap
                if (com.stationly.mobile.BuildConfig.DEBUG) Log.d("SummaryViewModel", "Loaded ${dbPreds.size} departures for ${selection.station}")
                
                // Load and bind SDUI template if it exists
                loadSduiTemplateForSelection(selection, dbPreds)
                
                if (predsTimestamp > 0) {
                    val updates = _stationUpdates.value.toMutableMap()
                    updates[selection.station] = predsTimestamp
                    _stationUpdates.value = updates
                    
                    if (predsTimestamp > _uiState.value.lastUpdated) {
                        _uiState.value = _uiState.value.copy(lastUpdated = predsTimestamp)
                    }

                    // Proactively update widget with what we just loaded
                    widgetManager.updateWidget(
                        com.stationly.core.model.WidgetState(
                            stationName = selection.stationName,
                            lineName = selection.line,
                            predictions = dbPreds,
                            status = null, // Will be updated by loadLineStatus
                            lastUpdated = predsTimestamp / 1000
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error loading predictions for ${selection.station}", e)
            }
        }
    }

    private fun loadSduiTemplateForSelection(selection: UserSelection, predictions: List<PredictionDisplay>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sduiJson = prefs.getString("sdui_layout_${selection.station}", null)
        
        if (sduiJson != null) {
            try {
                val format = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val template = format.decodeFromString<com.stationly.core.model.sdui.SduiWidgetPayload>(sduiJson)
                
                // Get line status for binding
                val status = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
                
                // Use unified binder
                val boundPayload = com.stationly.core.util.GlobalBoardProcessor.bindSduiTemplate(
                    template,
                    predictions,
                    status?.statusSeverityDescription,
                    status?.reason
                )
                
                val currentSdui = _sduiPayloads.value.toMutableMap()
                currentSdui[selection.station] = boundPayload
                _sduiPayloads.value = currentSdui
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error binding SDUI for inside-app board", e)
            }
        } else {
             val currentSdui = _sduiPayloads.value.toMutableMap()
             currentSdui.remove(selection.station)
             _sduiPayloads.value = currentSdui
        }
    }
    
    private fun loadLineStatus(selection: UserSelection) {
        val key = "${selection.mode}_${selection.line}".lowercase()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dbStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
                if (dbStatus != null) {
                    var cleanReason = com.stationly.core.util.StationlyFormatters.formatStatusReason(dbStatus.reason ?: "").trim()
                    if (cleanReason.isNotEmpty()) cleanReason = ": $cleanReason"
                    val formattedStatus = "${dbStatus.statusSeverityDescription}$cleanReason"

                    _lineStatuses.value = _lineStatuses.value.toMutableMap().also { it[key] = formattedStatus }
                    _failedLineStatusKeys.value = _failedLineStatusKeys.value - key

                    widgetManager.updateWidget(
                        com.stationly.core.model.WidgetState(
                            stationName = selection.stationName,
                            lineName = selection.line,
                            predictions = _predictions.value[selection.station] ?: emptyList(),
                            status = formattedStatus,
                            lastUpdated = System.currentTimeMillis() / 1000
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error loading line status", e)
                _failedLineStatusKeys.value = _failedLineStatusKeys.value + key
            }
        }
    }

    /**
     * Eagerly refresh station data if it is missing or older than 60 seconds.
     * Bridges the gap between app launch and the next FCM update.
     */
    private fun refreshDataIfStale(selection: UserSelection) {
        viewModelScope.launch {
            val existingPreds = Platform.sqlStorage.getPredictions(selection.station, selection.line)
            val existingStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
            
            val lastUpdatedByStation = _stationUpdates.value[selection.station] ?: 0L
            val isStale = System.currentTimeMillis() - lastUpdatedByStation > 60_000

            if (existingPreds.isEmpty() || existingStatus == null || isStale) {
                departureRepository.fetchInitialData(selection)

                // Refresh local UI state flows from the updated database
                loadPredictions(selection)
                loadLineStatus(selection)

                // Fan out the fresh-data signal to every surface — same
                // helper FCM and pull-to-refresh use. Without this, the
                // dream's chronometer would stay anchored to its old
                // lastUpdatedMs if it happens to be active during app
                // launch (rare but possible when phone is docked while
                // the user re-opens the app).
                com.stationly.mobile.util.FreshDataNotifier.notify(
                    context,
                    stationId = selection.station,
                    lineId = selection.line,
                )
            }
        }
    }

    
    /**
     * Update predictions from FCM payload
     * Called by FCM service when new data arrives
     */
    fun updatePredictionsFromFcm(stationId: String, predictions: List<PredictionDisplay>) {
        viewModelScope.launch {
            try {
                // Update predictions map
                val currentMap = _predictions.value.toMutableMap()
                currentMap[stationId] = predictions
                _predictions.value = currentMap
                
                // Cache the predictions
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val selection = _selections.value.find { it.station == stationId }
                if (selection != null) {
                    val cacheKey = "predictions_${selection.station}_${selection.line}"
                    val json = gson.toJson(predictions)
                    prefs.edit().putString(cacheKey, json).apply()
                    
                    if (com.stationly.mobile.BuildConfig.DEBUG) Log.d("SummaryViewModel", "FCM: Updated predictions for ${selection.stationName}")
                    
                    // Propagate to Home Screen Widget
                    widgetManager.updateWidget(
                         com.stationly.core.model.WidgetState(
                             stationName = selection.stationName,
                             lineName = selection.line,
                             predictions = predictions,
                             status = _lineStatuses.value["${selection.mode}_${selection.line}".lowercase()],
                             lastUpdated = System.currentTimeMillis() / 1000
                         )
                    )
                }
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error updating predictions", e)
            }
        }
    }
    
    /**
     * Refresh all predictions manually
     * Called when user pulls to refresh
     */
    fun refreshAll() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            try {
                _selections.value.forEach { selection ->
                    departureRepository.fetchInitialData(selection)
                    // Update our own VM state synchronously so the home
                    // Board reflects fresh data immediately on this
                    // coroutine path; don't wait for the SharedPrefs
                    // listener round-trip.
                    loadPredictions(selection)
                    loadLineStatus(selection)
                    // Fan out to the dream + widget. The home is already
                    // updated above, so the SharedPrefs ping that
                    // FreshDataNotifier fires is mostly a defensive
                    // duplicate for us — it costs one redundant
                    // loadPredictions call, harmless. The dream broadcast
                    // and widget redraw are what matter here.
                    com.stationly.mobile.util.FreshDataNotifier.notify(
                        context,
                        stationId = selection.station,
                        lineId = selection.line,
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    lastUpdated = System.currentTimeMillis()
                )
                if (com.stationly.mobile.BuildConfig.DEBUG) Log.d("SummaryViewModel", "Manual refresh complete for ${_selections.value.size} board(s)")
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error refreshing", e)
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    isBackendOffline = true,
                    error = "Unable to reach Stationly servers. Check your connection."
                )
                scheduleAutoRetry()
            }
        }
    }
    
    /**
     * Delete a selection
     */
    fun deleteSelection(selection: UserSelection) {
        viewModelScope.launch {
            // Minimum on-screen time for the delete overlay. The actual
            // teardown is mostly fast local work (FCM unsubscribe + SQL), so
            // without a floor it can finish in ~100ms — too fast for the
            // overlay's fade to render a single frame, making it look like
            // "nothing happened". 650ms reads as a deliberate action.
            val startMs = System.currentTimeMillis()
            val minVisibleMs = 650L
            _isDeletingBoard.value = selection.station
            try {
                stationLifecycleUseCase.discardStation(selection, clearSelectionInRepo = true)

                // Update UI state predictions map
                val currentMap = _predictions.value.toMutableMap()
                currentMap.remove(selection.station)
                _predictions.value = currentMap
                
                val currentLineStatuses = _lineStatuses.value.toMutableMap()
                currentLineStatuses.remove("${selection.mode}_${selection.line}".lowercase())
                _lineStatuses.value = currentLineStatuses

                // Sync the resulting state to backend to ensure single board is accurate 
                try {
                    val authUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    if (authUser != null) {
                        val remainingSelections = _selections.value.filter { it.station != selection.station }
                        val mapped = remainingSelections.map { 
                            com.stationly.core.model.sdui.SubscribedStation(
                                id = it.station, 
                                name = it.stationName, 
                                line = it.line, 
                                mode = it.mode, 
                                direction = it.direction
                            ) 
                        }
                        sduiService.syncStations(authUser.uid, mapped)
                    }
                } catch (e: Exception) {
                    Log.e("SummaryViewModel", "Failed to sync delete to backend", e)
                }

                if (com.stationly.mobile.BuildConfig.DEBUG) Log.d("SummaryViewModel", "Deleted selection: ${selection.stationName}")
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error deleting selection", e)
                _uiState.value = _uiState.value.copy(error = "Failed to delete: ${e.message}")
            } finally {
                // Hold the overlay until the minimum visible window elapses.
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed < minVisibleMs) delay(minVisibleMs - elapsed)
                _isDeletingBoard.value = null
            }
        }
    }

    private suspend fun fetchAnnouncement() {
        try {
            val screen = sduiService.getHomeAnnouncement()
            val component = screen.components.filterIsInstance<com.stationly.core.model.sdui.SduiAppComponent.Announcement>().firstOrNull()
            if (component != null) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val key = component.dismissKey ?: component.id
                val dismissed = prefs.getBoolean("dismissed_announcement_$key", false)
                if (!dismissed) _announcement.value = component
            }
        } catch (e: Exception) {
            Log.w("SummaryViewModel", "Could not fetch home announcement — using no banner", e)
        }
    }

    private suspend fun fetchHomeConfig() {
        try {
            val config = sduiService.getHomeConfig().strings
            _homeConfig.value = config
            // Persist so the widget, dream, and DreamSettingsActivity
            // (launched from system Settings, no ViewModel) all read the
            // latest SDUI copy without re-fetching.
            HomeConfigStore.write(context, config)
            config["app.minVersion"]?.let { minVer ->
                _forceUpdate.value = isVersionBelow(com.stationly.mobile.BuildConfig.VERSION_NAME, minVer)
            }
        } catch (e: Exception) {
            Log.w("SummaryViewModel", "Could not fetch home config — falling back to hardcoded strings", e)
        }
    }

    private fun isVersionBelow(installed: String, minimum: String): Boolean {
        fun parse(v: String) = v.trim().split(".").mapNotNull { it.toIntOrNull() }
        val ins = parse(installed)
        val min = parse(minimum)
        for (i in 0 until maxOf(ins.size, min.size)) {
            val a = ins.getOrElse(i) { 0 }
            val b = min.getOrElse(i) { 0 }
            if (a < b) return true
            if (a > b) return false
        }
        return false
    }

    fun checkWidgetPromo() {
        // Check each widget instance's host category — filters out lock-screen/systemui
        // phantom instances which linger even when the home screen widget is removed
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, com.stationly.mobile.widget.DepartureWidgetProvider::class.java)
        val hasHomeScreenWidget = manager.getAppWidgetIds(provider).any { id ->
            val category = manager.getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, -1)
            category == AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        }
        _showWidgetPromo.value = !hasHomeScreenWidget
    }

    // X button — hides until next resume (checkWidgetPromo re-evaluates on every ON_RESUME)
    fun dismissWidgetPromo() {
        _showWidgetPromo.value = false
    }

    // Called by Add button — hide until next resume check
    fun hideWidgetPromoForSession() {
        _showWidgetPromo.value = false
    }

    /**
     * Decide whether to surface the "notifications are off" banner.
     *
     * `NotificationPermissionEffect` is what fires the system permission
     * dialog and persists `post_notifications_asked` + `_granted` in
     * SharedPrefs. We read those here:
     *   - `asked == true` AND `granted == false`  → show banner
     *   - anything else  → hide
     *
     * Idempotent — re-running this after the user has flipped the
     * permission in system Settings will flip the banner off. The
     * banner itself just dismisses session-locally via
     * `dismissNotificationDeniedBanner` until the next ON_RESUME.
     *
     * No-op on API < 33 (the legacy auto-grant model).
     */
    fun checkNotificationDeniedBanner() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            _showNotificationDeniedBanner.value = false
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val asked = prefs.getBoolean("post_notifications_asked", false)
        // The system is the truth — the user can flip the permission
        // via Settings without our SharedPrefs flag knowing. So we
        // ignore our own `granted` cache here and just ask the OS.
        // The `asked` flag is still load-bearing: until the user has
        // seen at least one prompt, the banner would be premature.
        val systemGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        _showNotificationDeniedBanner.value = asked && !systemGranted
    }

    fun dismissNotificationDeniedBanner() {
        _showNotificationDeniedBanner.value = false
    }

    /**
     * Detect whether Stationly is the user's active screensaver. The banner
     * shows whenever ANY of these is true:
     *
     *   1. Screensavers are disabled system-wide (`screensaver_enabled = 0`).
     *      Toggling "Off" in system Settings leaves `screensaver_components`
     *      pointing at the last-picked dream, so we MUST also check the
     *      enabled flag — otherwise the banner stays hidden after the user
     *      sets us and then disables screensavers.
     *   2. Screensavers are enabled but `screensaver_components` doesn't
     *      contain our `StationlyDreamService`.
     *
     * Called from `init` and again on every ON_RESUME via
     * `reloadSelectionsFromDb()`, so the banner re-appears the moment the
     * user backs out of system Settings having toggled us off.
     */
    fun checkDreamPromo() {
        val enabled = try {
            android.provider.Settings.Secure.getInt(
                context.contentResolver,
                "screensaver_enabled",
                1,
            ) == 1
        } catch (e: Exception) { true }

        val components = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "screensaver_components",
            )
        } catch (e: Exception) { null }

        val ours = ComponentName(
            context,
            com.stationly.mobile.dream.StationlyDreamService::class.java,
        )
        val oursLong  = ours.flattenToString()
        val oursShort = ours.flattenToShortString()

        val isOurs = components
            ?.split(',')
            ?.any { it.trim().let { c -> c == oursLong || c == oursShort } } == true

        _showDreamPromo.value = !(enabled && isOurs)
    }

    // X button — hides until next ON_RESUME re-checks the system setting.
    fun dismissDreamPromo() {
        _showDreamPromo.value = false
    }

    // Called by "Set up" button after we open system dream settings —
    // hide until next ON_RESUME confirms whether the user actually picked us.
    fun hideDreamPromoForSession() {
        _showDreamPromo.value = false
    }

    fun dismissAnnouncement() {
        val current = _announcement.value ?: return
        val key = current.dismissKey ?: current.id
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("dismissed_announcement_$key", true).apply()
        _announcement.value = null
    }

    /**
     * Re-read selections from SQLite and refresh UI.
     * Called when the screen resumes after another screen (e.g. profile) may have mutated storage.
     */
    fun reloadSelectionsFromDb() {
        viewModelScope.launch {
            selectionRepository.initialize()
        }
        checkWidgetPromo()
        checkDreamPromo()
        checkNotificationDeniedBanner()
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, isBackendOffline = false)
    }

    /** Called by NoConnectionScreen retry button on the board */
    fun retryLoad() {
        _uiState.value = _uiState.value.copy(error = null, isBackendOffline = false)
        refreshAll()
    }

    private fun scheduleAutoRetry() {
        viewModelScope.launch {
            delay(30_000)
            if (_uiState.value.isBackendOffline) retryLoad()
        }
    }
    
}

data class SummaryUiState(
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val isBackendOffline: Boolean = false,
    val lastUpdated: Long = 0L,
    val activeStationId: String? = null,
    val activeLineId: String? = null
)