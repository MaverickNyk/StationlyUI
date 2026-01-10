package com.stationly.mobile.ui.summary

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.service.TflApiServiceFactory
import com.stationly.core.usecase.FormatDeparturesUseCase
import com.stationly.core.usecase.FetchInitialDataUseCase
import com.stationly.core.platform.WebStorageManager
import com.stationly.core.platform.WebNotificationManager
import com.stationly.core.platform.WebWidgetManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.abs

@JsName("console")
external object console {
    fun log(message: String)
    fun error(message: String)
}

data class SummaryUiState(
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val lastUpdated: Long = 0L
)

class SummaryViewModel {
    private val scope = CoroutineScope(Dispatchers.Main)

    // Platform implementations
    private val storageManager = WebStorageManager()
    private val notificationManager = WebNotificationManager()
    private val widgetManager = WebWidgetManager()
    
    // API Service
    private val apiService = TflApiServiceFactory.create()
    
    // Repositories
    private val selectionRepository = SelectionRepository(storageManager)
    private val departureRepository = DepartureRepository(apiService, storageManager)
    
    // Use Cases
    private val formatDeparturesUseCase = FormatDeparturesUseCase()
    private val fetchInitialDataUseCase = FetchInitialDataUseCase(departureRepository)

    private val _uiState = MutableStateFlow(SummaryUiState())
    val uiState: StateFlow<SummaryUiState> = _uiState.asStateFlow()

    private val _selections = MutableStateFlow<List<UserSelection>>(emptyList())
    val selections: StateFlow<List<UserSelection>> = _selections.asStateFlow()

    private val _predictions = MutableStateFlow<Map<String, List<PredictionDisplay>>>(emptyMap())
    val predictions: StateFlow<Map<String, List<PredictionDisplay>>> = _predictions.asStateFlow()

    init {
        scope.launch {
            // Initialize repository
            selectionRepository.initialize()
            
            // Load saved selections
            loadSavedSelections()
        }
    }

    private fun loadSavedSelections() {
        scope.launch {
            try {
                val selections = selectionRepository.selections.value
                _selections.value = selections
                console.log("Loaded ${selections.size} saved selections")
                
                // Load predictions for each selection
                selections.forEach { selection ->
                    loadPredictions(selection)
                }
            } catch (e: Exception) {
                console.error("Error loading saved selections: ${e.message}")
                _uiState.value = _uiState.value.copy(error = "Failed to load selections: ${e.message}")
            }
        }
    }

    private fun loadPredictions(selection: UserSelection) {
        scope.launch {
            try {
                // Check if we have cached predictions
                val cachedJson = storageManager.loadString("predictions_${selection.station}_${selection.line}")
                
                if (cachedJson != null) {
                    // In a real implementation, we would parse this
                    // For now, show waiting state
                    val currentMap = _predictions.value.toMutableMap()
                    currentMap[selection.station] = emptyList()
                    _predictions.value = currentMap
                } else {
                    // Show waiting state
                    val currentMap = _predictions.value.toMutableMap()
                    currentMap[selection.station] = emptyList()
                    _predictions.value = currentMap
                }
            } catch (e: Exception) {
                console.error("Error loading predictions for ${selection.station}: ${e.message}")
            }
        }
    }

    fun refreshAll() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        
        scope.launch {
            try {
                // In a real implementation, would fetch from API
                // For now, simulate delay
                kotlinx.coroutines.delay(1000)
                
                _selections.value.forEach { selection ->
                    // Would call API here
                    // For demo, just clear cache to show waiting state
                    storageManager.saveString("predictions_${selection.station}_${selection.line}", "")
                    
                    loadPredictions(selection)
                }
                
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    lastUpdated = Clock.System.now().epochSeconds
                )
                
                console.log("Refreshed all predictions")
            } catch (e: Exception) {
                console.error("Error refreshing: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = "Failed to refresh: ${e.message}"
                )
            }
        }
    }

    fun deleteSelection(selection: UserSelection) {
        scope.launch {
            try {
                selectionRepository.deleteSelection(selection)
                
                // Update UI
                val currentSelections = _selections.value.toMutableList()
                currentSelections.remove(selection)
                _selections.value = currentSelections
                
                // Remove from predictions
                val currentMap = _predictions.value.toMutableMap()
                currentMap.remove(selection.station)
                _predictions.value = currentMap
                
                // Clear cache
                storageManager.saveString("predictions_${selection.station}_${selection.line}", "")
                
                console.log("Deleted selection: ${selection.stationName}")
            } catch (e: Exception) {
                console.error("Error deleting selection: ${e.message}")
                _uiState.value = _uiState.value.copy(error = "Failed to delete: ${e.message}")
            }
        }
    }

    fun getPredictionsForSelection(selection: UserSelection): List<PredictionDisplay> {
        return _predictions.value[selection.station] ?: emptyList()
    }
    
    fun hasPredictions(selection: UserSelection): Boolean {
        val preds = _predictions.value[selection.station]
        return !preds.isNullOrEmpty()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun getLastUpdatedString(): String {
        val lastUpdated = _uiState.value.lastUpdated
        if (lastUpdated == 0L) return "Never"
        
        try {
            val nowSeconds = Clock.System.now().epochSeconds
            val secondsDiff = nowSeconds - lastUpdated
            
            return when {
                secondsDiff >= 3600L -> "${secondsDiff / 3600L} hr ago"
                secondsDiff >= 60L -> "${secondsDiff / 60L} min ago"
                else -> "Just now"
            }
        } catch (e: Exception) {
            return "Updated"
        }
    }
}
