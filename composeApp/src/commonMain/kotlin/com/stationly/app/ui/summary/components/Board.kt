package com.stationly.app.ui.summary.components

import com.stationly.app.resources.Res
import com.stationly.app.resources.stationly_logo
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.common.pressScale
import com.stationly.app.ui.theme.TflAmber
import com.stationly.app.ui.theme.isDarkTheme
import com.stationly.core.util.BOARD_FALLBACK_ROW_COUNT
import com.stationly.core.util.BoardFallbackDefaults
import com.stationly.core.util.BoardFallbackState
import com.stationly.core.util.computeBoardFallbackState
import com.stationly.core.util.parseHHmm
import com.stationly.core.util.resolveBoardFallbackCopy
import com.stationly.app.ui.util.rememberMinuteTick
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.util.LineShortNames
import com.stationly.core.util.LineStatusRanker
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.config.BoardPolicyStore
import com.stationly.core.util.BoardTicker
import com.stationly.core.util.MultiLineBoardProcessor
import com.stationly.core.util.StationlyFormatters
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// Fixed TfL dot-matrix amber — the signage board is locked to this regardless
// of app theme (matches android `@color/tfl_amber` = #FFC819).
private val BoardAmber = Color(0xFFFFC819)

/**
 * The amber a retained already-departed row is drawn in — the widget's
 * `WidgetTheme.amberDim`, to the component. A "Gone" row on the home board and
 * the same row on the widget are the same fact and must not be two different
 * shades of it.
 */
private val BoardAmberDim = Color(0xFFB88F1A)
private val PanelBg = Color(0xFF0C0C0C)
private val ActiveRowBg = Color(0xFF161616)
private val StationlyRed = Color(0xFFE32017)
/** Status amber for the "delayed but running" pill dot — see LineStatusRanker.Tone. */
private val StatusAmber = Color(0xFFFFA000)

val TFL_LINE_COLORS = mapOf(
    "bakerloo" to Color(0xFFB36305), "central" to Color(0xFFE32017),
    "circle" to Color(0xFFFFD300), "district" to Color(0xFF00782A),
    "hammersmith-city" to Color(0xFFF3A9BB), "jubilee" to Color(0xFFA0A5A9),
    "metropolitan" to Color(0xFF9B0056), "northern" to Color(0xFF888888),
    "piccadilly" to Color(0xFF003688), "victoria" to Color(0xFF0098D4),
    "waterloo-city" to Color(0xFF95CDBA), "dlr" to Color(0xFF00A4A7),
    "elizabeth" to Color(0xFF6950A1), "lioness" to Color(0xFFE2A12B),
    "mildmay" to Color(0xFF1A6DB4), "windrush" to Color(0xFFE2231A),
    "weaver" to Color(0xFF7B2D8B), "suffragette" to Color(0xFF00843D),
    "liberty" to Color(0xFF6B717E), "tram" to Color(0xFF84B817),
    "cable-car" to Color(0xFFE21836),
)

private val TFL_LINE_COLORS_DARK = mapOf(
    "piccadilly" to Color(0xFF3B7AE0), "suffragette" to Color(0xFF1FB54E),
    "metropolitan" to Color(0xFFD14990), "weaver" to Color(0xFFB069BE),
    "mildmay" to Color(0xFF4C95D8), "district" to Color(0xFF2BB55D),
    "bakerloo" to Color(0xFFD17F2A), "elizabeth" to Color(0xFF9482D0),
)

private val TFL_LINE_COLORS_LIGHT = mapOf(
    "northern" to Color(0xFF6E6A66), "jubilee" to Color(0xFF7A7E83),
    "liberty" to Color(0xFF5A6068),
)

fun lineColorForTheme(line: String?, isDark: Boolean): Color {
    val key = line?.lowercase() ?: return TflAmber
    if (isDark) TFL_LINE_COLORS_DARK[key]?.let { return it }
    if (!isDark) TFL_LINE_COLORS_LIGHT[key]?.let { return it }
    return TFL_LINE_COLORS[key] ?: TflAmber
}

/**
 * Split a TfL "Severity: Reason" line-status string into its two parts —
 * `severity to reason`, where reason is null when there's no `:` or it's blank.
 * One parser for the several places the board needs this split (disruption
 * banner, status strip, fallback detection, legacy rows).
 */
private fun splitLineStatus(lineStatus: String?): Pair<String?, String?> {
    if (lineStatus == null) return null to null
    return if (lineStatus.contains(":")) {
        lineStatus.substringBefore(":").trim() to
            lineStatus.substringAfter(":").trim().takeIf { it.isNotBlank() }
    } else {
        lineStatus.trim() to null
    }
}

/** TfL roundel tint per transport mode (used on the dot-matrix station strip). */
private fun modeRoundelColor(mode: String): Color = when (mode.lowercase()) {
    "tube", "underground" -> Color(0xFFDC241F)
    "bus"                 -> Color(0xFFDC241F)
    "dlr"                 -> Color(0xFF00A4A7)
    "overground"          -> Color(0xFFEE7C0E)
    "elizabeth", "elizabeth-line" -> Color(0xFF6950A1)
    "tram"                -> Color(0xFF84B817)
    else                  -> Color(0xFFDC241F)
}

/**
 * One tracked (line, direction) at a station, plus everything needed to draw it.
 *
 * A station card is a list of these. Every field here used to be a separate
 * parameter on `Board` because a card WAS a single line; bundling them keeps the
 * multi-section call site readable and makes it obvious which state is
 * per-line (all of it) versus per-station (only the name and mode).
 */
data class BoardSection(
    val selection: UserSelection,
    val predictions: List<PredictionDisplay>,
    val lineStatus: String?,
    val lineStatusFailed: Boolean = false,
    val lastUpdated: Long = 0L,
) {
    /** This board's identity — see [UserSelection.boardKey], the one definition. */
    val key: String get() = selection.boardKey
}

/** Per-section values derived once per recomposition and reused by the chrome. */
/**
 * The board processor's view of these sections.
 *
 * One builder for the expanded board and the collapsed legs, so the two can
 * never disagree about which naptan a departure was fetched from — the bug this
 * exists to prevent is a collapsed leg naming a pole the open board does not
 * show.
 */
private fun List<SectionRender>.toFeeds(): List<MultiLineBoardProcessor.Feed> = map { r ->
    MultiLineBoardProcessor.Feed(
        // The resolved per-direction naptan (the POLE), not the hub —
        // `parentStationId` is what groups poles into this card.
        stationId = r.section.selection.station,
        line = r.section.selection.line,
        direction = r.section.selection.direction,
        predictions = r.ticked,
    )
}

/**
 * The same feeds built from the RAW stored departures, before anything has been
 * shed or relabelled.
 *
 * This is what [MultiLineBoardProcessor.buildGroups] must be given, and the
 * distinction from [toFeeds] is the whole point of the pipeline: grouping has to
 * see the reserves AND the trains that have just left, because the first are
 * what shift up and the second are what the "Gone" retention holds.
 * [SectionRender.ticked] is the OUTPUT of that process, not an input to it.
 */
private fun List<BoardSection>.toRawFeeds(): List<MultiLineBoardProcessor.Feed> = map { section ->
    MultiLineBoardProcessor.Feed(
        stationId = section.selection.station,
        line = section.selection.line,
        direction = section.selection.direction,
        predictions = section.predictions,
    )
}

private data class SectionRender(
    val section: BoardSection,
    /**
     * This line's LIVE departures, with the labels the board is showing.
     *
     * Read back out of the ticked blocks rather than derived here, which is what
     * makes the hero and the row beneath it the same object: a label that has
     * been through the per-block bump ("Due, Due" → "Due, 1 min") cannot be
     * recomputed from the timestamp without undoing the bump, and that is
     * exactly how the two used to contradict each other. Retained "Gone" rows
     * are excluded — they are board furniture, and the hero must never point at
     * a train that has left.
     */
    val ticked: List<PredictionDisplay>,
    val next: PredictionDisplay?,
    val fallbackState: BoardFallbackState?,
    val lineColor: Color,
)

/**
 * A station's departure card: one dot-matrix panel showing every line the user
 * tracks here, stacked in selection order — Piccadilly's rows, then Victoria's,
 * then Victoria's other direction, each under its own line header.
 *
 * Replaces the old per-line `Board`. A single-section card renders exactly as it
 * did before (same line pill, same accent, same glow); the extra chrome — the
 * per-platform headers merged across lines — only appears once there is more
 * than one section, so the common case is visually untouched.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StationBoard(
    stationName: String,
    mode: String,
    sections: List<BoardSection>,
    /**
     * Applied to the card's own root.
     *
     * Exists so a caller can attach something POSITIONAL without wrapping the
     * card in a box that would change how it sits: `SummaryScreen` hangs a
     * `BringIntoViewRequester` here to scroll a station into view when its
     * widget is tapped. The default is identity, so every existing call site
     * lays out exactly as before.
     */
    modifier: Modifier = Modifier,
    homeConfig: Map<String, String> = emptyMap(),
    isOnline: Boolean = true,
    /**
     * Hard ceiling for the whole card, measured from the real viewport by the
     * caller.
     *
     * A card must never be taller than the screen it sits on. The panel used to
     * cap its rows at a fixed 360/460dp, which is not a screen: add the header,
     * the hero row and the footer and a single card already overflowed an
     * iPhone 11, so the page itself had to scroll to reveal a board that is
     * supposed to be the thing you look at. Bounding the card instead keeps it
     * in view and pushes the overflow inside, where it belongs.
     *
     * The caller derives this from what the rest of the home screen needs — see
     * `boardMaxHeight` in SummaryScreen.
     *
     * [Dp.Unspecified] means unbounded — the old behaviour, for any caller that
     * genuinely has infinite height to give.
     */
    maxHeight: Dp = Dp.Unspecified,
    /**
     * Ceiling on the card's width, for tablets and large windows. See
     * MAX_BOARD_WIDTH in SummaryScreen for why height scales freely but width
     * does not. [Dp.Unspecified] means unbounded.
     */
    maxWidth: Dp = Dp.Unspecified,
    /**
     * Whether this station's board is open.
     *
     * Collapsed, the card is its header and nothing else — see [StationHeader].
     * The header still answers "when is my next train", so a collapsed station
     * is glanceable rather than hidden.
     */
    expanded: Boolean = true,
    /**
     * Toggles [expanded]. `null` means this caller does not offer collapsing, so
     * the header renders without a tap target.
     */
    onToggleExpanded: (() -> Unit)? = null,
    /** Marked in the header — see `BoardConfig.expanded`. */
    startsExpanded: Boolean = false,
    /**
     * Whether the hero is drawn above the board, from `BoardConfig.view`.
     *
     * A boolean here rather than the enum itself, because this composable renders
     * what it is told and has no business knowing which views are offered — that
     * rule belongs to `BoardView`, where it is one type and cannot be got wrong.
     */
    showHero: Boolean = true,
    /**
     * How this station's board is arranged: what it is ordered by, how deep each
     * platform goes, and which block leads. Set on the station's settings screen.
     *
     * Applied inside [MultiLineBoardProcessor], never here — the panel renders
     * whatever rows it is given, and the reason for every one of these rules
     * lives in `BoardConfig` where it can be tested. The default is the
     * board exactly as it was before it had settings.
     *
     * Not passed to the HERO on purpose. The hero answers "what do I run for",
     * which is the soonest train across every line tracked here; a pin says
     * which block leads the board, and letting it also re-point the hero would
     * be one setting quietly doing two jobs. The pills are how the hero is
     * switched, and they still are.
     */
    boardPrefs: BoardConfig = BoardConfig(),
    /**
     * Opens this station's settings — lines, layout, pin, delete.
     *
     * A whole screen rather than a menu on the card. Two of those settings are
     * choices worth SHOWING (the layout picker draws what it will do) and one is
     * destructive, and neither survives being squeezed into a popover over a
     * live departure board.
     */
    onOpenSettings: () -> Unit = {},
) {
    if (sections.isEmpty()) return

    val isDark = isDarkTheme()
    val isMulti = sections.size > 1

    // Self-tick ETAs each wall-clock minute so "5 min" drops to "4 min" between
    // stream frames (the 30s SummaryViewModel poll handles fresh data). No
    // refetch is involved and none is needed: every label is re-derived from the
    // absolute arrival time the backend already sent.
    val nowMin by rememberMinuteTick()

    val londonTime = remember(nowMin) {
        Instant.fromEpochMilliseconds(nowMin)
            .toLocalDateTime(TimeZone.of("Europe/London")).time
    }

    // One board is one station, and a bus stop groups by pole rather than by
    // platform. Hoisted to the top of the card because everything below now
    // needs it: the blocks, the collapsed header's legs, and the panel.
    val isBus = MultiLineBoardProcessor.isBus(mode)

    // The board rules in force, read ONCE for this composition rather than at
    // each of the four call sites below.
    //
    // Not a micro-optimisation — it is a correctness requirement. The store can
    // adopt a new policy the moment a home-config fetch returns, and these four
    // steps have to agree with each other: rows are LABELLED by the tick and
    // then FILTERED by their label. Read separately, a refresh landing between
    // the two would have the tick write "Gone" while the filter looked for
    // "Left", and every retained row would be handed to the hero as a live
    // train. One value for one pass; the next minute tick picks up the change
    // for all of them together.
    val policy = BoardPolicyStore.current

    // Footer freshness is the most recent update across the card's lines, and
    // also what gates the "Gone" retention — see BoardPolicy.retentionMinAgeMs.
    val lastUpdated = remember(sections) { sections.maxOf { it.lastUpdated } }

    // ── The board, at this minute ──
    //
    // Group FIRST (from the raw stored rows, reserves and departed trains
    // included), tick SECOND. The order is load-bearing and is argued in
    // BoardTicker: capping to what fits before shedding the departed rows leaves
    // a block with nothing to shift up, which is how this board used to empty
    // itself out between pushes while the widget kept counting down.
    //
    // Grouping is also what makes the bump correct. It used to run per
    // `platform` on a per-line list, which on a bus hub — where every unlettered
    // pole reports a blank platform — bumped two poles as though they were one
    // queue. The blocks are the queues, so the blocks are what get bumped.
    // `lastUpdated` is deliberately NOT a key: it is derived from `sections`, so
    // it can never change without `sections` changing first.
    val tickedGroups = remember(sections, isBus, boardPrefs, nowMin) {
        BoardTicker.tick(
            groups = MultiLineBoardProcessor.buildGroups(
                feeds = sections.toRawFeeds(),
                isBus = isBus,
                prefs = boardPrefs,
                rowCap = MultiLineBoardProcessor.rowReserve,
                nowMs = nowMin,
                policy = policy,
            ),
            nowMs = nowMin,
            // A board that has never updated has no age to speak of — treat it
            // as fresh so an empty first paint says "no departures" rather than
            // resurrecting rows that were never there.
            payloadAgeMs = if (lastUpdated <= 0L) 0L else (nowMin - lastUpdated).coerceAtLeast(0L),
            displayRows = boardPrefs.rowCap,
            policy = policy,
        )
    }

    // The user's depth is applied HERE, at render, and not inside the tick —
    // see BoardTicker.tick. The blocks keep their reserves so the hero below can
    // still find a line's next train even when the board has no room to draw it.
    val unifiedRows = remember(tickedGroups, boardPrefs) {
        MultiLineBoardProcessor.rowsFrom(tickedGroups, rowCap = boardPrefs.rowCap, policy = policy)
    }

    // Each line's live departures, put back where they came from — see
    // [SectionRender.ticked]. Already in arrival order per board, so the hero
    // below is just `first()`.
    val liveByBoard = remember(tickedGroups) {
        tickedGroups.asSequence()
            .flatMap { it.departures.asSequence() }
            .filterNot { BoardTicker.isGone(it.prediction, policy) }
            .groupBy { it.boardKey }
            .mapValues { (_, rows) ->
                StationlyFormatters.sortPredictions(rows.map { it.prediction })
            }
    }

    // Everything per-section, derived in ONE remember rather than a remember per
    // loop iteration: the section list is dynamic, and per-iteration remembers
    // would rebuild their slots whenever a line is added or removed.
    val rendered = remember(sections, liveByBoard, nowMin, isOnline, homeConfig, londonTime, isDark) {
        sections.map { section ->
            val ticked = liveByBoard[section.selection.boardKey].orEmpty()
            val (sev, reason) = splitLineStatus(section.lineStatus)
            SectionRender(
                section = section,
                ticked = ticked,
                // Already in arrival order — `liveByBoard` sorted it.
                next = ticked.firstOrNull(),
                // Empty-board fallback message (Offline / Live updates paused /
                // Service ended for tonight / Disrupted / Nothing departing right
                // now / …) — parity with Android's Board. Computed PER LINE, since
                // one line can have ended service for the night while another at
                // the same station is still running.
                fallbackState = computeBoardFallbackState(
                    hasPredictions = ticked.isNotEmpty(),
                    isOnline = isOnline,
                    lastUpdatedMs = section.lastUpdated,
                    nowMs = nowMin,
                    londonTime = londonTime,
                    lineStatusSeverity = sev,
                    lineStatusReason = reason,
                    signalLostMin = homeConfig["board.fallback.signalLostMin"]?.toLongOrNull()
                        ?: BoardFallbackDefaults.SIGNAL_LOST_MIN,
                    lateNightStart = parseHHmm(homeConfig["board.fallback.lateNightStart"], BoardFallbackDefaults.LATE_NIGHT_START),
                    lateNightEnd = parseHHmm(homeConfig["board.fallback.lateNightEnd"], BoardFallbackDefaults.LATE_NIGHT_END),
                    earlyMorningEnd = parseHHmm(homeConfig["board.fallback.earlyMorningEnd"], BoardFallbackDefaults.EARLY_MORNING_END),
                ),
                lineColor = lineColorForTheme(section.selection.line, isDark),
            )
        }
    }

    // Accent drives the glow and the panel border. A single-line card keeps its
    // line colour exactly as before; a multi-line card has no one line to speak
    // for it, so it falls back to the station's mode roundel tint.
    val accent = if (isMulti) modeRoundelColor(mode) else rendered.first().lineColor

    // The hero shows the soonest train across every line tracked here — that is
    // the "what do I run for" answer, which is the whole point of tracking more
    // than one line at a station.
    //
    // Compared on the absolute arrival time, never on the `eta` label. The label
    // is rounded to a minute AND deliberately bumped so two same-platform trains
    // never read the same, so comparing labels across lines could hand the hero
    // to the wrong train — the exact case `arrivalSortKey` exists for.
    val hero = remember(rendered) {
        rendered.mapNotNull { r -> r.next?.let { r to it } }
            .minByOrNull { (_, p) -> StationlyFormatters.arrivalSortKey(p) }
    }
    val heroPrediction = hero?.second

    // Which line's hero is showing. See the hero block below for why null is a
    // meaningful value rather than "nothing selected".
    //
    // Keyed on the LINE SET, not on `sections`. `sections` is rebuilt on every
    // recomposition and its contents change on every minute tick (new
    // predictions, new `lastUpdated`), so keying on it threw the user's choice
    // away roughly once a minute and snapped the hero back to the soonest line.
    // The selection should survive new data and only reset when the lines
    // themselves change — which is exactly what this key expresses.
    val trackedLines = remember(sections) { sections.map { it.selection.line }.distinct() }
    var selectedLine by remember(trackedLines) { mutableStateOf<String?>(null) }

    // When the "turn the countdown on" hint was last asked for, or 0 for never.
    // A timestamp rather than a boolean so tapping a second pill re-arms the
    // auto-dismiss instead of being ignored as "already showing".
    var heroHintShownAt by remember { mutableStateOf(0L) }
    var heroHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(heroHintShownAt) {
        if (heroHintShownAt == 0L) return@LaunchedEffect
        heroHintVisible = true
        delay(HERO_HINT_MS)
        heroHintVisible = false
    }

    // Reads what the hero DISPLAYS, not the raw timestamp, so the border and the
    // number the user is looking at can never disagree. See displayedMinutes.
    val isUrgent = heroPrediction != null &&
        StationlyFormatters.displayedMinutes(heroPrediction) <= 1

    // ── Outer column: chrome on the themed canvas, only the dot-matrix is dark ──
    //
    // Bounded, so everything below distributes within one screen: the fixed-size
    // chrome (pills, banners, hero, footer) takes what it needs and the panel
    // gets the remainder via `weight(fill = false)`. `fill = false` is the whole
    // trick — the card still SHRINKS to its content when there is little of it,
    // so a one-line board looks exactly as it always did, and only a board with
    // more rows than fit is capped.
    // A CEILING, not a fixed height.
    //
    // A short board should still render short — a stop with two departures has
    // no business occupying a full screen of empty panel. What must be stable is
    // the MAXIMUM: past it the departure rows scroll inside the panel rather
    // than growing the card, so the page never has to scroll to reveal a board.
    //
    // The height-driven flicker is addressed where it actually came from — the
    // expanding disruption banner (removed) and the content-sized hero (now
    // pinned at HERO_HEIGHT) — rather than by freezing the whole card.
    // ── The card is a CONTAINER, and it has to look like one ──
    //
    // Everything here used to sit straight on the home canvas with a 20dp gap
    // between stations and nothing else. That works for one station and fails
    // for three: a gap is not a boundary, so the eye read the page as one long
    // ribbon of pills, heroes and panels, and the only thing marking a new
    // station was its name — 17sp of text competing with a full dot-matrix board
    // directly above it.
    //
    // Three cues, because one is not enough against a board this loud:
    //   - a raised surface (#161616 against the #0A0A0A canvas) with a hairline
    //     border, so the card has literal edges;
    //   - a coloured rail across the top in the station's own accent — its line
    //     colour, or the mode roundel tint at a multi-line station. This is the
    //     one that does the work: it is the first thing the eye lands on coming
    //     down the page, it says "new station" before a word is read, and it
    //     says WHICH station by colour alone;
    //   - the dot-matrix panel now sits INSIDE something, so the board reads as
    //     this station's board rather than as the page's background.
    //
    // ── ONE clock for the whole card ──
    //
    // Everything that moves when a station opens or closes is driven from this
    // single [updateTransition]: the body, the collapsed legs, the chevron, the
    // colour rail. They used to be three independent animations that merely
    // happened to be given the same duration, which is not the same thing —
    // separate springs start on the frame each one is first composed, so the
    // chevron finished a beat before the board and the legs arrived after both,
    // and the card read as three parts reacting to the tap rather than one card
    // responding to it.
    //
    // A Transition also lets the parts differ ON PURPOSE. They share the clock;
    // the stagger inside the body (pills, then hero, then panel) is phrased
    // against it as delays, so the card unfolds top-down like a thing with a
    // hinge instead of every element fading up at once.
    val card = updateTransition(targetState = expanded, label = "station_card")

    // Full strength when the card is open, dimmed when it is closed. A collapsed
    // station is still THIS station and keeps its colour, but a page of closed
    // cards should not be a page of stripes all shouting equally — the open one
    // is where you are.
    //
    // A `State` read inside `graphicsLayer` rather than an unwrapped value:
    // deferred to the draw phase, so the animation never recomposes the card.
    val railAlpha = card.animateFloat(
        transitionSpec = { tween(if (targetState) EXPAND_MS else COLLAPSE_MS, easing = EaseInOut) },
        label = "rail",
    ) { open -> if (open) 1f else 0.62f }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // Centred once the window is wider than the board is allowed to be,
            // so a tablet gets margin rather than a stretched row.
            .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
            .then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier),
        shape = RoundedCornerShape(CARD_CORNER),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f)),
    ) {
    Column(modifier = Modifier.fillMaxWidth()) {

        // The station's colour rail. Fades out to the right rather than running
        // flat edge to edge: solid, it reads as a progress bar.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CARD_RAIL_HEIGHT)
                .graphicsLayer { alpha = railAlpha.value }
                .background(
                    Brush.horizontalGradient(
                        listOf(accent, accent.copy(alpha = 0.85f), accent.copy(alpha = 0.10f))
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 10.dp)
        ) {

        // ── Station header: roundel + name + marks + settings ──
        //
        // The station identity used to live INSIDE the dot-matrix panel, which
        // meant the two things above the panel — the line pills and the hero —
        // were unlabelled: with several stations on the page nothing said which
        // station's "3 min" you were looking at. The name now sits above
        // everything it owns, so the card reads top-down: station, then its
        // lines, then its next train, then its board.
        // Collapsed, the header is the only thing left, so it carries the answer
        // the board would have given: the soonest departure each way.
        //
        // Computed whether or not the card is open, which it did not used to be.
        // Skipping it while expanded saved nothing measurable — it is a `remember`
        // keyed on the data, so it runs when departures change, not per frame —
        // and it cost the collapse animation its content: the legs arrived as an
        // empty list on the frame the card closed, so there was nothing to
        // animate in, and on opening they vanished instantly instead of folding
        // away.
        val collapsedLegs = remember(rendered, isBus, boardPrefs) {
            MultiLineBoardProcessor.collapsedLegs(rendered.toFeeds(), isBus, boardPrefs)
        }

        StationHeader(
            stationName = stationName,
            mode = mode,
            accent = accent,
            card = card,
            expanded = expanded,
            startsExpanded = startsExpanded,
            legs = collapsedLegs,
            legColor = { line -> lineColorForTheme(line, isDark) },
            onToggleExpanded = onToggleExpanded,
            onOpenSettings = onOpenSettings,
        )

        // Everything below is the OPEN card. Collapsed, the header above is
        // the whole card — see StationHeader.
        //
        // The change used to be instantaneous: `if (expanded)` swapped a full
        // departure board for two leg rows between one frame and the next, and
        // every card below it jumped up the page by ~400dp with no motion to
        // follow. Now the card's own height carries the change, so the page
        // below it slides rather than teleports.
        //
        // Opening is slower than closing and leads with height, holding the
        // fade back slightly so the board materialises into a space that is
        // already being made for it. Closing is quicker and fades first — once
        // the user has decided to put a board away, watching it leave is not
        // interesting, and a slow collapse is the one that feels sluggish.
        card.AnimatedVisibility(
            // `fill = false` preserved from the old weight: a short board still
            // renders short, and only an over-tall one is capped.
            modifier = Modifier.weight(1f, fill = false),
            visible = { it },
            // The card's HEIGHT is the only thing this transition animates. The
            // contents fade and slide on their own delays below
            // (`animateEnterExit`), which is what turns one size change into an
            // unfolding — a single fade over the whole body reads as a panel
            // being switched on.
            enter = expandVertically(tween(EXPAND_MS, easing = EaseOutCubic)),
            exit = shrinkVertically(tween(COLLAPSE_MS, easing = EaseInCubic)),
        ) {
        // Its own column: `AnimatedVisibility`'s content is not a ColumnScope,
        // and the panel below needs `weight` to take the leftover height.
        Column(modifier = Modifier.fillMaxWidth()) {

            // One pill per tracked line. `BoxWithConstraints` is load-bearing: the
            // pills decide between full and short names by MEASURING against the row
            // they have, not by counting themselves.
            Row(
                modifier = Modifier.fillMaxWidth()
                    // FIRST beat of the unfold — see STAGGER_STEP_MS.
                    .animateEnterExit(
                        enter = fadeIn(tween(CONTENT_MS, delayMillis = STAGGER_STEP_MS)) +
                            slideInVertically(tween(CONTENT_MS, delayMillis = STAGGER_STEP_MS)) { -it / 2 },
                        exit = fadeOut(tween(CONTENT_OUT_MS, delayMillis = STAGGER_STEP_MS * 2)),
                    )
                    .padding(start = 4.dp, top = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                  val pillRowWidth = maxWidth
                  Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    // Distinct lines only — two directions on one line share a pill,
                    // otherwise "Victoria Line  Victoria Line" reads like a bug.
                    val pills = rendered.distinctBy { it.section.selection.line }
                    val activeLine = selectedLine ?: hero?.first?.section?.selection?.line
                    // MEASURE, don't guess. This was `pills.size > 2`, which shortened
                    // "Circle / District" on a device with room for both and left
                    // three narrow names ("Bank", "DLR") unshortened on one without.
                    // Short forms are a response to running out of width, so width is
                    // what has to decide.
                    val useShortName = !fullPillNamesFit(
                        pills.map { it.section.selection.line }, pillRowWidth, !isMulti
                    )
                    pills.forEach { r ->
                        val (severity, _) = splitLineStatus(r.section.lineStatus)
                        LinePill(
                            line = r.section.selection.line,
                            lineColor = r.lineColor,
                            tone = LineStatusRanker.toneOf(severity),
                            selected = r.section.selection.line == activeLine,
                            useShortName = useShortName,
                            // With several pills the word "Line" on each is noise.
                            showSuffix = !isMulti,
                            onClick = {
                                selectedLine = r.section.selection.line
                                // With the hero hidden the pills have nothing to
                                // switch, so a tap does nothing and reads as a dead
                                // control. Say what the pills are FOR instead of
                                // swallowing the tap (or disabling them — they are
                                // still the board's legend and still carry each
                                // line's status dot).
                                if (!showHero) heroHintShownAt = Clock.System.now().toEpochMilliseconds()
                            },
                        )
                    }
                  }
                }
            }

            // NO disruption banner here. It used to render one expandable
            // "Severity : Reason" card per disrupted line, above the hero.
            //
            // It was the single worst thing for home-screen stability: it appeared
            // and vanished as statuses changed, and it EXPANDED on tap, so the card
            // — and therefore the page — changed height under the user's finger
            // while they were reading it. A departure board must not move.
            //
            // Nothing is lost: the same severity and reason are on the rotating
            // status strip at the foot of the panel, and in the Network section
            // further down the home screen.

            // Next departure hero — one per line, switched by the pills above.
            //
            // `null` means "no explicit choice yet", which resolves to whichever line
            // has the soonest departure. That keeps the opening frame the same as it
            // has always been while letting a tap pin a specific line. Keyed on
            // `sections` so adding or removing a line drops a stale selection.
            val activeHeroLine = selectedLine ?: hero?.first?.section?.selection?.line
            val allHeroSections = rendered.filter { it.section.selection.line == activeHeroLine }
            // Split into two ONLY when the two directions actually say different
            // things. Both directions of a suspended line report the same closure
            // with no departures either side, and showing that twice is two copies
            // of one fact taking the width of two boards.
            //
            // Deliberately NOT "merge whenever the line is disrupted": a part
            // closure very often leaves one direction running, and that asymmetry is
            // exactly what the user needs to see. The test is whether the two halves
            // would be identical — no departures on either side AND the same status.
            val heroSections = remember(allHeroSections) {
                val bothEmpty = allHeroSections.all { it.next == null }
                val sameStatus = allHeroSections.map { it.section.lineStatus }.distinct().size == 1
                if (allHeroSections.size >= 2 && bothEmpty && sameStatus) {
                    listOf(allHeroSections.first())
                } else allHeroSections
            }
            if (showHero && heroSections.isNotEmpty()) {
                // Keyed on the SPLIT COUNT ALONE, deliberately — not on the line.
                //
                // Keying it on the line meant switching lines ran two animations over
                // the same pixels at once: this crossfade AND the per-character flip
                // inside it. Two overlapping transitions on the same glyphs is what
                // read as flickering rather than as motion.
                //
                // Now a line change is carried purely by the split-flap — which is
                // the effect that is actually meant to sell it — and this only runs
                // when the LAYOUT genuinely changes, one card to two. The height is
                // identical either way (HERO_HEIGHT), so it only has to carry the
                // split: a crossfade with a slight scale reads as one card parting
                // into two rather than two cards appearing.
                AnimatedContent(
                    // SECOND beat. The hero follows the pills by one step, so the
                    // eye is led down the card in the order it reads it.
                    modifier = Modifier.animateEnterExit(
                        enter = fadeIn(tween(CONTENT_MS, delayMillis = STAGGER_STEP_MS * 2)) +
                            slideInVertically(tween(CONTENT_MS, delayMillis = STAGGER_STEP_MS * 2)) { -it / 3 },
                        exit = fadeOut(tween(CONTENT_OUT_MS, delayMillis = STAGGER_STEP_MS)),
                    ),
                    targetState = heroSections.size.coerceAtMost(2),
                    transitionSpec = {
                        (fadeIn(tween(420, easing = EaseOutCubic)) +
                            scaleIn(initialScale = 0.96f, animationSpec = tween(420, easing = EaseOutCubic)))
                            .togetherWith(
                                fadeOut(tween(260, easing = EaseInCubic)) +
                                    scaleOut(targetScale = 0.96f, animationSpec = tween(260, easing = EaseInCubic))
                            )
                    },
                    label = "hero_split",
                ) { count ->
                    if (count >= 2) {
                        // Both directions of one line are tracked, so there are two
                        // "next departures" and neither is more correct. Split the
                        // WIDTH and keep the height: stacking them would move
                        // everything below, and picking one would silently drop a
                        // board the user explicitly asked for.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            heroSections.take(2).forEach { r ->
                                Box(modifier = Modifier.weight(1f)) {
                                    NextDepartureRow(
                                        render = r,
                                        prediction = r.next,
                                        homeConfig = homeConfig,
                                        compact = true,
                                    )
                                }
                            }
                        }
                    } else {
                        val r = heroSections.first()
                        NextDepartureRow(
                            render = r,
                            prediction = if (selectedLine == null) heroPrediction else r.next,
                            homeConfig = homeConfig,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // ── Dot-matrix board (the only dark section) with ambient glow ──
            // Takes whatever height the chrome above left over, and no more.
            //
            // The breathing glow is part of the board's identity — never gate, pin or
            // otherwise "optimise" it away while the board is VISIBLE. It reads as
            // continuously running because it is. Declared inside the expanded branch
            // only so that a COLLAPSED card, which draws no glow at all, does not keep
            // an infinite transition subscribed to the frame clock: with four stations
            // collapsed that was four animations driving nothing.
            val glowAlpha by rememberInfiniteTransition(label = "board_fx").animateFloat(
                initialValue = 0.06f, targetValue = 0.18f,
                animationSpec = infiniteRepeatable(tween(3200, easing = EaseInOut), RepeatMode.Reverse),
                label = "glow"
            )
            Box(
                modifier = Modifier.fillMaxWidth()
                    // THIRD and last beat: the board itself, arriving into a
                    // card that has already finished making room for it. It
                    // leaves first on the way out — the big dark rectangle is
                    // what the collapse is about, so it should be the thing that
                    // visibly goes.
                    .animateEnterExit(
                        enter = fadeIn(tween(CONTENT_MS, delayMillis = STAGGER_STEP_MS * 3)) +
                            slideInVertically(tween(CONTENT_MS, delayMillis = STAGGER_STEP_MS * 3)) { -it / 6 },
                        exit = fadeOut(tween(CONTENT_OUT_MS)),
                    )
                    .weight(1f, fill = false)
            ) {
                Box(
                    modifier = Modifier.matchParentSize()
                        .graphicsLayer { clip = false; scaleX = 1.18f; scaleY = 1.22f; alpha = glowAlpha }
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)), RoundedCornerShape(20.dp))
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = PanelBg,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(if (isUrgent) 1.5.dp else 1.dp, accent.copy(alpha = 0.22f))
                ) {
                    DotMatrixPanel(
                        rows = unifiedRows,
                        rendered = rendered,
                        lastUpdated = lastUpdated,
                        homeConfig = homeConfig,
                    )
                }

                // FLOATS over the panel rather than taking a row above it. Anything
                // that occupies layout space here would push the board down as it
                // appears and pull it back as it goes — under the user's finger,
                // milliseconds after they tapped. The one rule this screen never
                // breaks is that the board does not move.
                Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
                    HeroHintOverlay(
                        visible = heroHintVisible,
                        onEnable = onOpenSettings,
                    )
                }
            }

        }
        }
        }
    }
    }
}

/** Corner radius of the station card container. */
private val CARD_CORNER = 20.dp

/** The station-colour rail across the top of the card. */
private val CARD_RAIL_HEIGHT = 3.dp

/**
 * The card's open/close timing. One set of numbers for every moving part of a
 * station card — see the `updateTransition` in [StationBoard].
 *
 * Asymmetric on purpose. Opening is the interesting direction and gets room to
 * unfold; closing is a decision the user has already made, and a slow collapse
 * is the one that feels sluggish.
 */
private const val EXPAND_MS = 340
private const val COLLAPSE_MS = 240

/**
 * The gap between beats as the card unfolds: pills, then hero, then board.
 *
 * The point of the stagger is that the parts of the card are not equal — the
 * order they arrive in is the order they are read in, so the card looks like it
 * has a hinge at the top rather than like a group of elements fading up
 * together. Four steps of this plus [CONTENT_MS] lands inside [EXPAND_MS], which
 * is the constraint: a beat still animating after the card has stopped growing
 * is the thing that reads as lag.
 */
private const val STAGGER_STEP_MS = 45
private const val CONTENT_MS = 200

/** Content leaves faster than it arrives, and mostly together. */
private const val CONTENT_OUT_MS = 130

/**
 * Whether every line's FULL name fits the pill row on one line.
 *
 * Short forms exist only to survive a narrow row, so the decision belongs to
 * measurement rather than a pill count: three short names ("Bank", "DLR",
 * "Tram") fit where two long ones ("Hammersmith & City", "Metropolitan") do not.
 *
 * Wrapping is not an option — a second row of pills changes the card's height,
 * which is the thing the whole layout is arranged to prevent.
 */
@Composable
private fun fullPillNamesFit(lines: List<String>, available: Dp, withSuffix: Boolean): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(lines, available, withSuffix) {
        val style = androidx.compose.ui.text.TextStyle(
            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
        )
        // Per pill: dot (7) + gap (5) + horizontal padding (7*2) + row spacing (6).
        val chromePx = with(density) { 31.dp.toPx() }
        val total = lines.sumOf { line ->
            val label = LineShortNames.displayName(line) + if (withSuffix) " Line" else ""
            measurer.measure(label, style, maxLines = 1).size.width + chromePx.toInt()
        }
        total <= with(density) { available.toPx() }
    }
}

/** Line chip on the themed canvas above the panel. */
@Composable
private fun LinePill(
    line: String,
    lineColor: Color,
    tone: LineStatusRanker.Tone,
    selected: Boolean,
    useShortName: Boolean,
    showSuffix: Boolean,
    onClick: () -> Unit,
) {
    // The dot is the LINE's colour normally, and the status tone when the line
    // is not healthy. A green dot would be redundant with "no news is good
    // news", and it would cost the pill its line identity for no gain.
    val dotColor = when (tone) {
        LineStatusRanker.Tone.GREEN -> lineColor
        LineStatusRanker.Tone.AMBER -> StatusAmber
        LineStatusRanker.Tone.RED -> StationlyRed
    }
    Surface(
        color = lineColor.copy(alpha = if (selected) 0.30f else 0.15f),
        shape = RoundedCornerShape(5.dp),
        // Selection is carried by fill AND a border: fill alone is too subtle at
        // this size, and on a pale line colour it is nearly invisible.
        border = if (selected) BorderStroke(1.dp, lineColor.copy(alpha = 0.7f)) else null,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(
                if (useShortName) LineShortNames.shortName(line)
                else LineShortNames.displayName(line) + if (showSuffix) " Line" else "",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Keeps ONE drag inside ONE scroller.
 *
 * The departure rows scroll inside a card that is itself inside the home page's
 * scroller. By default the leftover of an inner drag chains to the outer one, so
 * reaching the last departure silently slid the whole home screen away while the
 * user was still reading — the board throwing them off it.
 *
 * The rule is ownership, decided once per gesture and never mid-drag:
 *
 *  - The gesture is the ROWS' if, at the moment it started, the rows could still
 *    move in that direction. Everything it produces stays here, including the
 *    fling, and the rows simply stop (and bounce) at their end.
 *  - Otherwise the rows never wanted it, nothing is consumed, and the page
 *    scrolls exactly as it would if the card were not scrollable at all.
 *
 * Deciding per gesture rather than per frame is the whole trick. "Consume
 * whenever the rows are at their end" would deadlock the page: at the bottom of
 * the rows every subsequent drag is also leftover, so the page could never move
 * again. Lifting the finger clears the decision ([onPostFling] runs at the end
 * of every gesture, fling or not), so the next touch is judged fresh — which is
 * why a second drag scrolls the page.
 */
@Composable
private fun boardScrollOwnership(scroll: ScrollState): NestedScrollConnection {
    return remember(scroll) {
        object : NestedScrollConnection {
            /** null = not yet decided for this gesture. */
            private var rowsOwnGesture: Boolean? = null

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (rowsOwnGesture == null && source == NestedScrollSource.UserInput) {
                    // Compose's sign convention: a negative delta scrolls the
                    // content up, i.e. forward through the list.
                    rowsOwnGesture = when {
                        available.y < 0f -> scroll.canScrollForward
                        available.y > 0f -> scroll.canScrollBackward
                        else -> null
                    }
                }
                return Offset.Zero
            }

            /** The rows' leftover, swallowed rather than passed up to the page. */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = if (rowsOwnGesture == true) available else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val owned = rowsOwnGesture == true
                // End of the gesture — the next touch decides again.
                rowsOwnGesture = null
                return if (owned) available else Velocity.Zero
            }
        }
    }
}

/** How long the hint stays up before dismissing itself. */
private const val HERO_HINT_MS = 3400L

/**
 * Why the line pills did nothing, and where to go about it.
 *
 * Shown when a pill is tapped on a station whose next-departure hero is hidden.
 * Phrased as an offer rather than an error — the user turned it off, quite
 * possibly on purpose, so this says what they are missing and points at the
 * setting instead of telling them they did something wrong.
 */
@Composable
private fun HeroHintOverlay(visible: Boolean, onEnable: () -> Unit) {
    // Its own function purely so `AnimatedVisibility` resolves to the plain
    // overload. Called inline from the card, the enclosing ColumnScope's
    // extension wins and lays the hint out IN the column instead of over the
    // panel — an explicit receiver would be the other fix, and is worse to read.
    AnimatedVisibility(
        visible = visible,
        // Springs in from slightly small rather than sliding down from above.
        // It is not arriving from anywhere — it belongs to the pill that was
        // just tapped — so growing into place reads as a response to that tap,
        // where a slide reads as a banner the system decided to show.
        enter = fadeIn(tween(160)) + scaleIn(
            initialScale = 0.92f,
            animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        ),
        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.96f, animationSpec = tween(180)),
    ) {
        HeroHint(onEnable = onEnable)
    }
}

/**
 * ## It is styled against the BOARD, not against the theme
 * This floats over the dot-matrix panel, which is near-black in every theme by
 * brand rule. It used to take `colorScheme.surface`, so in light mode it was a
 * white capsule sitting on a black board — legible, but visibly a piece of the
 * app's chrome dropped on top of the signage rather than part of it. The palette
 * here is therefore fixed and dark, like the panel it sits on.
 *
 * ## It says "Settings", because that is where it goes
 * The action used to read "Turn on" while the tap opened the station's settings
 * screen. Two words that promise an immediate switch and then navigate somewhere
 * are worse than no label: the user braces for one thing and gets another. The
 * chevron and the word now agree with the behaviour.
 *
 * The copy also said "countdown", which is not what the setting is called any
 * more — it is "Next dept. + board", and the hero itself says NEXT DEPARTURE.
 * A hint naming a control the user then cannot find is a dead end.
 */
@Composable
private fun HeroHint(onEnable: () -> Unit) {
    // The board's own signage amber, NOT the card's line colour.
    // The capsule is fixed dark (below), and a line colour is only
    // lightened for dark THEME — so in light theme this drew Piccadilly
    // #003688 on #1B1B1D at about 1.4:1, which is invisible. Amber is what
    // the panel underneath already writes in, so it is both legible by
    // construction and the colour this thing should have been all along.
    val accent = TflAmber
    Surface(
        shape = RoundedCornerShape(50),
        // Lifted a touch off the panel's own black so the capsule has an edge
        // even where the shadow falls on an equally dark row.
        color = Color(0xFF1B1B1D),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        // Deeper than a card's, because this one has to read as floating ABOVE a
        // surface it nearly matches in colour.
        shadowElevation = 12.dp,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(50))
            // The app's own press idiom. This was a bare `clickable`, which
            // brings Material's ripple — a circle spreading from the touch, and
            // the single loudest tell that a Compose app is not a native one.
            .pressScale(onClick = onEnable, scale = 0.96f),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A stopwatch rather than the accent dot that was here. A dot is
            // decoration; this one glyph says which feature is missing before
            // the sentence is read.
            Icon(
                Icons.Rounded.Timer,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Show the next departure to compare lines",
                color = Color.White.copy(alpha = 0.90f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                // Weighted and ellipsised rather than sized to its own text: a
                // capsule that wraps its content grows with the sentence, and
                // this one has to survive a narrow station card at large text
                // sizes without clipping its own action off the end.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Settings",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = accent.copy(alpha = 0.8f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** Height of a collapsed card's leg row, mirrored by `COLLAPSED_LEG_HEIGHT` in SummaryScreen. */
private val LEG_HEIGHT = 22.dp

/** Height of the nameplate row itself, mirrored by `STATION_HEADER_HEIGHT` in SummaryScreen. */
private val HEADER_HEIGHT = 44.dp

/**
 * The station's nameplate: roundel, name, settings — and, when the card is
 * collapsed, a leg per direction underneath.
 *
 * This is the card's identity. Everything below it belongs to this station, and
 * collapsed this IS the card, which is why the legs are here rather than a
 * chevron over a bare name. A collapsed station the user cannot read anything
 * from is just a hidden station.
 *
 * There is no expand/collapse chevron. The whole row is the control, which is
 * both a bigger target and the one people reach for anyway; a chevron sitting
 * next to a real button (settings) mostly reads as a second button, and it
 * competed with the station name for the width the name needed. What the card is
 * doing is legible without it: legs mean collapsed, a board means open.
 */
@Composable
private fun StationHeader(
    stationName: String,
    mode: String,
    accent: Color,
    /**
     * The card's shared open/close transition, so the chevron and the legs run
     * on the same clock as the board — see the call site.
     */
    card: androidx.compose.animation.core.Transition<Boolean>,
    expanded: Boolean,
    startsExpanded: Boolean,
    /** Empty when expanded, or when nothing is departing. */
    legs: List<MultiLineBoardProcessor.Leg>,
    legColor: (String) -> Color,
    onToggleExpanded: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onToggleExpanded != null) {
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggleExpanded)
                } else Modifier
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Real backend roundel from the App-Group ModeIconStore (same cache
            // the widget reads); drawn-roundel fallback until the first /modes
            // sync lands.
            val modeIcon = remember(mode) {
                com.stationly.app.platform.ModeIconStore.cachedIconBitmap(mode)
            }
            if (modeIcon != null) {
                Image(bitmap = modeIcon, contentDescription = null, modifier = Modifier.size(22.dp))
            } else {
                TflRoundel(modeRoundelColor(mode), 22.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                stationName,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // `weight(1f)`, filling — this row has ONE flexible child so the
                // name gets every pixel the icons do not want. It was briefly
                // `fill = false` next to a spacer that also weighted 1f, which
                // splits the free space evenly between them: a long station name
                // ellipsised at half the row with the other half left blank.
                modifier = Modifier.weight(1f),
            )
            // ── ONE chevron, carrying two facts ──
            //
            // There were two glyphs here: a chevron for the card's current state
            // and a separate badge for "this station opens itself". Side by side
            // they are two marks about the same axis, and the user has to work
            // out that the small one is a setting and the big one is a control.
            // Three different glyphs were tried for the badge and every one of
            // them read as a second chevron or as something else entirely
            // (`OpenInFull` is iOS's fullscreen glyph and promised a fullscreen
            // board).
            //
            // The two facts are not equals, and drawing them as equals was the
            // mistake. Which way the card is open right now is a STATE, and the
            // chevron's rotation has always said it. Whether it opens itself is
            // a MARK ON that state, so it is drawn as one: the same chevron,
            // filled in behind.
            //
            // The fill is the app's accent, at the same weight the station
            // settings screen puts behind the selected "Expanded" segment. That
            // rhyme is the whole point — the disc on the card and the highlighted
            // option on the settings screen are one fact in two places, and now
            // they are literally the same colour doing the same job.
            //
            // Accent, NOT the card's line colour: `accent` varies per card
            // (Victoria blue here, Central red there), which made one shared
            // meaning look like several different marks.
            if (onToggleExpanded != null) {
                val chevronTurn = card.animateFloat(
                    transitionSpec = {
                        tween(if (targetState) EXPAND_MS else COLLAPSE_MS, easing = EaseInOut)
                    },
                    label = "chevron",
                ) { open -> if (open) 180f else 0f }
                // Animated, because this is set on ANOTHER screen: without it the
                // disc is simply present on the frame the user navigates back,
                // and the change they just made is something they have to spot
                // rather than something they saw happen.
                val marked by animateFloatAsState(
                    targetValue = if (startsExpanded) 1f else 0f,
                    animationSpec = tween(260, easing = EaseInOut),
                    label = "starts_expanded_mark",
                )
                val primary = MaterialTheme.colorScheme.primary
                val plain = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .drawBehind {
                            if (marked > 0.01f) {
                                drawCircle(color = primary.copy(alpha = 0.16f * marked))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.ExpandMore,
                        buildString {
                            append(if (expanded) "Collapse $stationName" else "Expand $stationName")
                            // The disc is a visual mark and nothing else says it
                            // out loud, so VoiceOver gets the sentence.
                            if (startsExpanded) append(". Opens expanded by default")
                        },
                        tint = lerp(plain, primary, marked),
                        modifier = Modifier.size(20.dp)
                            .graphicsLayer { rotationZ = chevronTurn.value },
                    )
                }
                Spacer(Modifier.width(2.dp))
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.Tune,
                    "$stationName settings",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        // ── Collapsed legs: one per direction ──
        //
        // Driven by [expanded] rather than by the list being empty, so the legs
        // have something to animate OUT to. Fed a list that survives the state
        // change (see the call site) — animating on emptiness would play the
        // exit over no content at all, i.e. nothing.
        card.AnimatedVisibility(
            visible = { !it && legs.isNotEmpty() },
            // The legs and the board are the two halves of the same swap, so
            // they run on the one clock and each takes the OTHER's duration:
            // the legs appear over a collapse (COLLAPSE_MS) and leave over an
            // expand (EXPAND_MS). Given their own timings they crossed the
            // board mid-flight and the card briefly showed both.
            enter = fadeIn(tween(COLLAPSE_MS, delayMillis = STAGGER_STEP_MS)) +
                expandVertically(tween(COLLAPSE_MS, easing = EaseOutCubic)),
            exit = fadeOut(tween(CONTENT_OUT_MS)) +
                shrinkVertically(tween(EXPAND_MS, easing = EaseInCubic)),
        ) {
        Column {
        legs.forEach { leg ->
            Row(
                modifier = Modifier.fillMaxWidth().height(LEG_HEIGHT).padding(start = 6.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(6.dp).background(legColor(leg.line), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    // Where first, then where it goes. Standing at a station the
                    // platform is the actionable half — it is what you walk to —
                    // and the destination is how you confirm it is your train.
                    listOf(leg.where, leg.towards).filter { it.isNotBlank() }.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    leg.eta,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DotMatrixPanel(
    /**
     * The board's rows, already grouped, ticked and capped by the card — see the
     * pipeline in [StationBoard].
     *
     * Built there rather than here because the hero has to be picked out of the
     * SAME blocks, and a panel that re-derived its own would be a second answer
     * to "what does this board say".
     */
    rows: List<MultiLineBoardProcessor.Row>,
    rendered: List<SectionRender>,
    lastUpdated: Long,
    homeConfig: Map<String, String>,
) {
    // Board-wide fallback: only when the board has no rows AT ALL.
    //
    // Keyed on the rows rather than on "every line has a live departure", which
    // is what it used to test. Those differ now that a block can hold retained
    // "Gone" rows: a station whose trains have all left still has a board, and
    // that board — dimmed, saying Gone — is the honest thing to show, because it
    // says both what the last departures were and that nothing has updated
    // since. The fallback copy takes over only when there is genuinely nothing
    // to draw, which is the fresh-and-empty case it was written for (offline,
    // service ended for the night, a suspended line), and the 8-minute staleness
    // cutoff in `SqlStorage.getPredictions` guarantees a held board eventually
    // becomes one.
    val boardFallback = remember(rows, rendered) {
        if (rows.isEmpty() && rendered.isNotEmpty()) {
            rendered.firstNotNullOfOrNull { it.fallbackState }
        } else null
    }

    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {

        // NO station strip here. The roundel and station name moved OUT of the
        // panel and onto the card header (StationHeader), where they can label
        // the pills and the hero as well as the board. Repeating the name here
        // would be the same fact twice, 8dp apart, and it cost the panel a row
        // of departures to say it.

        // Rows scroll independently — the clock footer
        // (below) stay pinned, matching the Android board's ScrollView so a
        // many-platform station (Bank, King's Cross) never clips departures off
        // the bottom of the panel.
        //
        // `weight(fill = false)` rather than a fixed `heightIn`: the rows take
        // the space the card has left after its pinned chrome, whatever that
        // turns out to be on this device. A hard 360/460dp could not know what
        // else was on screen with it, so on a small phone the card overflowed
        // and the PAGE scrolled — which is
        // the one thing a departure board must not do. `fill = false` keeps a
        // short board short instead of padding it out to the cap.
        //
        // The rows are a scroller nested inside the home screen's scroller, and
        // the two must not run in ONE gesture.
        //
        // Chaining was tried first: at the last departure the leftover drag fell
        // through to the page, so a single drag scrolled the board and then kept
        // going into the rest of the home screen. It reads as the board throwing
        // you off it — you were reading departures, you reach the end, and the
        // thing you were reading slides away without you asking.
        //
        // A gesture now belongs to whichever scroller could use it when it
        // started ([boardScrollOwnership]): a drag that begins with departures
        // left to show stops dead at the last one, and the page moves only when
        // you lift and drag again. The inner bounce is deliberately kept — it is
        // the iOS way of saying "this is the end of this list", which is exactly
        // the thing that used to be missing.
        //
        val rowsScroll = rememberScrollState()
        val rowsModifier = Modifier
            .weight(1f, fill = false)
            .nestedScroll(boardScrollOwnership(rowsScroll))
        Box(modifier = rowsModifier) {
            Column(
                modifier = Modifier.verticalScroll(rowsScroll),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Nothing but departures lives in the rows area. Filter
                // captions ("VIA GREEN PARK", "NO MATCHES · SHOWING ALL") used
                // to sit here; they are app chrome on a surface that is meant
                // to read as station signage — station, platform header,
                // departure, status. The filter is the user's own setting and
                // is visible where they set it.
                if (boardFallback != null) {
                    // Whole board is empty (offline / night / disrupted) — the
                    // fallback copy replaces the platform blocks entirely.
                    BoardFallbackRows(boardFallback)
                } else {
                    UnifiedBoardRows(rows = rows)
                }
            }
            BoardScrollbar(scroll = rowsScroll, modifier = Modifier.align(Alignment.CenterEnd))
        }

        // ── ONE status strip for the whole board, below every departure ──
        // Was a strip per line, which at a four-line station meant four strips
        // (mostly "Good Service") shoving the actual departures off the panel.
        // Now one strip, rotating worst-first — see BoardStatusStrip.
        BoardStatusStrip(rendered = rendered, homeConfig = homeConfig)

        // ── Footer: roundel mark + live clock + "X ago" ──
        BoardFooter(lastUpdated)
    }
}


/**
 * The board's departure rows: a header per platform (rail) or stop (bus), with
 * every tracked line's departures merged underneath in true arrival order.
 *
 * Replaces the old per-section `SectionRows`. That rendered one line's rows at a
 * time, which meant a platform served by two tracked lines appeared as two
 * separate blocks, neither in real arrival order. Grouping now happens across
 * lines in [MultiLineBoardProcessor] — the one place that owns the rule.
 */
@Composable
private fun UnifiedBoardRows(rows: List<MultiLineBoardProcessor.Row>) {
    rows.forEach { row ->
        when (row) {
            is MultiLineBoardProcessor.Row.PlatformHeader ->
                ActiveStrip { PlatformHeaderText(row.title) }
            is MultiLineBoardProcessor.Row.Departure -> ActiveStrip {
                // A train that has already left, held to keep the block from
                // collapsing — see BoardTicker. Dimmed rather than removed,
                // exactly as the widget dims it (WidgetTheme.amberDim): the row
                // still carries a fact worth having ("the 14:32 has gone"), but
                // it must never be mistaken for something you can still catch.
                val tint = if (row.departed) BoardAmberDim else BoardAmber
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Line prefix, present only when this platform actually
                    // mixes lines (the processor decides). Bold so the eye can
                    // pick one line out of a merged platform without reading
                    // every destination.
                    if (row.linePrefix.isNotBlank()) {
                        Text(
                            row.linePrefix, color = tint, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        // Match Android widget_departure_row.xml destination_text:
                        // 15sp, REGULAR weight (no textStyle), system font (no
                        // letter-spacing).
                        row.destination, color = tint, fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (row.eta.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        // ETA = bold, DEFAULT font (Android eta_text: textStyle=bold,
                        // no monospace). Monospace bold read heavier than Android.
                        Text(
                            row.eta, color = tint, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * A platform header that shrinks its own text rather than ellipsising it.
 *
 * A header carries several facts — lines, platform number, direction — and at a
 * busy interchange ("Dist & Circ. Platform 2 Northbound") they do not fit a
 * phone board row. Ellipsising truncates the RIGHT, which is where the direction
 * and often the number live, so the row would lose exactly what it exists to
 * say.
 *
 * Instead it measures, and falls back through [MultiLineBoardProcessor.headerVariants]
 * — "Platform 7" → "Plat. 7" → drop the compass suffix — taking the first that
 * fits. Boards with room are untouched: the full text is the first rung, so the
 * common case renders exactly as before.
 *
 * Measurement, not a character-count guess: the board's glyph widths vary and a
 * heuristic would either shrink headers that fit or fail to shrink ones that
 * don't. `rememberTextMeasurer` caches its layouts, and the variants list is at
 * most three strings that only change when the departures do.
 */
@Composable
private fun PlatformHeaderText(title: String) {
    val measurer = rememberTextMeasurer()
    val style = androidx.compose.ui.text.TextStyle(
        // Match Android widget_platform_header.xml: 15sp bold, centered,
        // system font (no letter-spacing — the Android TextView sets none).
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val text = remember(title, widthPx) {
            val variants = MultiLineBoardProcessor.headerVariants(title)
            variants.firstOrNull { variant ->
                measurer.measure(variant, style, maxLines = 1).size.width <= widthPx
            } ?: variants.last()
        }
        Text(
            text, color = BoardAmber,
            fontSize = style.fontSize, fontWeight = style.fontWeight,
            // Still capped at one line: if even the shortest variant overflows
            // (a very narrow device), ellipsising is the right last resort.
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Empty-board copy (Offline / Live updates paused / Service ended for tonight /
 * ...), shown only when EVERY tracked line is empty — parity with Android's
 * applyBoardFallbackToRows.
 *
 * Padded to BOARD_FALLBACK_ROW_COUNT so the panel keeps its size rather than
 * collapsing to a two-line sliver.
 */
@Composable
private fun BoardFallbackRows(state: BoardFallbackState, homeConfig: Map<String, String> = emptyMap()) {
    val rows: List<Pair<String, Boolean>> = remember(state, homeConfig) {
        val copy = resolveBoardFallbackCopy(state, homeConfig)
        buildList {
            add(copy.title to true)
            copy.detailLines.forEach { add(it to false) }
            while (size < BOARD_FALLBACK_ROW_COUNT) add("" to false)
        }
    }
    // Departure-row style (15sp, 4dp horizontal pad, centered);
    // title bold, details normal — like Android's fallback rows.
    rows.forEach { (text, bold) ->
        ActiveStrip {
            Text(
                text, color = BoardAmber, fontSize = 15.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp)
            )
        }
    }
}

/**
 * Scrolls the disruption reason when it does not fit. BACK ON (2026-08-03).
 *
 * It was briefly off, and the history matters because it is easy to reintroduce:
 * this strip used to live INSIDE the departure rows' scroller, and
 * `basicMarquee` animates continuously, which kept that whole subtree dirty
 * every frame. The rows above it repainted at 60/120fps whether or not anything
 * had changed — and each row was then re-issuing ~800 `drawCircle` calls for its
 * dot grid. Together that is what made the in-card scroll feel clanky.
 *
 * Both causes are now fixed: the dot grid is a single tiled draw (see
 * [rememberDotGridBrush]), and this strip is pinned BELOW the scroller next to
 * the clock footer, so its invalidation no longer touches a departure row.
 *
 * The invariant to preserve: nothing that animates every frame may sit inside
 * the rows' scroller. Keep this strip outside it.
 */
private const val STATUS_MARQUEE = true

/** How long each line's status holds before the strip rotates to the next. */
private const val STATUS_ROTATION_MS = 8_000L

/**
 * ONE status strip for the whole board, pinned between the departures and the
 * clock footer.
 *
 * There used to be one of these per tracked line, rendered inside the scrolling
 * rows. At a four-line station that is four strips — usually four copies of
 * "Good Service" — eating the panel and pushing real departures out of view.
 *
 * With several lines there is a genuine question of WHICH status to show, and
 * the answer is [LineStatusRanker]: worst first, then rotate through the rest so
 * nothing is hidden. Good Service never takes a rotation slot; it appears only
 * when every line is healthy, once, for the board.
 *
 * The rotation is a state change every [STATUS_ROTATION_MS], not a per-frame
 * animation — it costs one recomposition every 8s. That distinction matters
 * here: see [STATUS_MARQUEE] for what per-frame invalidation did to this panel.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BoardStatusStrip(rendered: List<SectionRender>, homeConfig: Map<String, String>) {
    val entries = remember(rendered, homeConfig) {
        LineStatusRanker.rotation(
            rendered.map { r ->
                val (severity, reason) = when {
                    r.section.lineStatus != null -> splitLineStatus(r.section.lineStatus)
                    // A failed status fetch is NOT good service — say so rather
                    // than quietly implying everything is fine.
                    r.section.lineStatusFailed ->
                        (homeConfig["board.status_label"] ?: "Status") to
                            (homeConfig["board.status_failed_label"]
                                ?: "Status unavailable. Pull down to retry")
                    else -> (homeConfig["board.good_service_label"] ?: "Good Service") to null
                }
                LineStatusRanker.Entry(
                    // Shared naming rule — "hammersmith-city" reads as
                    // "Hammersmith City", not "Hammersmith-city".
                    lineLabel = LineShortNames.displayName(r.section.selection.line),
                    severity = severity.orEmpty(),
                    reason = reason.orEmpty(),
                )
            }
        )
    }
    if (entries.isEmpty()) return

    var index by remember(entries) { mutableStateOf(0) }
    // Only run a timer when there is more than one thing to say. A single
    // status must not schedule a coroutine that wakes up forever to re-select
    // index 0 — that is the sort of idle cost that is invisible until it isn't.
    if (entries.size > 1) {
        LaunchedEffect(entries) {
            while (true) {
                delay(STATUS_ROTATION_MS)
                index = (index + 1) % entries.size
            }
        }
    }
    val entry = entries[index.coerceIn(entries.indices)]

    // A healthy board still has something worth saying. The feed's own Good
    // Service description is preferred (LineStatusRanker.rotation carries it
    // through), but TfL frequently ships the severity with no text at all, and
    // "Good Service" alone on a strip built for a scrolling sentence reads as a
    // half-loaded row rather than as reassurance. Falling back to the same words
    // the Network section uses keeps one phrase for one state across the app.
    val reason = entry.reason.ifBlank {
        if (LineStatusRanker.isGoodService(entry.severity)) {
            homeConfig["board.good_service_sub"]
                ?: homeConfig["explore.good_service_sub"]
                ?: "All lines running normally"
        } else ""
    }

    ActiveStrip {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            // "Northern Part Closure" — the line is named because on a
            // multi-line board a bare severity does not say WHICH line is shut.
            Text(
                LineStatusRanker.label(entry),
                color = BoardAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (reason.isNotBlank()) {
                Text(" : ", color = BoardAmber, fontSize = 12.sp)
                Text(
                    reason, color = BoardAmber, fontSize = 12.sp, maxLines = 1,
                    // Truncate while the marquee is off (see STATUS_MARQUEE);
                    // without this a long reason would clip mid-word with no
                    // signal that there is more text.
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                        .then(if (STATUS_MARQUEE) Modifier.basicMarquee() else Modifier)
                )
            }
        }
    }
}

/**
 * The panel's scroll indicator, matching the Android board's.
 *
 * Only drawn when the rows actually overflow — `maxValue` is 0 when everything
 * fits, and a track with a full-length thumb is just a decoration that says
 * nothing. It exists to answer "is there more below?", which on a signage panel
 * with no other affordance is not obvious.
 *
 * Amber rather than a system scrollbar: this sits ON the dot-matrix panel, where
 * a platform grey bar would look like a rendering artefact. It is drawn as a lit
 * rail in the same amber as the departures, so it belongs to the board rather
 * than floating over it.
 *
 * **Visible whenever the board is scrollable, not only while it is moving.** It
 * was previously drawn at 45% of an already-translucent amber at rest, which on
 * a black panel is close enough to nothing that the answer to "is there more
 * below" arrived only after the user had already guessed and dragged. A hint
 * that appears in response to the action it exists to prompt is not a hint. It
 * still brightens under the finger, because knowing which of the two states you
 * are in is worth something.
 *
 * The thumb reads [ScrollState] inside a `drawBehind` lambda, so scrolling
 * invalidates the DRAW phase only. Reading `scroll.value` during composition
 * would recompose every row on every frame of a drag, which is the exact cost
 * the panel was rebuilt to avoid.
 */
@Composable
private fun BoardScrollbar(scroll: ScrollState, modifier: Modifier = Modifier) {
    // A single pixel of overflow is not "there is more below".
    //
    // `maxValue > 0` was the test, and it was true on boards that had nothing to
    // scroll: the rows column carries 2dp inter-row spacing and the panel is
    // sized in whole dp against a fractional-density viewport, so a board that
    // visually fits routinely overflows by a pixel or two. The bar was therefore
    // up almost always, which is exactly the state that makes a scroll hint
    // meaningless — a permanent decoration cannot answer a question.
    //
    // Half a departure row is the threshold: less than that and there is no row
    // hidden down there to go and find.
    // `Int.MAX_VALUE` is `ScrollState`'s value for "not measured yet", not an
    // enormous board. Without this the bar is composed on the first frame of
    // every card and then withdrawn on the next, which is a flicker on boards
    // that never scroll at all.
    val minOverflowPx = with(LocalDensity.current) { 13.dp.toPx() }
    if (scroll.maxValue == Int.MAX_VALUE || scroll.maxValue < minOverflowPx) return

    // Brighter under the finger, and legible the rest of the time. The gap
    // between the two states is deliberately small: this is a readout, and a
    // readout that halves in brightness when you stop touching it is telling you
    // about your finger rather than about the board.
    val active = scroll.isScrollInProgress
    val emphasis by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(if (active) 120 else 520, easing = EaseInOut),
        label = "scrollbar_emphasis",
    )
    Box(
        modifier = modifier
            // Pushed out over the panel's 8dp padding so it rides the black
            // edge instead of sitting on top of the departure text. A scroll
            // hint must not cost a row its last characters.
            .offset(x = 7.dp)
            .width(4.dp)
            .fillMaxHeight()
            .padding(vertical = 2.dp)
            .drawBehind {
                val viewport = size.height
                val overflow = scroll.maxValue
                // Both are read at DRAW time, and either can have changed since
                // the composition that decided to draw the bar at all: layout
                // updates `maxValue` in the same frame, and the recomposition
                // that would withdraw the bar happens in the NEXT one. So a
                // board that has just stopped overflowing gets exactly one draw
                // with `maxValue == 0` — and every ratio below is then 0/0.
                //
                // NaN is not survivable here, and `coerceIn` does not launder it
                // (every comparison against NaN is false, so it passes straight
                // through). Skia answers a gradient with a NaN endpoint by
                // returning a null shader, and Kotlin/Native answers the null
                // shader by aborting the process: "Can't wrap nullptr", on the
                // first frame, every launch. It only became fatal when the thumb
                // gained a gradient — the solid fill it replaced took the same
                // NaN and quietly drew nothing.
                if (viewport <= 0f || overflow <= 0) return@drawBehind
                // Floor the thumb so a very long board still leaves something
                // grabbable-looking rather than a single pixel.
                val minThumb = 26.dp.toPx()
                // A viewport shorter than its own minimum thumb, which is not a
                // hypothetical: `card.AnimatedVisibility` expands the body from
                // ZERO, so every open sweeps the rows area up through the small
                // heights, and the scrollbar draws on those frames because
                // `maxValue` already says the content overflows.
                //
                // This crashed the app — `coerceIn(min, max)` throws when the
                // range is empty, and here `max` is the viewport. It went
                // unnoticed for as long as it did because the bar only draws on
                // a board that OVERFLOWS, so a station had to be tall enough to
                // scroll before its expand animation could reach the bug; the
                // retained "Gone" blocks pushed King's Cross over that line.
                //
                // Skipping is the whole fix: there is nothing meaningful to say
                // about scroll position in a strip shorter than one thumb, and
                // the animation is past it within a frame or two.
                if (viewport < minThumb) return@drawBehind
                val content = viewport + overflow
                val thumbHeight = (viewport * viewport / content)
                    .coerceIn(minThumb, viewport)
                val progress = (scroll.value.toFloat() / overflow).coerceIn(0f, 1f)
                val offsetY = (viewport - thumbHeight) * progress
                val radius = size.width / 2f
                // The unlit rail. Present at rest so the thumb has something to
                // travel along and the bar reads as a scale rather than a mark.
                drawRoundRect(
                    color = BoardAmber.copy(alpha = 0.14f + 0.06f * emphasis),
                    cornerRadius = CornerRadius(radius, radius),
                )
                // The thumb, lit. Ends slightly brighter than its middle, the
                // way the panel's own rows fall off at their edges, so it reads
                // as part of the same display.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            BoardAmber.copy(alpha = 0.95f),
                            BoardAmber.copy(alpha = 0.72f + 0.28f * emphasis),
                            BoardAmber.copy(alpha = 0.95f),
                        ),
                        startY = offsetY,
                        endY = offsetY + thumbHeight,
                    ),
                    topLeft = Offset(0f, offsetY),
                    size = Size(size.width, thumbHeight),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
    )
}

/**
 * A "lit cell" strip on the dot-matrix panel. Matches the Android board's
 * `departure_board_active_row_background`: a SQUARE-cornered strip (the
 * Android original is a tiled pixel bitmap — no per-row radius; only the
 * outer panel is rounded) with a faint unlit-dot grid so the rows read as
 * LED matrix cells rather than flat chips.
 */
@Composable
private fun ActiveStrip(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ActiveRowBg)
            .background(rememberDotGridBrush())
    ) { content() }
}

/** Unlit-dot grid geometry. Kept as named constants so the tile below and any
 *  future consumer can never drift apart. */
private val DOT_PITCH = 3.dp
private val DOT_RADIUS = 0.6.dp
private val DOT_COLOR = Color.White.copy(alpha = 0.030f)

/**
 * The unlit-dot grid, as a ONE-CELL bitmap repeated by the GPU.
 *
 * This is the direct analogue of what Android has always done: the original
 * `departure_board_active_row_background` is a tiled pixel bitmap, i.e. one
 * small image the framework repeats. The Compose port reimplemented that as a
 * nested loop issuing an individual `drawCircle` per dot, and at a 3dp pitch a
 * single phone-width departure row is ~800 draw calls — a ten-row card is
 * ~8,000, re-issued on every repaint of that area. That was the board's scroll
 * jank: not layout, not the nested scroller, just an absurd draw-call count for
 * a texture that is by definition repetition.
 *
 * A `ShaderBrush` over a `TileMode.Repeated` `ImageShader` costs one draw. The
 * OUTPUT IS PIXEL-IDENTICAL: the loop started at `pitch / 2` and stepped by
 * `pitch`, which is exactly a tile of side `pitch` with the dot at its centre.
 * The board's look is non-negotiable (see docs/BOARD_AND_DREAM_UI.md) — this
 * changes only how those pixels are produced.
 *
 * Remembered against density, since the tile is authored in device pixels; a
 * fold/unfold or display-scale change re-bakes it. It is a handful of pixels,
 * so keeping one per density is free.
 */
@Composable
private fun rememberDotGridBrush(): ShaderBrush {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    return remember(density) {
        // At least 1px, or a very low density would round the pitch to zero and
        // hand ImageBitmap a degenerate size.
        val side = with(density) { DOT_PITCH.toPx() }.roundToInt().coerceAtLeast(1)
        val tile = ImageBitmap(side, side)
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = layoutDirection,
            // Fully qualified: `Canvas` in this file is the foundation
            // COMPOSABLE (imported above), not the graphics drawing surface.
            canvas = androidx.compose.ui.graphics.Canvas(tile),
            size = Size(side.toFloat(), side.toFloat()),
        ) {
            drawCircle(
                color = DOT_COLOR,
                radius = DOT_RADIUS.toPx(),
                center = Offset(side / 2f, side / 2f),
            )
        }
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
}

/** TfL roundel: a coloured ring with a horizontal bar. */
@Composable
private fun TflRoundel(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(color = color, radius = r * 0.92f, style = Stroke(width = r * 0.34f))
        drawRect(
            color = color,
            topLeft = Offset(0f, r - r * 0.17f),
            size = Size(this.size.width, r * 0.34f)
        )
    }
}

@Composable
private fun BoardFooter(lastUpdated: Long) {
    // Per-second tick for the live clock + "X ago" relative timer.
    var nowSec by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); nowSec = Clock.System.now().toEpochMilliseconds() }
    }
    val clock = remember(nowSec / 1000) {
        val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
        "${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}"
    }
    val ago = remember(nowSec / 1000, lastUpdated) { agoLabel(lastUpdated, nowSec) }

    Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
        // Stationly maker mark (left) — the REAL brand logo, matching
        // Android's footer `stationly_logo` ImageView (22dp); red-disc drawn
        // fallback keeps the board crash-proof when resources aren't bundled.
        if (com.stationly.app.platform.composeResourcesBundled) {
            Image(
                painter = org.jetbrains.compose.resources.painterResource(Res.drawable.stationly_logo),
                contentDescription = "Stationly",
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(22.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(22.dp)
                    .background(StationlyRed, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("S", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
        // Live clock (center) on its own lit strip, like Android's TextClock
        // with the active-row background.
        Box(modifier = Modifier.background(ActiveRowBg).padding(horizontal = 6.dp)) {
            // Match Android TextClock (widget_departure_board.xml): 19sp bold,
            // system font — Android's board clock is NOT monospace.
            Text(clock, color = BoardAmber, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        // X ago (right)
        //
        // UPRIGHT, where Android's `widget_departure_board.xml` sets
        // `textStyle="italic"` on the same element. One deliberate divergence,
        // and it is the board-wide rule rather than a decision about this label:
        // every glyph on a signage panel comes off the same machine, so the
        // panel carries ONE face in one style and lets size and weight do the
        // ranking. This was the only slanted text on it — see the same change on
        // the widget's `LiveAgo`, which the two surfaces have to agree on since
        // a user sees them within seconds of each other.
        Text(
            ago, color = BoardAmber, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)
        )
    }
}

private fun Int.pad(): String = toString().padStart(2, '0')

private fun agoLabel(lastUpdated: Long, nowMs: Long): String {
    if (lastUpdated <= 0L) return "0:00 ago"
    val secs = ((nowMs - lastUpdated) / 1000).coerceAtLeast(0L)
    val m = secs / 60
    val s = secs % 60
    return "$m:${s.toInt().pad()} ago"
}

// ── Next departure hero (themed canvas card) ──

/**
 * Fixed height for the hero, in EVERY state.
 *
 * A hero that sizes to its content changes the card's height whenever the line
 * changes, a platform label appears, or the last train of the night drops off —
 * and on the home screen that moves everything below it. Pinning it means the
 * "no departures" state and a full state occupy identical space, so switching
 * lines animates the TEXT and nothing else.
 *
 * Sized to its content and no more: label row + headline + [HERO_SLOT_HEIGHT] +
 * the card's own padding. It was briefly 116dp while chasing a clipped platform
 * chip, but the clipping was the SLOT being shorter than the chip, not the card
 * being too short — so the extra height was pure dead space under the chip.
 *
 * The split (two-direction) hero uses this SAME height, so splitting never moves
 * anything below it.
 */
private val HERO_HEIGHT = 94.dp

/**
 * Height of the hero's lower slot — platform chip or disruption reason.
 *
 * Was 18dp, which is SHORTER than the chip it was reserving space for (11sp text
 * plus 2dp padding each side), so the chip was clipped in half by its own slot.
 */
private val HERO_SLOT_HEIGHT = 24.dp

/**
 * Reserved width for the ETA column — see the comment at its call site.
 */
private val ETA_WIDTH = 54.dp
private val ETA_WIDTH_COMPACT = 44.dp

/**
 * Split-flap timings.
 *
 * These were 22/260, which read as a flicker rather than a flip: at that speed
 * the eye registers that something changed without ever resolving the movement,
 * which is the worst of both worlds — the cost of an animation with none of the
 * legibility. Slower, with a wider stagger, so the ripple across the word is
 * actually followable.
 *
 * The out-transition is deliberately SHORTER than the in-transition. The old
 * character is leaving and does not need to be read; the new one does. Matching
 * them makes both glyphs half-visible for the same span, which is what actually
 * looks like flickering.
 */
private const val FLIP_STAGGER_MS = 32
private const val FLIP_DURATION_MS = 420
private const val FLIP_OUT_DURATION_MS = 240

/**
 * The hero: the one departure you are being told to run for.
 *
 * Shows [prediction] for [render]'s line, or a status-aware empty state when
 * that line has nothing to show. Switching lines animates via [SplitFlapText].
 */
@Composable
private fun NextDepartureRow(
    render: SectionRender,
    prediction: PredictionDisplay?,
    homeConfig: Map<String, String>,
    /**
     * Half-width variant, used when both directions of a line are tracked and two
     * heroes share the row. Same height, smaller type — the card has half the
     * width but the same job, so what gives is glyph size, not information.
     */
    compact: Boolean = false,
) {
    val lineColor = render.lineColor
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfMute = MaterialTheme.colorScheme.onSurfaceVariant

    val (severity, rawReason) = splitLineStatus(render.section.lineStatus)
    // A Good Service status still carries a reason — TfL uses it for standing
    // advice like "Please offer your seat to anyone who needs it". That is not a
    // disruption, and printing it under "No departures reported yet" implies the
    // two are related when they are not. Only a genuine disruption gets to
    // explain itself here.
    val reason = rawReason?.takeIf { !LineStatusRanker.isGoodService(severity) }
    val isDue = prediction != null && (prediction.isDue || prediction.eta.trim().equals("Due", true))
    // The ticked label's own number — NOT a re-derive from targetEpochMs, which
    // would drop tickPredictions' per-platform bump and let this contradict the
    // board row below. Same reasoning as DreamSummary. See displayedMinutes.
    val countdown = prediction?.let { StationlyFormatters.displayedMinutes(it) } ?: 0
    val etaColor = if (isDue || countdown == 1) MaterialTheme.colorScheme.primary else onSurface

    // Destination, or WHY there isn't one. A disrupted line names its severity
    // here rather than showing a bare "no departures", which would read as our
    // bug rather than as the closure it actually is.
    val headline = when {
        prediction != null -> "\u2192 ${prediction.destination}"
        !LineStatusRanker.isGoodService(severity) && !severity.isNullOrBlank() -> severity
        else -> homeConfig["board.hero.no_departures"] ?: "No departures reported yet"
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(HERO_HEIGHT),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, lineColor.copy(alpha = 0.20f)),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(lineColor.copy(alpha = 0.05f))) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val livePulse by rememberInfiniteTransition(label = "live_dot").animateFloat(
                            0.4f, 1f, infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse), label = "live_dot_alpha"
                        )
                        Box(Modifier.size(4.dp).graphicsLayer { alpha = livePulse }.background(lineColor, CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (compact) {
                                // The two halves share a line, so the line name
                                // is the one thing they do NOT need to repeat —
                                // the direction is the entire reason there are
                                // two of them. Direction alone when there is a
                                // compass one; the line name only as a fallback,
                                // since "Inbound" is operational vocabulary that
                                // tells a passenger nothing (same rule the
                                // platform headers use, via compassOrNull).
                                MultiLineBoardProcessor
                                    .compassOrNull(render.section.selection.direction)
                                    ?.uppercase()
                                    ?: LineShortNames.displayName(render.section.selection.line).uppercase()
                            } else {
                                LineShortNames.displayName(render.section.selection.line) +
                                    " \u00B7 NEXT DEPARTURE"
                            },
                            color = onSurfMute, fontSize = if (compact) 9.sp else 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = if (compact) 0.8.sp else 1.1.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    SplitFlapText(
                        text = headline,
                        color = onSurface,
                        fontSize = if (compact) 13.sp else 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Reserve this slot's height whether or not it has content,
                    // so the hero never reflows — see HERO_HEIGHT. `fillMaxWidth`
                    // is load-bearing: with no width bound the chip's Text had
                    // nothing to ellipsise against and was hard-cut mid-word by
                    // the card's edge instead of truncating.
                    val platform = prediction?.platform?.takeIf { it.isNotBlank() && it != "null" }
                    Box(
                        modifier = Modifier.fillMaxWidth().height(HERO_SLOT_HEIGHT),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        when {
                            platform != null -> Surface(
                                color = onSurface.copy(alpha = 0.07f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    // Half the width in split mode, so spend the
                                    // boilerplate word rather than the number:
                                    // "Plat. 2" beats "Platfor…".
                                    if (compact) MultiLineBoardProcessor.headerVariants(platform)
                                        .getOrElse(1) { platform }
                                    else platform,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    color = onSurface.copy(alpha = 0.65f),
                                    fontSize = if (compact) 10.sp else 11.sp,
                                    fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            // Status text belongs here ONLY when there is nothing
                            // to catch. With a real departure on screen the line
                            // status is not what you need — the train is — and
                            // showing a closure notice beside a live countdown
                            // reads as a contradiction. The status still has its
                            // own strip at the foot of the panel.
                            prediction == null && !reason.isNullOrBlank() -> Text(
                                reason,
                                color = onSurfMute,
                                fontSize = if (compact) 10.sp else 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                // RESERVED width, not intrinsic.
                //
                // With an intrinsic width this column is measured from its own
                // content, and a per-character `AnimatedContent` reports a
                // changing width while it flips — so mid-animation the text
                // column was handed width that the ETA then drew back over, and
                // "Due" printed on top of the destination. A fixed reservation
                // means the destination's own truncation budget is stable and
                // the two can never occupy the same pixels.
                Box(
                    modifier = Modifier.width(if (compact) ETA_WIDTH_COMPACT else ETA_WIDTH),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                  Column(horizontalAlignment = Alignment.End) {
                    if (prediction != null) {
                        SplitFlapText(
                            text = if (isDue) "Due" else "$countdown",
                            color = etaColor,
                            // Was Black at 28sp, which read as a shout: the glyphs
                            // were so heavy the counter looked like a warning
                            // rather than a number. SemiBold at 30sp is LARGER but
                            // lighter — same presence in the layout, but it reads
                            // as precise instrumentation, which is what a
                            // departure countdown is.
                            fontSize = if (compact) 24.sp else 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            fillWidth = false,
                        )
                        if (!isDue) {
                            Text(
                                homeConfig["board.hero.min_label"] ?: "min",
                                color = etaColor.copy(alpha = 0.55f), fontSize = 11.sp,
                                fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp
                            )
                        }
                    }
                  }
                }
            }
        }
    }
}

/**
 * Text that changes the way a TfL board changes: each character flips to its new
 * value on its own, staggered left to right, sliding upward as it goes.
 *
 * Per CHARACTER rather than per string, because that is what makes it read as a
 * mechanical board rather than a crossfade — the eye follows the ripple across
 * the word. The stagger is deliberately small ([FLIP_STAGGER_MS]) so a long
 * destination still settles quickly.
 *
 * Characters that do not change are not animated at all: Compose keys each slot
 * on its own character, so "Edgware Road" \u2192 "Edgware Town" flips the four that
 * differ and leaves the rest alone. Cheaper, and truer to the real thing.
 *
 * Only the TRANSITION animates. Unlike the ambient glow this is not a
 * continuously running effect, so it costs nothing while the hero sits still —
 * which is what keeps it safe to put on the home screen.
 */
@Composable
private fun SplitFlapText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily? = null,
    /**
     * Whether this instance competes for the row's width.
     *
     * `true` for the destination, which must be bounded and truncated. `false`
     * for the ETA: it is 2-3 glyphs in a column that has no weight, so measuring
     * it against `fillMaxWidth` made it claim the whole row and starve the
     * destination of every pixel.
     */
    fillWidth: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = androidx.compose.ui.text.TextStyle(
        fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily,
    )
    // A Row of per-character cells CANNOT ellipsise the way a single Text can —
    // each cell is laid out independently, so an over-long destination simply
    // ran past its column and printed on top of the ETA. It has to be truncated
    // before it is split into characters.
    if (!fillWidth) {
        // Short, fixed-width content — no bound to measure against, so render
        // the cells directly rather than through BoxWithConstraints.
        FlapRow(shown = text, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily)
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availablePx = with(density) { maxWidth.toPx() }
        val shown = remember(text, availablePx, fontSize, fontWeight) {
            if (measurer.measure(text, style, maxLines = 1).size.width <= availablePx) text
            else {
                val ellipsis = "\u2026"
                val budget = availablePx - measurer.measure(ellipsis, style, maxLines = 1).size.width
                // Longest prefix that still leaves room for the ellipsis, by
                // BINARY search \u2014 text width is monotonic in prefix length, so
                // the linear scan this replaces measured every prefix (~30 text
                // layouts for a long destination) to find the same answer in ~5.
                // It runs on the layout pass, so the difference is worth having.
                var lo = 0
                var hi = text.length
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (measurer.measure(text.take(mid), style, maxLines = 1).size.width <= budget) {
                        lo = mid
                    } else {
                        hi = mid - 1
                    }
                }
                text.take(lo).trimEnd() + ellipsis
            }
        }
        FlapRow(shown = shown, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily)
    }
}

/** The per-character flip cells themselves — see [SplitFlapText]. */
@Composable
private fun FlapRow(
    shown: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { index, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    val delay = index * FLIP_STAGGER_MS
                    (slideInVertically(
                        animationSpec = tween(FLIP_DURATION_MS, delayMillis = delay, easing = EaseOutCubic)
                    ) { height -> height } + fadeIn(tween(FLIP_DURATION_MS, delayMillis = delay)))
                        .togetherWith(
                            slideOutVertically(
                                animationSpec = tween(FLIP_OUT_DURATION_MS, delayMillis = delay, easing = EaseInCubic)
                            ) { height -> -height } + fadeOut(tween(FLIP_OUT_DURATION_MS, delayMillis = delay))
                        )
                    // Cell CLIPPING is the effect, not a limitation: a real
                    // split-flap character appears from behind the housing. With
                    // clip disabled the outgoing and incoming glyphs were drawn
                    // outside their own cell, over the rows above and below,
                    // which is what made a flip look like a smear.
                },
                label = "flap_" + index,
            ) { c ->
                Text(
                    c.toString(),
                    color = color, fontSize = fontSize,
                    fontWeight = fontWeight, fontFamily = fontFamily,
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}
