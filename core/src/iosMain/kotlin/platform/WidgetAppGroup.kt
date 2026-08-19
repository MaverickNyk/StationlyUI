package com.stationly.core.platform

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.user.BoardConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    /** DISPLAY names ("Hammersmith & City"), for the picker's subtitle. */
    val lines: List<String> = emptyList(),
    /**
     * Canonical line IDs ("hammersmith-city"), for anything that has to MATCH
     * rather than render.
     *
     * Added because [lines] holds display names and the two are not
     * interchangeable: the backend scopes disruption pushes by line id, so a
     * device registering "Hammersmith & City" would never match a
     * `hammersmith-city` incident and would silently receive nothing. Tube
     * lines whose display name is just the id capitalised hid this — it only
     * breaks on the ones with punctuation, which are exactly the ones people
     * notice.
     *
     * Defaulted so a board written by an older build still decodes.
     */
    val lineIds: List<String> = emptyList(),
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
    /**
     * The board's resolved allow-list, so the extension's OWN refresh applies
     * the same filter the app does.
     *
     * Without it the widget rebuilt its rows straight from the REST payload and
     * showed every departure in the direction — a board narrowed to "through
     * Bank" in the app sat beside an unfiltered widget of the same board on the
     * same home screen.
     *
     * Carried rather than re-derived because resolving a filter needs route
     * data, which the extension has no way to fetch and no business knowing.
     * Empty means no filter, exactly as it does everywhere else.
     *
     * Defaulted so a board written by an older build still decodes.
     */
    val destinationIds: List<String> = emptyList(),
    /**
     * Branch tokens a departure's own `viaKey` must be one of, alongside
     * [destinationIds]. Empty means "do not narrow by branch".
     *
     * See `stationly-backend/docs/BRANCH_VIA_KEYS.md`.
     */
    val viaKeys: List<String> = emptyList(),
)

/**
 * One block of the board — a platform on rail, a pole on bus — exactly as
 * [MultiLineBoardProcessor.Group] built it.
 *
 * ## Why the grouping is carried rather than re-derived
 * The extension used to group the flat [WidgetBoard.predictions] list by
 * `platform` itself, and that is wrong for buses in a way no amount of care in
 * Swift can fix: TfL letters stops only at multi-stop interchanges, so at an
 * ordinary hub every pole reports `platform = ""` and they all collapse into ONE
 * block with both directions interleaved. Smithwood Close tracked both ways is
 * two naptans, 7 predictions, every one with a blank platform.
 *
 * The pole identity only exists BEFORE the merge, so only KMP can group on it —
 * which is [MultiLineBoardProcessor.groupKeyFor]'s whole subject. Sending the
 * blocks means the widget renders the same board the home screen does, from the
 * same code, including the ordering rules (unassigned last, the pin, the sort)
 * that the extension has no way to know about.
 */
@Serializable
data class WidgetGroup(
    /**
     * Stable identity: the platform string on rail, the pole naptan on bus.
     *
     * This is what the extension's OWN refresh re-associates its freshly-fetched
     * rows against — it cannot call [MultiLineBoardProcessor], so matching on
     * this key is how a refreshed board keeps the blocks (and the [header]s) KMP
     * decided on. See `WidgetRefreshService.regroup`.
     */
    val key: String,
    /** "Northern Platform 1 Westbound", "Bus 39, 34 Stop N" — KMP's own text. */
    val header: String,
    /**
     * [header] and its shorter forms, widest first — the extension measures and
     * takes the first that fits.
     *
     * A pager header is the tightest text on the widget: two arrow slots and a
     * "3/6" page marker are taken out of its width before the title gets any.
     * Without the ladder the only fallbacks are scaling the type down (which
     * makes the board's loudest row its smallest) or ellipsising, which eats the
     * platform number off the right-hand end — the one thing the header exists
     * to say. Sent rather than derived for the same reason as [header]: the
     * rules are `MultiLineBoardProcessor.headerVariants` and there is one copy.
     */
    val headerVariants: List<String> = emptyList(),
    /** The backend's platform label verbatim: "Platform 8", "Stop C", "". */
    val label: String,
    /** Whether rows in this block should carry their line prefix. */
    val mixesLines: Boolean = false,
    val predictions: List<PredictionDisplay> = emptyList(),
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
    /**
     * CANONICAL line id ("hammersmith-city"), blank when the station tracks
     * several — see `IosWidgetManager.buildBoard`.
     *
     * An identity, never display text: the extension's legacy refresh path uses
     * it as the lookup key into the predictions payload. [lineDisplay] is the
     * one to render.
     */
    val lineName: String,
    /**
     * [lineName] as a person reads it ("Hammersmith & City").
     *
     * A second field rather than a nicer [lineName] because the two have
     * genuinely different jobs and one string cannot do both — the refresh needs
     * the canonical id to key on, and the fallback header needs a name. Swift
     * used to bridge the gap with `lineName.capitalized`, which is how an H&C
     * station rendered "Hammersmith-City".
     */
    val lineDisplay: String = "",
    val direction: String,
    val mode: String,
    val status: String? = null,
    /** Epoch SECONDS, matching the legacy `widget_last_updated` key. */
    val lastUpdated: Long = 0L,
    val feeds: List<WidgetFeed> = emptyList(),
    /**
     * How many departures the user asked to see under each header —
     * `BoardConfig.rowsPerPlatform`, already clamped.
     *
     * ## Why it is on the wire rather than applied before sending
     * [WidgetGroup.predictions] carries RESERVES, not display depth
     * (`MultiLineBoardProcessor.ROW_RESERVE`): the extension re-derives its
     * labels every minute from one timeline, so trimming to what fits would
     * leave it nothing to shift up as trains depart. The cap therefore has to be
     * applied on the far side, at render — which means the far side has to be
     * told what it is.
     *
     * The extension cannot read it for itself. `UserSettings` writes to
     * the app's own NSUserDefaults suite, not the App Group, so the widget has
     * never been able to see this setting — which is why it hardcoded three rows
     * and disagreed with the home board for anyone who moved the slider.
     *
     * A renderer still clamps this to what its family can physically draw: a
     * medium widget has room for three rows whatever the setting says.
     *
     * Defaulted so a board written by an older build decodes to the depth that
     * build was using — which is the same default the setting itself has, hence
     * the constant rather than a literal.
     */
    val rowCap: Int = BoardConfig.DEFAULT_ROWS_PER_PLATFORM,
    /**
     * The board as blocks — what the extension actually renders and pages
     * between. See [WidgetGroup].
     */
    val groups: List<WidgetGroup> = emptyList(),
    /**
     * [groups] flattened, in the same order — IN MEMORY ONLY.
     *
     * `@Transient` because it is derived: writing it as well as [groups] put
     * every departure on the wire TWICE, and the extension then parsed both
     * copies on every timeline build and every render — in a process iOS
     * cold-launches for a single tap. A board of five platforms went from ~6KB
     * to ~12KB for no reader.
     *
     * The property stays because `writeLegacy` needs a flat list for the
     * pre-multi-station keys, and it takes this object in memory rather than
     * reading the JSON back. So the flattening is still computed once and used
     * once; it just no longer travels.
     */
    @Transient
    val predictions: List<PredictionDisplay> = emptyList(),
)

// ─────────────────────────────────────────────────────────────────────────────
// The "nothing to show, and here is why" table
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One resolved message: a bold title and zero or more detail lines.
 *
 * Mirrors `BoardFallbackCopy` in `core/util/BoardFallback.kt`, which is where
 * the wording comes from. This exists as a separate type only because that one
 * is not `@Serializable` and does not need to be — it is read in-process by the
 * Compose boards, where nothing crosses a process boundary.
 */
@Serializable
data class WidgetFallbackCopy(
    val title: String,
    val detail: List<String> = emptyList(),
)

/**
 * Every message an empty board can print, plus the thresholds that select
 * between them. Written by `IosWidgetManager.publishFallbackCopy`, read by
 * `BoardFallbackTable` in the widget target.
 *
 * ## Why the whole table travels rather than the answer
 * WidgetKit builds an hour of timeline entries in one pass, and which message
 * is correct MOVES inside that hour: a board becomes "Live updates paused" six
 * minutes after its payload, and "Service ended for tonight" at midnight. A
 * finished string chosen at write time would be frozen onto entries that appear
 * long after it stopped being true.
 *
 * So KMP supplies the words and the boundaries; the extension owns the clock and
 * picks the row. Same division as `WidgetGroup.headerVariants`, for the same
 * reason.
 *
 * ## Keys are enum NAMES
 * [BoardFallbackKind.name], so `OFFLINE`, `LATE_NIGHT` and so on. Deliberately
 * not ordinals: a kind inserted in the middle would silently re-map every
 * message on a device whose widget had not been rebuilt.
 */
@Serializable
data class WidgetFallbackTable(
    val copy: Map<String, WidgetFallbackCopy> = emptyMap(),
    /** Minutes of age past which a board is "Live updates paused". */
    val signalLostMin: Long = 6,
    /**
     * The closed-network windows, as MINUTES PAST LOCAL MIDNIGHT.
     *
     * Not "HH:mm" and not a `LocalTime`: Swift has no `LocalTime`, and a string
     * would have to be re-parsed on the far side with its own idea of what
     * malformed means. An integer is the one shape both halves already agree
     * about, and the parsing stays on the Kotlin side next to the SDUI keys it
     * comes from.
     */
    val lateNightStartMin: Int = 0,
    val lateNightEndMin: Int = 270,
    val earlyMorningEndMin: Int = 360,
)
