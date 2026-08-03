package com.stationly.app.ui.summary.components

import com.stationly.app.resources.Res
import com.stationly.app.resources.stationly_logo
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.stationly.app.ui.theme.LocalThemeTokens
import com.stationly.app.ui.theme.TflAmber
import com.stationly.app.ui.theme.isDarkTheme
import com.stationly.app.ui.util.BOARD_FALLBACK_ROW_COUNT
import com.stationly.app.ui.util.BoardFallbackDefaults
import com.stationly.app.ui.util.BoardFallbackState
import com.stationly.app.ui.util.computeBoardFallbackState
import com.stationly.app.ui.util.parseHHmm
import com.stationly.app.ui.util.resolveBoardFallbackCopy
import com.stationly.app.ui.util.rememberMinuteTick
import com.stationly.app.ui.util.tickPredictions
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.FilterMode
import com.stationly.core.model.UserSelection
import com.stationly.core.util.LineShortNames
import com.stationly.core.util.LineStatusRanker
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
private data class SectionRender(
    val section: BoardSection,
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
 * in-panel line headers and the multi-line delete dialog — only appears once
 * there is more than one section, so the common case is visually untouched.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StationBoard(
    stationName: String,
    mode: String,
    sections: List<BoardSection>,
    onDeleteSection: (UserSelection) -> Unit,
    onDeleteStation: () -> Unit,
    homeConfig: Map<String, String> = emptyMap(),
    isOnline: Boolean = true,
    isDeleting: Boolean = false,
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
) {
    if (sections.isEmpty()) return

    val isDark = isDarkTheme()
    val isMulti = sections.size > 1

    // Self-tick ETAs each wall-clock minute so "5 min" drops to "4 min" between
    // stream frames (the 30s SummaryViewModel poll handles fresh data). Shared
    // tick contract (ui/util/PredictionTicker) — same 30s departed grace PLUS
    // Android's per-platform monotonic bump, so two same-platform trains never
    // collide on one label ("Due, Due" → "Due, 1 min") on any surface.
    val nowMin by rememberMinuteTick()

    val londonTime = remember(nowMin) {
        Instant.fromEpochMilliseconds(nowMin)
            .toLocalDateTime(TimeZone.of("Europe/London")).time
    }

    // Everything per-section, derived in ONE remember rather than a remember per
    // loop iteration: the section list is dynamic, and per-iteration remembers
    // would rebuild their slots whenever a line is added or removed.
    val rendered = remember(sections, nowMin, isOnline, homeConfig, londonTime, isDark) {
        sections.map { section ->
            val ticked = tickPredictions(section.predictions, nowMin)
            val (sev, reason) = splitLineStatus(section.lineStatus)
            SectionRender(
                section = section,
                ticked = ticked,
                next = StationlyFormatters.sortPredictions(ticked).firstOrNull(),
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

    // Footer freshness is the most recent update across the card's lines.
    val lastUpdated = remember(sections) { sections.maxOf { it.lastUpdated } }

    var showDeleteDialog by remember { mutableStateOf(false) }

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

    // Reads what the hero DISPLAYS, not the raw timestamp, so the border and the
    // number the user is looking at can never disagree. See displayedMinutes.
    val isUrgent = heroPrediction != null &&
        StationlyFormatters.displayedMinutes(heroPrediction) <= 1

    // The ambient breathing glow is part of the board's identity — do not gate,
    // pin or otherwise "optimise" it away. It reads as continuously running
    // because it is. Smoothness is bought elsewhere (see the height bound and
    // the overscroll note below), never by changing how the board looks.
    val glowAlpha by rememberInfiniteTransition(label = "board_fx").animateFloat(
        initialValue = 0.06f, targetValue = 0.18f,
        animationSpec = infiniteRepeatable(tween(3200, easing = EaseInOut), RepeatMode.Reverse),
        label = "glow"
    )

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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Centred once the window is wider than the board is allowed to be,
            // so a tablet gets margin rather than a stretched row.
            .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
            .then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier)
    ) {

        // Header (canvas): one pill per tracked line + delete trash
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
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
                        onClick = { selectedLine = r.section.selection.line },
                    )
                }
              }
            }
            IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    if (isMulti) "Remove lines" else "Delete board",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                    modifier = Modifier.size(18.dp)
                )
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
        if (heroSections.isNotEmpty()) {
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
        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
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
                    stationName = stationName,
                    mode = mode,
                    rendered = rendered,
                    lastUpdated = lastUpdated,
                    homeConfig = homeConfig,
                )
            }
        }
    }

    if (showDeleteDialog) {
        BoardDeleteDialog(
            stationName = stationName,
            sections = sections,
            isDeleting = isDeleting,
            onDismiss = { showDeleteDialog = false },
            onDeleteSection = { showDeleteDialog = false; onDeleteSection(it) },
            onDeleteStation = { showDeleteDialog = false; onDeleteStation() },
        )
    }
}

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
 * Delete confirmation.
 *
 * Single-line card: the original "Delete This Board?" dialog, unchanged.
 * Multi-line card: the trash can no longer mean one thing, so the dialog lists
 * each tracked line with its own Remove and offers removing the whole station.
 */
@Composable
private fun BoardDeleteDialog(
    stationName: String,
    sections: List<BoardSection>,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onDeleteSection: (UserSelection) -> Unit,
    onDeleteStation: () -> Unit,
) {
    val danger = LocalThemeTokens.current.error
    val onSurf = MaterialTheme.colorScheme.onSurface
    val onSurfMute = onSurf.copy(alpha = 0.55f)
    val onSurfDim = onSurf.copy(alpha = 0.25f)
    val isMulti = sections.size > 1
    val isDark = isDarkTheme()

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = onSurf,
        textContentColor = onSurfMute,
        icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = danger, modifier = Modifier.size(28.dp)) },
        title = { Text(if (isMulti) "Remove a Line?" else "Delete This Board?", fontWeight = FontWeight.Bold) },
        text = {
            // Scrollable: Material3 does not scroll the text slot, and a card at
            // the 8-row cap stacks eight removable lines plus the header and
            // footnote. Without this the last line clips off the bottom on an
            // iPhone 11 — and a line you cannot see is a line you cannot remove.
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (isMulti) {
                    Text("Choose a line to stop tracking at $stationName.", fontWeight = FontWeight.Medium)
                    sections.forEach { section ->
                        Surface(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                                .clickable(enabled = !isDeleting) { onDeleteSection(section.selection) }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(8.dp).background(
                                        lineColorForTheme(section.selection.line, isDark), CircleShape
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    section.selection.line.replaceFirstChar { it.uppercase() } +
                                        " · " + section.selection.direction.replaceFirstChar { it.uppercase() },
                                    color = onSurf, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text("Remove", color = danger, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Removing the last line deletes the whole board.",
                        color = onSurfDim, fontSize = 12.sp
                    )
                } else {
                    Text("You're about to remove your $stationName board.", fontWeight = FontWeight.Medium)
                    BoardDeleteBullet("Live departure tracking will stop", danger, onSurfMute)
                    BoardDeleteBullet("Departure notifications will be unsubscribed", danger, onSurfMute)
                    BoardDeleteBullet("Widget will be cleared", danger, onSurfMute)
                    Spacer(Modifier.height(2.dp))
                    Text("You can always set up a new board from the home screen.", color = onSurfDim, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (isMulti) onDeleteStation() else onDeleteSection(sections.first().selection) },
                enabled = !isDeleting,
                colors = ButtonDefaults.textButtonColors(contentColor = danger)
            ) {
                Text(if (isMulti) "Remove All Lines" else "Delete Board", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (!isDeleting) TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = onSurfMute)
            ) { Text("Keep It") }
        }
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DotMatrixPanel(
    stationName: String,
    mode: String,
    rendered: List<SectionRender>,
    lastUpdated: Long,
    homeConfig: Map<String, String>,
) {
    // One board = one station. Buses group by stop (naptan) instead of platform
    // and never take a client-side line prefix on a row — the backend appends
    // the route number to the destination itself ("53 Nags Head").
    val isBus = mode.equals("bus", ignoreCase = true)

    // Platform/stop blocks across EVERY tracked line, in true arrival order.
    val unifiedRows = remember(rendered, isBus) {
        MultiLineBoardProcessor.buildRows(
            feeds = rendered.map { r ->
                MultiLineBoardProcessor.Feed(
                    // The resolved per-direction naptan (the POLE), not the hub
                    // — `parentStationId` is what groups poles into this card.
                    stationId = r.section.selection.station,
                    line = r.section.selection.line,
                    direction = r.section.selection.direction,
                    predictions = r.ticked,
                )
            },
            isBus = isBus,
        )
    }

    // Board-wide fallback: only when EVERY tracked line is empty. One line
    // having ended service for the night while another still runs is not an
    // empty board — those rows still render, and that line simply contributes
    // no block. Taking the first non-null keeps the existing copy resolution.
    val boardFallback = remember(rendered) {
        if (rendered.isNotEmpty() && rendered.all { it.ticked.isEmpty() }) {
            rendered.firstNotNullOfOrNull { it.fallbackState }
        } else null
    }

    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {

        // ── Station strip: centered roundel + station name ──
        ActiveStrip {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Real backend roundel from the App-Group ModeIconStore (same
                // cache the widget reads); drawn-roundel fallback until the
                // first /modes sync lands.
                val modeIcon = remember(mode) {
                    com.stationly.app.platform.ModeIconStore.cachedIconBitmap(mode)
                }
                if (modeIcon != null) {
                    Image(
                        bitmap = modeIcon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    TflRoundel(modeRoundelColor(mode), 22.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    // Match Android widget_departure_board.xml line_name: 16sp bold,
                    // system font (no letter-spacing).
                    stationName,
                    color = BoardAmber, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Rows scroll independently — station strip (above) and clock footer
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
        // the thing that makes that combination feel wrong on iOS is the INNER
        // rubber band: drag past the last departure and the rows bounce inside
        // the panel, absorbing the gesture, and the page only starts moving
        // after you let go and drag again. Dropping the inner overscroll hands
        // the gesture straight to the page at the boundary, so one continuous
        // drag reads as one continuous scroll. The page keeps its own bounce —
        // this removes a second, competing one, it does not remove the effect.
        //
        // Built in ColumnScope and passed in, because CompositionLocalProvider
        // emits no layout node: the Column below is still a direct child of
        // this one, so `weight` still resolves against it.
        val rowsModifier = Modifier.weight(1f, fill = false)
        val rowsScroll = rememberScrollState()
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
          Box(modifier = rowsModifier) {
            Column(
                modifier = Modifier.verticalScroll(rowsScroll),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // A filtered board must say so, or it reads as a board quietly
                // dropping trains for no reason. One notice for the card: the
                // captions are per-selection but the rows below are no longer
                // segregated by selection, so there is nothing to hang a
                // per-section caption on any more.
                rendered.mapNotNull { it.section.filterCaption() }.distinct()
                    .forEach { FilterNoticeStrip(it) }

                if (boardFallback != null) {
                    // Whole board is empty (offline / night / disrupted) — the
                    // fallback copy replaces the platform blocks entirely.
                    BoardFallbackRows(boardFallback)
                } else {
                    UnifiedBoardRows(rows = unifiedRows)
                }
            }
            BoardScrollbar(scroll = rowsScroll, modifier = Modifier.align(Alignment.CenterEnd))
          }
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
 * True when the board is filtered but EVERY row on it was excluded, so
 * `getPredictions` has fallen back to the unfiltered list.
 *
 * Derived from the rows themselves rather than a second query: the fallback is
 * exactly the state where nothing shown passes the filter. Without saying so the
 * board looks like it is ignoring the user's setting.
 */
private fun BoardSection.isShowingUnfilteredFallback(): Boolean =
    !selection.isUnfiltered && predictions.isNotEmpty() && predictions.none { it.matchesFilter }

/** Shown when a filter is on but nothing currently passes it — see §3.5 fail-open. */
private const val NO_MATCHES_CAPTION = "NO MATCHES · SHOWING ALL"

/**
 * The one filter caption for a section, or null when it shows everything.
 *
 * Resolves the fail-open case ahead of the filter's own label, because "showing
 * all" is the more urgent thing to say: the board is deliberately ignoring the
 * user's filter right now, and a tag still reading "VIA GREEN PARK" over an
 * unfiltered list would be actively wrong.
 */
private fun BoardSection.filterCaption(): String? =
    if (isShowingUnfilteredFallback()) NO_MATCHES_CAPTION else selection.filterTag()

/**
 * Board-signage label for a section's filter, or null when it shows everything.
 *
 * Reads as destination-board copy ("VIA GREEN PARK") rather than app chrome,
 * because it sits inside the dot-matrix panel.
 */
private fun UserSelection.filterTag(): String? = when {
    isUnfiltered -> null
    filterMode == FilterMode.VIA -> when (viaStationNames.size) {
        0 -> "VIA A STOP"
        1 -> "VIA ${viaStationNames[0].uppercase()}"
        // Board signage has no room to list several; the count is honest and the
        // full list is one tap away in the selection flow.
        else -> "VIA ${viaStationNames.size} STOPS"
    }
    destinations.size == 1 -> "ONLY ${destinations[0].uppercase()}"
    else -> "${destinations.size} DESTINATIONS"
}

/**
 * Filter notice for a SINGLE-section card, which has no header strip to hang it
 * on. Without this a filtered board silently omits trains with nothing on screen
 * explaining why.
 */
@Composable
private fun FilterNoticeStrip(filterTag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            filterTag,
            color = BoardAmber.copy(alpha = 0.72f),
            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
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
                            row.linePrefix, color = BoardAmber, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        // Match Android widget_departure_row.xml destination_text:
                        // 15sp, REGULAR weight (no textStyle), system font (no
                        // letter-spacing).
                        row.destination, color = BoardAmber, fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (row.eta.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        // ETA = bold, DEFAULT font (Android eta_text: textStyle=bold,
                        // no monospace). Monospace bold read heavier than Android.
                        Text(
                            row.eta, color = BoardAmber, fontSize = 15.sp,
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
                                ?: "Status unavailable — pull down to retry")
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

    ActiveStrip {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            // "Northern Part Closure" — the line is named because on a
            // multi-line board a bare severity does not say WHICH line is shut.
            Text(
                LineStatusRanker.label(entry),
                color = BoardAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (entry.reason.isNotBlank()) {
                Text(" : ", color = BoardAmber, fontSize = 12.sp)
                Text(
                    entry.reason, color = BoardAmber, fontSize = 12.sp, maxLines = 1,
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
 * Amber at low alpha rather than a system scrollbar: this sits ON the dot-matrix
 * panel, where a platform grey bar would look like a rendering artefact. It is
 * deliberately thin and dim — it is a hint, not a control, and it must never
 * compete with the departures.
 *
 * The thumb reads [ScrollState] inside a `drawBehind` lambda, so scrolling
 * invalidates the DRAW phase only. Reading `scroll.value` during composition
 * would recompose every row on every frame of a drag, which is the exact cost
 * the panel was rebuilt to avoid.
 */
@Composable
private fun BoardScrollbar(scroll: ScrollState, modifier: Modifier = Modifier) {
    if (scroll.maxValue <= 0) return
    Box(
        modifier = modifier
            // Pushed out over the panel's 8dp padding so it rides the black
            // edge instead of sitting on top of the departure text. A scroll
            // hint must not cost a row its last characters.
            .offset(x = 7.dp)
            .width(3.dp)
            .fillMaxHeight()
            .drawBehind {
                val viewport = size.height
                val content = viewport + scroll.maxValue
                // Floor the thumb so a very long board still leaves something
                // grabbable-looking rather than a single pixel.
                val thumbHeight = (viewport * viewport / content).coerceAtLeast(24.dp.toPx())
                val progress = scroll.value.toFloat() / scroll.maxValue
                val offsetY = (viewport - thumbHeight) * progress
                val radius = size.width / 2f
                drawRoundRect(
                    color = BoardAmber.copy(alpha = 0.10f),
                    cornerRadius = CornerRadius(radius, radius),
                )
                drawRoundRect(
                    color = BoardAmber.copy(alpha = 0.45f),
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
        Text(
            ago, color = BoardAmber, fontSize = 12.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
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

@Composable
private fun BoardDeleteBullet(text: String, dangerRed: Color, mute: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).background(dangerRed.copy(alpha = 0.6f), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, color = mute, fontSize = 14.sp)
    }
}
