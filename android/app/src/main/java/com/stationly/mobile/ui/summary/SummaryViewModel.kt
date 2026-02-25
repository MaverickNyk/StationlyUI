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
    private val selectionRepository = SelectionRepository(storageManager)
    private val departureRepository = DepartureRepository(apiService, storageManager)
    
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
    
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("predictions_")) {
            // A prediction changed via FCM. Reload predictions for all selections
            viewModelScope.launch {
                _selections.value.forEach { loadPredictions(it) }
            }
        } else if (key == "line_status_data") {
            viewModelScope.launch {
                _selections.value.forEach { loadLineStatus(it) }
            }
        } else if (key == "selections") {
            loadSavedSelections()
        }
    }
    
    init {
        context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        loadSavedSelections()
    }
    
    override fun onCleared() {
        super.onCleared()
        context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }
    
    private fun loadSavedSelections() {
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val json = prefs.getString("selections", "[]")
                val type = object : TypeToken<List<UserSelection>>() {}.type
                val selections: List<UserSelection> = gson.fromJson(json, type)
                
                _selections.value = selections
                Log.d("SummaryViewModel", "Loaded ${selections.size} saved selections")
                
                // Load predictions for each selection AND ensure subscribed
                val fcm = FirebaseMessaging.getInstance()
                selections.forEach { selection ->
                    // Re-subscribe to stay reliable in case of token change or app reboot
                    fcm.subscribeToTopic("Station_${selection.station}")
                    fcm.subscribeToTopic("LineStatus_${selection.mode}_${selection.line}")
                    loadPredictions(selection)
                    loadLineStatus(selection)
                }
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error loading saved selections", e)
                _uiState.value = _uiState.value.copy(error = "Failed to load selections: ${e.message}")
            }
        }
    }
    
    private fun loadPredictions(selection: UserSelection) {
        viewModelScope.launch {
            try {
                // In real implementation, would fetch from API
                // For now, simulate with empty data or cached data
                
                // Check if we have cached predictions
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val cacheKey = "predictions_${selection.station}_${selection.line}"
                val cachedJson = prefs.getString(cacheKey, null)
                
                if (cachedJson != null) {
                    val mapType = object : TypeToken<Map<String, Any>>() {}.type
                    try {
                        val cachedMap: Map<String, Any> = gson.fromJson(cachedJson, mapType)
                        val timestamp = (cachedMap["timestamp"] as? Number)?.toLong() ?: 0
                        val dataJson = cachedMap["data"] as? String
                        
                        // Directly parse whatever was found
                        if (dataJson != null) {
                            val type = object : TypeToken<List<PredictionDisplay>>() {}.type
                            val cachedPredictions: List<PredictionDisplay> = gson.fromJson(dataJson, type)
                            
                            val currentMap = _predictions.value.toMutableMap()
                            currentMap[selection.station] = cachedPredictions
                            _predictions.value = currentMap
                            Log.d("SummaryViewModel", "Loaded cached predictions for ${selection.stationName}")
                        } else {
                            val currentMap = _predictions.value.toMutableMap()
                            currentMap[selection.station] = emptyList()
                            _predictions.value = currentMap
                        }
                    } catch (e: Exception) {
                        Log.e("SummaryViewModel", "Error parsing cached predictions: ", e)
                    }
                } else {
                    // Show waiting state
                    val currentMap = _predictions.value.toMutableMap()
                    currentMap[selection.station] = emptyList()
                    _predictions.value = currentMap
                }
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error loading predictions for ${selection.station}", e)
            }
        }
    }
    
    private fun loadLineStatus(selection: UserSelection) {
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val statusJson = prefs.getString("line_status_data", null)
                val timestamp = if (statusJson != null) {
                    val statusData = gson.fromJson<Map<String, Any>>(statusJson, object : TypeToken<Map<String, Any>>() {}.type)
                    (statusData["timestamp"] as? Number)?.toLong() ?: 0
                } else 0
                
                val now = System.currentTimeMillis()
                var validDataJson: String? = null
                
                // Trust local cache indefinitely since FCM will refresh it!
                if (statusJson != null) {
                    val statusData = gson.fromJson<Map<String, Any>>(statusJson, object : TypeToken<Map<String, Any>>() {}.type)
                    validDataJson = statusData["data"] as? String
                    if (validDataJson == null || !validDataJson.contains(selection.line, ignoreCase = true)) {
                        validDataJson = null
                    }
                }
                
                // If we don't have valid cached data, fetch from API
                if (validDataJson == null) {
                    Log.d("SummaryViewModel", "No local line status for ${selection.line}, fetching from API")
                    val apiStatus = apiService.getLineStatuses(selection.line).firstOrNull()
                    if (apiStatus != null) {
                        validDataJson = gson.toJson(apiStatus)
                        val newStatusData = mapOf(
                            "data" to validDataJson,
                            "timestamp" to now
                        )
                        prefs.edit().putString("line_status_data", gson.toJson(newStatusData)).apply()
                        Log.d("SummaryViewModel", "Saved line status from API")
                    }
                }
                
                if (validDataJson != null && validDataJson.contains(selection.line, ignoreCase = true)) {
                    var severityStr = ""
                    var reasonStr = ""
                    try {
                        val parsedData = gson.fromJson<Map<String, Any>>(validDataJson, object : TypeToken<Map<String, Any>>() {}.type)
                        val severity = parsedData["statusSeverityDescription"] as? String ?: ""
                        val reason = parsedData["reason"] as? String ?: ""
                        severityStr = severity
                        reasonStr = reason
                        
                        var cleanReason = com.stationly.mobile.util.FormatUtils.formatStatusReason(reason).trim()
                        if (cleanReason.isNotEmpty()) {
                            cleanReason = ": $cleanReason"
                        }
                        val formattedStatus = "$severity$cleanReason"
                        
                        val currentMap = _lineStatuses.value.toMutableMap()
                        currentMap["${selection.mode}_${selection.line}"] = formattedStatus
                        _lineStatuses.value = currentMap
                    } catch (e: Exception) {
                        val currentMap = _lineStatuses.value.toMutableMap()
                        currentMap["${selection.mode}_${selection.line}"] = validDataJson
                        _lineStatuses.value = currentMap
                    }
                    
                    // Trigger widget update with fresh status
                    com.stationly.mobile.widget.DepartureWidgetProvider.updateWidgetContent(
                        context,
                        selection.stationName,
                        selection.line.replaceFirstChar { it.uppercase() },
                        getPredictionsForSelection(selection),
                        severityStr,
                        reasonStr
                    )
                }
            } catch (e: Exception) {
                Log.e("SummaryViewModel", "Error managing line status", e)
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
                // In real implementation, would fetch from API
                // For now, just simulate delay
                kotlinx.coroutines.delay(1000)
                
                _selections.value.forEach { selection ->
                    // Would call API here
                    // For demo, just clear cache to show waiting state
                    val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                    val cacheKey = "predictions_${selection.station}_${selection.line}"
                    prefs.edit().remove(cacheKey).apply()
                    
                    loadPredictions(selection)
                    loadLineStatus(selection)
                }
                
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    lastUpdated = System.currentTimeMillis()
                )
                
                Log.d("SummaryViewModel", "Refreshed all predictions")
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
                // Remove from saved selections
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val json = prefs.getString("selections", "[]")
                val type = object : TypeToken<List<UserSelection>>() {}.type
                val existing: List<UserSelection> = gson.fromJson(json, type)
                
                val updated = existing.filter { it != selection }
                val updatedJson = gson.toJson(updated)
                prefs.edit()
                    .putString("selections", updatedJson)
                    .remove("line_status_data")
                    .apply()
                
                // Update UI stream implicitly handled by listener, but we can do it explicitly
                _selections.value = updated
                
                // Remove from predictions
                val currentMap = _predictions.value.toMutableMap()
                currentMap.remove(selection.station)
                _predictions.value = currentMap
                
                // Clear cache
                val cacheKey = "predictions_${selection.station}_${selection.line}"
                prefs.edit().remove(cacheKey).apply()
                
                com.stationly.mobile.widget.DepartureWidgetProvider.updateAppWidget(
                    context, android.appwidget.AppWidgetManager.getInstance(context), 
                    android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID,
                    "Stationly", "", emptyList(), null, null
                )
                
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
        val lastUpdated = _uiState.value.lastUpdated
        if (lastUpdated == 0L) return "Never"
        
        val diff = System.currentTimeMillis() - lastUpdated
        val seconds = diff / 1000
        
        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            else -> "${seconds / 3600} hr ago"
        }
    }
}

data class SummaryUiState(
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val lastUpdated: Long = 0L
)