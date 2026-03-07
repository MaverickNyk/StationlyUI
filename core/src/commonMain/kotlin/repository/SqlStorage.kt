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
            mode = selection.mode,
            line = selection.line,
            station = selection.station,
            stationName = selection.stationName,
            direction = selection.direction,
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
        queries.transaction {
            queries.deleteSelection(station, line)
            queries.clearPredictionsForStation(station, line)
        }
    }

    fun clearSelections() {
        queries.clearSelections()
    }

    fun savePredictions(stationId: String, lineId: String, predictions: List<PredictionDisplay>) {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        queries.transaction {
            queries.clearPredictionsForStation(stationId, lineId)
            predictions.forEach {
                queries.insertPrediction(
                    stationId = stationId,
                    lineId = lineId,
                    destination = it.destination,
                    platform = it.platform,
                    eta = it.eta,
                    isDue = if (it.isDue) 1L else 0L,
                    stopLetter = it.stopLetter,
                    timestamp = timestamp
                )
            }
        }
    }

    fun getPredictions(stationId: String, lineId: String): List<PredictionDisplay> {
        val results = queries.getPredictionsForStation(stationId, lineId).executeAsList()
        if (results.isEmpty()) return emptyList()
        
        val now = Clock.System.now().toEpochMilliseconds()
        val timestamp = results.first().timestamp
        
        // If data is older than 2 minutes, don't return it
        if (now - timestamp > 120_000) {
            return emptyList()
        }
        
        return results.map {
            PredictionDisplay(
                destination = it.destination,
                platform = it.platform,
                eta = it.eta,
                isDue = it.isDue == 1L,
                stopLetter = it.stopLetter
            )
        }
    }

    fun saveLineStatus(status: LineStatus) {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        queries.insertLineStatus(
            mode = status.mode,
            line = status.id,
            statusSeverityDescription = status.statusSeverityDescription,
            reason = status.reason,
            lastUpdatedTime = status.lastUpdatedTime,
            timestamp = timestamp
        )
    }

    fun getLineStatus(mode: String, line: String): LineStatus? {
        return queries.getLineStatus(mode, line).executeAsOneOrNull()?.let {
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
        queries.clearPredictionsForStation(stationId, lineId)
    }

    fun clearLineStatuses() {
        queries.clearLineStatuses()
    }
}
