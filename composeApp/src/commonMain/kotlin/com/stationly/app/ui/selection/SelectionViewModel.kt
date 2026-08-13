package com.stationly.app.ui.selection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.app.sync.UserStateSync
import com.stationly.core.repository.UserSettings
import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.model.FilterMode
import com.stationly.core.model.UserSelection
import com.stationly.core.util.BoardFilterResolver
import com.stationly.core.util.LineNameStore
import com.stationly.core.util.RouteTree
import com.stationly.core.model.sdui.SduiRouteStop
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.core.config.AppConfig
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
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

    companion object {
        /**
         * Hard ceiling on (line, direction) rows for ONE station card.
         *
         * Not a monetisation limit — a technical one. Every row is a live stream
         * subscription plus a section on a card that has to stay readable on an
         * iPhone 11. With "select all lines" and "both ways" one tap away, a
         * King's Cross-sized interchange would otherwise let a user build a
         * 20-subscription board by accident.
         */
        const val MAX_ROWS_PER_STATION = 8

        /** Prefix for the per-line direction-fetch failure keys in `failedFetches`. */
        private const val DIRECTION_FAIL_PREFIX = "direction_"

        /** Filters are per (line, direction) — the two directions of a line are separate journeys. */
        fun boardFilterKey(lineId: String, directionId: String) = "$lineId|$directionId"
    }

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

    /**
     * The lines checked on the line step, in the order they were checked, each
     * mapped to the SET of directions checked for that line (empty until the
     * user picks at least one).
     *
     * Replaces the single `selections["line"]` / `selections["direction"]` pair.
     * Direction cannot live in the flat selection map any more because it is a
     * property OF a line, not of the station: Circle runs inner/outer rail while
     * Jubilee runs north/south, so two lines checked at the same station have
     * disjoint direction vocabularies and each needs its own answer. It is a
     * SET rather than a single value because both directions of one line are a
     * legitimate choice — "show me trains each way at this platform" — and each
     * one becomes its own board section.
     *
     * Insertion-ordered (`toMutableMap` on a LinkedHashMap preserves both
     * insertion order and the position of updated keys), which is what makes the
     * saved boards appear in the order the user picked them.
     */
    private val _linePicks = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val linePicks: StateFlow<Map<String, Set<String>>> = _linePicks.asStateFlow()

    /** Directions available per checked line, keyed by line id. */
    private val _directionsByLine = MutableStateFlow<Map<String, List<SduiDropdownOption>>>(emptyMap())
    val directionsByLine: StateFlow<Map<String, List<SduiDropdownOption>>> = _directionsByLine.asStateFlow()

    /** Line ids whose direction list is currently in flight. */
    private val _loadingDirections = MutableStateFlow<Set<String>>(emptySet())
    val loadingDirections: StateFlow<Set<String>> = _loadingDirections.asStateFlow()

    /**
     * Per-board departure filter, keyed by [boardFilterKey].
     *
     * Keyed on (line, direction) rather than line, because the two directions of
     * one line are separate boards with separate journeys — "via Green Park"
     * southbound says nothing about what you want northbound.
     *
     * Holds the user's INTENT only. The expensive part — turning "via Green
     * Park" into the set of destination ids that pass through it — happens once
     * in [saveSelection] via [BoardFilterResolver], never while the sheet is open.
     */
    private val _boardFilters = MutableStateFlow<Map<String, BoardFilter>>(emptyMap())
    val boardFilters: StateFlow<Map<String, BoardFilter>> = _boardFilters.asStateFlow()

    /**
     * The one line whose directions are currently expanded, or null for none.
     *
     * An accordion rather than "every ticked line stays open": with four lines
     * ticked, four expanded direction blocks push the list far past a phone
     * screen and the user loses track of what they have already answered. Only
     * the line being worked on is open; the rest collapse to a summary of their
     * picks and reopen on tap.
     *
     * Kept separate from [_linePicks] so collapsing NEVER touches the selection —
     * a line stays fully chosen while closed, and reopening restores exactly the
     * state it had.
     */
    private val _expandedLine = MutableStateFlow<String?>(null)
    val expandedLine: StateFlow<String?> = _expandedLine.asStateFlow()

    /**
     * Open a line's directions, closing whichever was open.
     *
     * Tapping the already-open line collapses it, so the header is its own
     * toggle and a finished line can be put away without ticking anything.
     */
    fun toggleExpandedLine(lineId: String) {
        performHaptic(HapticType.TAP)
        _expandedLine.value = if (_expandedLine.value == lineId) null else lineId
    }

    /**
     * What the user ALREADY had saved for the station currently being edited,
     * in the same shape as [_linePicks]. Seeded by [prefillPicksForStation] and
     * never mutated by ticking.
     *
     * Two jobs: it lets the UI mark a row as already on the board, and it is the
     * baseline [saveSelection] diffs against so untouched boards are left
     * completely alone instead of being deleted and re-inserted (which would
     * reorder them and steal the widget's primary slot).
     */
    private val _existingPicks = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val existingPicks: StateFlow<Map<String, Set<String>>> = _existingPicks.asStateFlow()

    /** Line ids whose direction fetch failed, unwrapped from the `direction_<id>` fail keys. */
    val failedDirections: StateFlow<Set<String>> = _uiState
        .map { st ->
            st.failedFetches
                .filter { it.startsWith(DIRECTION_FAIL_PREFIX) }
                .mapTo(mutableSetOf()) { it.removePrefix(DIRECTION_FAIL_PREFIX) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** How many board rows the current picks would produce — one per (line, direction). */
    val pickedRowCount: StateFlow<Int> = _linePicks
        .map { picks -> picks.values.sumOf { it.size } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** True once the station has as many rows as one card is allowed to hold. */
    val isAtCap: StateFlow<Boolean> = pickedRowCount
        .map { it >= MAX_ROWS_PER_STATION }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True once at least one line is checked AND every checked line has >=1 direction. */
    val isSelectionComplete: StateFlow<Boolean> = _linePicks
        .map { picks -> picks.isNotEmpty() && picks.values.all { it.isNotEmpty() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Completion of the repository's initial load from SQL.
     *
     * Every path that diffs against existing boards has to await this. The
     * prefill and the save both read `selectionRepository.selections`, which is
     * an in-memory mirror that is EMPTY until `initialize()` finishes — race it
     * and the line step opens blank at a station that has boards, then the save
     * diffs against an empty baseline and re-adds rows that already exist.
     */
    private var repoReady: Job? = null

    init {
        repoReady = viewModelScope.launch {
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

    /**
     * Open the flow already on a station, at the LINE step — "Edit station" from
     * the home screen.
     *
     * Drives the same [onDropdownSelected] path a tapping user would, rather
     * than writing `_selections` directly: that call is what fetches the line
     * list and what runs [prefillPicksForStation], so a shortcut around it lands
     * on an empty line step with none of the station's saved boards ticked.
     *
     * [stationName] is carried because the later steps read the station's label
     * out of `dropdownData["station"]` ("Lines from Oxford Circus"), and that
     * list is the NEARBY-stations result — which need not contain a station the
     * user saved somewhere else, or anything at all before location resolves. A
     * synthetic option is seeded so the copy reads correctly either way; a real
     * fetch overwrites it.
     *
     * Waits for the layout because [onDropdownSelected] resolves its cascade
     * against `layout.components`, and with no layout there is nothing to
     * cascade into.
     */
    /**
     * The station [openForStation] was entered on, kept so a later refresh of the
     * station list cannot lose it.
     *
     * `fetchNearbyStations` and `searchStations` REPLACE `dropdownData["station"]`
     * wholesale, and neither is guaranteed to contain a station the user saved
     * somewhere else. Losing it is not cosmetic: `saveSelection` reads the
     * station's display name out of that same list and falls back to the naptan,
     * so adding a line while editing such a station would have persisted a board
     * titled "940GZZLUKSX".
     */
    private var editStationOption: SduiDropdownOption? = null

    /** Re-inserts [editStationOption] into a freshly fetched station list. */
    private fun withEditStation(options: List<SduiDropdownOption>): List<SduiDropdownOption> {
        val pinned = editStationOption ?: return options
        return if (options.any { it.id == pinned.id }) options else listOf(pinned) + options
    }

    fun openForStation(mode: String, stationId: String, stationName: String) {
        viewModelScope.launch {
            // The layout arrives from cache within a frame or two, or from the
            // network. Bounded so a dead backend leaves the user on the mode
            // step — the normal flow — rather than on a spinner.
            val layout = withTimeoutOrNull(5_000) {
                _uiState.first { it.layout != null }.layout
            } ?: return@launch

            if (layout.components.none { it is SduiAppComponent.Dropdown && it.id == "station" }) return@launch

            editStationOption = SduiDropdownOption(id = stationId, label = stationName)

            onDropdownSelected("mode", mode)

            val seeded = _dropdownData.value.toMutableMap()
            seeded["station"] = withEditStation(seeded["station"] ?: emptyList())
            _dropdownData.value = seeded

            onDropdownSelected("station", stationId)
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
                    updatedData["station"] = withEditStation(nearbyStations)
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
                updatedData["station"] = withEditStation(results)
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

        // Clear downstream state when a parent changes. Line/direction now live
        // in _linePicks rather than this map, so changing mode or station has to
        // drop those too — otherwise the Piccadilly tick from the previous
        // station would survive into the new one.
        when (componentId) {
            "mode" -> {
                listOf("station", "line", "direction", "lat", "lon").forEach {
                    newSelections.remove(it)
                    newDropdownData.remove(it)
                }
                clearLinePicks()
            }
            "station" -> {
                listOf("line", "direction").forEach {
                    newSelections.remove(it)
                    newDropdownData.remove(it)
                }
                clearLinePicks()
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

        // Station selected → fetch lines, and re-open whatever is already on
        // this station's card so the line step reads as "edit this board"
        // rather than "start again".
        if (componentId == "station") {
            components.find { it is SduiAppComponent.Dropdown && it.id == "line" }
                ?.let { fetchDropdownData(it as SduiAppComponent.Dropdown, newSelections) }
            prefillPicksForStation(value)
        }

        // Cascading: fetch children that depend on this selection
        components.forEach { comp ->
            if (comp is SduiAppComponent.Dropdown && comp.dependsOn == componentId && comp.id != "line") {
                fetchDropdownData(comp, newSelections)
            }
        }
    }

    /**
     * Re-open the boards already saved for [stationId] as ticked picks.
     *
     * Without this the line step opens blank even when the station already has a
     * card, which both misrepresents the board and makes a returning user
     * re-tick lines they never removed — and re-ticking used to delete and
     * re-insert the row, silently reordering the card.
     *
     * The saved rows only carry direction IDS, so each line still needs its
     * option list fetched for the cards to render labels; [fetchDirectionsFor]
     * is cheap here because the 24-hour dropdown cache is almost always warm
     * from when the board was first created.
     */
    private fun prefillPicksForStation(stationId: String) {
        viewModelScope.launch {
            repoReady?.join()
            val saved = selectionRepository.selections.value.filter { it.groupingId == stationId }
            if (saved.isEmpty()) {
                _existingPicks.value = emptyMap()
                return@launch
            }
            val picks = LinkedHashMap<String, Set<String>>()
            val filters = LinkedHashMap<String, BoardFilter>()
            saved.forEach { sel ->
                picks[sel.line] = (picks[sel.line] ?: emptySet()) + sel.direction
                // Restore the INTENT, not the resolved allow-list — reopening the
                // sheet must show "via Green Park", not the 35 ids that produced.
                // Re-saving then re-resolves against current route data, which is
                // also how a stale allow-list gets refreshed.
                if (sel.filterMode != FilterMode.ALL) {
                    filters[boardFilterKey(sel.line, sel.direction)] = BoardFilter(
                        mode = sel.filterMode,
                        destinationIds = if (sel.filterMode == FilterMode.DESTINATIONS)
                            sel.destinationIds.toSet() else emptySet(),
                        viaStops = sel.viaStationIds.mapIndexed { i, id ->
                            SduiRouteStop(id, sel.viaStationNames.getOrElse(i) { id })
                        },
                    )
                }
            }
            _existingPicks.value = picks
            _linePicks.value = picks
            _boardFilters.value = filters
            picks.keys.forEach { line ->
                if (_directionsByLine.value[line] == null) fetchDirectionsFor(line)
            }
        }
    }

    /**
     * Check or uncheck a line on the multi-line step.
     *
     * Checking kicks off that line's direction fetch immediately, so the inline
     * direction control is populated by the time the row finishes expanding.
     */
    fun toggleLine(lineId: String) {
        performHaptic(HapticType.TAP)
        val current = _linePicks.value
        // Ticking a line makes it the one being worked on; unticking closes it.
        _expandedLine.value = if (lineId in current) null else lineId
        if (lineId in current) {
            _linePicks.value = current - lineId
            // Keep the fetched directions cached: re-checking the same line is
            // common (mis-tap, changed mind) and should not re-hit the network.
        } else {
            _linePicks.value = current + (lineId to emptySet())
            val known = _directionsByLine.value[lineId]
            // Auto-tick a line that only runs one way, so a non-choice never
            // costs a tap. This has to live here rather than only in the fetch:
            // on a cache hit (untick then re-tick) no fetch runs, and the line
            // would sit expanded with an unticked lone card holding the CTA
            // disabled.
            if (known == null) fetchDirectionsFor(lineId) else autoSelectSoleDirection(lineId, known)
        }
    }

    /**
     * Check or uncheck ONE direction of an already-checked line. Both
     * directions may be held at once; each becomes its own board section.
     *
     * Unchecking the last remaining direction leaves the line checked with an
     * empty set rather than silently unchecking the line — the CTA stays
     * disabled and the user is shown an incomplete line instead of having their
     * line selection disappear from under them.
     */
    fun toggleDirection(lineId: String, directionId: String) {
        val current = _linePicks.value[lineId] ?: return
        val adding = directionId !in current
        if (adding && !claimRowBudget(1)) return
        performHaptic(HapticType.TAP)
        val updated = if (adding) current + directionId else current - directionId
        _linePicks.value = _linePicks.value.toMutableMap().also { it[lineId] = updated }
    }

    /**
     * Tick every direction of one line — the "both ways" shortcut, which is the
     * single most common multi-pick ("show me trains each way at my platform")
     * and otherwise costs one tap per direction.
     *
     * Toggles off if the line is already fully picked.
     */
    fun toggleAllDirections(lineId: String) {
        val options = _directionsByLine.value[lineId] ?: return
        val current = _linePicks.value[lineId] ?: return
        val allIds = options.mapTo(LinkedHashSet()) { it.id }
        val next = if (current.containsAll(allIds)) {
            emptySet()
        } else {
            val added = allIds.size - current.size
            if (!claimRowBudget(added)) return
            allIds
        }
        performHaptic(HapticType.TAP)
        _linePicks.value = _linePicks.value.toMutableMap().also { it[lineId] = next }
    }

    /**
     * Tick every line serving the station. Directions still have to be chosen
     * per line — this only saves the tap-per-line, which at an interchange like
     * King's Cross is the difference between eight taps and one.
     *
     * Toggles off if every line is already checked.
     */
    fun toggleAllLines(lines: List<SduiDropdownOption>) {
        performHaptic(HapticType.TAP)
        if (lines.all { it.id in _linePicks.value }) {
            _linePicks.value = emptyMap()
            return
        }
        val picks = LinkedHashMap(_linePicks.value)
        lines.forEach { line ->
            if (line.id !in picks) {
                picks[line.id] = emptySet()
                val known = _directionsByLine.value[line.id]
                if (known == null) fetchDirectionsFor(line.id) else autoSelectSoleDirection(line.id, known)
            }
        }
        _linePicks.value = picks
        // Open the first line that still needs a direction, so "select all" ends
        // somewhere actionable instead of on a list of collapsed, incomplete rows.
        _expandedLine.value = picks.entries.firstOrNull { it.value.isEmpty() }?.key
            ?: picks.keys.firstOrNull()
    }

    private fun autoSelectSoleDirection(lineId: String, options: List<SduiDropdownOption>) {
        if (options.size == 1 && _linePicks.value[lineId]?.isEmpty() == true) {
            toggleDirection(lineId, options[0].id)
        }
    }

    /**
     * Gate on [MAX_ROWS_PER_STATION]. Returns false when [additional] rows would
     * overflow the card, so the caller aborts instead of building a board that
     * cannot be rendered or streamed.
     *
     * Deliberately does NOT set `uiState.error`: that field is only rendered by
     * the backend-offline overlay, so the message would be invisible here AND
     * would resurface later as the offline screen's copy. The cap is surfaced by
     * the summary bar's counter and hint instead — a rejected tap answers itself
     * on screen rather than through a toast.
     */
    private fun claimRowBudget(additional: Int): Boolean {
        val current = _linePicks.value.values.sumOf { it.size }
        if (current + additional <= MAX_ROWS_PER_STATION) return true
        performHaptic(HapticType.ERROR)
        return false
    }

    /**
     * Fetch the direction list for one specific line.
     *
     * The SDUI `direction` dropdown's `dataSourceUrl` is templated on `{line}`
     * (plus `{mode}`/`{station}`), and the shared [fetchDropdownData] resolves
     * those from the flat selection map and files the result under the component
     * id. Neither works here: several lines are live at once, so the line id has
     * to be injected explicitly and the result filed per line instead of
     * clobbering a single "direction" slot.
     */
    private fun fetchDirectionsFor(lineId: String) {
        val dropdown = _uiState.value.layout?.components
            ?.filterIsInstance<SduiAppComponent.Dropdown>()
            ?.find { it.id == "direction" } ?: return
        val failKey = "$DIRECTION_FAIL_PREFIX$lineId"

        viewModelScope.launch {
            _loadingDirections.value = _loadingDirections.value + lineId
            try {
                var finalUrl = dropdown.dataSourceUrl
                val deps = _selections.value + ("line" to lineId)
                deps.forEach { (key, value) -> finalUrl = finalUrl.replace("{$key}", value) }
                if (finalUrl.contains("{")) return@launch // Unresolved params

                // Same 24-hour cache contract as fetchDropdownData — the cache
                // key is the resolved URL, so it is already per-line.
                val cacheKey = "cached_dropdown_$finalUrl"
                val tsKey = "${cacheKey}_ts"
                val cachedJson = storageManager.loadString(cacheKey)
                val cachedTs = storageManager.loadString(tsKey)?.toLongOrNull() ?: 0L
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                if (cachedJson != null && now - cachedTs < 24 * 60 * 60 * 1000L) {
                    try {
                        val cached = jsonFormat.decodeFromString<List<SduiDropdownOption>>(cachedJson)
                        _directionsByLine.value = _directionsByLine.value + (lineId to cached)
                    } catch (_: Exception) {}
                }

                val options = sduiService.getDropdownData(finalUrl)
                storageManager.saveString(cacheKey, jsonFormat.encodeToString(options))
                storageManager.saveString(tsKey, now.toString())

                _directionsByLine.value = _directionsByLine.value + (lineId to options)
                _uiState.value = _uiState.value.copy(
                    failedFetches = _uiState.value.failedFetches - failKey
                )

                // Auto-skip a single-option direction step, same courtesy the
                // single-line flow gave. No delay here: the row is expanding
                // anyway, so the pre-filled choice reads as instant rather than
                // as something that moved under the user's finger.
                autoSelectSoleDirection(lineId, options)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    failedFetches = _uiState.value.failedFetches + failKey
                )
            } finally {
                _loadingDirections.value = _loadingDirections.value - lineId
            }
        }
    }

    /* ── Per-board departure filters ─────────────────────────────────────── */

    /** Switch a board between "all trains", a destination list, and a via stop. */
    fun setFilterMode(lineId: String, directionId: String, mode: FilterMode) {
        performHaptic(HapticType.TAP)
        updateFilter(lineId, directionId) { current ->
            when (mode) {
                // Selecting a mode does NOT carry the other mode's picks over —
                // they answer different questions and silently reinterpreting
                // "Heathrow" as "via Heathrow" would change which trains show.
                FilterMode.ALL -> BoardFilter()
                FilterMode.DESTINATIONS -> BoardFilter(mode = mode, destinationIds = current.destinationIds)
                FilterMode.VIA -> BoardFilter(mode = mode, viaStops = current.viaStops)
            }
        }
    }

    fun toggleFilterDestination(lineId: String, directionId: String, destId: String) {
        performHaptic(HapticType.TAP)
        updateFilter(lineId, directionId) { current ->
            val next = if (destId in current.destinationIds) current.destinationIds - destId
                       else current.destinationIds + destId
            current.copy(mode = FilterMode.DESTINATIONS, destinationIds = next)
        }
    }

    /**
     * Add or remove ONE stop from the via set.
     *
     * Multi-select ACROSS branches, single-select WITHIN one. Picking two stops
     * on the same branch is meaningless — on A→B→C→D, choosing B and then C can
     * only ever mean "trains reaching C", since anything reaching C already
     * passed B. So a new pick REPLACES any existing pick that shares its branch,
     * and only genuinely separate branches accumulate.
     *
     * Two stops share a branch exactly when one's reachable-terminus set
     * contains the other's: those sets nest along a path and are disjoint across
     * a split, so containment is the test.
     *
     * Removing the last stop drops back to ALL rather than leaving an
     * active-but-empty filter that would hide everything.
     */
    fun toggleFilterVia(lineId: String, directionId: String, stopId: String, stopName: String) {
        performHaptic(HapticType.TAP)
        val tree = _directionsByLine.value[lineId]
            ?.find { it.id == directionId }
            ?.let { RouteTree.from(it) }

        updateFilter(lineId, directionId) { current ->
            if (stopId in current.viaStopIds) {
                // Stay in VIA even when the last stop goes. Dropping to ALL here
                // collapsed the whole "Going through" section on an unselect —
                // an unselect is just an unselect. `isActive` is already false
                // for an empty set, so nothing is filtered either way.
                current.copy(mode = FilterMode.VIA, viaStops = current.viaStops.filterNot { it.id == stopId })
            } else {
                val incoming = tree?.terminiFrom(stopId).orEmpty()
                val kept = current.viaStops.filterNot { existing ->
                    val other = tree?.terminiFrom(existing.id).orEmpty()
                    incoming.isNotEmpty() && other.isNotEmpty() &&
                        (incoming.containsAll(other) || other.containsAll(incoming))
                }
                current.copy(
                    mode = FilterMode.VIA,
                    viaStops = kept + SduiRouteStop(stopId, stopName),
                )
            }
        }
    }

    fun clearFilter(lineId: String, directionId: String) {
        performHaptic(HapticType.TAP)
        updateFilter(lineId, directionId) { BoardFilter() }
    }

    /**
     * Build a [UserSelection], resolving its filter intent into the stored
     * allow-list. This is the ONLY place resolution happens — once per board per
     * save, never on a render or ingest path.
     */
    private suspend fun buildSelection(
        mode: String,
        stationId: String,
        stationName: String,
        line: String,
        direction: String,
        /**
         * The pole this board is ALREADY on, for rows that exist.
         *
         * Supplying it skips the resolve entirely. That is not just an
         * optimisation: `updateSelectionInPlace` matches on `station`, so if a
         * re-resolve returned anything different — a genuine route change, or
         * the hub fallback after a network failure — the match would miss and
         * the user's filter edit would be silently discarded. Re-resolving also
         * cost one network round trip per untouched board on every save.
         */
        knownStation: String? = null,
    ): UserSelection {
        // Resolve the exact stop this (line, direction) departs from.
        //
        // Re-enabled after being disabled for multi-line tube: it used to be the
        // ONLY station id we stored, so a line-specific child id split one
        // station into several identically-named cards. Now the resolved id is
        // the FETCH key and `parentStationId` is the GROUP key, so resolving is
        // safe — and necessary. On bus the two directions of a route sit on
        // different poles with different naptans (Smithwood Close: 39 inbound
        // 490008805N, outbound 490012211N), so storing the picked hub for both
        // served inbound departures on the outbound board.
        //
        // Best-effort: if resolve fails we fall back to the hub, which is what
        // shipped before, rather than blocking the save.
        val resolvedStation = knownStation ?: try {
            sduiService.resolveStation(stationId, mode, line, direction)
                .takeIf { it.isNotBlank() } ?: stationId
        } catch (_: Exception) {
            stationId
        }
        val filter = _boardFilters.value[boardFilterKey(line, direction)] ?: BoardFilter()
        val dirOption = _directionsByLine.value[line]?.find { it.id == direction }

        val resolution = if (filter.isActive && dirOption != null) {
            BoardFilterResolver.resolve(
                mode = filter.mode,
                direction = dirOption,
                chosenDestinationIds = filter.destinationIds,
                viaStopIds = filter.viaStopIds,
            )
        } else BoardFilterResolver.EMPTY

        // A filter that resolves to NOTHING is stored as no filter at all.
        // That happens when the route payload predates `upcomingStops` (a cached
        // 24h response, or an older backend), and an empty allow-list would mean
        // "hide everything" if it were taken literally. Degrading to ALL keeps
        // the board honest — it shows every train rather than none — and the
        // intent is simply not persisted, so nothing claims to be filtered when
        // it isn't.
        val effective = if (resolution.isEmpty) FilterMode.ALL else filter.mode

        return UserSelection(
            mode = mode,
            line = line,
            station = resolvedStation,
            parentStationId = stationId,
            stationName = stationName,
            direction = direction,
            destinations = resolution.destinationNames,
            destinationIds = resolution.destinationIds,
            filterMode = effective,
            viaStationIds = if (effective == FilterMode.VIA) filter.viaStopIds.toList() else emptyList(),
            viaStationNames = if (effective == FilterMode.VIA) filter.viaStopNames else emptyList(),
            routeResolvedAt = if (resolution.isEmpty) 0L
                              else kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        )
    }

    private inline fun updateFilter(
        lineId: String,
        directionId: String,
        transform: (BoardFilter) -> BoardFilter,
    ) {
        val key = boardFilterKey(lineId, directionId)
        val current = _boardFilters.value[key] ?: BoardFilter()
        _boardFilters.value = _boardFilters.value.toMutableMap().also { it[key] = transform(current) }
    }

    fun retryDirections(lineId: String) {
        _uiState.value = _uiState.value.copy(
            failedFetches = _uiState.value.failedFetches - "$DIRECTION_FAIL_PREFIX$lineId"
        )
        fetchDirectionsFor(lineId)
    }

    private fun clearLinePicks() {
        _linePicks.value = emptyMap()
        _existingPicks.value = emptyMap()
        // Otherwise the previous station's line id stays "open" and the first
        // line of the new station silently renders collapsed.
        _expandedLine.value = null
        _boardFilters.value = emptyMap()
        _directionsByLine.value = emptyMap()
        _loadingDirections.value = emptySet()
        _uiState.value = _uiState.value.copy(
            failedFetches = _uiState.value.failedFetches
                .filterNot { it.startsWith(DIRECTION_FAIL_PREFIX) }.toSet()
        )
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
            // Back on the line step returns to the station list in ONE press.
            // It used to peel off one checked line per press, so a user who had
            // ticked six lines pressed back six times while the header never
            // changed — and unticking a single mis-tap is what the checkboxes
            // are already for.
            "station" in sel -> { clearLinePicks(); removeSelection("station") }
            "mode"    in sel -> clearSelections()
        }
    }

    fun saveSelection() {
        val state = _uiState.value
        val selMap = _selections.value
        val mode      = selMap["mode"]
        val stationId = selMap["station"]
        val picks     = _linePicks.value

        if (mode == null || stationId == null || picks.isEmpty() || picks.values.any { it.isEmpty() }) {
            // Unticking everything at a station the user already tracks is a
            // "delete this card" intent, but deleting from a save button is too
            // easy to do by accident — point them at the card's own delete
            // instead of showing the generic incomplete-selection message.
            val emptiedExisting = picks.isEmpty() && _existingPicks.value.isNotEmpty()
            _uiState.value = state.copy(
                error = if (emptiedExisting)
                    "Keep at least one line, or remove this station from the home screen."
                else "Please complete all selections"
            )
            performHaptic(HapticType.ERROR)
            return
        }

        val stationName = _dropdownData.value["station"]?.find { it.id == stationId }?.label ?: stationId

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, isSaving = true)
            try {
                repoReady?.join()
                // One board per (line, direction). A line with both directions
                // checked yields two boards, which is exactly how they render:
                // two stacked sections under the same station card.
                //
                // `buildSelection` DOES call `sduiService.resolveStation` — see
                // the two-id model there. Resolve was disabled for a while
                // during the multi-line tube work because `station` was then the
                // only id we stored, so a line-specific child naptan split one
                // station into several identically-named cards. That is fixed by
                // storing BOTH: `station` is the fetch key (the resolved pole)
                // and `parentStationId` is the group key (the hub the user
                // picked), so resolving is now both safe and necessary — on bus
                // the two directions of a route sit on different poles.
                //
                // ── Diff against what the station already had ──
                //
                // The line step opens PREFILLED with this station's saved boards
                // ([prefillPicksForStation]), so `picks` is the desired END
                // STATE, not a list of additions. Diffing is what lets an
                // untouched board be left completely alone: the previous version
                // deleted and re-inserted every picked row, and since
                // `SelectionRepository.saveSelection` inserts at index 0 that
                // silently reordered the card and handed the widget's primary
                // slot to whichever row happened to be written last.
                val desiredOrdered = picks.flatMap { (line, dirs) -> dirs.map { line to it } }
                val baseline = _existingPicks.value
                    .flatMap { (line, dirs) -> dirs.map { line to it } }.toSet()
                val addedRows = desiredOrdered.filter { it !in baseline }
                val removedRows = baseline - desiredOrdered.toSet()

                // ── Removals: lines the user unticked ──
                //
                // Sequential, with `remaining` re-read from the repository on
                // every pass rather than computed once up front. discardStation
                // only unsubscribes topics no survivor still needs, so a stale
                // survivor list would either leak a subscription or silence a
                // board the user is keeping.
                for ((line, direction) in removedRows) {
                    // Match on the GROUPING id: `station` is now a resolved pole
                    // that the pick list has no knowledge of.
                    val victim = selectionRepository.selections.value.find {
                        it.groupingId == stationId && it.line == line && it.direction == direction
                    } ?: continue
                    val remaining = selectionRepository.selections.value.filterNot {
                        it.station == victim.station &&
                            it.line == victim.line &&
                            it.direction == victim.direction
                    }
                    stationLifecycleUseCase.discardStation(
                        victim, clearSelectionInRepo = true, remaining = remaining
                    )
                }

                // Belt and braces: never re-add a row the repository already
                // holds. The diff above should already guarantee this, but the
                // baseline comes from UI state — if it were ever stale, an
                // "addition" that already exists would be inserted a SECOND time
                // (persistAndFetch saves with no `oldSelection` to replace), and
                // a duplicated board is very hard to unpick from the card.
                val alreadyHeld = selectionRepository.selections.value
                    .filter { it.groupingId == stationId }
                    .mapTo(mutableSetOf()) { it.line to it.direction }
                val newSelections = addedRows
                    .filterNot { it in alreadyHeld }
                    .map { (line, direction) -> buildSelection(mode, stationId, stationName, line, direction) }

                // Persisted in reverse because `SelectionRepository.saveSelection`
                // inserts at index 0 — walking the additions backwards leaves
                // them in the order the user checked the lines.
                //
                // `persistAndFetch` (rather than the composed `setupStation`) so
                // the backend sync below sees the finished list before we start
                // subscribing.
                for (sel in newSelections.reversed()) {
                    stationLifecycleUseCase.persistAndFetch(sel)
                }

                // ── Filter-only edits to boards the user KEPT ──
                //
                // These rows are unchanged as far as the add/remove diff is
                // concerned, so nothing above touches them — but changing a
                // filter and pressing save has to take effect. Updated in place
                // so the card keeps its order and its primary board, and the
                // already-downloaded departures are re-evaluated immediately
                // rather than staying wrong until the next stream frame.
                val unchangedRows = desiredOrdered.filter { it in baseline }
                for ((line, direction) in unchangedRows) {
                    val existing = selectionRepository.selections.value.find {
                        it.groupingId == stationId && it.line == line && it.direction == direction
                    } ?: continue
                    val rebuilt = buildSelection(
                        mode, stationId, stationName, line, direction,
                        knownStation = existing.station,
                    )
                    if (rebuilt.filterMode == existing.filterMode &&
                        rebuilt.destinationIds == existing.destinationIds &&
                        rebuilt.viaStationIds == existing.viaStationIds
                    ) continue

                    selectionRepository.updateSelectionInPlace(rebuilt)
                    Platform.sqlStorage.reapplyFilter(
                        rebuilt.station, line, direction, rebuilt.destinationIds.toSet()
                    )
                }

                // ── Tell the BACKEND about the boards ──
                //
                // Without this the station never lands in Firestore
                // `metadata/subscribed_stations`, which is the exact list the
                // Syncer polls — so TfL is never polled for it and NO live
                // message is ever published for it. The client-side topic
                // subscription still succeeds, which is what makes the failure
                // so quiet: the device listens to a topic nobody publishes to.
                // It also breaks board-restore-on-login, because the backend has
                // nothing saved to hand back. Symptom: "Subscribed stations (0)"
                // in admin while the board works fine locally.
                //
                // Writes the v2 `boards` list, NOT the legacy `stations` array
                // this used to post to. Both platforms writing that one array is
                // what made it lossy: Android calls `cleanupAll()` before saving,
                // so the "full list" it posts is always a single board, and a
                // full-replace endpoint then deleted every board added here.
                //
                // `boardsChanged()` reads the selections itself, at push time —
                // so a partial list cannot be posted by accident (the mistake
                // this call site made twice), and a burst of saves collapses
                // into one request instead of sending each intermediate list.
                //
                // Best-effort and debounced: the board must still appear if the
                // network is down, and the next change re-sends everything
                // because the payload is a full replacement rather than a delta.
                // A station whose last row was unticked is no longer a board,
                // so its configuration must go with it — otherwise re-adding
                // the station silently restores the arrangement of the one the
                // user removed, and the orphan row keeps a `position` that
                // reorders the boards still on the home screen.
                if (selectionRepository.selections.value.none { it.groupingId == stationId }) {
                    UserSettings.forget(stationId)
                }
                // This screen SAVES, but unticking every row is a delete — and
                // unticking the last row of the only station empties the account.
                // The server refuses to store an empty board list unless the
                // client says the user is what emptied it.
                UserStateSync.boardsChanged(
                    emptiedByUser = selectionRepository.selections.value.isEmpty(),
                )
                newSelections.forEach { sel ->
                    ActivityLog.record(
                        ActivityEvents.BOARD_ADDED,
                        mapOf(
                            "station" to sel.station,
                            "line" to sel.line,
                            "mode" to sel.mode,
                            "direction" to sel.direction,
                        ),
                    )
                    if (!sel.isUnfiltered) {
                        ActivityLog.record(
                            ActivityEvents.BOARD_FILTERED,
                            mapOf(
                                "station" to sel.station,
                                "filter" to sel.filterMode.name,
                                "count" to sel.destinationIds.size.toString(),
                            ),
                        )
                    }
                }

                // Subscribe the DISTINCT poles once, not once per board.
                //
                // Several routes commonly share a pole — at Smithwood Close both
                // 39 inbound and 639 inbound resolve to 490008805N — so a topic
                // per board would re-subscribe the same topic repeatedly and, on
                // FCM, wake us once per board for a single message.
                val newTopics = newSelections.flatMap {
                    listOf("Station_${it.station}", "LineStatus_${it.mode}_${it.line}")
                }.distinct()
                if (newTopics.isNotEmpty()) {
                    try {
                        Platform.notificationManager.subscribeToTopics(newTopics)
                    } catch (_: Exception) {
                        // Best-effort; completeSetupAsync re-subscribes below.
                    }
                }
                for (sel in newSelections.reversed()) {
                    stationLifecycleUseCase.completeSetupAsync(sel, subscribeTopics = false)
                }

                // completeSetupAsync also writes the widget payload, so finish on
                // the list's ACTUAL primary. Ending on the last-written row would
                // mean that merely adding a line to an existing station silently
                // repointed the widget at the new line instead of leaving it on
                // the board sitting at the top of the card.
                selectionRepository.selections.value.firstOrNull()?.let {
                    stationLifecycleUseCase.completeSetupAsync(it)
                }

                // The picks just became the saved state — re-baseline so a
                // second save in the same session diffs against reality rather
                // than re-adding everything.
                _existingPicks.value = picks
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

    /*
     * `clearBoardsPreservingIdentity()` used to live here: it wiped every board
     * on save (because the product was single-board) while carefully avoiding
     * `StationLifecycleUseCase.cleanupAll()`, whose `storageManager.clearAll()`
     * is `removePersistentDomainForName(bundleId)` on iOS and takes the whole
     * NSUserDefaults domain with it — `firebase_user_uid` and
     * `firebase_auth_token` included, silently breaking every auth-gated call
     * until AuthBridge re-persisted them.
     *
     * Saves are additive now, so nothing needs wiping and the function is gone.
     * The iOS hazard it documented is NOT gone: never call `cleanupAll()` (or
     * `storageManager.clearAll()`) from a board-editing path. LoginViewModel
     * carries the same warning for the same reason.
     */

    fun onActionTriggered(action: String) {
        if (action == "SAVE_SELECTION_ACTION") saveSelection()
    }

    fun dismissSuccess() {
        _uiState.value = _uiState.value.copy(showSuccessDialog = false)
    }

    fun dismissSuccessDialog() = dismissSuccess()

    fun clearSelections() {
        editStationOption = null
        _selections.value = emptyMap()
        _dropdownData.value = emptyMap()
        clearLinePicks()
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

    /**
     * Keep the backend's short line names from a line-dropdown payload.
     *
     * This screen is the ONLY place they arrive: the lines endpoint is what
     * carries `shortName`, and the board that needs it fetches departures and
     * nothing else. Learning them here is also the right moment — the user is
     * choosing the very lines whose labels this will shorten.
     *
     * Guarded on the dropdown id so a station or direction payload cannot write
     * naptans and compass points into a map of line names.
     *
     * Deliberately NOT awaited into the render path and deliberately not able to
     * fail it: [LineNameStore.remember] swallows its own write errors, and a
     * store that stays empty simply leaves [LineShortNames] on its local table.
     */
    private suspend fun rememberLineShortNames(
        dropdownId: String,
        options: List<SduiDropdownOption>,
    ) {
        if (dropdownId != "line") return
        LineNameStore.remember(
            options.mapNotNull { opt -> opt.shortName?.let { opt.id to it } }.toMap()
        )
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
                        // Also from the CACHE, not just the live fetch below.
                        // The fetch is what normally teaches the store, but it
                        // is the one part of this that can fail — and an offline
                        // launch is exactly when falling back to compiled-in
                        // names is most visible. The cached payload already
                        // holds the answer; reading it costs nothing.
                        rememberLineShortNames(dropdown.id, cached)
                    } catch (_: Exception) {}
                }

                val options = sduiService.getDropdownData(finalUrl)
                storageManager.saveString(cacheKey, jsonFormat.encodeToString(options))
                storageManager.saveString(tsKey, now.toString())
                rememberLineShortNames(dropdown.id, options)

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
