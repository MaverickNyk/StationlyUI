package com.stationly.core.repository

import com.stationly.core.config.FeatureFlags
import com.stationly.core.model.BoardNotice
import com.stationly.core.model.StationNotice
import com.stationly.core.model.StationNotices
import com.stationly.core.model.UserSelection
import com.stationly.core.model.forBoard
import com.stationly.core.platform.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The station notices this phone knows of, shared by every surface.
 *
 * Kept apart from departures: those arrive several times a minute and must not
 * wipe out "closed". On disk, because a widget is drawn in a process that may
 * never have fetched anything.
 *
 * Off (see [FeatureFlags.STATION_STATUS]) it fetches nothing and answers nothing.
 */
object StationNoticeStore {

    private const val KEY = "station_notices_v1"
    /** App open, resume and pull-to-refresh land together; one answer does for all. */
    private const val MIN_GAP_MS = 20_000L

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val lock = Mutex()
    private var loaded = false
    private var askedAt = 0L
    private var askedFor: Set<String> = emptySet()

    /** When the backend last spoke about each stop, "none" included: pushes can arrive out of order. */
    private val heard = mutableMapOf<String, Long>()

    private val _all = MutableStateFlow<Map<String, StationNotice>>(emptyMap())

    /** By stop id. Includes ones that have expired: ask [forBoard]. */
    val all: StateFlow<Map<String, StationNotice>> = _all.asStateFlow()

    /** What to show on [selection]'s board, or null. Call [ensureLoaded] first in a process with no screen. */
    fun forBoard(selection: UserSelection, nowMs: Long, platforms: List<String> = emptyList()): BoardNotice? =
        forBoard(_all.value, selection, nowMs, platforms)

    fun forBoard(
        notices: Map<String, StationNotice>,
        selection: UserSelection,
        nowMs: Long,
        platforms: List<String> = emptyList(),
    ): BoardNotice? =
        if (!FeatureFlags.isOn(FeatureFlags.STATION_STATUS)) null
        else notices[selection.station]?.forBoard(selection, nowMs, platforms)

    suspend fun ensureLoaded() = lock.withLock { load() }

    private suspend fun load() {
        if (loaded) return
        val stored = runCatching {
            Platform.storageManager.loadString(KEY)?.let { json.decodeFromString(ListSerializer(StationNotice.serializer()), it) }
        }.getOrNull().orEmpty()
        _all.value = stored.filter { it.id.isNotBlank() }.associateBy { it.id }
        _all.value.values.forEach { heard[it.id] = it.at }
        loaded = true
    }

    /** A push about one stop. Returns whether anything changed. Null or unreadable changes nothing. */
    suspend fun adoptPush(raw: String?): Boolean {
        val notice = raw?.let { runCatching { json.decodeFromString(StationNotice.serializer(), it) }.getOrNull() } ?: return false
        if (notice.id.isBlank() || notice.kind.isBlank()) return false
        return write {
            if (!isNews(notice.id, notice.at)) it
            else if (notice.kind == "none") it - notice.id
            else it + (notice.id to notice)
        }
    }

    /** Adopt an answer: what it covers is replaced, what it does not is left alone. */
    suspend fun adopt(answer: StationNotices): Boolean =
        write {
            val stops = answer.known.filter { (id, at) -> isNews(id, at) }.keys
            (it - stops) + answer.notices.filter { n -> n.id in stops }.associateBy { n -> n.id }
        }

    /** Whether something said at [at] is at least as new as what we know of [id]. Remembers it. Under [lock]. */
    private fun isNews(id: String, at: Long): Boolean {
        if (at < (heard[id] ?: 0L)) return false
        heard[id] = at
        return true
    }

    /** Ask about the stops of [selections]. Never throws; a failure changes nothing. Returns whether anything changed. */
    suspend fun refresh(
        selections: List<UserSelection>,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
        fetch: suspend (List<String>) -> StationNotices,
    ): Boolean {
        if (!FeatureFlags.isOn(FeatureFlags.STATION_STATUS)) return false
        val ids = selections.map { it.station }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return false
        lock.withLock {
            if (nowMs - askedAt in 0 until MIN_GAP_MS && askedFor.containsAll(ids)) return false
            askedAt = nowMs
            askedFor = ids.toSet()
        }
        val answer = try {
            withTimeoutOrNull(8_000L) { fetch(ids) }
        } catch (e: CancellationException) {
            // Given up on, not answered: the next one asks again.
            withContext(NonCancellable) { lock.withLock { askedAt = 0L } }
            throw e
        } catch (e: Exception) {
            null
        }
        if (answer == null) {
            lock.withLock { askedAt = 0L }
            return false
        }
        return adopt(answer)
    }

    private suspend fun write(change: (Map<String, StationNotice>) -> Map<String, StationNotice>): Boolean = lock.withLock {
        load()
        val now = Clock.System.now().toEpochMilliseconds()
        val next = change(_all.value).filterValues { it.expiresAt > now }
        if (next == _all.value) return@withLock false
        _all.value = next
        runCatching { Platform.storageManager.saveString(KEY, json.encodeToString(ListSerializer(StationNotice.serializer()), next.values.toList())) }
        true
    }

    fun resetForTest() {
        _all.value = emptyMap()
        loaded = false
        heard.clear()
        askedAt = 0L
        askedFor = emptySet()
    }
}
