package com.stationly.core.repository

import com.stationly.db.StationlyDatabase
import com.stationly.core.model.UserSelection
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
            stationName = selection.stationName,
            direction = selection.direction.lowercase(),
            destinations = selection.destinations.joinToString(","),
            destinationIds = selection.destinationIds.joinToString(",")
        )
    }

    fun getAllSelections(): List<UserSelection> {
        return queries.selectAllSelections().executeAsList().map {
            UserSelection(
                mode = it.mode,
                line = it.line,
                station = it.station,
                stationName = it.stationName,
                direction = it.direction,
                destinations = it.destinations.split(",").filter { s -> s.isNotEmpty() },
                destinationIds = it.destinationIds.split(",").filter { s -> s.isNotEmpty() }
            )
        }
    }
    
    fun deleteSelection(station: String, line: String) {
        val normalizedLine = line.lowercase()
        queries.transaction {
            queries.deleteSelection(station, normalizedLine)
            queries.clearPredictionsForStation(station, normalizedLine)
        }
    }

    fun clearSelections() {
        queries.clearSelections()
    }

    fun savePredictions(stationId: String, lineId: String, predictions: List<PredictionDisplay>) {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val normalizedLineId = lineId.lowercase()
        queries.transaction {
            queries.clearPredictionsForStation(stationId, normalizedLineId)
            predictions.forEach {
                queries.insertPrediction(
                    stationId = stationId,
                    lineId = normalizedLineId,
                    destination = it.destination,
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

    fun getPredictions(stationId: String, lineId: String): List<PredictionDisplay> {
        val results = queries.getPredictionsForStation(stationId, lineId.lowercase()).executeAsList()
        if (results.isEmpty()) return emptyList()

        val now = Clock.System.now().toEpochMilliseconds()
        val timestamp = results.first().timestamp

        // Drop stale data. Widened from 2 min → 8 min now that the UI
        // self-ticks per-minute via targetEpochMs — between FCM pushes
        // we want the rows to keep counting down instead of disappearing.
        // 8 min covers a comfortable buffer for the longest "X min" rows
        // we'd want to show; past that, the data is genuinely stale.
        if (now - timestamp > 8 * 60 * 1000) {
            return emptyList()
        }

        return results.map {
            PredictionDisplay(
                destination = it.destination,
                platform = it.platform,
                eta = it.eta,
                isDue = it.isDue == 1L,
                stopLetter = it.stopLetter,
                targetEpochMs = it.targetEpochMs,
            )
        }
    }

    fun hasPredictionsInDatabase(stationId: String, lineId: String): Boolean {
        return queries.getPredictionsForStation(stationId, lineId.lowercase()).executeAsList().isNotEmpty()
    }

    /**
     * Wall-clock millis (epoch) when the most recent prediction row for
     * this station+line was persisted to SQL. Returns null if there are
     * no rows. Used to drive the "X ago" timer honestly — that label is
     * supposed to mean "time since the last FCM / REST sync gave us
     * fresh data", and this is the only value that knows that.
     */
    fun getPredictionsTimestamp(stationId: String, lineId: String): Long? {
        return queries.getPredictionsTimestamp(stationId, lineId.lowercase())
            .executeAsOneOrNull()
            ?.lastTimestamp
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

    fun clearPredictions(stationId: String, lineId: String) {
        queries.clearPredictionsForStation(stationId, lineId.lowercase())
    }

    fun clearLineStatuses() {
        queries.clearLineStatuses()
    }

    fun clearAllData() {
        queries.transaction {
            queries.clearSelections()
            queries.clearAllPredictions()
            queries.clearLineStatuses()
        }
    }
}
