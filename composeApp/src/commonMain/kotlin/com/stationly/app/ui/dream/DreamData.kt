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

    val predictions = Platform.sqlStorage.getPredictions(selection.station, selection.line, selection.direction)
    val lineStatus  = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
    // "X ago" should reflect when the data was last synced from the backend
    // (FCM landed / REST returned), NOT when this snapshot was loaded from SQL.
    val lastUpdatedMs = Platform.sqlStorage.getLastUpdatedTimestamp(selection.station, selection.line, selection.direction)
        ?: Clock.System.now().toEpochMilliseconds()
    return DreamSnapshot(selection, predictions, lineStatus, lastUpdatedMs)
}

/**
 * One station the screensaver can be pointed at.
 *
 * Everything a picker row needs, and nothing about any one BOARD — see
 * [dreamStationOptions] for why that distinction is the whole point.
 */
data class DreamStationOption(
    /** Exactly the value [DreamSettings.setStationId] stores. */
    val stationId: String,
    val name: String,
    val mode: String,
    /** Every line tracked here, de-duplicated, in the order they were added. */
    val lines: List<String>,
)

/**
 * The choices the screensaver's station picker can actually offer.
 *
 * ## One row per CHOICE, not one per board
 * The setting is a single station naptan, and [loadDreamSnapshot] resolves it
 * with `firstOrNull { it.station == id }`. The picker used to list
 * `getAllSelections()` raw — one row per (station, line, direction) — so at an
 * interchange it drew a row per tracked line per direction, all with the same
 * name, differing only in a subtitle.
 *
 * Every one of those rows wrote the same naptan. So they all lit up together
 * when any was tapped, and the screensaver showed whichever board sorted first
 * rather than the one the user chose. It was a picker offering a choice it had
 * no way to honour.
 *
 * Collapsing on [UserSelection.station] — the key the setting stores — makes the
 * rows and the outcomes one-to-one.
 *
 * ## Why the naptan and not the hub
 * Everywhere else in the app a "station" is the `groupingId`, the hub. Not here,
 * and deliberately: a bus hub's poles have different naptans, the naptan IS what
 * this setting stores, and `loadDreamSnapshot` matches on it. Grouping by hub
 * would offer one row whose stored value could resolve to either pole — which is
 * the same class of bug, one level up.
 */
fun dreamStationOptions(selections: List<UserSelection>): List<DreamStationOption> =
    selections
        .groupBy { it.station }
        .map { (stationId, boards) ->
            DreamStationOption(
                stationId = stationId,
                name = boards.first().stationName,
                mode = boards.first().mode,
                lines = boards.map { it.line }.distinct(),
            )
        }
