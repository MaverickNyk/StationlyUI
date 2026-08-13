package com.stationly.core.repository

import com.stationly.db.StationlyDatabase
import com.stationly.core.model.UserSelection
import com.stationly.core.model.FilterMode
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.LineStatus
import kotlinx.datetime.Clock

class SqlStorage(private val database: StationlyDatabase) {
    private val queries = database.stationlyDatabaseQueries

    fun saveSelection(selection: UserSelection) {
        queries.insertSelection(
            mode = selection.mode.lowercase(),
            line = selection.line.lowercase(),
            station = selection.station,
            parentStationId = selection.parentStationId,
            stationName = selection.stationName,
            direction = selection.direction.lowercase(),
            destinations = selection.destinations.joinToString(","),
            destinationIds = selection.destinationIds.joinToString(","),
            filterMode = selection.filterMode.name,
            // CSV like `destinations`/`destinationIds` above — a junction line
            // can have several via stops picked, and this keeps the schema flat
            // rather than adding a join table for at most a handful of ids.
            viaStationId = selection.viaStationIds.joinToString(","),
            viaStationName = selection.viaStationNames.joinToString(","),
            routeResolvedAt = selection.routeResolvedAt,
        )
    }

    fun getAllSelections(): List<UserSelection> {
        return queries.selectAllSelections().executeAsList().map {
            UserSelection(
                mode = it.mode,
                line = it.line,
                station = it.station,
                parentStationId = it.parentStationId,
                stationName = it.stationName,
                direction = it.direction,
                destinations = it.destinations.splitCsv(),
                destinationIds = it.destinationIds.splitCsv(),
                filterMode = FilterMode.fromStorage(it.filterMode),
                viaStationIds = it.viaStationId.orEmpty().splitCsv(),
                viaStationNames = it.viaStationName.orEmpty().splitCsv(),
                routeResolvedAt = it.routeResolvedAt,
            )
        }
    }

    private fun String.splitCsv(): List<String> =
        if (isEmpty()) emptyList() else split(",").filter { it.isNotEmpty() }
    
    /**
     * Remove ONE tracked board: a (station, line, direction) triple.
     *
     * Direction-scoped because a user can track both directions of the same
     * line at a station — deleting "Piccadilly westbound" must not take
     * "Piccadilly eastbound" with it.
     */
    fun deleteSelection(station: String, line: String, direction: String) {
        val normalizedLine = line.lowercase()
        val normalizedDirection = direction.lowercase()
        queries.transaction {
            // Both normalized: saveSelection stores `direction.lowercase()`, so
            // matching on the raw value silently deletes nothing and orphans the
            // row — the board's predictions go but the selection stays.
            queries.deleteSelection(station, normalizedLine, normalizedDirection)
            queries.clearPredictionsForStation(station, normalizedLine, normalizedDirection)
        }
    }

    fun clearSelections() {
        queries.clearSelections()
    }

    fun savePredictions(
        stationId: String,
        lineId: String,
        direction: String,
        predictions: List<PredictionDisplay>,
        // Caller may supply the sync instant so the prediction rows and the
        // SyncStatusEntity stamp share ONE timestamp — keeping the "X ago"
        // value byte-identical whether a surface reads the sync stamp or
        // (legacy fallback) the newest row. Defaults to now for ad-hoc saves.
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        val normalizedLineId = lineId.lowercase()
        val normalizedDirection = direction.lowercase()
        queries.transaction {
            queries.clearPredictionsForStation(stationId, normalizedLineId, normalizedDirection)
            predictions.forEach {
                queries.insertPrediction(
                    stationId = stationId,
                    lineId = normalizedLineId,
                    direction = normalizedDirection,
                    destination = it.destination,
                    destId = it.destId,
                    matchesFilter = if (it.matchesFilter) 1L else 0L,
                    platform = it.platform,
                    eta = it.eta,
                    isDue = if (it.isDue) 1L else 0L,
                    stopLetter = it.stopLetter,
                    timestamp = timestamp,
                    targetEpochMs = it.targetEpochMs,
                )
            }
        }
    }

    /**
     * Departures for one board, filter applied.
     *
     * The filter is applied in SQL off the precomputed `matchesFilter` flag and
     * the rows come back sorted, so callers do NO filtering and NO sorting —
     * this is read on every recomposition and every one-second countdown tick.
     *
     * FAILS OPEN. If the filter currently matches nothing, the unfiltered list
     * is returned instead: showing an extra train costs the user a glance,
     * hiding the one they needed costs them the journey. The board detects the
     * fallback from the rows themselves — every row having matchesFilter=false —
     * so it can caption the case without a second query.
     */
    fun getPredictions(stationId: String, lineId: String, direction: String): List<PredictionDisplay> {
        val normalizedLine = lineId.lowercase()
        val normalizedDir = direction.lowercase()
        val results = queries.getPredictionsForStation(stationId, normalizedLine, normalizedDir)
            .executeAsList()
            .ifEmpty {
                queries.getAllPredictionsForStation(stationId, normalizedLine, normalizedDir)
                    .executeAsList()
            }
        if (results.isEmpty()) return emptyList()

        val now = Clock.System.now().toEpochMilliseconds()
        val timestamp = results.first().timestamp

        // Drop stale data — see [PAYLOAD_TTL_MS] for why the number is what it is.
        if (now - timestamp > PAYLOAD_TTL_MS) {
            return emptyList()
        }

        return results.map {
            PredictionDisplay(
                destination = it.destination,
                platform = it.platform,
                eta = it.eta,
                isDue = it.isDue == 1L,
                stopLetter = it.stopLetter,
                destId = it.destId,
                matchesFilter = it.matchesFilter == 1L,
                targetEpochMs = it.targetEpochMs,
            )
        }
    }

    /**
     * Re-apply a changed filter to rows already on device, so toggling a filter
     * takes effect immediately and offline rather than at the next stream frame.
     */
    fun reapplyFilter(
        stationId: String,
        lineId: String,
        direction: String,
        allowedDestIds: Set<String>,
    ) {
        val normalizedLine = lineId.lowercase()
        val normalizedDir = direction.lowercase()
        queries.transaction {
            val rows = queries.getAllPredictionsForStation(stationId, normalizedLine, normalizedDir).executeAsList()
            rows.forEach { row ->
                queries.insertPrediction(
                    stationId = row.stationId,
                    lineId = row.lineId,
                    direction = row.direction,
                    destination = row.destination,
                    destId = row.destId,
                    matchesFilter = if (matchesFilter(row.destId, allowedDestIds)) 1L else 0L,
                    platform = row.platform,
                    eta = row.eta,
                    isDue = row.isDue,
                    stopLetter = row.stopLetter,
                    timestamp = row.timestamp,
                    targetEpochMs = row.targetEpochMs,
                )
            }
        }
    }

    fun hasPredictionsInDatabase(stationId: String, lineId: String, direction: String): Boolean {
        // Deliberately unfiltered: this answers "do we hold data for this
        // board", which drives fetch/refresh decisions. An over-restrictive
        // filter must not make a populated board look empty and trigger a
        // pointless re-fetch.
        return queries.getAllPredictionsForStation(stationId, lineId.lowercase(), direction.lowercase())
            .executeAsList().isNotEmpty()
    }

    companion object {
        /**
         * How long a stored payload stays readable before the board treats it as
         * gone entirely.
         *
         * ## Sized to TfL's own prediction horizon, not to a guess
         * Measured against the live feed: the furthest arrival TfL returns is
         * consistently **~25 minutes** out, and the same 24.5–24.8 minute ceiling
         * appears across unrelated lines and stations. So a payload eight minutes
         * old — the previous value — still describes trains up to seventeen
         * minutes in the future, and discarding it threw away usable predictions
         * to show an empty board. Underground, where signal is patchy and this
         * path matters most, that is precisely backwards: the user is handed
         * "Signal lost" while the device holds a perfectly good answer.
         *
         * Thirty minutes covers the feed's whole horizon with a little slack, so
         * nothing is dropped while it still describes a future train.
         *
         * ## Why this can be generous now, when 8 could not
         * It is no longer the only thing saying "this is old". A payload past its
         * usefulness degrades on its own: every row crosses its target and
         * [com.stationly.core.util.BoardTicker] relabels it "Gone", so a board
         * nobody has refreshed becomes visibly spent without a cutoff, and the
         * footer's "X ago" has been amber then red long before. The cutoff is now
         * the backstop rather than the mechanism.
         *
         * It is NOT removed, and should not be. Without it the app would go on
         * confidently counting down a train TfL cancelled an hour ago, with
         * nothing left on the board to suggest otherwise.
         */
        const val PAYLOAD_TTL_MS: Long = 30L * 60 * 1000

        /**
         * The single runtime filter check, shared by ingest and re-apply so the
         * two can never disagree about what a board shows.
         *
         * FAILS OPEN twice over: an empty allow-list means "no filter", and a
         * departure with no usable destId is shown rather than hidden. TfL sends
         * destinations like "Check Front of Train" and depot moves that map to
         * no station at all — those must never silently disappear from a board.
         */
        fun matchesFilter(destId: String?, allowedDestIds: Set<String>): Boolean =
            allowedDestIds.isEmpty() || destId.isNullOrBlank() || destId in allowedDestIds
    }

    /**
     * Wall-clock millis (epoch) when the most recent prediction row for
     * this station+line was persisted to SQL. Returns null if there are
     * no rows. Used to drive the "X ago" timer honestly — that label is
     * supposed to mean "time since the last FCM / REST sync gave us
     * fresh data", and this is the only value that knows that.
     */
    fun getPredictionsTimestamp(stationId: String, lineId: String, direction: String): Long? {
        return queries.getPredictionsTimestamp(stationId, lineId.lowercase(), direction.lowercase())
            .executeAsOneOrNull()
            ?.lastTimestamp
    }

    /**
     * Record that a backend payload was just processed for this board —
     * called on EVERY sync (FCM or REST), regardless of whether it carried
     * any predictions. This is what lets a 0-row update still reset the
     * "X ago" timer. See [SyncStatusEntity] and [getLastUpdatedTimestamp].
     */
    fun saveSyncTimestamp(
        stationId: String,
        lineId: String,
        direction: String,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        queries.upsertSyncStatus(stationId, lineId.lowercase(), direction.lowercase(), timestamp)
    }

    /**
     * Wall-clock millis of the last backend sync for this board, or null if
     * we've never synced it. Unlike [getPredictionsTimestamp] this is present
     * even when the last sync returned zero rows.
     */
    fun getSyncTimestamp(stationId: String, lineId: String, direction: String): Long? {
        return queries.getSyncStatus(stationId, lineId.lowercase(), direction.lowercase())
            .executeAsOneOrNull()
    }

    /**
     * The honest "last updated from backend" time that drives the "X ago"
     * timer on every surface (home / widget / dream). Prefers the dedicated
     * sync timestamp (survives 0-row updates); falls back to the newest
     * prediction-row timestamp for boards last persisted before sync
     * tracking existed. Null only when we have neither.
     */
    fun getLastUpdatedTimestamp(stationId: String, lineId: String, direction: String): Long? {
        return getSyncTimestamp(stationId, lineId, direction)
            ?: getPredictionsTimestamp(stationId, lineId, direction)
    }

    fun saveLineStatus(status: LineStatus) {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        queries.insertLineStatus(
            mode = status.mode.lowercase(),
            line = status.id.lowercase(),
            statusSeverityDescription = status.statusSeverityDescription,
            reason = status.reason,
            lastUpdatedTime = status.lastUpdatedTime,
            timestamp = timestamp
        )
    }

    fun getLineStatus(mode: String, line: String): LineStatus? {
        return queries.getLineStatus(mode.lowercase(), line.lowercase()).executeAsOneOrNull()?.let {
            LineStatus(
                id = it.line,
                name = "", // Name is usually not used from status object for display
                mode = it.mode,
                statusSeverityDescription = it.statusSeverityDescription,
                reason = it.reason,
                lastUpdatedTime = it.lastUpdatedTime
            )
        }
    }

    fun clearAllPredictions() {
        queries.clearAllPredictions()
    }

    /** Clear one direction's board. */
    fun clearPredictions(stationId: String, lineId: String, direction: String) {
        queries.clearPredictionsForStation(stationId, lineId.lowercase(), direction.lowercase())
    }

    /** Clear every direction of a line at a station. */
    fun clearPredictionsForLine(stationId: String, lineId: String) {
        queries.clearPredictionsForLine(stationId, lineId.lowercase())
    }

    fun clearLineStatuses() {
        queries.clearLineStatuses()
    }

    /**
     * Wipe the user's cached transport data — run at login and at logout.
     *
     * ⚠️ Names its tables explicitly, and `ActivityEventEntity` is deliberately
     * NOT among them. The activity queue has to outlive an auth change or it
     * can never report one: "logged out" is enqueued moments before this runs,
     * and a blanket wipe would delete the event as its own side effect. Adding
     * a `deleteAll` here would silently take the queue with it.
     */
    fun clearAllData() {
        queries.transaction {
            queries.clearSelections()
            queries.clearAllPredictions()
            queries.clearLineStatuses()
            queries.clearSyncStatuses()
        }
    }

    // ── Activity trail ──────────────────────────────────────────────────────
    //
    // Thin pass-throughs; the policy (queue cap, batching, when to flush) lives
    // in `ActivityLog` / `ActivityUploader`, which is also where it is testable
    // without a database.

    fun enqueueActivityEvent(id: String, uid: String, name: String, t: Long, props: String) {
        queries.enqueueActivityEvent(id = id, uid = uid, name = name, t = t, props = props)
    }

    /** Oldest-first, for [uid] plus the signed-out rows. See the query's note. */
    fun activityBatch(uid: String, limit: Int): List<ActivityRow> =
        queries.selectActivityBatch(uid, limit.toLong()).executeAsList().map {
            ActivityRow(id = it.id, name = it.name, t = it.t, props = it.props)
        }

    fun activityCount(): Long = queries.countActivityEvents().executeAsOne()

    /** Epoch millis of the oldest queued event, or null when the queue is empty. */
    fun oldestActivityEventAt(): Long? = queries.oldestActivityEvent().executeAsOne().oldest

    /** Delete an uploaded batch. One transaction so a crash cannot half-drain it. */
    fun deleteActivityEvents(ids: List<String>) {
        if (ids.isEmpty()) return
        queries.transaction { ids.forEach { queries.deleteActivityEvent(it) } }
    }

    /** Drop the [count] oldest rows — the queue cap. See `trimActivityEvents`. */
    fun trimActivityEvents(count: Int) {
        if (count <= 0) return
        queries.trimActivityEvents(count.toLong())
    }

    /** One queued event as stored. `props` is the raw JSON object string. */
    data class ActivityRow(
        val id: String,
        val name: String,
        val t: Long,
        val props: String,
    )
}
