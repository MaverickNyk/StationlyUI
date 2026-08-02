package com.stationly.app.ui.selection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.core.model.sdui.SubscribedStation
import com.stationly.core.config.AppConfig
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.stationly.app.platform.performHaptic
import com.stationly.app.platform.HapticType

class SelectionViewModel(
    private val locationProvider: LocationProvider = platformLocationProvider()
) : ViewModel() {

    private val sduiService = NetworkModule.sduiApi
    private val storageManager = Platform.storageManager
    private val selectionRepository = SelectionRepository(Platform.storageManager, Platform.sqlStorage)
    private val departureRepository = DepartureRepository(
        NetworkModule.tflApi,
        Platform.storageManager,
        Platform.sqlStorage,
        SyncPredictionsUseCase(Platform.sqlStorage)
    )
    private val stationLifecycleUseCase = StationLifecycleUseCase(
        selectionRepository = selectionRepository,
        departureRepository = departureRepository,
        notificationManager = Platform.notificationManager,
        widgetManager = Platform.widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = Platform.storageManager
    )

    private val jsonFormat = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(SelectionUiState())
    val uiState: StateFlow<SelectionUiState> = _uiState.asStateFlow()

    private val _selections = MutableStateFlow<Map<String, String>>(emptyMap())
    val selections: StateFlow<Map<String, String>> = _selections.asStateFlow()

    private val _dropdownData = MutableStateFlow<Map<String, List<SduiDropdownOption>>>(emptyMap())
    val dropdownData: StateFlow<Map<String, List<SduiDropdownOption>>> = _dropdownData.asStateFlow()

    private val _recentStations = MutableStateFlow<List<SduiDropdownOption>>(emptyList())
    val recentStations: StateFlow<List<SduiDropdownOption>> = _recentStations.asStateFlow()

    private val _modes = MutableStateFlow<List<SduiDropdownOption>>(emptyList())
    val modes: StateFlow<List<SduiDropdownOption>> = _modes.asStateFlow()

    init {
        viewModelScope.launch {
            selectionRepository.initialize()
        }
        loadCachedLayout()
        loadServerLayout()
        loadModes()
        loadRecentStations()
        silentlyFetchLocation()
    }

    private fun silentlyFetchLocation() {
        viewModelScope.launch {
            try {
                val loc = locationProvider.getCurrentLocation()
                if (loc != null) {
                    _uiState.value = _uiState.value.copy(userLat = loc.first, userLon = loc.second)
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadRecentStations() {
        viewModelScope.launch {
            val json = storageManager.loadString("recent_stations") ?: return@launch
            try {
                val stations = jsonFormat.decodeFromString<List<SduiDropdownOption>>(json)
                _recentStations.value = stations
            } catch (_: Exception) {}
        }
    }

    private fun saveRecentStation(station: SduiDropdownOption) {
        viewModelScope.launch {
            val updated = (_recentStations.value.filterNot { it.id == station.id } + station)
                .takeLast(3).reversed().take(3)
            _recentStations.value = updated
            try {
                storageManager.saveString("recent_stations", jsonFormat.encodeToString(updated))
            } catch (_: Exception) {}
        }
    }

    private fun loadCachedLayout() {
        viewModelScope.launch {
            val cached = storageManager.loadString("cached_app_layout") ?: return@launch
            try {
                val layout = jsonFormat.decodeFromString<SduiAppScreen>(cached)
                _uiState.value = _uiState.value.copy(layout = layout)
                bootstrapDropdowns(layout)
            } catch (_: Exception) {}
        }
    }

    private fun bootstrapDropdowns(layout: SduiAppScreen) {
        layout.components.forEach { component ->
            if (component is SduiAppComponent.Dropdown) {
                val isBootstrap = component.dependsOn == null
                val isSatisfied = component.dependsOn != null &&
                    _selections.value.containsKey(component.dependsOn)
                if (isBootstrap || isSatisfied) fetchDropdownData(component)
            }
        }
    }

    private fun loadServerLayout() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val screenLayout = sduiService.getSelectionLayout()
                storageManager.saveString("cached_app_layout", jsonFormat.encodeToString(screenLayout))
                _uiState.value = _uiState.value.copy(isLoading = false, layout = screenLayout)
                bootstrapDropdowns(screenLayout)
            } catch (e: Exception) {
                if (_uiState.value.layout == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isBackendOffline = true,
                        error = "Could not connect to Stationly servers."
                    )
                    scheduleAutoRetry()
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    private fun loadModes() {
        viewModelScope.launch {
            try {
                val rawModes = sduiService.getDropdownData("/modes")
                val modesResult = rawModes.map { opt ->
                    val url = opt.iconUrl
                    if (url != null)
                        opt.copy(iconUrl = url.replace(AppConfig.PROD_API_URL, AppConfig.apiBaseUrl))
                    else opt
                }
                val updatedData = _dropdownData.value.toMutableMap()
                updatedData["mode"] = modesResult
                _dropdownData.value = updatedData
                _modes.value = modesResult
                _uiState.value = _uiState.value.copy(
                    failedFetches = _uiState.value.failedFetches - "mode"
                )
                // Mirror Android SelectionViewModel: persist the mode roundel
                // icons + tints into the App-Group cache so the widget and the
                // board header render the real backend roundels offline.
                val iconEntries = modesResult.map {
                    com.stationly.app.platform.ModeIconEntry(it.id, it.iconUrl, it.tintHex)
                }
                val iconVersion = modesResult.firstOrNull { !it.iconVersion.isNullOrBlank() }?.iconVersion
                if (iconEntries.isNotEmpty()) {
                    viewModelScope.launch {
                        com.stationly.app.platform.ModeIconStore.sync(iconEntries, iconVersion)
                    }
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    failedFetches = _uiState.value.failedFetches + "mode"
                )
            }
        }
    }

    fun fetchNearbyStations(lat: Double? = null, lon: Double? = null, modeId: String? = null) {
        if (lat == null || lon == null) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLocating = true)
                val loc = locationProvider.getCurrentLocation()
                if (loc != null) {
                    _uiState.value = _uiState.value.copy(userLat = loc.first, userLon = loc.second)
                    fetchNearbyStations(loc.first, loc.second, modeId)
                } else {
                    _uiState.value = _uiState.value.copy(isLocating = false, isGpsUnavailable = true)
                }
            }
            return
        }
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLocating = true)
                val nearbyStations = sduiService.getNearbyStations(lat, lon, modeId)
                if (nearbyStations.isNotEmpty()) {
                    val updatedData = _dropdownData.value.toMutableMap()
                    updatedData["station"] = nearbyStations
                    _dropdownData.value = updatedData
                    _uiState.value = _uiState.value.copy(
                        isLocating = false, isGpsUnavailable = false,
                        isSearchEmpty = false, userLat = lat, userLon = lon
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLocating = false, isGpsUnavailable = true)
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLocating = false, isGpsUnavailable = true)
            }
        }
    }

    fun searchStations(query: String) {
        val state = _uiState.value
        val mode = _selections.value["mode"] ?: ""
        if (query.isBlank()) {
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
                val locationSuffix = if (lat != null && lon != null) "&lat=$lat&lon=$lon" else ""
                val results = sduiService.getDropdownData(
                    "/stations/search?searchKey=${query.trim()}&mode=$mode$locationSuffix"
                )
                val updatedData = _dropdownData.value.toMutableMap()
                updatedData["station"] = results
                _dropdownData.value = updatedData
                _uiState.value = _uiState.value.copy(
                    isSearchEmpty = results.isEmpty(), isGpsUnavailable = false
                )
            } catch (_: Exception) {}
        }
    }

    fun onDropdownSelected(componentId: String, value: String) {
        performHaptic(HapticType.TAP)
        if (value.isBlank()) { removeSelection(componentId); return }

        val newSelections = _selections.value.toMutableMap()
        val newDropdownData = _dropdownData.value.toMutableMap()

        // Clear downstream state when a parent changes
        when (componentId) {
            "mode" -> {
                listOf("station", "line", "direction", "lat", "lon").forEach {
                    newSelections.remove(it)
                    newDropdownData.remove(it)
                }
            }
            "station" -> {
                listOf("line", "direction").forEach {
                    newSelections.remove(it)
                    newDropdownData.remove(it)
                }
            }
            "line" -> {
                newSelections.remove("direction")
                newDropdownData.remove("direction")
            }
        }

        newSelections[componentId] = value
        if (componentId == "station") {
            _dropdownData.value["station"]?.find { it.id == value }?.let { saveRecentStation(it) }
        }
        _selections.value = newSelections
        _dropdownData.value = newDropdownData

        val components = _uiState.value.layout?.components ?: emptyList()

        // Mode selected → kick off station fetch
        if (componentId == "mode") {
            val lat = _uiState.value.userLat
            val lon = _uiState.value.userLon
            if (lat != null && lon != null) {
                fetchNearbyStations(lat, lon, value)
            }
        }

        // Station selected → fetch lines
        if (componentId == "station") {
            components.find { it is SduiAppComponent.Dropdown && it.id == "line" }
                ?.let { fetchDropdownData(it as SduiAppComponent.Dropdown, newSelections) }
        }

        // Cascading: fetch children that depend on this selection
        components.forEach { comp ->
            if (comp is SduiAppComponent.Dropdown && comp.dependsOn == componentId && comp.id != "line") {
                fetchDropdownData(comp, newSelections)
            }
        }
    }

    fun removeSelection(componentId: String) {
        val newSel = _selections.value.toMutableMap()
        val newData = _dropdownData.value.toMutableMap()
        newSel.remove(componentId)
        when (componentId) {
            "mode"    -> listOf("station", "line", "direction").forEach { newSel.remove(it); newData.remove(it) }
            "station" -> listOf("line", "direction").forEach { newSel.remove(it) }
            "line"    -> newSel.remove("direction")
        }
        _selections.value = newSel
        _dropdownData.value = newData
        _uiState.value = _uiState.value.copy(isGpsUnavailable = false, isSearchEmpty = false)
    }

    fun popLastSelection() {
        performHaptic(HapticType.TAP)
        val sel = _selections.value
        when {
            "direction" in sel -> removeSelection("direction")
            "line"      in sel -> removeSelection("line")
            "station"   in sel -> removeSelection("station")
            "mode"      in sel -> clearSelections()
        }
    }

    fun saveSelection() {
        val state = _uiState.value
        val selMap = _selections.value
        val mode      = selMap["mode"]
        val line      = selMap["line"]
        val direction = selMap["direction"]
        val stationId = selMap["station"]

        if (mode == null || line == null || direction == null || stationId == null) {
            _uiState.value = state.copy(error = "Please complete all selections")
            performHaptic(HapticType.ERROR)
            return
        }

        val stationName = _dropdownData.value["station"]?.find { it.id == stationId }?.label ?: stationId

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, isSaving = true)
            try {
                val resolvedId = sduiService.resolveStation(stationId, mode, line, direction)
                val userSelection = UserSelection(
                    mode = mode, line = line,
                    station = resolvedId, stationName = stationName,
                    direction = direction, destinations = emptyList(), destinationIds = emptyList()
                )
                // ── Tell the BACKEND about the new board — BEFORE cleanupAll ──
                //
                // Without this the station never lands in Firestore
                // `metadata/subscribed_stations`, which is the exact list the
                // Syncer polls — so TfL is never polled for it and NO FCM
                // message is ever published for it. The client-side topic
                // subscription still succeeds, which is what makes the failure
                // so quiet: the device listens to a topic nobody publishes to.
                // It also breaks board-restore-on-login, because the backend has
                // nothing saved to hand back. Symptom: "Subscribed stations (0)"
                // in admin while the board works fine locally.
                //
                // ⚠️ ORDER IS LOAD-BEARING ON iOS. `cleanupAll()` below calls
                // `storageManager.clearAll()`, which on iOS is
                // `removePersistentDomainForName(bundleId)` — it wipes the whole
                // standard NSUserDefaults domain, including `firebase_user_uid`
                // and `firebase_auth_token`. Run after it, this block reads a
                // null uid and skips silently (and the POST would be
                // unauthenticated anyway). Android places the same call in a
                // detached tail AFTER its cleanup and is fine, because it reads
                // the uid from `FirebaseAuth.currentUser` and its `clearAll()`
                // only clears a SharedPrefs file — the identity is never in the
                // wiped store. Same code, different blast radius.
                //
                // Backend semantics: `syncStations` is a full REPLACE that diffs
                // old vs new ids to inc/dec subscription counts, and the product
                // is deliberately single-board (`addStation` hardcodes
                // `[station]`), so sending just the new one is correct and
                // releases the previous station's hold.
                //
                // Best-effort: the board must still appear if this fails, and
                // the Profile / board-delete paths re-sync the full list.
                try {
                    val uid = Platform.storageManager.loadString("firebase_user_uid")
                    if (uid != null) {
                        sduiService.syncStations(
                            uid,
                            listOf(
                                com.stationly.core.model.sdui.SubscribedStation(
                                    id        = userSelection.station,
                                    name      = userSelection.stationName,
                                    line      = userSelection.line,
                                    mode      = userSelection.mode,
                                    direction = userSelection.direction,
                                )
                            )
                        )
                    }
                } catch (_: Exception) {
                    // Retried by the Profile/delete sync paths.
                }

                clearBoardsPreservingIdentity()
                stationLifecycleUseCase.setupStation(userSelection, isFirstTime = true)
                _uiState.value = state.copy(isLoading = false, isSaving = false, showSuccessDialog = true)
                performHaptic(HapticType.SUCCESS)
            } catch (e: Exception) {
                _uiState.value = state.copy(
                    isLoading = false, isSaving = false,
                    error = "Failed to save: ${e.message}"
                )
                performHaptic(HapticType.ERROR)
            }
        }
    }

    /**
     * Swap boards without destroying the signed-in session.
     *
     * `StationLifecycleUseCase.cleanupAll()` does exactly this PLUS
     * `storageManager.clearAll()`. That last step is harmless on Android — it
     * clears the "StationlyPrefs" file, and the identity lives in
     * `FirebaseAuth.currentUser`, not prefs. On iOS it is
     * `removePersistentDomainForName(bundleId)`, which wipes the ENTIRE
     * standard NSUserDefaults domain — including the `firebase_user_uid` /
     * `firebase_auth_token` keys the Swift AuthBridge owns.
     *
     * The damage cascades: after one board change the app has no uid and no
     * bearer token until AuthBridge happens to re-persist them on a later
     * foreground, so every auth-gated call in between fails — silently, since
     * they're all best-effort. That is what kept `syncStations` from ever
     * reaching the backend, which in turn kept the Syncer from polling the
     * station, which is why the widget never got a push. `LoginViewModel`
     * already carries a comment warning never to call `cleanupAll()` there for
     * this same reason; a board swap has no more business wiping the session
     * than a login does.
     *
     * So: same teardown, minus the storage wipe. Board state lives in SQLite +
     * the selection repo + the widget's app-group payload, all of which are
     * cleared here; the leftover NSUserDefaults entries (SDUI layout caches,
     * dismissed announcements) are per-station keys that the next setup
     * overwrites anyway.
     */
    private suspend fun clearBoardsPreservingIdentity() {
        val allSelections = Platform.sqlStorage.getAllSelections()
        val allTopics = allSelections.flatMap { sel ->
            listOf("Station_${sel.station}", "LineStatus_${sel.mode}_${sel.line}")
        }.distinct()

        selectionRepository.clearAll()
        Platform.sqlStorage.clearAllData()
        Platform.widgetManager.clearWidgetData()

        if (allTopics.isNotEmpty()) {
            Platform.notificationManager.unsubscribeFromTopics(allTopics)
        }
    }

    fun onActionTriggered(action: String) {
        if (action == "SAVE_SELECTION_ACTION") saveSelection()
    }

    fun dismissSuccess() {
        _uiState.value = _uiState.value.copy(showSuccessDialog = false)
    }

    fun dismissSuccessDialog() = dismissSuccess()

    fun clearSelections() {
        _selections.value = emptyMap()
        _dropdownData.value = emptyMap()
        _uiState.value = _uiState.value.copy(
            error = null, failedFetches = emptySet(),
            isGpsUnavailable = false, isSearchEmpty = false
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun retryLoad() {
        _uiState.value = _uiState.value.copy(error = null, isBackendOffline = false, failedFetches = emptySet())
        loadServerLayout()
        loadModes()
    }

    fun retryDropdown(componentId: String) {
        val layout = _uiState.value.layout ?: return
        val component = layout.components.find { it is SduiAppComponent.Dropdown && it.id == componentId }
            as? SduiAppComponent.Dropdown ?: return
        _uiState.value = _uiState.value.copy(failedFetches = _uiState.value.failedFetches - componentId)
        fetchDropdownData(component, _selections.value)
    }

    private fun fetchDropdownData(
        dropdown: SduiAppComponent.Dropdown,
        selectionsMap: Map<String, String>? = null
    ) {
        viewModelScope.launch {
            var finalUrl = dropdown.dataSourceUrl
            try {
                val deps = selectionsMap ?: _selections.value
                deps.forEach { (key, value) -> finalUrl = finalUrl.replace("{$key}", value) }

                if (finalUrl.contains("{")) return@launch // Unresolved params

                // 24-hour cache
                val cacheKey = "cached_dropdown_$finalUrl"
                val tsKey = "${cacheKey}_ts"
                val cachedJson = storageManager.loadString(cacheKey)
                val cachedTsStr = storageManager.loadString(tsKey)
                val cachedTs = cachedTsStr?.toLongOrNull() ?: 0L
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                val cacheAgeMs = now - cachedTs
                val cacheValid = cachedJson != null && cacheAgeMs < 24 * 60 * 60 * 1000L

                if (cacheValid && cachedJson != null) {
                    try {
                        val cached = jsonFormat.decodeFromString<List<SduiDropdownOption>>(cachedJson)
                        val cur = _dropdownData.value.toMutableMap()
                        cur[dropdown.id] = cached
                        _dropdownData.value = cur
                    } catch (_: Exception) {}
                }

                val options = sduiService.getDropdownData(finalUrl)
                storageManager.saveString(cacheKey, jsonFormat.encodeToString(options))
                storageManager.saveString(tsKey, now.toString())

                val cur = _dropdownData.value.toMutableMap()
                cur[dropdown.id] = options
                _dropdownData.value = cur
                _uiState.value = _uiState.value.copy(
                    failedFetches = _uiState.value.failedFetches - dropdown.id
                )

                // Auto-skip single-option steps
                if (options.size == 1 && dropdown.id in listOf("line", "direction")
                    && dropdown.id !in _selections.value) {
                    delay(500)
                    if (dropdown.id !in _selections.value) {
                        onDropdownSelected(dropdown.id, options[0].id)
                    }
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    failedFetches = _uiState.value.failedFetches + dropdown.id
                )
            }
        }
    }

    private fun scheduleAutoRetry() {
        viewModelScope.launch {
            delay(30_000)
            if (_uiState.value.isBackendOffline) retryLoad()
        }
    }
}
