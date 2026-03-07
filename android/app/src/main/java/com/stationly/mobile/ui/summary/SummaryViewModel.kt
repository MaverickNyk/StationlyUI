package com.stationly.mobile.ui.summary

import android.app.Application
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
import com.stationly.core.usecase.SaveSelectionUseCase
import com.stationly.core.usecase.FetchInitialDataUseCase
import com.stationly.core.platform.Platform
import com.stationly.core.platform.AndroidStorageManager
import com.stationly.core.platform.AndroidNotificationManager
import com.stationly.core.platform.AndroidWidgetManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.google.firebase.messaging.FirebaseMessaging

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
    
    // Repositories
    private val selectionRepository = SelectionRepository(storageManager, Platform.sqlStorage)
    private val departureRepository = DepartureRepository(apiService, storageManager, Platform.sqlStorage)
    
    // KMP Core Use Cases
    private val formatDeparturesUseCase = FormatDeparturesUseCase()
    private val fetchInitialDataUseCase = FetchInitialDataUseCase(departureRepository)
    private val saveSelectionUseCase = SaveSelectionUseCase(
        selectionRepository,
        notificationManager,
        widgetManager,
        fetchInitialDataUseCase
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
    
    // Per-station last updated timestamps
    private val _stationUpdates = MutableStateFlow<Map<String, Long>>(emptyMap())
    val stationUpdates: StateFlow<Map<String, Long>> = _stationUpdates.asStateFlow()
    
    // Per-station bound SDUI payloads (Perfectly synced with Widget)
    private val _sduiPayloads = MutableStateFlow<Map<String, com.stationly.core.model.sdui.SduiWidgetPayload?>>(emptyMap())
    val sduiPayloads: StateFlow<Map<String, com.stationly.core.model.sdui.SduiWidgetPayload?>> = _sduiPayloads.asStateFlow()
    
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
                _selections.value.forEach { loadLineStatus(it) }
            }
        } else if (key == "selections") {
            // Handled by repository flow
        }
    }
    
    init {
        context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        
        viewModelScope.launch {
            selectionRepository.initialize()
            selectionRepository.selections.collect { selections ->
                _selections.value = selections
                Log.d("SummaryViewModel", "Repository pushed ${selections.size} selections")
                
                val fcm = FirebaseMessaging.getInstance()
                selections.forEach { selection ->
                    fcm.subscribeToTopic("Station_${selection.station}")
                    fcm.subscribeToTopic("LineStatus_${selection.mode}_${selection.line}")
                    loadPredictions(selection)
                    loadLineStatus(selection)
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun loadSavedSelections() {
        // Now handled by reactive stream in init
    }
    
    private fun loadPredictions(selection: UserSelection) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Use unified processor for identical sorting as the widget
                val rawPreds = Platform.sqlStorage.getPredictions(selection.station, selection.line)
                val dbPreds = com.stationly.core.util.GlobalBoardProcessor.processPredictions(rawPreds)
                
                // Also get when it was updated if we had a generic timestamp method, 
                // but since it's not exposed easily, we'll just check if there's any prediction.
                val predsTimestamp = if (dbPreds.isNotEmpty()) System.currentTimeMillis() else 0L
                val currentMap = _predictions.value.toMutableMap()
                currentMap[selection.station] = dbPreds
                _predictions.value = currentMap
                
                // Load and bind SDUI template if it exists
                loadSduiTemplateForSelection(selection, dbPreds)
                
                if (predsTimestamp > 0) {
                    val updates = _stationUpdates.value.toMutableMap()
                    updates[selection.station] = predsTimestamp
                    _stationUpdates.value = updates
                    
                    if (predsTimestamp > _uiState.value.lastUpdated) {
                        _uiState.value = _uiState.value.copy(lastUpdated = predsTimestamp)
                    }
                }
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error loading predictions for \${selection.station}", e)
            }
        }
    }

    private fun loadSduiTemplateForSelection(selection: UserSelection, predictions: List<PredictionDisplay>) {
        val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dbStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
                
                if (dbStatus != null) {
                    val severity = dbStatus.statusSeverityDescription
                    val reason = dbStatus.reason
                    
                    var cleanReason = com.stationly.core.util.StationlyFormatters.formatStatusReason(reason ?: "").trim()
                    if (cleanReason.isNotEmpty()) {
                        cleanReason = ": $cleanReason"
                    }
                    val formattedStatus = "$severity$cleanReason"
                    
                    val currentMap = _lineStatuses.value.toMutableMap()
                    currentMap["${selection.mode}_${selection.line}"] = formattedStatus
                    _lineStatuses.value = currentMap
                }
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error loading line status", e)
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
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val selection = _selections.value.find { it.station == stationId }
                if (selection != null) {
                    val cacheKey = "predictions_${selection.station}_${selection.line}"
                    val json = gson.toJson(predictions)
                    prefs.edit().putString(cacheKey, json).apply()
                    
                    Log.d("SummaryViewModel", "Updated predictions for ${selection.stationName}")
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
                // Simulate refresh delay for UX
                kotlinx.coroutines.delay(800)
                
                loadSavedSelections()
                
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    lastUpdated = System.currentTimeMillis()
                )
                
                Log.d("SummaryViewModel", "Refreshed from internal storage")
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error refreshing", e)
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = "Failed to refresh: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Delete a selection
     */
    fun deleteSelection(selection: UserSelection) {
        viewModelScope.launch {
            try {
                // Remove from database via repository
                selectionRepository.deleteSelection(selection)
                
                // Unsubscribe from FCM topics
                val fcm = com.google.firebase.messaging.FirebaseMessaging.getInstance()
                fcm.unsubscribeFromTopic("Station_${selection.station}")
                fcm.unsubscribeFromTopic("LineStatus_${selection.mode}_${selection.line}")

                // Update UI state predictions map
                val currentMap = _predictions.value.toMutableMap()
                currentMap.remove(selection.station)
                _predictions.value = currentMap
                
                val currentLineStatuses = _lineStatuses.value.toMutableMap()
                currentLineStatuses.remove("${selection.mode}_${selection.line}")
                _lineStatuses.value = currentLineStatuses

                // Remove SDUI layout from prefs
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                
                // Write current selections to SharedPreferences for legacy widget compatibility
                val remainingSelections = _selections.value.filter { it.station != selection.station }
                val selectionsJson = gson.toJson(remainingSelections)
                
                prefs.edit()
                    .remove("sdui_layout_${selection.station}")
                    .putString("selections", selectionsJson)
                    .apply()

                com.stationly.mobile.widget.DepartureWidgetProvider.updateFromStorage(context)
                
                Log.d("SummaryViewModel", "Deleted selection: ${selection.stationName}")
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error deleting selection", e)
                _uiState.value = _uiState.value.copy(error = "Failed to delete: ${e.message}")
            }
        }
    }
    
    /**
     * Get predictions for a specific selection
     */
    fun getPredictionsForSelection(selection: UserSelection): List<PredictionDisplay> {
        return _predictions.value[selection.station] ?: emptyList()
    }
    
    /**
     * Check if selection has predictions
     */
    fun hasPredictions(selection: UserSelection): Boolean {
        val preds = _predictions.value[selection.station]
        return !preds.isNullOrEmpty()
    }
    
    fun getLineStatusForSelection(selection: UserSelection): String? {
        return _lineStatuses.value["${selection.mode}_${selection.line}"]
    }
    
    fun getLastUpdatedForStation(station: String): Long {
        return _stationUpdates.value[station] ?: 0L
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Get last updated time as string
     */
    fun getLastUpdatedString(): String {
        return com.stationly.core.util.StationlyFormatters.formatLastUpdated(_uiState.value.lastUpdated)
    }
}

data class SummaryUiState(
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val lastUpdated: Long = 0L
)