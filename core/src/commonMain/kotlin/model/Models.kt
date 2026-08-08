package com.stationly.core.model

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

/**
 * Transport mode (Tube, DLR, Overground, etc.)
 * Mirrors the MindTheTimeAndroid TransportMode data class
 */
@Serializable
data class TransportMode(
    val modeName: String,
    val displayName: String
)

/**
 * Line information
 * Mirrors the MindTheTimeAndroid LineInfo data class
 */
@Serializable
data class LineInfo(
    val id: String,
    val name: String,
    val modeName: String
)

/**
 * Station brief information with associated lines
 * Mirrors the MindTheTimeAndroid StationBrief data class
 */
@Serializable
data class StationBrief(
    val naptanId: String,
    val commonName: String,
    val lines: List<LineSummary>? = null
) {
    @Serializable
    data class LineSummary(
        val id: String,
        val name: String
    )
    
    // Helper method to safely get lines
    fun getLinesOrEmpty(): List<LineSummary> = lines ?: emptyList()
}

/**
 * Line route response with directions and destinations
 * Mirrors the MindTheTimeAndroid LineRouteResponse data class
 */
@Serializable
data class LineRouteResponse(
    val id: String,
    val name: String,
    val modeName: String,
    val directions: List<DirectionInfo>
) {
    @Serializable
    data class Destination(
        val id: String,
        val name: String
    )
    
    @Serializable
    data class DirectionInfo(
        val direction: String,
        val destinations: List<Destination>
    )
}

/**
 * User selection - saved station configuration
 * This is the core model that drives the widget and app
 * Mirrors the MindTheTimeAndroid UserSelection data class
 */
@Serializable
data class UserSelection(
    val mode: String,
    val line: String,
    /**
     * The naptan we FETCH departures from — the resolved stop for this exact
     * (line, direction), not necessarily the one the user tapped.
     *
     * On tube these usually coincide. On bus they frequently do not: every pole
     * has its own naptan, so Smithwood Close resolves route 39 inbound to
     * 490008805N but outbound to 490012211N. Storing the picked hub for both
     * would silently serve inbound departures on the outbound board.
     */
    val station: String,
    /**
     * The hub the user actually picked, and the key cards are GROUPED by.
     *
     * Separate from [station] so several poles can render as one departure
     * board. Without it, resolving per line/direction would split one bus stop
     * into a card per pole, all with the same name.
     *
     * Defaults to blank, meaning "same as [station]" — see [groupingId].
     */
    val parentStationId: String = "",
    val stationName: String,
    val direction: String,
    /**
     * Display names of the allowed destinations, for the card subtitle.
     * Index-aligned with [destinationIds] where both are populated.
     */
    val destinations: List<String>,
    /**
     * RESOLVED allow-list: the naptan ids a departure's `destId` must be one of
     * for it to appear on this board. Empty means no filtering.
     *
     * The resolution differs per [filterMode] but the runtime check does not,
     * which is the point — any filter we invent later compiles down to this one
     * list and costs the render path nothing:
     *  - [FilterMode.DESTINATIONS] → exactly the termini the user ticked.
     *  - [FilterMode.VIA] → the via station plus EVERY stop beyond it, on every
     *    branch that reaches it. Storing the downstream closure rather than the
     *    termini is what makes a short-terminating service match: a Piccadilly
     *    train showing "Northfields" genuinely does call at Green Park, and a
     *    termini-only list would wrongly hide it.
     */
    val destinationIds: List<String>,
    /** Which kind of filter produced [destinationIds]. */
    val filterMode: FilterMode = FilterMode.ALL,
    /**
     * The stops the user asked to travel through, kept alongside the resolution
     * they produced. Needed because a resolved id list goes stale — engineering
     * works and branch closures change which services reach a stop — so the
     * intent has to survive to be re-resolved without asking the user again.
     *
     * A LIST because a junction line gives a genuine multi-choice: picking both
     * the Heathrow and Uxbridge branches at Acton Town is two downstream sets
     * unioned, not one. Empty unless [filterMode] is [FilterMode.VIA].
     * Index-aligned with [viaStationNames].
     */
    val viaStationIds: List<String> = emptyList(),
    val viaStationNames: List<String> = emptyList(),
    /** When [destinationIds] was last resolved from route data, epoch millis. */
    val routeResolvedAt: Long = 0L,
) {
    /** True when this board shows every departure in its direction. */
    val isUnfiltered: Boolean
        get() = filterMode == FilterMode.ALL || destinationIds.isEmpty()

    /**
     * What this board's CARD is keyed on. Falls back to [station] for rows saved
     * before hubs existed, so old data groups exactly as it always did.
     */
    val groupingId: String
        get() = parentStationId.ifBlank { station }

    /**
     * Identity of ONE board — the (station, line, direction) triple that every
     * per-board map, list key and in-flight flag is keyed on.
     *
     * Defined here, once, because it is a property of the selection rather than
     * of any screen. It was previously rebuilt by hand at three call sites (the
     * home ViewModel's state maps, the card's Compose item key, and the section
     * model), which agreed only by coincidence: nothing connected them, and the
     * consequence of drifting is silent — two boards collapse onto one key and
     * the second one written erases the first's departures.
     *
     * Distinct from [groupingId], which keys the CARD: many boards share a card.
     */
    val boardKey: String
        get() = "${station}_${line}_$direction"
}

/**
 * How a board's departure list is narrowed.
 *
 * Serialized by [name], so entries may be added but never renamed. Unknown
 * values decode to [ALL] — an unrecognised filter must show everything rather
 * than hide trains the user needed.
 */
@Serializable
enum class FilterMode {
    /** No filtering — every departure in this direction. */
    ALL,

    /**
     * Only trains terminating at the chosen destinations. Deliberately EXACT:
     * a user who asks for Heathrow trains does not want one that turns short at
     * Northfields, even though it heads the same way.
     */
    DESTINATIONS,

    /**
     * Only trains that call at the chosen station — including services that
     * terminate short of a branch end, as long as they still reach it.
     */
    VIA;

    companion object {
        fun fromStorage(raw: String?): FilterMode =
            entries.firstOrNull { it.name == raw } ?: ALL
    }
}

/**
 * Line status information
 * Mirrors the MindTheTimeAndroid LineStatus data class
 */
@Serializable
data class LineStatus(
    val id: String,
    val name: String,
    val mode: String,
    val statusSeverityDescription: String,
    val reason: String?,
    val lastUpdatedTime: String
)

/**
 * FCM payload for real-time predictions
 * Mirrors the MindTheTimeAndroid FcmPayload data class
 */
@Serializable
data class FcmPayload(
    val lines: Map<String, LineData>,
    val id: String,
    val name: String,
    val lut: String // Last update time
)

/**
 * Line data within FCM payload
 * Mirrors the MindTheTimeAndroid LineData data class
 */
@Serializable
data class LineData(
    val id: String,
    val name: String,
    val dirs: Map<String, DirectionPredictions>
)

/**
 * Direction predictions within FCM payload
 * Mirrors the MindTheTimeAndroid DirectionPredictions data class
 */
@Serializable
data class DirectionPredictions(
    val preds: List<PredictionItem>
)

/**
 * Individual prediction item
 * Mirrors the MindTheTimeAndroid PredictionItem data class
 */
@Serializable
data class PredictionItem(
    val destId: String = "",
    val displayName: String = "",
    val platform: String = "",
    val eta: String = "",
    val stopLetter: String? = null
)

/**
 * Widget state - used for rendering widget UI
 * This is NEW for StationlyUI to standardize widget data
 */
@Serializable
data class WidgetState(
    val stationName: String,
    val lineName: String,
    val predictions: List<PredictionDisplay>,
    val status: String?,
    val lastUpdated: Long, // Unix timestamp
    // Selection direction (e.g. "eastbound"). Optional + defaulted so every
    // existing call site (Android included) is unchanged; the iOS widget uses it
    // to render the "Line: Platform N (Direction)" header like Android's board.
    val direction: String = "",
    // Transport mode (e.g. "tube"). Same optional+defaulted pattern; the iOS
    // widget tints the header roundel per mode like Android's mode_icon.
    val mode: String = ""
)

/**
 * Prediction display format for widget
 * NEW for StationlyUI - formatted for widget display
 */
@Serializable
data class PredictionDisplay(
    val destination: String,
    val platform: String,
    val eta: String, // "Due", "X min", etc. — formatted at receipt time.
    val isDue: Boolean,
    val stopLetter: String? = null,
    /**
     * Naptan id of the train's destination, straight from [PredictionItem.destId].
     *
     * Carried because destination FILTERS match on id, never on display text:
     * the route sequence calls a stop "Hammersmith (Dist&Picc Line)" where a
     * prediction says "Hammersmith", so name comparison silently fails on
     * exactly the short-terminating services a "via" filter exists to catch.
     *
     * Defaulted to empty so every existing construction site is unchanged, and
     * so a payload without it degrades to "unmatched" — which the filter treats
     * as SHOW, never hide.
     */
    val destId: String = "",
    /**
     * Whether this departure passes the board's destination/via filter.
     *
     * Computed ONCE at ingest against the selection's resolved allow-list and
     * then persisted, because the board re-reads these rows on every
     * recomposition and every one-second tick of the live countdown — the
     * filter must never be re-evaluated per render. `true` when the board has
     * no filter, so unfiltered boards behave exactly as before.
     */
    val matchesFilter: Boolean = true,
    /**
     * Absolute arrival time as epoch millis. Lets the UI tick the
     * minutes-remaining label locally between FCM pushes — at 12:25 with
     * targetEpochMs=12:30 the row reads "5 min", at 12:26 it ticks to
     * "4 min" without waiting for a new FCM. Nullable as a defensive
     * fallback: if the FCM payload's ISO timestamp fails to parse,
     * consumers fall through to the stored `eta` string instead of
     * dropping the row.
     */
    val targetEpochMs: Long? = null,
    /**
     * Which line this departure belongs to, ALREADY in short display form
     * ("Cir.", "H&C") — see [com.stationly.core.util.LineShortNames.shortName].
     *
     * ## Why a resolved label and not the canonical id
     * The only consumer is the iOS widget's wire format, and the widget
     * extension is a separate process with no line vocabulary at all. Writing
     * the id would force a second naming map into Swift, which this project has
     * already had and deleted once: it disagreed with the app's own settings
     * screen on the same station, and `LineShortNames` is documented as a
     * stopgap the backend is expected to take over — at which point one copy
     * would have been updated and one forgotten. Resolving here keeps exactly
     * one map.
     *
     * ## Why it lives on the prediction at all
     * A widget board is MERGED across every line the user tracks at a station,
     * so once the rows are in one list there is nothing else left to say which
     * line a row came from. The app's own board keeps that association in
     * `MultiLineBoardProcessor.Feed` and never needs this field.
     *
     * Empty on every non-widget path, which is every Android path — the board,
     * the dream and the app's own rendering all resolve the line from the feed
     * they queried. Defaulted so no existing construction site changes.
     */
    val lineShort: String = "",
)

/**
 * API Response wrapper
 * NEW for StationlyUI - standardizes API responses
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: String? = null
)