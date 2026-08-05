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
 * "this station is pinned" on it would mean the same fact written N times with
 * no rule for which copy wins when they disagree. These are properties of the
 * CARD, which is the station.
 *
 * Local-only, and intentionally not synced to the backend with the selections.
 * Pinning and hiding a hero are statements about one device's home screen, not
 * about what the user tracks — the iPad and the phone can reasonably disagree.
 */
@Serializable
data class StationPrefs(
    /**
     * Keep this station at the TOP of the home screen.
     *
     * Pinning is ORDERING, which is what "pin" means everywhere else a user has
     * met it — pinned chats, pinned notes, pinned files, pinned emails. It moves
     * the thing to the top, keeps it there while unpinned items come and go, and
     * marks it so you can see why it is first. It is not a second, hidden
     * setting for something else.
     *
     * This deliberately replaced "opens expanded on launch", which borrowed the
     * word for a meaning nobody would guess: two pinned stations then meant two
     * boards fighting for one viewport, and unpinning a station did nothing
     * visible until the next cold start. Expansion still follows from it — the
     * home screen opens the first station, and a pinned station IS first — but
     * as a consequence of the order, not as a separate promise.
     */
    val pinned: Boolean = false,
    /**
     * Start expanded every time the app opens, not just the first time.
     *
     * Separate from [pinned] because they answer different questions — WHERE a
     * station sits and WHETHER it is already open — and a user can want either
     * without the other: the station you check twice a day belongs at the top
     * whether or not you want its board unfolded, and a station further down
     * can be worth having open when you get to it.
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
    private val json = Json { ignoreUnknownKeys = true }

    private val _prefs = MutableStateFlow<Map<String, StationPrefs>>(emptyMap())
    val prefs: StateFlow<Map<String, StationPrefs>> = _prefs.asStateFlow()

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
        loaded = true
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
     * re-added station silently comes back pinned.
     */
    suspend fun forget(stationId: String) {
        if (stationId !in _prefs.value) return
        persist(_prefs.value - stationId)
    }

    /**
     * Bulk edits from the home settings screen.
     *
     * These are one-shot ACTIONS, not toggles, and they only touch stations the
     * map already knows about PLUS whatever ids the caller passes — a station
     * with default preferences has no row here, which is why
     * [setOpenByDefaultForAll] takes the list of ids rather than mapping over
     * its own keys.
     */
    suspend fun setOpenByDefaultForAll(open: Boolean, stationIds: List<String> = emptyList()) {
        val ids = (_prefs.value.keys + stationIds).toSet()
        persist(ids.associateWith { id -> of(id).copy(openByDefault = open) })
    }

    /** Un-hide every station's hero. */
    suspend fun showHeroEverywhere() {
        persist(_prefs.value.mapValues { (_, p) -> p.copy(hideHero = false) })
    }

    /** Unpin every station. */
    suspend fun clearPins() {
        persist(_prefs.value.mapValues { (_, p) -> p.copy(pinned = false) })
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
