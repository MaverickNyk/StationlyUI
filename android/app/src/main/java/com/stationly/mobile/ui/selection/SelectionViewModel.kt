package com.stationly.mobile.ui.selection

import android.app.Application
import android.util.Log
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.*
import com.stationly.core.platform.AndroidNotificationManager
import com.stationly.core.platform.AndroidStorageManager
import com.stationly.core.platform.AndroidWidgetManager
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.stationly.core.service.SduiApiServiceFactory
import com.stationly.core.service.TflApiServiceFactory
import com.stationly.core.platform.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.CancellationTokenSource

// Dynamic State UI Class for Jetpack Compose
data class SduiUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isBackendOffline: Boolean = false,
    val layout: SduiAppScreen? = null,
    // Store simple values selected by users keyed by component ID
    val selections: Map<String, String> = emptyMap(),
    // Data sources fetched for dropdowns keyed by component ID
    val dropdownData: Map<String, List<SduiDropdownOption>> = emptyMap(),
    val modes: List<SduiDropdownOption> = emptyList(),
    val showSuccessDialog: Boolean = false,
    val isSaving: Boolean = false,
    val isLocating: Boolean = false,
    val currentTrack: String? = null,
    val noNearbyStationsFound: Boolean = false
)

class SelectionViewModel(application: Application) : AndroidViewModel(application) {
    
    private val sduiService = SduiApiServiceFactory.create()
    
    private val context = application.applicationContext
    private val storageManager = AndroidStorageManager(context)
    private val notificationManager = AndroidNotificationManager(context)
    private val widgetManager = AndroidWidgetManager(context)
    
    private val apiService = TflApiServiceFactory.create()
    private val syncPredictionsUseCase = com.stationly.core.usecase.SyncPredictionsUseCase(Platform.sqlStorage)
    private val selectionRepository = SelectionRepository(storageManager, Platform.sqlStorage)
    private val departureRepository = DepartureRepository(apiService, storageManager, Platform.sqlStorage, syncPredictionsUseCase)
    
    private val stationLifecycleUseCase = com.stationly.core.usecase.StationLifecycleUseCase(
        selectionRepository = selectionRepository,
        departureRepository = departureRepository,
        notificationManager = Platform.notificationManager,
        widgetManager = Platform.widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = Platform.storageManager
    )

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    
    private val _uiState = MutableStateFlow(SduiUiState())
    val uiState: StateFlow<SduiUiState> = _uiState.asStateFlow()
    
    init {
        // Initialize repositories from SQL
        viewModelScope.launch {
            selectionRepository.initialize()
            Log.d("SDUI", "SelectionRepository initialized with ${selectionRepository.selections.value.size} stations")
        }
        
        loadCachedLayout()
        loadServerLayout()
        loadModes()
    }

    /** Called by the NoConnectionScreen retry button */
    fun retryLoad() {
        _uiState.value = _uiState.value.copy(error = null)
        loadServerLayout()
        loadModes()
    }

    private fun loadModes() {
        viewModelScope.launch {
            try {
                val modes = sduiService.getDropdownData("/modes")
                val updatedData = _uiState.value.dropdownData.toMutableMap()
                updatedData["mode"] = modes
                _uiState.value = _uiState.value.copy(modes = modes, dropdownData = updatedData)
            } catch (e: Exception) {
                Log.e("SDUI", "Failed to fetch modes", e)
            }
        }
    }

    private fun loadCachedLayout() {
        val cachedLayoutJson = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
            .getString("cached_app_layout", null)
        
        if (cachedLayoutJson != null) {
            try {
                val format = Json { ignoreUnknownKeys = true }
                val cachedLayout = format.decodeFromString<SduiAppScreen>(cachedLayoutJson)
                _uiState.value = _uiState.value.copy(layout = cachedLayout)
                Log.d("SDUI", "Loaded cached app layout")
                
                // Trigger dropdowns from cache if bootstrap
                bootstrapDropdowns(cachedLayout)
            } catch (e: Exception) {
                Log.e("SDUI", "Failed to parse cached layout", e)
            }
        }
    }

    private fun bootstrapDropdowns(layout: SduiAppScreen) {
        layout.components.forEach { component ->
            if (component is SduiAppComponent.Dropdown) {
                val isBootstrap = component.dependsOn == null
                val isSatisfied = component.dependsOn != null && _uiState.value.selections.containsKey(component.dependsOn)
                
                if (isBootstrap || isSatisfied) {
                    fetchDropdownData(component)
                }
            }
        }
    }
    
    // 1. Fetch Blueprint Layout
    private fun loadServerLayout(track: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val screenLayout = sduiService.getSelectionLayout(track)
                
                // Cache it for offline use before applying
                val jsonStr = Json.encodeToString(screenLayout)
                context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("cached_app_layout", jsonStr)
                    .apply()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    layout = screenLayout
                )
                // Populating currently valid dropdowns (Bootstrap + existing selections)
                bootstrapDropdowns(screenLayout)
            } catch (e: Exception) {
                Log.e("SDUI", "Failed to fetch Server Layout", e)
                val isBackendError = com.stationly.mobile.util.BackendErrorUtil.isBackendConnectionError(e)
                if (_uiState.value.layout == null) {
                    // No cached layout — show the service unavailable screen
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isBackendOffline = true, // Treat as service unavailable
                        error = com.stationly.mobile.util.BackendErrorUtil.getFriendlyMessage(e)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    Log.i("SDUI", "Fetch failed, but using cached layout instead of showing error.")
                }
            }
        }
    }
    
    // 3. Fetch Nearby Stations using Lat/Lon
    fun fetchNearbyStations(lat: Double? = null, lon: Double? = null, modeId: String? = null) {
        Log.d("SDUI", "fetchNearbyStations called for lat=$lat lon=$lon mode=$modeId")
        
        if (lat == null || lon == null) {
            fetchActualLocation(modeId)
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLocating = true)
                val nearbyStations = sduiService.getNearbyStations(lat, lon, modeId)
                Log.d("SDUI", "sduiService.getNearbyStations returned ${nearbyStations.size} stations")
                
                // Populate the 'station' dropdown with nearby candidates
                if (nearbyStations.isNotEmpty()) {
                    val updatedDropdownData = _uiState.value.dropdownData.toMutableMap()
                    updatedDropdownData["station"] = nearbyStations
                    
                    _uiState.value = _uiState.value.copy(
                        isLocating = false,
                        dropdownData = updatedDropdownData
                    )
                    Log.d("SDUI", "Fetched ${nearbyStations.size} nearby stations for lat=$lat lon=$lon")

                    // If there's exactly one very close station, we could auto-select it
                    val autoSelect = if (nearbyStations.size == 1) nearbyStations.first().id else null
                    if (autoSelect != null) {
                        onSelectionChanged("station", autoSelect)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(isLocating = false, noNearbyStationsFound = true)
                    Log.w("SDUI", "Zero nearby stations found for lat=$lat lon=$lon")
                }
            } catch (e: Exception) {
                Log.e("SDUI", "Failed to fetch nearby stations", e)
                val isBackendError = com.stationly.mobile.util.BackendErrorUtil.isBackendConnectionError(e)
                _uiState.value = _uiState.value.copy(
                    isLocating = false,
                    isBackendOffline = isBackendError,
                    error = "Location search failed: ${e.message}"
                )
            }
        }
    }

    private fun fetchActualLocation(modeId: String? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("SDUI", "Location permission not granted in ViewModel")
            _uiState.value = _uiState.value.copy(error = "Location permission missing", isLocating = false)
            return
        }

        _uiState.value = _uiState.value.copy(isLocating = true)
        
        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    Log.d("SDUI", "Real location found: ${location.latitude}, ${location.longitude}")
                    val updatedSelections = _uiState.value.selections.toMutableMap()
                    updatedSelections["lat"] = location.latitude.toString()
                    updatedSelections["lon"] = location.longitude.toString()
                    _uiState.value = _uiState.value.copy(selections = updatedSelections)
                    fetchNearbyStations(location.latitude, location.longitude, modeId)
                } else {
                    Log.e("SDUI", "Location is null, falling back to default")
                    // Fallback to London Central if GPS fails but permission is there
                    fetchNearbyStations(51.5226, -0.1085, modeId)
                }
            }.addOnFailureListener { e ->
                Log.e("SDUI", "Failed to get location", e)
                fetchNearbyStations(51.5226, -0.1085, modeId) // Fallback
            }
        } catch (e: SecurityException) {
            Log.e("SDUI", "SecurityException fetching location", e)
            _uiState.value = _uiState.value.copy(isLocating = false)
        }
    }

    // 4. Fetch Option Data dynamically via the Server API Route
    private fun fetchDropdownData(dropdown: SduiAppComponent.Dropdown, selections: Map<String, String>? = null) {
        viewModelScope.launch {
            var finalUrl = dropdown.dataSourceUrl
            try {
                // Parse dependency values into the URL: e.g. "?mode={mode}"
                val dependencies = selections ?: _uiState.value.selections
                
                // Replace {key} with the actual selected value so the server knows what to return
                dependencies.forEach { (key, value) ->
                    finalUrl = finalUrl.replace("{$key}", value)
                }
                
                Log.d("SDUI", "Fetching dropdown data from: $finalUrl")
                
                // Try to load from cache first for immediate feel
                val cacheKey = "cached_dropdown_$finalUrl"
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val cachedOptionsJson = prefs.getString(cacheKey, null)
                if (cachedOptionsJson != null) {
                    try {
                        val format = Json { ignoreUnknownKeys = true }
                        val cachedOptions = format.decodeFromString<List<SduiDropdownOption>>(cachedOptionsJson)
                        val currentData = _uiState.value.dropdownData.toMutableMap()
                        currentData[dropdown.id] = cachedOptions
                        _uiState.value = _uiState.value.copy(dropdownData = currentData)
                        Log.d("SDUI", "Loaded from cache for ${dropdown.id}")
                    } catch (e: Exception) {}
                }

                val options = sduiService.getDropdownData(finalUrl)
                Log.d("SDUI", "Fetched ${options.size} options for ${dropdown.id}")
                
                // Save to cache
                prefs.edit().putString(cacheKey, Json.encodeToString(options)).apply()

                val currentData = _uiState.value.dropdownData.toMutableMap()
                currentData[dropdown.id] = options
                
                _uiState.value = _uiState.value.copy(dropdownData = currentData)
            } catch (e: Exception) {
                Log.e("SDUI", "Failed to fetch options for ${dropdown.id} from $finalUrl", e)
            }
        }
    }
    
    // Cleanly removes a selection and cascades downwards
    fun removeSelection(componentId: String) {
        val uiState = _uiState.value
        val newSelections = uiState.selections.toMutableMap()
        val currentDropdownData = uiState.dropdownData.toMutableMap()
        
        if (!newSelections.containsKey(componentId)) return
        
        Log.d("SDUI", "Removing selection: $componentId")
        newSelections.remove(componentId)
        
        val layout = uiState.layout
        val components = layout?.components ?: emptyList()
        
        fun recursiveClear(parentId: String) {
            components.forEach { comp ->
                val compId = comp.id
                if (compId != null) {
                    val dependsOn = when(comp) {
                        is SduiAppComponent.Dropdown -> comp.dependsOn
                        is SduiAppComponent.FlowPicker -> comp.dependsOn
                        else -> null
                    }
                    if (dependsOn == parentId) {
                        newSelections.remove(compId)
                        currentDropdownData.remove(compId)
                        recursiveClear(compId)
                    }
                }
            }
        }
        
        if (componentId == "tracking_flow") {
            _uiState.value = uiState.copy(
                selections = newSelections,
                currentTrack = null,
                dropdownData = currentDropdownData
            )
            loadServerLayout(null)
        } else {
            if (uiState.currentTrack == "discovery") {
                if (componentId == "station") {
                    newSelections.remove("line")
                    newSelections.remove("direction")
                    currentDropdownData.remove("line")
                    currentDropdownData.remove("direction")
                } else if (componentId == "line") {
                    newSelections.remove("direction")
                    currentDropdownData.remove("direction")
                }
            } else {
                recursiveClear(componentId)
            }
            _uiState.value = uiState.copy(selections = newSelections, dropdownData = currentDropdownData)
        }
    }

    fun onSelectionChanged(componentId: String, selectedValue: String) {
        if (selectedValue.isBlank()) {
            removeSelection(componentId)
            return
        }
        
        val uiState = _uiState.value
        val newSelections = uiState.selections.toMutableMap()
        val currentDropdownData = uiState.dropdownData.toMutableMap()
        
        newSelections[componentId] = selectedValue
        Log.d("SDUI", "Selection changed: $componentId -> $selectedValue")

        // Cascading Clear: If a parent changes, we must wipe its children, grandchildren, etc.
        val layout = uiState.layout
        val components = layout?.components ?: emptyList()
        
        fun recursiveClear(parentId: String) {
            components.forEach { comp: SduiAppComponent ->
                val compId = comp.id
                if (compId != null) {
                    val dependsOn = when(comp) {
                        is SduiAppComponent.Dropdown -> comp.dependsOn
                        is SduiAppComponent.FlowPicker -> comp.dependsOn
                        else -> null
                    }
                    if (dependsOn == parentId) {
                        Log.d("SDUI", "Cascading clear for child: $compId (depends on $parentId)")
                        newSelections.remove(compId)
                        currentDropdownData.remove(compId)
                        recursiveClear(compId)
                    }
                }
            }
        }
        
        // Update internal track state if tracking_flow component is set
        if (componentId == "tracking_flow") {
            val keysToKeep = listOf("mode", "tracking_flow")
            val keysToRemove = newSelections.keys.filter { !keysToKeep.contains(it) }
            keysToRemove.forEach { key ->
                newSelections.remove(key)
                currentDropdownData.remove(key)
            }

            _uiState.value = uiState.copy(
                selections = newSelections,
                dropdownData = currentDropdownData,
                currentTrack = selectedValue,
                isLocating = false,
                noNearbyStationsFound = false
            )
            // ACTION: Re-fetch layout from server specifically for this track
            loadServerLayout(selectedValue)
        } else {
            // Reverse dependencies for discovery track (special UX logic)
            if (uiState.currentTrack == "discovery") {
                if (componentId == "station") {
                    newSelections.remove("line")
                    newSelections.remove("direction")
                    currentDropdownData.remove("line")
                    currentDropdownData.remove("direction")
                } else if (componentId == "line") {
                    newSelections.remove("direction")
                    currentDropdownData.remove("direction")
                }
            } else {
                recursiveClear(componentId)
            }
            
            _uiState.value = uiState.copy(
                selections = newSelections,
                dropdownData = currentDropdownData
            )
        }
        
        // Special Cascade: When Station is selected (manually or via location), fetch lines for it
        if (componentId == "station") {
             val lineComponent = components.find { it is SduiAppComponent.Dropdown && it.id == "line" } as? SduiAppComponent.Dropdown
             if (lineComponent != null) {
                 fetchDropdownData(lineComponent, newSelections)
             }
        }

        // Standard Cascade: Fetch components that depend on this selection
        components.forEach { component ->
            if (component is SduiAppComponent.Dropdown && component.dependsOn == componentId) {
                fetchDropdownData(component, newSelections)
            }
        }
        
        Log.d("SDUI", "State updated. Selections: $newSelections, Data keys: ${currentDropdownData.keys}")
    }

    private val selectionHierarchy = listOf("mode", "tracking_flow", "station", "line", "direction")

    fun popLastSelection() {
        val currentSelections = _uiState.value.selections
        if (currentSelections.isEmpty()) return
        
        val lastKey = selectionHierarchy.lastOrNull { currentSelections.containsKey(it) }
        if (lastKey != null) {
            removeSelection(lastKey)
        }
    }

    fun resetToStage(stageKey: String) {
        val currentSelections = _uiState.value.selections
        if (!currentSelections.containsKey(stageKey)) return
        
        val stageIndex = selectionHierarchy.indexOf(stageKey)
        if (stageIndex >= 0) {
            // Remove the stage immediately following this one to keep this stage selected
            val nextStage = selectionHierarchy.getOrNull(stageIndex + 1)
            if (nextStage != null && currentSelections.containsKey(nextStage)) {
                removeSelection(nextStage)
            }
        }
    }

    fun onActionTriggered(action: String) {
        if (action == "SAVE_SELECTION_ACTION") {
            Log.d("SDUI", "Server-Driven Saving Sequence: ${_uiState.value.selections}")
            
            val state = _uiState.value
            val mode = state.selections["mode"]
            val line = state.selections["line"]
            val direction = state.selections["direction"]
            val stationId = state.selections["station"]
            
            if (mode == null || line == null || direction == null || stationId == null) {
                _uiState.value = state.copy(error = "Please complete all selections")
                return
            }
            
            val stationName = state.dropdownData["station"]?.find { it.id == stationId }?.label ?: stationId
            
            val userSelection = UserSelection(
                mode = mode,
                line = line,
                station = stationId,
                stationName = stationName,
                direction = direction,
                destinations = emptyList(),
                destinationIds = emptyList()
            )
            
            viewModelScope.launch {
                _uiState.value = state.copy(isLoading = true, isSaving = true)
                try {
                    // 1. Enforce Single Station Rule: purge all existing data before saving
                    stationLifecycleUseCase.cleanupAll()

                    // 2. Set up the new station (FCM subscription + eager data fetch)
                    stationLifecycleUseCase.setupStation(userSelection, isFirstTime = true)

                    // 3. Sync selection to cloud profile (best-effort)
                    try {
                        val authUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                        if (authUser != null) {
                            val mapped = listOf(com.stationly.core.model.sdui.SubscribedStation(
                                id = userSelection.station,
                                name = userSelection.stationName,
                                line = userSelection.line,
                                mode = userSelection.mode,
                                direction = userSelection.direction
                            ))
                            sduiService.syncStations(authUser.uid, mapped)
                        }
                    } catch (e: Exception) {
                        Log.e("SelectionViewModel", "Cloud sync failed (non-fatal)", e)
                    }

                    _uiState.value = state.copy(
                        isLoading = false,
                        isSaving = false,
                        showSuccessDialog = true
                    )
                } catch (e: Exception) {
                    Log.e("SelectionViewModel", "Failed to save station selection", e)
                    _uiState.value = state.copy(isLoading = false, isSaving = false, error = "Failed to save: ${e.message}")
                }
            }
        }
    }

    fun dismissSuccessDialog() {
        _uiState.value = _uiState.value.copy(showSuccessDialog = false)
    }
    
    fun setCurrentTrack(track: String?) {
        if (track == null) {
            removeSelection("tracking_flow")
        } else {
            _uiState.value = _uiState.value.copy(currentTrack = track, noNearbyStationsFound = false)
        }
    }

    fun clearSelections() {
        _uiState.value = _uiState.value.copy(
            selections = emptyMap(),
            dropdownData = emptyMap(),
            currentTrack = null,
            error = null
        )
        // Refresh the layout to trigger fresh bootstrap dropdowns if needed
        loadServerLayout()
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}