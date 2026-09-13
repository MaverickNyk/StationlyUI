package com.stationly.app.ui.dream

import com.stationly.core.model.LineStatus
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.repository.UserSettings
import com.stationly.core.util.MultiLineBoardProcessor
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
    /**
     * One feed per board at this station — the input `MultiLineBoardProcessor`
     * takes, exactly as the widget builds it.
     *
     * Raw and UNTICKED on purpose. The rows have to be rebuilt each minute
     * against the current clock or a screensaver left on overnight goes on
     * saying "2 min" until the next push, so [DreamBoard] ticks these and
     * groups them rather than being handed finished rows.
     */
    val feeds: List<MultiLineBoardProcessor.Feed> = emptyList(),
    /** Departures per platform block — the station's own setting, default 3. */
    val rowCap: Int = BoardConfig.DEFAULT_ROWS_PER_PLATFORM,
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
/**
 * Every board at the station the dream is pointed at.
 *
 * ## A station is not a board
 * The dream used to resolve its setting with `firstOrNull { it.station == id }`
 * and render that ONE board. A station routinely carries several — four lines
 * at an interchange, both directions of each — so the screensaver named
 * "King's Cross St. Pancras Underground Station" and drew Piccadilly, Platform
 * 6, Eastbound. Three trains and then a screen of black, with nothing on it to
 * say the other three lines existed.
 *
 * Precisely the defect the widget had, fixed the same way and for the same
 * reason: `first` on a list that has more than one answer silently discards
 * the rest, and a departure board that shows less than the user asked for
 * looks exactly like a station that is quiet.
 *
 * ## The fallbacks are deliberate
 * A [preferredStationId] naming a station the user has since deleted, or none
 * at all (the "Auto" row), falls back to the first board's WHOLE station —
 * never to that one board. The screensaver runs unattended; a panel that is
 * empty all night is worse than one showing the top station.
 *
 * ## Keyed on the naptan, not the hub
 * A bus hub's poles have different naptans and the setting stores one of them,
 * so this shows that pole. Widening to the hub would put the other side of the
 * road on a board the user chose one side of.
 */
fun dreamBoardsFor(
    selections: List<UserSelection>,
    preferredStationId: String?,
): List<UserSelection> {
    val anchor = selections.firstOrNull { it.station == preferredStationId }
        ?: selections.firstOrNull()
        ?: return emptyList()
    return selections.filter { it.station == anchor.station }
}

fun loadDreamSnapshot(preferredStationId: String?): DreamSnapshot {
    val selections = Platform.sqlStorage.getAllSelections()
    val boards = dreamBoardsFor(selections, preferredStationId)
    val selection = boards.firstOrNull()
        ?: return DreamSnapshot(null, emptyList(), null)

    // One feed per board at this station, the way the widget and the home
    // screen build them. The dream used to read the FIRST board's predictions
    // and render those alone; see dreamBoardsFor.
    val feeds = boards.map { b ->
        MultiLineBoardProcessor.Feed(
            stationId = b.station,
            line = b.line,
            direction = b.direction,
            predictions = runCatching {
                Platform.sqlStorage.getPredictions(b.station, b.line, b.direction)
            }.getOrNull().orEmpty(),
        )
    }

    // The UNION, so a station with one quiet line and one busy one reads as
    // having data. The fallback copy ("no upcoming departures") is driven off
    // this, and judging it by one board put that message over a board that had
    // trains on three other platforms.
    val predictions = feeds.flatMap { it.predictions }
    val lineStatus  = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)

    // The station's own "Show up to N per platform", default 3 — the same
    // setting the home screen and the widget obey, read the same way.
    val rowCap = UserSettings.configOf(selection.groupingId).rowCap
    // "X ago" should reflect when the data was last synced from the backend
    // (FCM landed / REST returned), NOT when this snapshot was loaded from SQL.
    val lastUpdatedMs = Platform.sqlStorage.getLastUpdatedTimestamp(selection.station, selection.line, selection.direction)
        ?: Clock.System.now().toEpochMilliseconds()
    return DreamSnapshot(selection, predictions, lineStatus, feeds, rowCap, lastUpdatedMs)
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

/**
 * How long the screensaver holds one platform before turning to the next.
 *
 * Eight seconds, and the number is a reading speed rather than a taste. A page
 * is a header and up to five departures; a glance from across a room takes two
 * to three seconds to find the one you want on it, and the eye needs to land
 * more than once because nobody watches a screensaver continuously. Much
 * shorter and the board is a slideshow you cannot read; much longer and a
 * four-platform station takes most of a minute to say everything it knows,
 * which on a surface people look at for two seconds at a time means it never
 * finishes saying it.
 *
 * Only applies when the screensaver is set to step. In scroll mode every
 * platform is already on screen and there is nothing to turn.
 */
const val DREAM_PAGE_DWELL_MS: Long = 8_000L
