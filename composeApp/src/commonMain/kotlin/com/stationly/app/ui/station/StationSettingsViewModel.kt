package com.stationly.app.ui.station

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.app.sync.UserStateSync
import com.stationly.app.ui.summary.BoardExpansion
import com.stationly.core.repository.UserSettings
import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.core.service.NetworkModule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.BoardView
import com.stationly.core.model.user.BoardPin
import com.stationly.core.util.MultiLineBoardProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Outlives any one settings screen, and holds exactly one job: the widget push
 * that fires as the screen closes.
 *
 * A `viewModelScope` is cancelled before `onCleared` returns, so work started
 * there never runs. This is deliberately a plain supervised scope rather than a
 * per-instance one — the push must survive the ViewModel that asked for it, and
 * a failure in one must not cancel the next.
 */
private val ExitScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * One station's settings: which lines it tracks, how it is laid out on the home
 * screen, how its board is arranged, and whether it exists at all.
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

    private val sduiApi = NetworkModule.sduiApi

    private val departureRepository = DepartureRepository(
        NetworkModule.tflApi,
        Platform.storageManager,
        Platform.sqlStorage,
        SyncPredictionsUseCase(Platform.sqlStorage),
    )

    // Deleting a board is TWO jobs: the local teardown below, and telling the
    // backend the board is gone. Without the second one the deletion is local
    // only — the backend still lists the board, and a cloud restore or another
    // device brings it straight back.
    //
    // That second job now goes through `UserStateSync`, which writes the v2
    // board list. It used to be `SyncSubscribedStationsUseCase`, which posts to
    // the LEGACY `stations` array — the one Android replaces wholesale on every
    // board setup. Writing there from here put both platforms on one list and
    // is what let an Android save delete every board added on an iPhone.

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

    val configs: StateFlow<Map<String, BoardConfig>> = UserSettings.configs

    /** True once the last board here is gone, so the screen can dismiss itself. */
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    // A `towards` map used to live here, read out of the cached departures and
    // used to label each direction row. It is gone: the row now describes the
    // SAVED SELECTION, which is what the user actually chose — see [BoardLabels].
    //
    // ⚠️ The "declared ABOVE init" rule the flows below carry is NOT about that
    // change and still applies. `viewModelScope` is `Dispatchers.Main.immediate`,
    // so the coroutine `init` launches runs SYNCHRONOUSLY when the screen
    // composes on the main thread, before any property declared below `init` has
    // been assigned. A flow declared after it is still null when the load writes
    // to it, and Kotlin/Native has no null check to turn that into an exception:
    // the app segfaulted the instant the settings screen opened.

    /**
     * The platform (or bus stop) labels this station has actually shown, for the
     * "show first" picker.
     *
     * Derived from the SQLite cache rather than from anything the user
     * configured, because a platform is not something they configure: TfL
     * decides it, and the only honest list is the one the board itself has been
     * showing. Read, never fetched: this screen has to open instantly and
     * offline.
     *
     * Unassigned blocks are left out by [MultiLineBoardProcessor.pinnablePlatforms]:
     * a pin on one would promote a block nobody can walk to, which the board
     * refuses to do anyway, so offering it would be offering a setting
     * guaranteed to have no effect.
     *
     * Also declared ABOVE [init] — see the note above it for the crash that rule
     * exists to prevent.
     */
    private val _platforms = MutableStateFlow<List<String>>(emptyList())
    val platforms: StateFlow<List<String>> = _platforms.asStateFlow()

    /**
     * The poles at a BUS hub, named by where their buses go — the pin picker's
     * options when [platforms] cannot have any.
     *
     * Always empty on rail, and [platforms] is always empty on bus: the two are
     * the same list for two kinds of station, and populating both would offer the
     * same block twice under two different names.
     *
     * Also declared ABOVE [init] — see the note above [platforms] for the crash
     * that rule exists to prevent.
     */
    private val _stops = MutableStateFlow<List<MultiLineBoardProcessor.StopOption>>(emptyList())
    val stops: StateFlow<List<MultiLineBoardProcessor.StopOption>> = _stops.asStateFlow()

    /**
     * The arrangement as it stood when this screen opened, so leaving can tell
     * whether anything actually moved — see [onCleared].
     *
     * Null until the first read completes. That is deliberate: a snapshot taken
     * before [UserSettings.ensureLoaded] would be the DEFAULT arrangement, so a
     * user who opened the screen and changed nothing would look like they had
     * reset every setting they had ever made.
     */
    private var arrangementOnEntry: BoardConfig? = null

    init {
        viewModelScope.launch {
            UserSettings.ensureLoaded()
            arrangementOnEntry = UserSettings.configOf(stationId)
            // This VM's repository starts empty — it is a separate instance from
            // the home screen's, so it holds no rows until it reads the database.
            selectionRepository.initialize()
            loadCachedBoard()
            // Once per board, and only for boards that predate the fields. NOT
            // in `refresh()`: that runs on every ON_RESUME, and this can reach
            // the network.
            backfillDirectionDetail()
        }
    }

    /**
     * Push the new arrangement to the WIDGET on the way out, and only if it moved.
     *
     * ## Why this is not done as the user changes things
     * It was, and it was expensive in a way that only shows on a real device.
     * `updateWidget` is `IosWidgetManager.refreshAllBoards` — it rebuilds EVERY
     * station's board from SQL and rewrites the App Group — and the depth slider
     * fires once per detent crossed. Dragging it from two to five ran three full
     * rebuilds during one gesture.
     *
     * The UI never stuttered for it (`refreshAllBoards` hops to `Dispatchers.IO`
     * itself), which is exactly why it survived: the cost was three rounds of
     * SQL per drag, and three bumps of the reload signal, none of it visible on
     * the device doing it.
     *
     * ## Why the home screen needs nothing here
     * It is already reactive and always was: `UserSettings.configs` is one
     * shared `StateFlow` that `SummaryViewModel` exposes directly, and the board
     * derives its rows with `remember(rendered, isBus, boardPrefs)`. Writing a
     * preference IS the redraw. The widget is the only surface that has to be
     * told, because it lives in another process.
     *
     * ## The diff is the point
     * A user who opens this screen to read it, or who moves the slider and puts it
     * back, has changed nothing — and `BoardConfig` is a data class, so
     * saying so is one comparison. Rebuilding regardless would spend the same
     * device work on a no-op and, worse, bump the reload signal that makes
     * WidgetKit regenerate timelines, which Apple meters at roughly 40–70 a day.
     *
     * Not on [viewModelScope]: that is cancelled before this runs. The work is
     * fire-and-forget platform sync that SHOULD outlive the screen — the
     * preference is already persisted, so the worst case if it never lands is the
     * widget picking the change up on its next natural refresh.
     */
    override fun onCleared() {
        super.onCleared()
        val entry = arrangementOnEntry ?: return
        // Only the fields the WIDGET renders. `expanded`, `view` and `position`
        // are home-screen facts the extension never reads, and comparing the
        // whole config would spend a full board rebuild — and a bump of the
        // reload signal Apple meters at roughly 40-70 timelines a day — on a
        // change the widget cannot show.
        val now = UserSettings.configOf(stationId)
        if (now.rowCap == entry.rowCap && now.pin == entry.pin) return
        ExitScope.launch {
            runCatching {
                Platform.widgetManager.updateWidget(
                    WidgetState(
                        stationName = "", lineName = "", predictions = emptyList(),
                        status = null, lastUpdated = 0L,
                    )
                )
            }
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
     * One pass over the cached departures, for the one thing on this screen that
     * has to be derived from them: the platforms and poles the pin picker offers.
     *
     * A platform is not something the user configures — TfL decides it — so the
     * only honest list is the one the board itself has been showing. Everything
     * else on this screen comes from the saved selection instead.
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

        val feeds = cached.map { (board, predictions) ->
            MultiLineBoardProcessor.Feed(
                // The resolved per-direction naptan (the POLE), not the hub —
                // exactly what the home screen passes, so the picker groups the
                // way the real board does and can never offer a place that board
                // would not show.
                stationId = board.station,
                line = board.line,
                direction = board.direction,
                predictions = predictions,
            )
        }
        // Exactly one of these is ever populated. A bus hub's poles have no
        // labels to offer, and a rail platform has no pole to name.
        _platforms.value =
            if (isBus) emptyList()
            else MultiLineBoardProcessor.pinnablePlatforms(feeds = feeds, isBus = false)
        _stops.value =
            if (isBus) MultiLineBoardProcessor.pinnableStops(feeds = feeds)
            else emptyList()

        // A bus hub used to offer its LETTERED poles as platform chips, so a pin
        // of that kind can still be stored. It has no chip any more — poles are
        // offered as `stops` now — but `isPinned` still honours it, which leaves
        // a setting in force that the picker shows as unset and gives the user
        // no way to reach or clear. Dropped rather than migrated: the pole it
        // names may not even be on today's board, and a pin nobody can see is
        // worse than one they can set again from chips that exist.
        if (isBus && UserSettings.configOf(stationId).pin?.kind == BoardPin.Kind.PLATFORM) {
            UserSettings.update(stationId) { it.copy(pin = null) }
        }
    }

    /**
     * Fill in [UserSelection.directionName] and [UserSelection.directionDestinations]
     * for boards saved before those existed.
     *
     * ## Why this is here and not left to the picker
     * They are route data — the backend's compass mapping and the destination
     * chips — so nothing on the device can derive them. Boards created from now
     * on carry them (`SelectionViewModel.buildSelection`), but every board
     * already on a phone has neither, and would go on showing TfL's raw
     * "Inbound" until the user happened to edit its lines again.
     *
     * ## It reads the picker's own cache first
     * The URL is resolved exactly as `SelectionViewModel.fetchDirectionsFor`
     * resolves it, so the cache key matches and a board set up in the last 24
     * hours costs no network at all. A miss falls through to one request per
     * line, and the result is written back to the same cache.
     *
     * ## Failure is silent and harmless
     * Every board keeps working without this: [BoardLabels] falls back to its
     * own compass table for the name, and to "All destinations" for the detail.
     * So there is no retry, no error state and no spinner — it either improves
     * the labels or leaves them exactly as they were.
     *
     * A board that resolves is stamped and then left alone for a fortnight —
     * see [ROUTE_TEXT_MAX_AGE_MS], which is also what finally gives
     * `routeResolvedAt` a reader. A board that does NOT resolve (a direction TfL
     * has withdrawn) is retried on every visit, which costs one storage read
     * inside the dropdown cache's own 24-hour window and no network. Stamping it
     * anyway would be recording an answer we never got.
     */
    private suspend fun backfillDirectionDetail() {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val stale = selectionRepository.selections.value
            .filter { it.groupingId == stationId }
            .filter { board ->
                // Never resolved…
                val unresolved = board.directionName.isBlank() &&
                    board.directionDestinations.isEmpty() &&
                    board.directionTowards.isBlank()
                // …or resolved so long ago that TfL may have moved underneath it.
                //
                // `routeResolvedAt` was written and never read by anything, which
                // mattered little when the resolution was a list of ids nobody
                // displayed. It matters now: these fields are TEXT ON THE SCREEN,
                // and a renamed destination or a rerouted branch would otherwise
                // sit there indefinitely.
                //
                // Cheap, because it goes through the same cache-first path: a
                // re-resolve inside the dropdown cache's own 24-hour window
                // costs one storage read and no network.
                unresolved || now - board.routeResolvedAt > ROUTE_TEXT_MAX_AGE_MS
            }
        if (stale.isEmpty()) return

        val template = directionUrlTemplate() ?: return

        // One fetch per LINE, not per board: both directions of a line come back
        // in the same list. And ONE write at the end, not one per board — every
        // replacement rewrites the whole selection table.
        //
        // Collected as TEXT keyed by [UserSelection.boardKey] — the model's own
        // (station, line, direction) identity — never as finished rows.
        // `stale` is a snapshot taken before the fetches below, which await the
        // network: the user can reach the line picker, change a filter and come
        // back inside that window, and writing these snapshots back would revert
        // the edit they just made. The keys are re-matched against the
        // repository as it stands at write time instead.
        val resolved = mutableMapOf<String, RouteText>()
        for ((line, boards) in stale.groupBy { it.line }) {
            val sample = boards.first()
            val url = template
                .replace("{line}", line)
                .replace("{mode}", sample.mode)
                .replace("{station}", sample.groupingId)
            // A placeholder we do not know how to fill. Guessing would build a
            // URL that either 404s or, worse, answers for the wrong station.
            if (url.contains("{")) continue
            val options = directionOptions(url) ?: continue
            for (board in boards) {
                val option = options.find { it.id.equals(board.direction, ignoreCase = true) }
                    ?: continue
                val name = option.directionName.orEmpty()
                val destinations = option.destinations?.map { it.label }.orEmpty()
                val towards = option.towards.orEmpty()
                if (name.isBlank() && destinations.isEmpty() && towards.isBlank()) continue
                resolved[board.boardKey] = RouteText(name, destinations, towards)
            }
        }
        if (resolved.isEmpty()) return

        // Applied to the CURRENT rows. A board deleted or edited while the
        // fetches were in flight is picked up as it is now, or not at all.
        val updated = selectionRepository.selections.value.mapNotNull { row ->
            resolved[row.boardKey]?.let { text ->
                row.copy(
                    directionName = text.name,
                    directionDestinations = text.destinations,
                    directionTowards = text.towards,
                    // Stamped so the staleness test above has something to move
                    // against. Without this a board with no filter keeps
                    // `routeResolvedAt = 0` and re-resolves on every visit.
                    routeResolvedAt = now,
                )
            }
        }
        selectionRepository.updateSelectionsInPlace(updated)
    }

    /** What one direction is CALLED, apart from the row it belongs to. */
    private data class RouteText(
        val name: String,
        val destinations: List<String>,
        val towards: String,
    )

    /**
     * The `dataSourceUrl` of the SDUI direction dropdown, cache first.
     *
     * The selection layout is already on disk — `SelectionViewModel` writes it to
     * `cached_app_layout` and reads it back on every launch — so calling the API
     * for it here was a network round trip for a string we own. It matters
     * because this runs on a screen that must open instantly and offline, and it
     * runs for any board still lacking route text: a board that cannot be
     * resolved at all (an option TfL has withdrawn) would have re-fetched the
     * whole layout on every single visit.
     */
    private suspend fun directionUrlTemplate(): String? {
        fun extract(screen: SduiAppScreen): String? = screen.components
            .filterIsInstance<SduiAppComponent.Dropdown>()
            .find { it.id == "direction" }
            ?.dataSourceUrl

        Platform.storageManager.loadString(LAYOUT_CACHE_KEY)?.let { raw ->
            runCatching { extract(dropdownJson.decodeFromString<SduiAppScreen>(raw)) }
                .getOrNull()
                ?.let { return it }
        }
        return runCatching { extract(sduiApi.getSelectionLayout()) }.getOrNull()
    }

    /** The line picker's 24-hour dropdown cache, read first and written on a miss. */
    private suspend fun directionOptions(url: String): List<SduiDropdownOption>? {
        val cacheKey = "cached_dropdown_$url"
        val tsKey = "${cacheKey}_ts"
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val cachedAt = Platform.storageManager.loadString(tsKey)?.toLongOrNull() ?: 0L
        if (now - cachedAt < DROPDOWN_CACHE_MS) {
            Platform.storageManager.loadString(cacheKey)
                ?.let { raw ->
                    runCatching { dropdownJson.decodeFromString(dropdownListSerializer, raw) }
                        .getOrNull()
                }
                // A cache entry that will not decode is not an answer. Falling
                // through re-fetches and overwrites it.
                ?.let { return it }
        }
        return runCatching {
            val fetched = sduiApi.getDropdownData(url)
            Platform.storageManager.saveString(
                cacheKey,
                dropdownJson.encodeToString(dropdownListSerializer, fetched),
            )
            Platform.storageManager.saveString(tsKey, now.toString())
            fetched
        }.getOrNull()
    }

    /**
     * Open this board's card on every app open — see [BoardConfig.expanded].
     *
     * Also applies the choice to the home screen NOW. The stored flag is only
     * the default, and the home screen's live expansion is session state that
     * deliberately outranks it once the user has touched a chevron — so without
     * this the user chooses Collapsed, goes back, and watches the card sit there
     * open until the next cold start. See [BoardExpansion].
     */
    fun setExpanded(expanded: Boolean) {
        // Raised on EVERY tap, including one that leaves the stored value alone.
        // The home screen's live expansion is session state and can already
        // disagree with the default, so "the setting did not change" does not
        // mean "the board is already like that" — see the note at the picker.
        BoardExpansion.request(stationId, expanded)
        if (UserSettings.configOf(stationId).expanded == expanded) return
        updateConfig("expanded", expanded.toString()) { it.copy(expanded = expanded) }
    }

    /** Which halves of the card this board draws — see [BoardView]. */
    fun setView(view: BoardView) = updateConfig("view", view.name) { it.copy(view = view) }

    /* ── How the board is arranged — see [BoardConfig] ────────────────── */

    /** Stored as asked and clamped on read — see [BoardConfig.rowCap]. */
    fun setRowsPerPlatform(rows: Int) = updateConfig("rowsPerPlatform", rows.toString()) {
        it.copy(rowsPerPlatform = rows)
    }

    /** `null` puts the board back to its natural order. One pin at a time. */
    fun setPin(pin: BoardPin?) = updateConfig("pin", pin?.let { "${it.kind}:${it.id}" } ?: "none") {
        it.copy(pin = pin)
    }

    /**
     * Persist an arrangement change, and nothing else.
     *
     * The write IS the redraw for every surface inside this process — see
     * [onCleared], which explains why, and which carries the one push that still
     * has to happen.
     *
     * There is no BACKEND write here at all, and that is the point rather than an
     * omission. Arrangement is device-local (see `UserSettings`), so a slider
     * drag costs a disk write per detent and nothing on the wire. This used to
     * mark the board list dirty and push it debounced, which put a Firestore
     * write behind every toggle on the one document every login reads.
     */
    private fun updateConfig(
        key: String,
        value: String,
        transform: (BoardConfig) -> BoardConfig,
    ) {
        viewModelScope.launch { UserSettings.update(stationId, transform) }
        recordSettingChange(key, value)
    }

    /**
     * Fires per change, including every step of a slider drag.
     *
     * Deliberately not debounced the way the network write is. An event is a
     * row in a local queue and costs nothing; knowing that a user dragged
     * through 2-3-4 before settling on 5 is more informative than knowing only
     * where they stopped, and collapsing it here would throw that away to save
     * something that was never expensive.
     */
    private fun recordSettingChange(key: String, value: String) {
        ActivityLog.record(
            ActivityEvents.SETTINGS_STATION_CHANGED,
            mapOf("station" to stationId, "key" to key, "value" to value),
        )
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
                // The live answer, not a literal: deleting one of a station's
                // four boards must not hand out permission to empty the account.
                UserStateSync.boardsChanged(
                    emptiedByUser = selectionRepository.selections.value.isEmpty(),
                )
                ActivityLog.record(
                    ActivityEvents.BOARD_DELETED,
                    mapOf(
                        "station" to board.station,
                        "line" to board.line,
                        "mode" to board.mode,
                        "direction" to board.direction,
                    ),
                )
                if (selectionRepository.selections.value.none { it.groupingId == stationId }) {
                    UserSettings.forget(stationId)
                    // The home screen leaves a request PENDING when it names a
                    // station it cannot see, so that a station still loading is
                    // not dropped for being early. A station that has just been
                    // deleted is the other reason a request goes unmatched, and
                    // nothing else would ever clear it: it would sit in the map
                    // for the rest of the session and apply itself to this
                    // station if the user added it back.
                    BoardExpansion.consume(listOf(stationId))
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
                // would send several intermediate lists for no gain. (The push
                // is debounced anyway, so several calls here would coalesce —
                // but the intent is worth stating at the call site.)
                //
                // Deleting the user's ONLY station is the ordinary way to reach
                // an empty account, and the server refuses to store an empty list
                // without being told so explicitly.
                UserStateSync.boardsChanged(
                    emptiedByUser = selectionRepository.selections.value.isEmpty(),
                )
                doomed.forEach { board ->
                    ActivityLog.record(
                        ActivityEvents.BOARD_DELETED,
                        mapOf(
                            "station" to board.station,
                            "line" to board.line,
                            "mode" to board.mode,
                            "direction" to board.direction,
                        ),
                    )
                }
                UserSettings.forget(stationId)
                // See [deleteBoard] — an unmatched request is never dropped by
                // the home screen, so a deleted station has to withdraw its own.
                BoardExpansion.consume(listOf(stationId))
                _deleted.value = true
                performHaptic(HapticType.SUCCESS)
            } catch (_: Exception) {
                performHaptic(HapticType.ERROR)
            } finally {
                _isDeleting.value = false
            }
        }
    }

    private companion object {
        /** Written by `SelectionViewModel.loadCachedLayout`; read, never written, here. */
        const val LAYOUT_CACHE_KEY = "cached_app_layout"

        /** The same 24-hour contract the line picker's dropdown cache uses. */
        const val DROPDOWN_CACHE_MS = 24L * 60 * 60 * 1000

        /**
         * How long a board's route TEXT is trusted before it is re-resolved.
         *
         * Two weeks. Long enough that the common case is a pure cache read and
         * nothing re-fetches, short enough that a renamed destination or a
         * rerouted branch corrects itself without the user editing anything.
         * This governs display text only — the filter's own allow-list is a
         * separate question, still open as item 9 in PENDING_BRANCH_WORK.md.
         */
        const val ROUTE_TEXT_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

        /**
         * Lenient for the same reason every other SDUI decode is: the payload is
         * backend-owned and gains fields, and a strict parse would fail the whole
         * backfill on one it has not been taught yet.
         */
        val dropdownJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Named once so the encode and the decode cannot disagree. */
        val dropdownListSerializer = ListSerializer(SduiDropdownOption.serializer())
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
