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
import com.stationly.mobile.util.PREFS_NAME
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
            // Handled by repository flow
        }
    }
    
    init {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        viewModelScope.launch { fetchAnnouncement() }
        viewModelScope.launch { fetchHomeConfig() }
        checkWidgetPromo()

        viewModelScope.launch {
            selectionRepository.initialize()
            selectionRepository.selections.value.firstOrNull()?.let { refreshDataIfStale(it) }
        }

        viewModelScope.launch {
            selectionRepository.selections.collect { selections ->
                _selections.value = selections
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
                val dbPreds = com.stationly.core.util.GlobalBoardProcessor.processPredictions(rawPreds)
                
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
                    loadPredictions(selection)
                    loadLineStatus(selection)
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