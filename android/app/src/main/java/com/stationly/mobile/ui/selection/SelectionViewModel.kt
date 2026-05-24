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
import kotlinx.coroutines.delay
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
    val recentStations: List<SduiDropdownOption> = emptyList(),
    val showSuccessDialog: Boolean = false,
    val isSaving: Boolean = false,
    val isLocating: Boolean = false,
    val isGpsUnavailable: Boolean = false,
    val isSearchEmpty: Boolean = false,
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
        loadRecentStations()
        silentlyFetchLocation()
    }

    private fun loadRecentStations() {
        val json = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
            .getString("recent_stations", null) ?: return
        try {
            val stations = Json { ignoreUnknownKeys = true }.decodeFromString<List<SduiDropdownOption>>(json)
            _uiState.value = _uiState.value.copy(recentStations = stations)
        } catch (_: Exception) {}
    }

    private fun saveRecentStation(station: SduiDropdownOption) {
        val updated = (_uiState.value.recentStations.filterNot { it.id == station.id } + station)
            .takeLast(3).reversed().take(3)
        _uiState.value = _uiState.value.copy(recentStations = updated)
        try {
            context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                .edit().putString("recent_stations", Json.encodeToString(updated)).apply()
        } catch (_: Exception) {}
    }

    /** Quietly grab user location on startup so it's ready when Mode is selected. */
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
        _uiState.value = _uiState.value.copy(error = null, isBackendOffline = false, failedFetches = emptySet())
        loadServerLayout()
        loadModes()
    }

    private fun scheduleAutoRetry() {
        viewModelScope.launch {
            delay(30_000)
            if (_uiState.value.isBackendOffline) retryLoad()
        }
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

    private fun loadServerLayout() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val screenLayout = sduiService.getSelectionLayout()
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
                    scheduleAutoRetry()
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    // ─── Station loading ───────────────────────────────────────────────────────

    /**
     * Load nearby stations. If lat/lon are null, triggers a GPS fix first.
     * Called from the screen when the station step becomes active.
     */
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
                        isGpsUnavailable = false, isSearchEmpty = false, userLat = lat, userLon = lon
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLocating = false, isGpsUnavailable = true)
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
                        _uiState.value = _uiState.value.copy(userLat = location.latitude, userLon = location.longitude)
                        fetchNearbyStations(location.latitude, location.longitude, modeId)
                    } else {
                        _uiState.value = _uiState.value.copy(isLocating = false, isGpsUnavailable = true)
                    }
                }
                .addOnFailureListener {
                    _uiState.value = _uiState.value.copy(isLocating = false, isGpsUnavailable = true)
                }
        } catch (_: SecurityException) {
            _uiState.value = _uiState.value.copy(isLocating = false)
        }
    }

    /** Live search — called by the station screen's search bar (debounced in the UI). */
    fun searchStations(query: String) {
        val state = _uiState.value
        val mode = state.selections["mode"] ?: ""

        if (query.isBlank()) {
            // Restore nearby results when search is cleared
            val lat = state.userLat
            val lon = state.userLon
            if (lat != null && lon != null) fetchNearbyStations(lat, lon, mode.ifBlank { null })
            else _uiState.value = state.copy(isGpsUnavailable = state.userLat == null, isSearchEmpty = false)
            return
        }

        viewModelScope.launch {
            try {
                val lat = state.userLat
                val lon = state.userLon
                // URL-encode the query so multi-word searches like "Bond Street"
                // or queries with special chars (& ? = #) reach the server
                // intact instead of being parsed as additional parameters.
                // The backend already does case-insensitive substring matching
                // (see DataCacheService.searchStationsByQuery) so client-side
                // we only need to pass the raw query through cleanly.
                val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                val encodedMode  = java.net.URLEncoder.encode(mode, "UTF-8")
                val locationSuffix = if (lat != null && lon != null) "&lat=$lat&lon=$lon" else ""
                val results = sduiService.getDropdownData(
                    "/stations/search?searchKey=$encodedQuery&mode=$encodedMode$locationSuffix"
                )
                val updatedData = state.dropdownData.toMutableMap()
                updatedData["station"] = results
                _uiState.value = _uiState.value.copy(
                    dropdownData = updatedData, isSearchEmpty = results.isEmpty(), isGpsUnavailable = false
                )
            } catch (e: Exception) {
                Log.e("SDUI", "Station search failed", e)
            }
        }
    }

    // ─── Selection changes ─────────────────────────────────────────────────────

    fun onSelectionChanged(componentId: String, selectedValue: String) {
        if (selectedValue.isBlank()) { removeSelection(componentId); return }

        val state = _uiState.value
        val newSelections = state.selections.toMutableMap()
        val newDropdownData = state.dropdownData.toMutableMap()

        // Clear downstream state when a parent changes
        when (componentId) {
            "mode" -> {
                newSelections.remove("station"); newSelections.remove("line"); newSelections.remove("direction")
                newSelections.remove("lat");     newSelections.remove("lon")
                newDropdownData.remove("station"); newDropdownData.remove("line"); newDropdownData.remove("direction")
            }
            "station" -> {
                newSelections.remove("line"); newSelections.remove("direction")
                newDropdownData.remove("line"); newDropdownData.remove("direction")
            }
            "line" -> {
                newSelections.remove("direction"); newDropdownData.remove("direction")
            }
        }

        newSelections[componentId] = selectedValue
        if (componentId == "station") {
            state.dropdownData["station"]?.find { it.id == selectedValue }?.let { saveRecentStation(it) }
        }
        _uiState.value = state.copy(selections = newSelections, dropdownData = newDropdownData)

        val components = state.layout?.components ?: emptyList()

        // Mode selected → kick off station fetch (nearby if location ready, else GPS prompt)
        if (componentId == "mode") {
            val lat = state.userLat
            val lon = state.userLon
            if (lat != null && lon != null) {
                fetchNearbyStations(lat, lon, selectedValue)
            }
            // If location is not yet known the screen will request it via fetchNearbyStations(null,null,mode)
        }

        // Station selected → fetch lines for this station group
        if (componentId == "station") {
            components.find { it is SduiAppComponent.Dropdown && it.id == "line" }
                ?.let { fetchDropdownData(it as SduiAppComponent.Dropdown, newSelections) }
        }

        // For all other cascading dropdowns (direction depends on line, etc.)
        components.forEach {
            if (it is SduiAppComponent.Dropdown && it.dependsOn == componentId && it.id != "line") {
                fetchDropdownData(it, newSelections)
            }
        }
    }

    fun removeSelection(componentId: String) {
        val state = _uiState.value
        val newSel = state.selections.toMutableMap()
        val newData = state.dropdownData.toMutableMap()

        newSel.remove(componentId)

        when (componentId) {
            "mode" -> {
                // Mode change invalidates everything downstream
                listOf("station", "line", "direction", "lat", "lon").forEach { newSel.remove(it); newData.remove(it) }
            }
            "station" -> {
                // Clear downstream selections only — keep dropdown data so back-nav restores instantly
                listOf("line", "direction").forEach { newSel.remove(it) }
            }
            "line" -> {
                newSel.remove("direction")
            }
            // "direction" has no downstream
        }

        _uiState.value = state.copy(selections = newSel, dropdownData = newData, isGpsUnavailable = false, isSearchEmpty = false)
    }

    fun popLastSelection() {
        val state = _uiState.value
        when {
            "direction" in state.selections -> removeSelection("direction")
            "line"      in state.selections -> removeSelection("line")
            "station"   in state.selections -> removeSelection("station")
            "mode"      in state.selections -> clearSelections()
        }
    }

    // ─── Save ──────────────────────────────────────────────────────────────────

    fun onActionTriggered(action: String) {
        if (action != "SAVE_SELECTION_ACTION") return
        val state = _uiState.value
        val mode      = state.selections["mode"]
        val line      = state.selections["line"]
        val direction = state.selections["direction"]
        val stationId = state.selections["station"]

        if (mode == null || line == null || direction == null || stationId == null) {
            _uiState.value = state.copy(error = "Please complete all selections"); return
        }

        val stationName = state.dropdownData["station"]?.find { it.id == stationId }?.label ?: stationId

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, isSaving = true)
            try {
                // Resolve the exact physical stop within the station group
                val resolvedId = sduiService.resolveStation(stationId, mode, line, direction)
                Log.d("SDUI", "Station resolved: $stationId → $resolvedId")

                val userSelection = UserSelection(
                    mode = mode, line = line,
                    station = resolvedId, stationName = stationName,
                    direction = direction, destinations = emptyList(), destinationIds = emptyList()
                )

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

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun fetchDropdownData(dropdown: SduiAppComponent.Dropdown, selections: Map<String, String>? = null) {
        viewModelScope.launch {
            var finalUrl = dropdown.dataSourceUrl
            try {
                val deps = selections ?: _uiState.value.selections
                deps.forEach { (key, value) -> finalUrl = finalUrl.replace("{$key}", value) }

                if (finalUrl.contains("{")) {
                    Log.d("SDUI", "Skipping fetch — unresolved params in URL: $finalUrl")
                    return@launch
                }

                Log.d("SDUI", "Fetching dropdown data from: $finalUrl")

                val cacheKey = "cached_dropdown_$finalUrl"
                val tsKey = "${cacheKey}_ts"
                val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val cachedJson = prefs.getString(cacheKey, null)
                val cachedTs = prefs.getLong(tsKey, 0L)
                val cacheAgeMs = System.currentTimeMillis() - cachedTs
                val cacheValid = cachedJson != null && cacheAgeMs < 24 * 60 * 60 * 1000L
                if (cacheValid) {
                    try {
                        val format = Json { ignoreUnknownKeys = true }
                        val cached = format.decodeFromString<List<SduiDropdownOption>>(cachedJson!!)
                        val cur = _uiState.value.dropdownData.toMutableMap()
                        cur[dropdown.id] = cached
                        _uiState.value = _uiState.value.copy(dropdownData = cur)
                    } catch (_: Exception) {}
                }

                val options = sduiService.getDropdownData(finalUrl)
                prefs.edit().putString(cacheKey, Json.encodeToString(options)).putLong(tsKey, System.currentTimeMillis()).apply()

                val cur = _uiState.value.dropdownData.toMutableMap()
                cur[dropdown.id] = options
                _uiState.value = _uiState.value.copy(
                    dropdownData = cur,
                    failedFetches = _uiState.value.failedFetches - dropdown.id
                )

                // Auto-skip single-option steps — brief pause so the screen renders first
                if (options.size == 1 && dropdown.id in listOf("line", "direction")
                    && dropdown.id !in _uiState.value.selections) {
                    kotlinx.coroutines.delay(500)
                    if (dropdown.id !in _uiState.value.selections) {
                        onSelectionChanged(dropdown.id, options[0].id)
                    }
                }
            } catch (e: Exception) {
                Log.e("SDUI", "Failed to fetch options for ${dropdown.id} from $finalUrl", e)
                _uiState.value = _uiState.value.copy(failedFetches = _uiState.value.failedFetches + dropdown.id)
            }
        }
    }

    fun dismissSuccessDialog() { _uiState.value = _uiState.value.copy(showSuccessDialog = false) }

    fun clearSelections() {
        _uiState.value = _uiState.value.copy(
            selections = emptyMap(), dropdownData = emptyMap(),
            error = null, failedFetches = emptySet(), isGpsUnavailable = false, isSearchEmpty = false
        )
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
}
