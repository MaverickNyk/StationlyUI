package com.stationly.app.ui.station

import com.stationly.core.config.BoardPolicyStore
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.core.platform.Platform
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.NetworkModule
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Fills in what a direction is CALLED ([UserSelection.directionName],
 * [UserSelection.directionDestinations], [UserSelection.directionTowards]) on boards
 * saved before those fields existed.
 *
 * Route data, so nothing on the device can derive it. Without it the settings say TfL's
 * raw "Inbound", and both sides of an unlettered bus stop are headed "Bus 498". It ran
 * only when a station's settings were opened; the home screen now runs it for bus boards
 * that have none (2026-11-26).
 *
 * It reads the line picker's own cache first: the URL is resolved exactly as
 * `SelectionViewModel.fetchDirectionsFor` does, so a board set up in the last 24 hours
 * costs no network. A failure is silent: the labels stay as they were. A board that
 * resolves is stamped and left alone for [BoardPolicyStore]'s `routeTextMaxAgeMs`; one
 * that does not (TfL withdrew the direction) is tried again, at the price of a storage
 * read inside the cache's window.
 */
object RouteTextBackfill {

    /** Whether nothing has ever been resolved for this board. */
    fun lacksText(board: UserSelection): Boolean =
        board.directionName.isBlank() && board.directionDestinations.isEmpty() && board.directionTowards.isBlank()

    /**
     * Boards [run] has already tried in this process with `oncePerProcess`. One
     * whose direction TfL no longer lists never resolves, and the home screen
     * would otherwise ask again on every visit.
     */
    private val tried = mutableSetOf<String>()

    suspend fun run(
        repository: SelectionRepository,
        oncePerProcess: Boolean = false,
        boards: (UserSelection) -> Boolean,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        val stale = repository.selections.value.filter(boards).filter {
            lacksText(it) || now - it.routeResolvedAt > BoardPolicyStore.current.routeTextMaxAgeMs
        }.filter { !oncePerProcess || it.boardKey !in tried }
        if (stale.isEmpty()) return
        val template = directionUrlTemplate() ?: return

        // One fetch per line and station: both directions come back in one list. Kept as
        // TEXT by board, never as finished rows: the fetches await the network, and the
        // rows may be edited or deleted meanwhile.
        val resolved = mutableMapOf<String, RouteText>()
        for ((_, same) in stale.groupBy { Triple(it.line, it.mode, it.groupingId) }) {
            val sample = same.first()
            val url = template
                .replace("{line}", sample.line)
                .replace("{mode}", sample.mode)
                .replace("{station}", sample.groupingId)
            // A placeholder we do not know. Guessing could answer for the wrong station.
            if (url.contains("{")) continue
            val options = directionOptions(url) ?: continue
            // Tried for real: TfL answered. A failed fetch (offline) tries again.
            if (oncePerProcess) same.forEach { tried.add(it.boardKey) }
            for (board in same) {
                val option = options.find { it.id.equals(board.direction, ignoreCase = true) } ?: continue
                val text = RouteText(
                    option.directionName.orEmpty(),
                    option.destinations?.map { it.label }.orEmpty(),
                    option.towards.orEmpty(),
                )
                if (text.name.isBlank() && text.destinations.isEmpty() && text.towards.isBlank()) continue
                resolved[board.boardKey] = text
            }
        }
        if (resolved.isEmpty()) return

        // One write, of the rows as they are then.
        repository.editInPlace { row ->
            resolved[row.boardKey]?.let {
                row.copy(
                    directionName = it.name,
                    directionDestinations = it.destinations,
                    directionTowards = it.towards,
                    routeResolvedAt = now,
                )
            }
        }
    }

    private data class RouteText(val name: String, val destinations: List<String>, val towards: String)

    /** The `dataSourceUrl` of the SDUI direction dropdown, from the cached layout if there is one. */
    private suspend fun directionUrlTemplate(): String? {
        fun extract(screen: SduiAppScreen): String? = screen.components
            .filterIsInstance<SduiAppComponent.Dropdown>()
            .find { it.id == "direction" }
            ?.dataSourceUrl

        Platform.storageManager.loadString(LAYOUT_CACHE_KEY)?.let { raw ->
            runCatching { extract(json.decodeFromString<SduiAppScreen>(raw)) }.getOrNull()?.let { return it }
        }
        return runCatching { extract(NetworkModule.sduiApi.getSelectionLayout()) }.getOrNull()
    }

    /** The line picker's 24-hour dropdown cache, read first and written on a miss. */
    private suspend fun directionOptions(url: String): List<SduiDropdownOption>? {
        val cacheKey = "cached_dropdown_$url"
        val tsKey = "${cacheKey}_ts"
        val now = Clock.System.now().toEpochMilliseconds()
        val cachedAt = Platform.storageManager.loadString(tsKey)?.toLongOrNull() ?: 0L
        if (now - cachedAt < BoardPolicyStore.current.dropdownCacheTtlMs) {
            Platform.storageManager.loadString(cacheKey)
                ?.let { raw -> runCatching { json.decodeFromString(options, raw) }.getOrNull() }
                // One that will not decode is not an answer: fetched again and overwritten.
                ?.let { return it }
        }
        return runCatching {
            val fetched = NetworkModule.sduiApi.getDropdownData(url)
            Platform.storageManager.saveString(cacheKey, json.encodeToString(options, fetched))
            Platform.storageManager.saveString(tsKey, now.toString())
            fetched
        }.getOrNull()
    }

    /** Written by `SelectionViewModel.loadCachedLayout`; only read here. */
    private const val LAYOUT_CACHE_KEY = "cached_app_layout"

    /** Lenient: the payload is the backend's and gains fields. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val options = ListSerializer(SduiDropdownOption.serializer())
}
