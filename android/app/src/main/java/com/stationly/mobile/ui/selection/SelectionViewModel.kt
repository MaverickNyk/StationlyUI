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

data class SduiUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isBackendOffline: Boolean = false,
    val layout: SduiAppScreen? = null,
    val selections: Map<String, String> = emptyMap(),
    val dropdownData: Map<String, List<SduiDropdownOption>> = emptyMap(),
    val modes: List<SduiDropdownOption> = emptyList(),
    val showSuccessDialog: Boolean = false,
    val isSaving: Boolean = false,
    val isLocating: Boolean = false,
    val currentTrack: String? = null,
    val noNearbyStationsFound: Boolean = false,
    val failedFetches: Set<String> = emptySet(),
    val userLat: Double? = null,
    val userLon: Double? = null
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
        viewModelScope.launch {
            selectionRepository.initialize()
            Log.d("SDUI", "SelectionRepository initialized with ${selectionRepository.selections.value.size} stations")
        }
        loadCachedLayout()
        loadServerLayout()
        loadModes()
        silentlyFetchLocation()
    }

    /** Grab user location quietly on startup so we can show distance in manual mode */
    private fun silentlyFetchLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { loc ->
                if (loc != null) {
                    _uiState.value = _uiState.value.copy(userLat = loc.latitude, userLon = loc.longitude)
                    Log.d("SDUI", "Silent location acquired: ${loc.latitude}, ${loc.longitude}")
                }
            }
        } catch (_: SecurityException) {}
    }

    fun retryLoad() {
        _uiState.value = _uiState.value.copy(error = null, failedFetches = emptySet())
        loadServerLayout()
        loadModes()
    }

    fun retryDropdown(componentId: String) {
        val state = _uiState.value
        val layout = state.layout ?: return
        val component = layout.components.find { it is SduiAppComponent.Dropdown && it.id == componentId }
            as? SduiAppComponent.Dropdown ?: return
        _uiState.value = state.copy(failedFetches = state.failedFetches - componentId)
        fetchDropdownData(component, state.selections)
    }

    private fun loadModes() {
        viewModelScope.launch {
            try {
                val modes = sduiService.getDropdownData("/modes")
                val updatedData = _uiState.value.dropdownData.toMutableMap()
                updatedData["mode"] = modes
                _uiState.value = _uiState.value.copy(
                    modes = modes, dropdownData = updatedData,
                    failedFetches = _uiState.value.failedFetches - "mode"
                )
            } catch (e: Exception) {
                Log.e("SDUI", "Failed to fetch modes", e)
                _uiState.value = _uiState.value.copy(failedFetches = _uiState.value.failedFetches + "mode")
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
                bootstrapDropdowns(cachedLayout)
            } catch (e: Exception) { Log.e("SDUI", "Failed to parse cached layout", e) }
        }
    }

    private fun bootstrapDropdowns(layout: SduiAppScreen) {
        layout.components.forEach { component ->
            if (component is SduiAppComponent.Dropdown) {
                val isBootstrap = component.dependsOn == null
                val isSatisfied = component.dependsOn != null && _uiState.value.selections.containsKey(component.dependsOn)
                if (isBootstrap || isSatisfied) fetchDropdownData(component)
            }
        }
    }

    private fun loadServerLayout(track: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val screenLayout = sduiService.getSelectionLayout(track)
                val jsonStr = Json.encodeToString(screenLayout)
                context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                    .edit().putString("cached_app_layout", jsonStr).apply()
                _uiState.value = _uiState.value.copy(isLoading = false, layout = screenLayout)
                bootstrapDropdowns(screenLayout)
            } catch (e: Exception) {
                Log.e("SDUI", "Failed to fetch Server Layout", e)
                if (_uiState.value.layout == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, isBackendOffline = true,
                        error = com.stationly.mobile.util.BackendErrorUtil.getFriendlyMessage(e)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    fun fetchNearbyStations(lat: Double? = null, lon: Double? = null, modeId: String? = null) {
        if (lat == null || lon == null) { fetchActualLocation(modeId); return }
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLocating = true)
                val nearbyStations = sduiService.getNearbyStations(lat, lon, modeId)
                if (nearbyStations.isNotEmpty()) {
                    val updatedDropdownData = _uiState.value.dropdownData.toMutableMap()
                    updatedDropdownData["station"] = nearbyStations
                    _uiState.value = _uiState.value.copy(
                        isLocating = false, dropdownData = updatedDropdownData,
                        userLat = lat, userLon = lon
                    )
                    if (nearbyStations.size == 1) onSelectionChanged("station", nearbyStations.first().id)
                } else {
                    _uiState.value = _uiState.value.copy(isLocating = false, noNearbyStationsFound = true)
                }
            } catch (e: Exception) {
                Log.e("SDUI", "Failed to fetch nearby stations", e)
                _uiState.value = _uiState.value.copy(
                    isLocating = false,
                    isBackendOffline = com.stationly.mobile.util.BackendErrorUtil.isBackendConnectionError(e),
                    error = "Location search failed: ${e.message}"
                )
            }
        }
    }

    private fun fetchActualLocation(modeId: String? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            _uiState.value = _uiState.value.copy(error = "Location permission missing", isLocating = false)
            return
        }
        _uiState.value = _uiState.value.copy(isLocating = true)
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        val s = _uiState.value.selections.toMutableMap()
                        s["lat"] = location.latitude.toString()
                        s["lon"] = location.longitude.toString()
                        _uiState.value = _uiState.value.copy(selections = s)
                        fetchNearbyStations(location.latitude, location.longitude, modeId)
                    } else fetchNearbyStations(51.5226, -0.1085, modeId)
                }
                .addOnFailureListener { fetchNearbyStations(51.5226, -0.1085, modeId) }
        } catch (_: SecurityException) {
            _uiState.value = _uiState.value.copy(isLocating = false)
        }
    }

    private fun fetchDropdownData(dropdown: SduiAppComponent.Dropdown, selections: Map<String, String>? = null) {
        viewModelScope.launch {
            var finalUrl = dropdown.dataSourceUrl
            try {
                val dependencies = selections ?: _uiState.value.selections
                dependencies.forEach { (key, value) -> finalUrl = finalUrl.replace("{$key}", value) }

                // For station dropdown in manual flow, append lat/lon so backend sorts by distance.
                // Skip if the URL already contains lat= (discovery flow already has them as template params).
                if (dropdown.id == "station" && !finalUrl.contains("lat=")) {
                    val lat = _uiState.value.userLat
                    val lon = _uiState.value.userLon
                    if (lat != null && lon != null) {
                        val sep = if ("?" in finalUrl) "&" else "?"
                        finalUrl = "${finalUrl}${sep}lat=$lat&lon=$lon"
                    }
                }

                // If the URL still has unresolved template params (e.g. {lat} before location is ready),
                // skip this fetch — it will be retried once the dependency value is available.
                if (finalUrl.contains("{")) {
                    Log.d("SDUI", "Skipping fetch — unresolved params in URL: $finalUrl")
                    return@launch
                }

                Log.d("SDUI", "Fetching dropdown data from: $finalUrl")

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
                    } catch (_: Exception) {}
                }

                val options = sduiService.getDropdownData(finalUrl)
                prefs.edit().putString(cacheKey, Json.encodeToString(options)).apply()

                val currentData = _uiState.value.dropdownData.toMutableMap()
                currentData[dropdown.id] = options
                _uiState.value = _uiState.value.copy(
                    dropdownData = currentData,
                    failedFetches = _uiState.value.failedFetches - dropdown.id
                )
            } catch (e: Exception) {
                Log.e("SDUI", "Failed to fetch options for ${dropdown.id} from $finalUrl", e)
                _uiState.value = _uiState.value.copy(failedFetches = _uiState.value.failedFetches + dropdown.id)
            }
        }
    }

    fun removeSelection(componentId: String) {
        val uiState = _uiState.value
        val newSelections = uiState.selections.toMutableMap()
        val currentDropdownData = uiState.dropdownData.toMutableMap()
        if (!newSelections.containsKey(componentId)) return
        newSelections.remove(componentId)

        val components = uiState.layout?.components ?: emptyList()
        fun recursiveClear(parentId: String) {
            components.forEach { comp ->
                val compId = comp.id
                if (compId != null) {
                    val dep = when (comp) {
                        is SduiAppComponent.Dropdown -> comp.dependsOn
                        is SduiAppComponent.FlowPicker -> comp.dependsOn
                        else -> null
                    }
                    if (dep == parentId) {
                        newSelections.remove(compId); currentDropdownData.remove(compId); recursiveClear(compId)
                    }
                }
            }
        }

        if (componentId == "tracking_flow") {
            _uiState.value = uiState.copy(selections = newSelections, currentTrack = null, dropdownData = currentDropdownData)
            loadServerLayout(null)
        } else {
            if (uiState.currentTrack == "discovery") {
                when (componentId) {
                    "station" -> { newSelections.remove("line"); newSelections.remove("direction"); currentDropdownData.remove("line"); currentDropdownData.remove("direction") }
                    "line"    -> { newSelections.remove("direction"); currentDropdownData.remove("direction") }
                }
            } else recursiveClear(componentId)
            _uiState.value = uiState.copy(selections = newSelections, dropdownData = currentDropdownData)
        }
    }

    fun onSelectionChanged(componentId: String, selectedValue: String) {
        if (selectedValue.isBlank()) { removeSelection(componentId); return }

        val uiState = _uiState.value
        val newSelections = uiState.selections.toMutableMap()
        val currentDropdownData = uiState.dropdownData.toMutableMap()

        if (componentId == "direction" && uiState.currentTrack == "manual") {
            newSelections.remove("station"); currentDropdownData.remove("station")
        }

        newSelections[componentId] = selectedValue

        val components = uiState.layout?.components ?: emptyList()
        fun recursiveClear(parentId: String) {
            components.forEach { comp ->
                val compId = comp.id
                if (compId != null) {
                    val dep = when (comp) {
                        is SduiAppComponent.Dropdown -> comp.dependsOn
                        is SduiAppComponent.FlowPicker -> comp.dependsOn
                        else -> null
                    }
                    if (dep == parentId) { newSelections.remove(compId); currentDropdownData.remove(compId); recursiveClear(compId) }
                }
            }
        }

        if (componentId == "tracking_flow") {
            newSelections.keys.filter { it !in listOf("mode", "tracking_flow") }.forEach { newSelections.remove(it); currentDropdownData.remove(it) }
            _uiState.value = uiState.copy(
                selections = newSelections, dropdownData = currentDropdownData,
                currentTrack = selectedValue, isLocating = false, noNearbyStationsFound = false
            )
            loadServerLayout(selectedValue)
        } else {
            if (uiState.currentTrack == "discovery") {
                when (componentId) {
                    "station" -> { newSelections.remove("line"); newSelections.remove("direction"); currentDropdownData.remove("line"); currentDropdownData.remove("direction") }
                    "line"    -> { newSelections.remove("direction"); currentDropdownData.remove("direction") }
                }
            } else recursiveClear(componentId)
            _uiState.value = uiState.copy(selections = newSelections, dropdownData = currentDropdownData)
        }

        if (componentId == "station") {
            (components.find { it is SduiAppComponent.Dropdown && it.id == "line" } as? SduiAppComponent.Dropdown)
                ?.let { fetchDropdownData(it, newSelections) }
        }
        components.forEach { if (it is SduiAppComponent.Dropdown && it.dependsOn == componentId) fetchDropdownData(it, newSelections) }

        if (componentId == "direction" && uiState.currentTrack == "manual") {
            (components.find { it is SduiAppComponent.Dropdown && it.id == "station" } as? SduiAppComponent.Dropdown)
                ?.let { fetchDropdownData(it, newSelections) }
        }
    }

    fun popLastSelection() {
        val state = _uiState.value
        val track = state.currentTrack
        val selections = state.selections
        if (selections.isEmpty() && track == null) return
        if (track == null) { clearSelections(); return }

        val flowKeys = when (track) {
            "manual" -> listOf("line", "direction", "station")
            else     -> listOf("station", "line", "direction")
        }
        val lastFlowKey = flowKeys.lastOrNull { selections.containsKey(it) }
        if (lastFlowKey != null) removeSelection(lastFlowKey)
        else {
            val newSel = selections.toMutableMap().apply { remove("tracking_flow") }
            _uiState.value = state.copy(
                selections = newSel, currentTrack = null,
                dropdownData = state.dropdownData.toMutableMap().apply { remove("line"); remove("direction"); remove("station") }
            )
        }
    }

    fun resetToStage(stageKey: String) {
        val state = _uiState.value
        if (!state.selections.containsKey(stageKey)) return
        val flowKeys = when (state.currentTrack) {
            "manual" -> listOf("line", "direction", "station")
            else     -> listOf("station", "line", "direction")
        }
        val full = listOf("mode", "tracking_flow") + flowKeys
        val idx = full.indexOf(stageKey)
        if (idx >= 0) full.getOrNull(idx + 1)?.takeIf { state.selections.containsKey(it) }?.let { removeSelection(it) }
    }

    fun onActionTriggered(action: String) {
        if (action != "SAVE_SELECTION_ACTION") return
        val state = _uiState.value
        val mode = state.selections["mode"]; val line = state.selections["line"]
        val direction = state.selections["direction"]; val stationId = state.selections["station"]
        if (mode == null || line == null || direction == null || stationId == null) {
            _uiState.value = state.copy(error = "Please complete all selections"); return
        }
        val stationName = state.dropdownData["station"]?.find { it.id == stationId }?.label ?: stationId
        val userSelection = UserSelection(mode = mode, line = line, station = stationId,
            stationName = stationName, direction = direction, destinations = emptyList(), destinationIds = emptyList())

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, isSaving = true)
            try {
                stationLifecycleUseCase.cleanupAll()
                stationLifecycleUseCase.setupStation(userSelection, isFirstTime = true)
                try {
                    com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.let { user ->
                        sduiService.syncStations(user.uid, listOf(SubscribedStation(
                            id = userSelection.station, name = userSelection.stationName,
                            line = userSelection.line, mode = userSelection.mode, direction = userSelection.direction
                        )))
                    }
                } catch (_: Exception) {}
                _uiState.value = state.copy(isLoading = false, isSaving = false, showSuccessDialog = true)
            } catch (e: Exception) {
                _uiState.value = state.copy(isLoading = false, isSaving = false, error = "Failed to save: ${e.message}")
            }
        }
    }

    fun dismissSuccessDialog() { _uiState.value = _uiState.value.copy(showSuccessDialog = false) }

    fun setCurrentTrack(track: String?) {
        if (track == null) removeSelection("tracking_flow")
        else _uiState.value = _uiState.value.copy(currentTrack = track, noNearbyStationsFound = false)
    }

    fun clearSelections() {
        _uiState.value = _uiState.value.copy(
            selections = emptyMap(), dropdownData = emptyMap(),
            currentTrack = null, error = null, failedFetches = emptySet()
        )
        loadServerLayout()
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
}
