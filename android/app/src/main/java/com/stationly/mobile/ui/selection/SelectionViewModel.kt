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

// Dynamic State UI Class for Jetpack Compose
data class SduiUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val layout: SduiAppScreen? = null,
    // Store simple values selected by users keyed by component ID
    val selections: Map<String, String> = emptyMap(),
    // Data sources fetched for dropdowns keyed by component ID
    val dropdownData: Map<String, List<SduiDropdownOption>> = emptyMap(),
    val showSuccessDialog: Boolean = false,
    val isSaving: Boolean = false
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
    private fun loadServerLayout() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val screenLayout = sduiService.getSelectionLayout()
                
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
                if (_uiState.value.layout == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Server Layout unavailable: ${e.message}")
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    Log.i("SDUI", "Fetch failed, but using cached layout instead of showing error.")
                }
            }
        }
    }
    
    // 2. Fetch Option Data dynamically via the Server API Route
    private fun fetchDropdownData(dropdown: SduiAppComponent.Dropdown) {
        viewModelScope.launch {
            var finalUrl = dropdown.dataSourceUrl
            try {
                // Parse dependency values into the URL: e.g. "?mode={mode}"
                val dependencies = _uiState.value.selections
                
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
    
    // 3. Handle Form Changes
    fun onSelectionChanged(componentId: String, selectedValue: String) {
        val uiState = _uiState.value
        val newSelections = uiState.selections.toMutableMap()
        val currentDropdownData = uiState.dropdownData.toMutableMap()
        
        newSelections[componentId] = selectedValue
        Log.d("SDUI", "Selection changed: $componentId -> $selectedValue")

        // Cascading Clear: If a parent changes, we must wipe its children, grandchildren, etc.
        val components = uiState.layout?.components ?: emptyList()
        
        // Find all dependencies and clear them recursively from the selection and data map
        fun recursiveClear(parentId: String) {
            components.forEach { comp: SduiAppComponent ->
                if (comp is SduiAppComponent.Dropdown && comp.dependsOn == parentId) {
                    Log.d("SDUI", "Cascading clear for child: ${comp.id} (depends on $parentId)")
                    newSelections.remove(comp.id)
                    currentDropdownData.remove(comp.id)
                    recursiveClear(comp.id)
                }
            }
        }
        recursiveClear(componentId)
        
        _uiState.value = uiState.copy(
            selections = newSelections,
            dropdownData = currentDropdownData
        )
        
        // Trigger data fetch for immediate children now that parent is set
        components.forEach { component: SduiAppComponent ->
            if (component is SduiAppComponent.Dropdown && component.dependsOn == componentId) {
                fetchDropdownData(component)
            }
        }
        
        Log.d("SDUI", "State updated. Selections: $newSelections, Data keys: ${currentDropdownData.keys}")
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
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}