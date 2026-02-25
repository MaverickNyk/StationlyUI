package com.stationly.mobile.ui.selection

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stationly.core.model.UserSelection
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.TflApiServiceFactory
import com.stationly.core.usecase.*
import com.stationly.core.platform.AndroidStorageManager
import com.stationly.core.platform.AndroidNotificationManager
import com.stationly.core.platform.AndroidWidgetManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SelectionViewModel - Android ViewModel for selection screen
 * 
 * Uses KMP core use cases for all business logic.
 * Handles station selection, line selection, and direction selection.
 */
class SelectionViewModel(application: Application) : AndroidViewModel(application) {
    
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
    
    // Use Cases (with proper dependency injection)
    private val fetchInitialDataUseCase = FetchInitialDataUseCase(departureRepository)
    private val formatDeparturesUseCase = FormatDeparturesUseCase()
    private val getModesUseCase = GetModesUseCase(apiService)
    private val getLinesUseCase = GetLinesUseCase(apiService)
    private val getRouteUseCase = GetRouteUseCase(apiService)
    private val searchStationsUseCase = SearchStationsUseCase(apiService)
    private val saveSelectionUseCase = SaveSelectionUseCase(
        selectionRepository,
        notificationManager,
        widgetManager,
        fetchInitialDataUseCase
    )
    
    // UI State
    private val _uiState = MutableStateFlow(SelectionUiState())
    val uiState: StateFlow<SelectionUiState> = _uiState.asStateFlow()
    
    // Saved selections
    private val _savedSelections = MutableStateFlow<List<UserSelection>>(emptyList())
    val savedSelections: StateFlow<List<UserSelection>> = _savedSelections.asStateFlow()
    
    init {
        loadSavedSelections()
        loadInitialData()
    }
    
    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                // Fetch modes
                val modes = getModesUseCase()
                val modeNames = modes.map { it.modeName }
                _uiState.value = _uiState.value.copy(
                    modes = modeNames,
                    isLoading = false
                )
                
                Log.d("SelectionViewModel", "Loaded modes: ${modeNames.size}")
            } catch (e: Exception) {
                Log.e("SelectionViewModel", "Error loading initial data", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load data: ${e.message}"
                )
            }
        }
    }
    
    fun onModeSelected(mode: String) {
        _uiState.value = _uiState.value.copy(
            selectedMode = mode,
            selectedLine = null,
            selectedStation = null,
            selectedDirection = null,
            availableLines = emptyList(),
            availableStations = emptyList(),
            availableDirections = emptyList()
        )
        
        loadLines(mode)
    }
    
    private var cachedLines: List<com.stationly.core.model.LineInfo> = emptyList()
    
    private fun loadLines(mode: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                val lines = getLinesUseCase(mode)
                cachedLines = lines
                val lineNames = lines.map { it.name }
                
                Log.d("SelectionViewModel", "Loaded ${lineNames.size} lines for mode $mode")

                if (lines.size == 1) {
                    // Auto-select the only line
                    _uiState.value = _uiState.value.copy(
                        availableLines = lineNames
                    )
                    onLineSelected(lines[0].name)
                } else {
                    _uiState.value = _uiState.value.copy(
                        availableLines = lineNames,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Log.e("SelectionViewModel", "Error loading lines", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load lines: ${e.message}"
                )
            }
        }
    }
    
    fun onLineSelected(lineName: String) {
        val line = cachedLines.find { it.name == lineName }
        val lineId = line?.id ?: lineName
        
        _uiState.value = _uiState.value.copy(
            selectedLine = lineName, // Keep name for UI display
            // We should store ID internally if needed, but uiState doesn't have a separate field.
            // We'll look it up again or rely on cachedMaps. 
            // Better: use the find result for next step.
            selectedStation = null,
            selectedDirection = null,
            availableStations = emptyList(),
            availableDirections = emptyList()
        )
        
        loadDirections(lineId)
    }

    private fun loadDirections(lineId: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                val routeResponse = getRouteUseCase(lineId)
                val directions = routeResponse.directions.map { it.direction }
                _uiState.value = _uiState.value.copy(
                    availableDirections = directions,
                    isLoading = false
                )
                
                Log.d("SelectionViewModel", "Loaded ${directions.size} directions for line $lineId")
            } catch (e: Exception) {
                Log.e("SelectionViewModel", "Error loading directions", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load directions: ${e.message}"
                )
            }
        }
    }
    
    fun onDirectionSelected(direction: String) {
        _uiState.value = _uiState.value.copy(
            selectedDirection = direction,
            selectedStation = null,
            availableStations = emptyList()
        )
        
        val lineName = _uiState.value.selectedLine
        val lineId = cachedLines.find { it.name == lineName }?.id
        
        if (lineId != null) {
            loadStations(lineId, direction)
        } else {
             _uiState.value = _uiState.value.copy(error = "Line ID not found for $lineName")
        }
    }

    private fun loadStations(lineId: String, direction: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                // Construct key as requested
                val searchKey = "${lineId}_$direction"
                val stations = searchStationsUseCase(searchKey)
                cachedStations = stations
                
                // Format for UI: "Name (ID)"
                val stationStrings = stations.map { "${it.commonName} (${it.naptanId})" }
                
                _uiState.value = _uiState.value.copy(
                    availableStations = stationStrings,
                    isLoading = false
                )
                
                Log.d("SelectionViewModel", "Loaded ${stationStrings.size} stations for $searchKey")
            } catch (e: Exception) {
                Log.e("SelectionViewModel", "Error loading stations", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load stations: ${e.message}"
                )
            }
        }
    }

    private var cachedStations: List<com.stationly.core.model.StationBrief> = emptyList()
    
    // onStationSelected remains the same as in previous modify but good to preserve
    fun onStationSelected(stationId: String, stationName: String) {
         _uiState.value = _uiState.value.copy(
            selectedStation = stationId,
            selectedStationName = stationName
        )
    }
    
    fun saveSelection() {
        val state = _uiState.value
        if (state.selectedMode == null || state.selectedLine == null || 
            state.selectedStation == null || state.selectedDirection == null) {
            _uiState.value = state.copy(error = "Please complete all selections")
            return
        }
        
        val selection = UserSelection(
            mode = state.selectedMode!!,
            line = state.selectedLine!!,
            station = state.selectedStation!!,
            stationName = state.selectedStationName ?: state.selectedStation!!,
            direction = state.selectedDirection!!,
            destinations = emptyList(),
            destinationIds = emptyList()
        )
        
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true)
            try {
                // Get existing selections
                val existing = _savedSelections.value
                
                // Unsubscribe from all existing topics
                val nManager = AndroidNotificationManager(context)
                existing.forEach { oldSel ->
                    val oldTopics = listOf(
                        "Station_${oldSel.station}",
                        "LineStatus_${oldSel.mode}_${oldSel.line}"
                    )
                    nManager.unsubscribeFromTopics(oldTopics)
                }
                
                // Clear any other storage (predictions, line status)
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.remove("line_status_data")
                existing.forEach {
                    editor.remove("predictions_${it.station}_${it.line}")
                }
                editor.apply()
                
                // Clear repository to ensure we only have one primary selection
                selectionRepository.clearAll()

                // Use the save selection use case
                saveSelectionUseCase(selection)
                
                // Update UI stream
                _savedSelections.value = listOf(selection)
                
                _uiState.value = state.copy(
                    isLoading = false,
                    success = "Selection saved!",
                    showSuccessDialog = true
                )
                
                Log.d("SelectionViewModel", "Saved selection as primary: $selection")
            } catch (e: Exception) {
                Log.e("SelectionViewModel", "Error saving selection", e)
                _uiState.value = state.copy(isLoading = false, error = "Failed to save: ${e.message}")
            }
        }
    }
    
    private fun loadSavedSelections() {
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val json = prefs.getString("selections", "[]")
                val type = object : TypeToken<List<UserSelection>>() {}.type
                val selections: List<UserSelection> = gson.fromJson(json, type)
                
                _savedSelections.value = selections
                Log.d("SelectionViewModel", "Loaded ${selections.size} saved selections")
            } catch (e: Exception) {
                Log.e("SelectionViewModel", "Error loading saved selections", e)
            }
        }
    }
    
    fun deleteSelection(selection: UserSelection) {
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val json = prefs.getString("selections", "[]")
                val type = object : TypeToken<List<UserSelection>>() {}.type
                val existing: List<UserSelection> = gson.fromJson(json, type)
                
                val updated = existing.filter { it != selection }
                val updatedJson = gson.toJson(updated)
                prefs.edit().putString("selections", updatedJson).apply()
                
                _savedSelections.value = updated
                Log.d("SelectionViewModel", "Deleted selection: $selection")
            } catch (e: Exception) {
                Log.e("SelectionViewModel", "Error deleting selection", e)
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    fun dismissSuccessDialog() {
        _uiState.value = _uiState.value.copy(showSuccessDialog = false)
    }
}

data class SelectionUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: String? = null,
    val showSuccessDialog: Boolean = false,
    
    // Available options
    val modes: List<String> = emptyList(),
    val availableLines: List<String> = emptyList(),
    val availableStations: List<String> = emptyList(),
    val availableDirections: List<String> = emptyList(),
    
    // Selected values
    val selectedMode: String? = null,
    val selectedLine: String? = null,
    val selectedStation: String? = null,
    val selectedStationName: String? = null,
    val selectedDirection: String? = null
)