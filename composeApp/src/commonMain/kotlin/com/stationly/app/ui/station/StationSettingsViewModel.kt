package com.stationly.app.ui.station

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.app.ui.util.StationPrefs
import com.stationly.app.ui.util.StationPrefsRepository
import com.stationly.core.model.UserSelection
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncSubscribedStationsUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One station's settings: which lines it tracks, how its card is laid out, and
 * whether it exists at all.
 *
 * [stationId] is the GROUPING id (the hub), which is what the home screen builds
 * one card per and what the preferences are keyed on. It is not a fetch key —
 * on bus each direction resolves to its own pole naptan, and matching on those
 * would miss half the boards this screen is meant to be about.
 */
class StationSettingsViewModel(
    private val stationId: String,
    private val selectionRepository: SelectionRepository = SelectionRepository(
        Platform.storageManager,
        Platform.sqlStorage,
    ),
) : ViewModel() {

    private val departureRepository = DepartureRepository(
        NetworkModule.tflApi,
        Platform.storageManager,
        Platform.sqlStorage,
        SyncPredictionsUseCase(Platform.sqlStorage),
    )

    /**
     * Deleting a board is TWO jobs: the local teardown below, and telling the
     * backend the board is gone. Without the second one the deletion is local
     * only — the backend still lists the board, and a cloud restore or another
     * device brings it straight back.
     */
    private val syncSubscribedStations = SyncSubscribedStationsUseCase(
        NetworkModule.sduiApi,
        Platform.storageManager,
    )

    private val stationLifecycleUseCase = StationLifecycleUseCase(
        selectionRepository = selectionRepository,
        departureRepository = departureRepository,
        notificationManager = Platform.notificationManager,
        widgetManager = Platform.widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = Platform.storageManager,
    )

    /**
     * This station's boards, in card order.
     *
     * Read from this screen's OWN repository instance rather than handed in from
     * the home screen: an edit round-trip (settings → line picker → back) changes
     * them, and a snapshot passed through navigation would show the user the
     * board list they had before their own edit.
     */
    val boards: StateFlow<List<UserSelection>> = selectionRepository.selections
        .map { all -> all.filter { it.groupingId == stationId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val prefs: StateFlow<Map<String, StationPrefs>> = StationPrefsRepository.prefs

    /** True once the last board here is gone, so the screen can dismiss itself. */
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    /**
     * Where each board is heading, keyed by [UserSelection.boardKey].
     *
     * Read from the departures already cached in SQLite rather than fetched:
     * this screen must be readable offline and instantly, and "towards
     * Hammersmith" is a stable fact about a board, not a live one. Falls out
     * empty for a board with no cached rows yet, and the row simply says less.
     *
     * DECLARED ABOVE [init], and it has to stay there. `viewModelScope` is
     * `Dispatchers.Main.immediate`, so the coroutine `init` launches runs
     * SYNCHRONOUSLY when the screen composes on the main thread — before any
     * property declared below `init` has been assigned. Declared after it, this
     * flow was still null when [loadTowards] wrote to it, and Kotlin/Native has
     * no null check to turn that into an exception: the app segfaulted the
     * instant the settings screen opened.
     */
    private val _towards = MutableStateFlow<Map<String, String>>(emptyMap())
    val towards: StateFlow<Map<String, String>> = _towards.asStateFlow()


    init {
        viewModelScope.launch {
            StationPrefsRepository.ensureLoaded()
            // This VM's repository starts empty — it is a separate instance from
            // the home screen's, so it holds no rows until it reads the database.
            selectionRepository.initialize()
            loadTowards()
        }
    }

    /** Re-read the boards from the database — see the screen's ON_RESUME hook. */
    fun refresh() {
        viewModelScope.launch {
            selectionRepository.initialize()
            loadTowards()
        }
    }

    private suspend fun loadTowards() {
        val current = selectionRepository.selections.value.filter { it.groupingId == stationId }
        // One SQLite read per board, off the main thread: this screen opens over
        // a live departure board, and a handful of synchronous queries on the
        // main dispatcher is a visible hitch in the push animation.
        _towards.value = withContext(Dispatchers.Default) {
            current.mapNotNull { board ->
                val cached = runCatching {
                    Platform.sqlStorage.getPredictions(board.station, board.line, board.direction)
                }.getOrNull().orEmpty()
                // The most common destination, not the soonest: one
                // short-terminating service should not relabel the board it
                // happens to be first on.
                cached.map { it.destination.trim() }
                    .filter { it.isNotEmpty() }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?.let { board.boardKey to it }
            }.toMap()
        }
    }

    /** Expand this station's card on every app open — see [StationPrefs.startExpanded]. */
    fun setStartExpanded(expanded: Boolean) {
        viewModelScope.launch { StationPrefsRepository.update(stationId) { it.copy(startExpanded = expanded) } }
    }

    fun setHeroVisible(visible: Boolean) {
        viewModelScope.launch { StationPrefsRepository.update(stationId) { it.copy(hideHero = !visible) } }
    }

    /**
     * Delete one (line, direction) board, leaving the rest of the station.
     *
     * Sequential `remaining` recomputation is the rule here and in every other
     * delete path: `discardStation` unsubscribes only what no survivor still
     * needs, so a survivor list computed from anything but the repository's
     * CURRENT contents either leaks a subscription or silences a board the user
     * is keeping.
     */
    fun deleteBoard(board: UserSelection) {
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                discard(board)
                syncSubscribedStations.sync(selectionRepository.selections.value)
                if (selectionRepository.selections.value.none { it.groupingId == stationId }) {
                    StationPrefsRepository.forget(stationId)
                    _deleted.value = true
                }
                performHaptic(HapticType.SUCCESS)
            } catch (_: Exception) {
                performHaptic(HapticType.ERROR)
            } finally {
                _isDeleting.value = false
            }
        }
    }

    /** Delete the station outright — every line, every direction. */
    fun deleteStation() {
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                // One at a time, re-reading the repository between each, for the
                // same reason [deleteBoard] does.
                val doomed = selectionRepository.selections.value.filter { it.groupingId == stationId }
                for (board in doomed) discard(board)
                // ONE sync for the whole station, after the last teardown: the
                // endpoint replaces the list it is given, so syncing per board
                // would send several intermediate lists for no gain.
                syncSubscribedStations.sync(selectionRepository.selections.value)
                StationPrefsRepository.forget(stationId)
                _deleted.value = true
                performHaptic(HapticType.SUCCESS)
            } catch (_: Exception) {
                performHaptic(HapticType.ERROR)
            } finally {
                _isDeleting.value = false
            }
        }
    }

    private suspend fun discard(board: UserSelection) {
        val remaining = selectionRepository.selections.value.filterNot {
            it.station == board.station &&
                it.line == board.line &&
                it.direction == board.direction
        }
        stationLifecycleUseCase.discardStation(
            board,
            clearSelectionInRepo = true,
            remaining = remaining,
        )
    }
}
