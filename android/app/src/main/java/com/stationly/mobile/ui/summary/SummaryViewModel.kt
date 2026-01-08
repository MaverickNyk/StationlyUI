package com.stationly.mobile.ui.summary

import android.app.Application
import android.content.Context
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
    
    init {
        loadSavedSelections()
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
                
                // Load predictions for each selection
                selections.forEach { selection ->
                    loadPredictions(selection)
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
                    val type = object : TypeToken<List<PredictionDisplay>>() {}.type
                    val cachedPredictions: List<PredictionDisplay> = gson.fromJson(cachedJson, type)
                    
                    // Update predictions map
                    val currentMap = _predictions.value.toMutableMap()
                    currentMap[selection.station] = cachedPredictions
                    _predictions.value = currentMap
                    
                    Log.d("SummaryViewModel", "Loaded cached predictions for ${selection.stationName}")
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
                prefs.edit().putString("selections", updatedJson).apply()
                
                // Update UI
                _selections.value = updated
                
                // Remove from predictions
                val currentMap = _predictions.value.toMutableMap()
                currentMap.remove(selection.station)
                _predictions.value = currentMap
                
                // Clear cache
                val cacheKey = "predictions_${selection.station}_${selection.line}"
                prefs.edit().remove(cacheKey).apply()
                
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