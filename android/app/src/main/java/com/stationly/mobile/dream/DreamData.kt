package com.stationly.mobile.dream

import com.stationly.core.model.LineStatus
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.Platform

/**
 * Read-only snapshot of everything the dream needs to render at a single instant.
 * Recomputed cheaply on every refresh tick — all data lives in SQL, no network.
 */
data class DreamSnapshot(
    val selection: UserSelection?,
    val predictions: List<PredictionDisplay>,
    val lineStatus: LineStatus?,
    /** Wall-clock millis at which the snapshot was loaded — drives the "X ago" timer. */
    val lastUpdatedMs: Long = System.currentTimeMillis(),
) {
    val hasData: Boolean get() = selection != null

    /** Headline that goes in the status strip. */
    val statusLine: String get() {
        val sel = selection ?: return ""
        val status = lineStatus?.statusSeverityDescription?.takeIf { it.isNotBlank() }
            ?: "Good Service"
        return "${sel.line.replaceFirstChar { it.uppercase() }} · $status"
    }

    val isDisrupted: Boolean get() {
        val s = lineStatus?.statusSeverityDescription?.lowercase()?.trim()
        return s != null && !s.startsWith("good service")
    }
}

/**
 * Build a DreamSnapshot for the given station id (or pick the first selection
 * the user has if [preferredStationId] is null or no longer exists).
 *
 * Pure SQL read — safe to call from any thread, but in practice we call from
 * a coroutine on Dispatchers.IO triggered by the refresh tick.
 */
fun loadDreamSnapshot(preferredStationId: String?): DreamSnapshot {
    val selections = Platform.sqlStorage.getAllSelections()
    val selection = selections.firstOrNull { it.station == preferredStationId }
        ?: selections.firstOrNull()
        ?: return DreamSnapshot(null, emptyList(), null)

    val predictions = Platform.sqlStorage.getPredictions(selection.station, selection.line)
    val lineStatus  = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
    return DreamSnapshot(selection, predictions, lineStatus, System.currentTimeMillis())
}
