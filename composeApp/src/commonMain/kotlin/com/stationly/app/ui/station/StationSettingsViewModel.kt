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
import com.stationly.core.util.BoardDisplayPrefs
import com.stationly.core.util.BoardPin
import com.stationly.core.util.BoardSort
import com.stationly.core.util.MultiLineBoardProcessor
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

    /**
     * The platform (or bus stop) labels this station has actually shown, for the
     * "show first" picker.
     *
     * Derived from the SQLite cache rather than from anything the user
     * configured, because a platform is not something they configure: TfL
     * decides it, and the only honest list is the one the board itself has been
     * showing. Read, never fetched, for the same reason [towards] is — this
     * screen has to open instantly and offline.
     *
     * Unassigned blocks are left out by [MultiLineBoardProcessor.pinnablePlatforms]:
     * a pin on one would promote a block nobody can walk to, which the board
     * refuses to do anyway, so offering it would be offering a setting
     * guaranteed to have no effect.
     *
     * Also declared ABOVE [init] — see [towards] for the crash that rule exists
     * to prevent.
     */
    private val _platforms = MutableStateFlow<List<String>>(emptyList())
    val platforms: StateFlow<List<String>> = _platforms.asStateFlow()

    init {
        viewModelScope.launch {
            StationPrefsRepository.ensureLoaded()
            // This VM's repository starts empty — it is a separate instance from
            // the home screen's, so it holds no rows until it reads the database.
            selectionRepository.initialize()
            loadCachedBoard()
        }
    }

    /** Re-read the boards from the database — see the screen's ON_RESUME hook. */
    fun refresh() {
        viewModelScope.launch {
            selectionRepository.initialize()
            loadCachedBoard()
        }
    }

    /**
     * One pass over the cached departures, feeding both things on this screen
     * that describe the board as it actually is: each board's `towards` label,
     * and the platforms the pin picker offers.
     *
     * One pass rather than two. They want the same rows, and reading them
     * separately would mean two sets of SQLite queries that can disagree about
     * what the board contains.
     */
    private suspend fun loadCachedBoard() {
        val current = selectionRepository.selections.value.filter { it.groupingId == stationId }
        val isBus = MultiLineBoardProcessor.isBus(current.firstOrNull()?.mode)
        // One SQLite read per board, off the main thread: this screen opens over
        // a live departure board, and a handful of synchronous queries on the
        // main dispatcher is a visible hitch in the push animation.
        val cached = withContext(Dispatchers.Default) {
            current.map { board ->
                board to runCatching {
                    Platform.sqlStorage.getPredictions(board.station, board.line, board.direction)
                }.getOrNull().orEmpty()
            }
        }

        _towards.value = cached.mapNotNull { (board, predictions) ->
            // The most common destination, not the soonest: one
            // short-terminating service should not relabel the board it
            // happens to be first on.
            predictions.map { it.destination.trim() }
                .filter { it.isNotEmpty() }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?.let { board.boardKey to it }
        }.toMap()

        _platforms.value = MultiLineBoardProcessor.pinnablePlatforms(
            feeds = cached.map { (board, predictions) ->
                MultiLineBoardProcessor.Feed(
                    // The resolved per-direction naptan (the POLE), not the hub —
                    // exactly what the home screen passes, so the picker groups
                    // the way the real card does and can never offer a platform
                    // that board would not show.
                    stationId = board.station,
                    line = board.line,
                    direction = board.direction,
                    predictions = predictions,
                )
            },
            isBus = isBus,
        )
    }

    /** Expand this station's card on every app open — see [StationPrefs.startExpanded]. */
    fun setStartExpanded(expanded: Boolean) {
        viewModelScope.launch { StationPrefsRepository.update(stationId) { it.copy(startExpanded = expanded) } }
    }

    fun setHeroVisible(visible: Boolean) {
        viewModelScope.launch { StationPrefsRepository.update(stationId) { it.copy(hideHero = !visible) } }
    }

    /* ── How the board is arranged — see [BoardDisplayPrefs] ────────────────── */

    fun setSort(sort: BoardSort) = updateBoard { it.copy(sort = sort) }

    /** Stored as asked and clamped on read — see [BoardDisplayPrefs.rowCap]. */
    fun setRowsPerPlatform(rows: Int) = updateBoard { it.copy(rowsPerPlatform = rows) }

    /** `null` puts the board back to its natural order. One pin at a time. */
    fun setPin(pin: BoardPin?) = updateBoard { it.copy(pin = pin) }

    private fun updateBoard(transform: (BoardDisplayPrefs) -> BoardDisplayPrefs) {
        viewModelScope.launch {
            StationPrefsRepository.update(stationId) { it.copy(board = transform(it.board)) }
        }
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
                } else {
                    // The boards list re-emits on its own (it maps the
                    // repository), but the CACHED reads do not: without this the
                    // pin picker keeps offering the deleted line's platforms and
                    // the rows keep their `towards` labels.
                    loadCachedBoard()
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
