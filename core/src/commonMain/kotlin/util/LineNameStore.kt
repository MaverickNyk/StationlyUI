package com.stationly.core.util

import com.stationly.core.platform.Platform
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Short line labels as served by the BACKEND, cached locally.
 *
 * ## Why this exists
 * [LineShortNames] documents itself as a stopgap: a hardcoded client map of
 * line abbreviations, in an app where naming is backend-owned everywhere else
 * (platform labels, status text, mode names all come down from the API). It
 * drifts — the whole Overground fleet was renamed in 2024 and every client copy
 * had to be found and shipped — and there were two copies of it, one per
 * platform, plus a third shape of the same idea in the widget.
 *
 * The lines API now carries `shortName` per line. This is where the answers it
 * gives are kept, so that [LineShortNames.shortName] can prefer them.
 *
 * ## Why a store rather than "just read it off the payload"
 * The board asks for a short name at RENDER time, for a line the user chose
 * days ago, on a screen that fetches departures and nothing else. The only
 * payload carrying `shortName` is the line list, and that is fetched in the
 * selection flow. So the answer has to outlive the request that brought it —
 * which means remembering it, keyed by line id, across launches.
 *
 * ## Why it cannot regress anything
 * Three properties, and all three matter:
 *  - **Reads are synchronous and in-memory.** [shortNameOrNull] is called from
 *    the same code paths that render a row, which cannot suspend.
 *  - **An empty store is a legal state.** Before the first line fetch, after a
 *    reinstall, or against a backend that does not serve the field, this answers
 *    null and [LineShortNames] falls through to exactly the behaviour it has
 *    today. The feature degrades to the status quo rather than to a blank label.
 *  - **It only ever ADDS.** [remember] merges; a fetch that returns three lines
 *    cannot forget the other twelve the user tracks.
 *
 * Common code on purpose: this is `core/commonMain`, so iOS and Android share
 * one cache, one key and one precedence rule rather than growing a third and
 * fourth copy of the map this is meant to retire.
 */
object LineNameStore {

    /**
     * Versioned so a future change to the stored shape is a new key rather than
     * a decode failure on everyone's device — the same convention as
     * `station_prefs_v1`.
     */
    private const val KEY = "line_short_names_v1"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    /**
     * Canonical line id → short name. Replaced wholesale rather than mutated, so
     * a reader on another thread always sees a complete map: [shortNameOrNull]
     * runs on whichever thread is rendering, and a half-updated map is the one
     * state that could show a wrong label.
     */
    private var names: Map<String, String> = emptyMap()

    private var loaded = false

    /**
     * The backend's short name for a line, or null if we have not been told one.
     *
     * Null is a normal answer, not a failure — bus routes have no entry and
     * never will ("39" is already the shortest true label), and every line has
     * none until the first fetch that mentions it.
     */
    fun shortNameOrNull(lineId: String?): String? {
        val key = lineId?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return null
        return names[key]
    }

    /**
     * Hydrate from disk. Idempotent, and safe to call from every screen entry.
     *
     * The flag is set AFTER the read for the same reason
     * `StationPrefsRepository.ensureLoaded` sets its own late: two callers can
     * enter in one frame, and flagging first would let the second return while
     * the first was still reading — handing it an empty map as though the
     * backend had never answered.
     */
    suspend fun ensureLoaded() {
        if (loaded) return
        names = runCatching {
            Platform.storageManager.loadString(KEY)?.let { json.decodeFromString(serializer, it) }
        }.getOrNull().orEmpty()
        loaded = true
    }

    /**
     * Record what a line payload just told us, and persist it.
     *
     * Merged over what is already known, never replacing it: the lines endpoint
     * answers for ONE mode, so a user who tracks a tube line and a bus would
     * otherwise lose the tube's short name the moment they browsed bus routes.
     *
     * Blank ids and blank names are dropped rather than stored. An empty string
     * would be indistinguishable from a real short name at read time and would
     * suppress the fallback chain — i.e. a board row with no line label at all,
     * which is strictly worse than the long name this feature exists to shorten.
     *
     * A failed write costs the cache until the next fetch, which is why memory
     * is updated first: the labels on screen must not wait on the disk.
     */
    suspend fun remember(shortNames: Map<String, String>) {
        val clean = shortNames
            .mapKeys { (id, _) -> id.trim().lowercase() }
            .filter { (id, short) -> id.isNotEmpty() && short.isNotBlank() }
        if (clean.isEmpty()) return

        val merged = names + clean
        if (merged == names) return
        names = merged
        runCatching { Platform.storageManager.saveString(KEY, json.encodeToString(serializer, merged)) }
    }
}
