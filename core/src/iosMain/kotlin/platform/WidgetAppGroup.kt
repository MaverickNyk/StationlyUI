package com.stationly.core.platform

import com.stationly.core.model.PredictionDisplay
import kotlinx.serialization.Serializable

/**
 * The wire format between KMP and the WidgetKit extension for MULTI-STATION
 * widgets.
 *
 * ## Why there is a wire format at all
 * The extension is a separate process. It cannot call Kotlin and it cannot open
 * the app's SQLite file, so everything it renders has to be pushed into the App
 * Group's NSUserDefaults ahead of time. Until multi-station widgets landed that
 * was a handful of flat keys describing ONE board; a widget configured with a
 * station needs a directory of stations and a payload per station, which is more
 * structure than flat keys can carry.
 *
 * ## The rule for changing anything here
 * Every field is mirrored by hand in Swift (`AppGroupStorage.swift`). Nothing
 * checks the two agree — a renamed field decodes as absent, which the extension
 * renders as an empty board rather than failing, i.e. the silent failure mode.
 * Add fields, never rename them, and change both sides in one commit.
 */
@Serializable
data class WidgetStationRef(
    /** Grouping id (the hub) — one card on the home screen, one widget here. */
    val id: String,
    val name: String,
    /** "tube", "bus", "dlr" — the picker turns this into a roundel. */
    val mode: String,
    /** Canonical line ids, for the picker's subtitle. */
    val lines: List<String> = emptyList(),
)

/** One tracked (line, direction) at a station, as the extension needs it. */
@Serializable
data class WidgetFeed(
    /** The naptan these departures are FETCHED from — the pole, not the hub. */
    val station: String,
    val line: String,
    val direction: String,
    /**
     * [line] in short display form ("Cir.", "H&C"), resolved by KMP.
     *
     * The extension's OWN refresh rebuilds rows from the REST payload, and it
     * walks these feeds to decide which lines to keep — so this is where it
     * learns what to label them. Without it, line prefixes written by a push
     * would vanish the moment the user tapped refresh, which is precisely the
     * "board that changes shape depending on who last wrote it" that kept
     * prefixes off the widget until now.
     *
     * Same rule as [PredictionDisplay.lineShort]: resolved here so there is one
     * naming map in the project rather than one per process.
     */
    val lineShort: String = "",
)

/**
 * One station's whole board: every line and direction the user tracks there,
 * merged into one platform-grouped list of departures.
 *
 * [feeds] is what makes the extension's own refresh button work for a station
 * rather than for a single line. The predictions endpoint answers per naptan
 * with every line on it, so the extension needs to know which (line, direction)
 * pairs to keep — without that it would either show the user lines they never
 * asked for or, worse, quietly drop half the board it is meant to be refreshing.
 */
@Serializable
data class WidgetBoard(
    /** Grouping id — matches [WidgetStationRef.id] and the configuration. */
    val id: String,
    /** Naptan for the extension's REST refresh. See [WidgetFeed.station]. */
    val stationId: String,
    val stationName: String,
    /** Blank when the station tracks several lines — see `IosWidgetManager.buildBoard`. */
    val lineName: String,
    val direction: String,
    val mode: String,
    val status: String? = null,
    /** Epoch SECONDS, matching the legacy `widget_last_updated` key. */
    val lastUpdated: Long = 0L,
    val feeds: List<WidgetFeed> = emptyList(),
    val predictions: List<PredictionDisplay> = emptyList(),
)
