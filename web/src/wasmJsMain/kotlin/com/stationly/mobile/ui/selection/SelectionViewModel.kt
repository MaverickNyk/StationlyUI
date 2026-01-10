package com.stationly.mobile.ui.selection

import com.stationly.core.model.UserSelection
import com.stationly.core.model.TransportMode
import com.stationly.core.model.LineInfo
import com.stationly.core.model.StationBrief
import com.stationly.core.model.LineRouteResponse
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.TflApiServiceFactory
import com.stationly.core.usecase.GetModesUseCase
import com.stationly.core.usecase.GetLinesUseCase
import com.stationly.core.usecase.GetRouteUseCase
import com.stationly.core.usecase.SearchStationsUseCase
import com.stationly.core.usecase.SaveSelectionUseCase
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

// Import console logging from core
@JsName("console")
external object console {
    fun log(message: String)
    fun error(message: String)
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

class SelectionViewModel {
    private val scope = CoroutineScope(Dispatchers.Main)

    // Platform implementations
    private val storageManager = WebStorageManager()
    private val notificationManager = WebNotificationManager()
    private val widgetManager = WebWidgetManager()
    
    // API Service
    private val apiService = TflApiServiceFactory.create()
    
    // Repositories
    private val selectionRepository = SelectionRepository(storageManager)
    
    // Use Cases
    private val getModesUseCase = GetModesUseCase(apiService)
    private val getLinesUseCase = GetLinesUseCase(apiService)
    private val getRouteUseCase = GetRouteUseCase(apiService)
    private val searchStationsUseCase = SearchStationsUseCase(apiService)
    private val fetchInitialDataUseCase = FetchInitialDataUseCase(
        com.stationly.core.repository.DepartureRepository(apiService, storageManager)
    )
    private val saveSelectionUseCase = SaveSelectionUseCase(
        selectionRepository,
        notificationManager,
        widgetManager,
        fetchInitialDataUseCase
    )

    private val _uiState = MutableStateFlow(SelectionUiState())
    val uiState: StateFlow<SelectionUiState> = _uiState.asStateFlow()

    private val _savedSelections = MutableStateFlow<List<UserSelection>>(emptyList())
    val savedSelections: StateFlow<List<UserSelection>> = _savedSelections.asStateFlow()

    private var cachedLines: List<LineInfo> = emptyList()
    private var cachedStations: List<StationBrief> = emptyList()

    init {
        scope.launch {
            // Initialize repository
            selectionRepository.initialize()
            
            // Load initial data
            loadInitialData()
            
            // Load saved selections
            loadSavedSelections()
        }
    }

    private fun loadInitialData() {
        scope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                // Fetch modes
                val modes = getModesUseCase()
                val modeNames = modes.map { it.modeName }
                _uiState.value = _uiState.value.copy(
                    modes = modeNames,
                    isLoading = false
                )
                
                console.log("Loaded modes: ${modeNames.size}")
            } catch (e: Exception) {
                console.error("Error loading initial data: ${e.message ?: "Unknown error"}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load data: ${e.message ?: "Unknown error"}"
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

    private fun loadLines(mode: String) {
        scope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                val lines = getLinesUseCase(mode)
                cachedLines = lines
                val lineNames = lines.map { it.name }
                
                console.log("Loaded ${lineNames.size} lines for mode $mode")

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
                console.error("Error loading lines: ${e.message ?: "Unknown error"}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load lines: ${e.message ?: "Unknown error"}"
                )
            }
        }
    }

    fun onLineSelected(lineName: String) {
        val line = cachedLines.find { it.name == lineName }
        val lineId = line?.id ?: lineName
        
        _uiState.value = _uiState.value.copy(
            selectedLine = lineName,
            selectedStation = null,
            selectedDirection = null,
            availableStations = emptyList(),
            availableDirections = emptyList()
        )
        
        loadDirections(lineId)
    }

    private fun loadDirections(lineId: String) {
        scope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                val routeResponse = getRouteUseCase(lineId)
                val directions = routeResponse.directions.map { it.direction }
                _uiState.value = _uiState.value.copy(
                    availableDirections = directions,
                    isLoading = false
                )
                
                console.log("Loaded ${directions.size} directions for line $lineId")
            } catch (e: Exception) {
                console.error("Error loading directions: ${e.message ?: "Unknown error"}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load directions: ${e.message ?: "Unknown error"}"
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
        scope.launch {
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
                
                console.log("Loaded ${stationStrings.size} stations for $searchKey")
            } catch (e: Exception) {
                console.error("Error loading stations: ${e.message ?: "Unknown error"}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load stations: ${e.message ?: "Unknown error"}"
                )
            }
        }
    }

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
        
        scope.launch {
            try {
                // Use the save selection use case
                saveSelectionUseCase(selection)
                
                // Update UI
                val currentSelections = _savedSelections.value.toMutableList()
                currentSelections.add(0, selection)
                _savedSelections.value = currentSelections
                
                _uiState.value = state.copy(
                    success = "Selection saved!",
                    showSuccessDialog = true
                )
                
                console.log("Saved selection: $selection")
            } catch (e: Exception) {
                console.error("Error saving selection: ${e.message ?: "Unknown error"}")
                _uiState.value = state.copy(error = "Failed to save: ${e.message ?: "Unknown error"}")
            }
        }
    }

    private fun loadSavedSelections() {
        scope.launch {
            try {
                val selections = selectionRepository.selections.value
                _savedSelections.value = selections
                console.log("Loaded ${selections.size} saved selections")
            } catch (e: Exception) {
                console.error("Error loading saved selections: ${e.message ?: "Unknown error"}")
            }
        }
    }

    fun deleteSelection(selection: UserSelection) {
        scope.launch {
            try {
                selectionRepository.deleteSelection(selection)
                
                val currentSelections = _savedSelections.value.toMutableList()
                currentSelections.remove(selection)
                _savedSelections.value = currentSelections
                
                console.log("Deleted selection: $selection")
            } catch (e: Exception) {
                console.error("Error deleting selection: ${e.message ?: "Unknown error"}")
                _uiState.value = _uiState.value.copy(error = "Failed to delete: ${e.message ?: "Unknown error"}")
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