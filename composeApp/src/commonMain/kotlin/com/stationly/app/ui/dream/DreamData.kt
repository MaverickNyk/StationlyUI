package com.stationly.app.ui.dream

import com.stationly.core.model.LineStatus
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.Platform
import kotlinx.datetime.Clock

/**
 * Read-only snapshot of everything the dream needs to render at a single
 * instant. Recomputed cheaply on every refresh tick — all data lives in SQL,
 * no network. Port of Android `dream/DreamData.kt`.
 */
data class DreamSnapshot(
    val selection: UserSelection?,
    val predictions: List<PredictionDisplay>,
    val lineStatus: LineStatus?,
    /** Wall-clock millis at which the data was last synced — drives the "X ago" timer. */
    val lastUpdatedMs: Long = Clock.System.now().toEpochMilliseconds(),
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
 * Pure SQL read — call from a background dispatcher.
 */
fun loadDreamSnapshot(preferredStationId: String?): DreamSnapshot {
    val selections = Platform.sqlStorage.getAllSelections()
    val selection = selections.firstOrNull { it.station == preferredStationId }
        ?: selections.firstOrNull()
        ?: return DreamSnapshot(null, emptyList(), null)

    val predictions = Platform.sqlStorage.getPredictions(selection.station, selection.line)
    val lineStatus  = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
    // "X ago" should reflect when the data was last synced from the backend
    // (FCM landed / REST returned), NOT when this snapshot was loaded from SQL.
    val lastUpdatedMs = Platform.sqlStorage.getLastUpdatedTimestamp(selection.station, selection.line)
        ?: Clock.System.now().toEpochMilliseconds()
    return DreamSnapshot(selection, predictions, lineStatus, lastUpdatedMs)
}
