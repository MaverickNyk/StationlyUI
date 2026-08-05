package com.stationly.app.ui.util

import com.stationly.core.platform.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-station home-screen preferences, keyed by the station's GROUPING id (the
 * hub the user picked — `UserSelection.groupingId`), which is also the key the
 * home screen builds one card per.
 *
 * Deliberately NOT on [com.stationly.core.model.UserSelection]: a selection is
 * one (line, direction) board and there are several per station, so storing
 * "this station opens by default" on it would mean the same fact written N times
 * with no rule for which copy wins when they disagree. These are properties of
 * the CARD, which is the station.
 *
 * Local-only, and intentionally not synced to the backend with the selections.
 * The order of your home screen and whether a hero is showing are statements
 * about one device, not about what the user tracks — the iPad and the phone can
 * reasonably disagree.
 *
 * There is no `pinned` here any more, and it should not come back. "Pin to top"
 * was offered as a per-station switch, which meant every station had it, and a
 * setting every item can turn on cannot express a ranking: pin all four and you
 * have said nothing. What the user actually wanted from it was ORDER, and order
 * is a property of the LIST, not of a station — see
 * [StationPrefsRepository.order], which the home settings screen edits by drag.
 */
@Serializable
data class StationPrefs(
    /**
     * Start expanded every time the app opens, not just the first time.
     *
     * Several stations may set this. The height budget then shares the viewport
     * between them (`boardMaxHeight`), so the honest outcome of opening four is
     * a page that scrolls, not four unreadable boards.
     */
    val openByDefault: Boolean = false,
    /**
     * Hide the big next-departure hero for this station, leaving the line pills
     * and the dot-matrix board.
     *
     * Worth having per-station because the hero answers "what do I run for",
     * which is a commute question. At the station you pass through twice a year
     * it is a large empty box, and the rows it costs are the ones you wanted.
     */
    val hideHero: Boolean = false,
)

/**
 * The one live copy of [StationPrefs] for the whole app, backed by a JSON blob
 * over the shared `Platform.storageManager` (NSUserDefaults on iOS) — same
 * pattern as [HomeConfigCache].
 *
 * An object with STATE rather than a pair of static read/write helpers, because
 * two screens now edit the same preferences: the home screen renders them and
 * the station settings screen changes them, and those are separate destinations
 * with separate ViewModels. Two independently-loaded copies would drift the
 * moment one wrote — the settings screen would flip a switch that the card
 * behind it never heard about.
 *
 * Reads are served from memory after the first [ensureLoaded]. Writes update
 * memory FIRST and persist after: a toggle must move under the user's finger,
 * and a failed write costs the preference at next launch, which is a far better
 * failure than a switch that appears not to respond.
 */
object StationPrefsRepository {
    private const val KEY = "station_prefs_v1"
    private const val ORDER_KEY = "station_order_v1"
    private val json = Json { ignoreUnknownKeys = true }

    private val _prefs = MutableStateFlow<Map<String, StationPrefs>>(emptyMap())
    val prefs: StateFlow<Map<String, StationPrefs>> = _prefs.asStateFlow()

    /**
     * The user's own top-to-bottom ordering of their station cards, by grouping
     * id, as set by dragging in home settings.
     *
     * A LIST, stored apart from the per-station map, because that is the shape
     * of the fact: "Victoria comes before King's Cross" is not something either
     * station knows on its own. (The pin flag this replaced tried to say it with
     * a per-station boolean, and could not — see [StationPrefs].)
     *
     * Partial by design, and treated as a preference rather than a manifest.
     * Stations missing from it keep their natural position after the ones that
     * are in it, so a station added after the last reorder appears at the bottom
     * instead of vanishing, and ids left behind by a deleted station are simply
     * ignored on read. That means this never needs pruning to stay correct —
     * see [orderedIds].
     */
    private val _order = MutableStateFlow<List<String>>(emptyList())
    val order: StateFlow<List<String>> = _order.asStateFlow()

    private var loaded = false

    /**
     * Idempotent — every screen that reads prefs may call this on entry.
     *
     * The flag is set AFTER the read, not before. Two screens can enter at once
     * (the home screen's VM and a settings VM created in the same frame), and
     * flagging first would let the second return while the first was still
     * reading, handing it an empty map as though the user had no preferences at
     * all. A duplicated read of one small NSUserDefaults string is the cheaper
     * mistake.
     */
    suspend fun ensureLoaded() {
        if (loaded) return
        _prefs.value = runCatching {
            Platform.storageManager.loadString(KEY)
                ?.let { json.decodeFromString<Map<String, StationPrefs>>(it) }
        }.getOrNull() ?: emptyMap()
        _order.value = runCatching {
            Platform.storageManager.loadString(ORDER_KEY)
                ?.let { json.decodeFromString<List<String>>(it) }
        }.getOrNull() ?: emptyList()
        loaded = true
    }

    /**
     * Apply the user's ordering to the stations that actually exist right now.
     *
     * The saved order leads, in its own sequence; anything it does not mention
     * follows in the caller's order. Both halves matter: without the first the
     * drag did nothing, and without the second a newly added station would be
     * invisible until the user next reordered.
     */
    fun orderedIds(ids: List<String>): List<String> {
        val saved = _order.value
        if (saved.isEmpty()) return ids
        val known = ids.toSet()
        val ranked = saved.filter { it in known }
        return ranked + ids.filterNot { it in ranked }
    }

    suspend fun setOrder(ids: List<String>) {
        _order.value = ids
        runCatching { Platform.storageManager.saveString(ORDER_KEY, json.encodeToString(ids)) }
    }

    /** Read one station's preferences, defaults included. */
    fun of(stationId: String): StationPrefs = _prefs.value[stationId] ?: StationPrefs()

    suspend fun update(stationId: String, transform: (StationPrefs) -> StationPrefs) {
        val next = _prefs.value.toMutableMap()
        next[stationId] = transform(next[stationId] ?: StationPrefs())
        persist(next)
    }

    /**
     * Forget a station entirely — call when its last board is deleted, or a
     * re-added station silently comes back with the settings of the one the user
     * removed.
     *
     * Only the preference row. Its id may stay in [order], which costs nothing:
     * [orderedIds] intersects with the stations that exist, so a stale id is
     * skipped, and if the user re-adds the station it lands back where they had
     * put it — which is the better outcome, not a leak.
     */
    suspend fun forget(stationId: String) {
        if (stationId !in _prefs.value) return
        persist(_prefs.value - stationId)
    }

    private suspend fun persist(next: Map<String, StationPrefs>) {
        _prefs.value = next
        runCatching {
            // Defaults are dropped rather than written, so untouched stations
            // never accumulate rows.
            val pruned = next.filterValues { it != StationPrefs() }
            Platform.storageManager.saveString(KEY, json.encodeToString(pruned))
        }
    }
}
