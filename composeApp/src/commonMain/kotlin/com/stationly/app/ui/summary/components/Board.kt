package com.stationly.app.ui.summary.components

import com.stationly.app.resources.Res
import com.stationly.app.resources.stationly_logo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.stationly.core.model.sdui.SduiWidgetComponent
import com.stationly.core.model.sdui.SduiWidgetPayload
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.LegacyRow
import com.stationly.core.util.StationlyFormatters
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
    val sduiPayload: SduiWidgetPayload? = null,
    val lastUpdated: Long = 0L,
) {
    val key: String get() = "${selection.station}_${selection.line}_${selection.direction}"
}

/** Per-section values derived once per recomposition and reused by the chrome. */
private data class SectionRender(
    val section: BoardSection,
    val ticked: List<PredictionDisplay>,
    val next: PredictionDisplay?,
    val fallbackState: BoardFallbackState?,
    val linePrefix: String,
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
                linePrefix = StationlyFormatters.formatLinePrefix(
                    section.selection.mode, section.selection.line, homeConfig
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
    val hero = remember(rendered) {
        rendered.mapNotNull { r -> r.next?.let { r to it } }
            .minByOrNull { (_, p) ->
                if (p.isDue) 0 else p.eta.replace(" min", "").trim().toIntOrNull() ?: Int.MAX_VALUE
            }
    }
    val heroPrediction = hero?.second

    // Footer freshness is the most recent update across the card's lines.
    val lastUpdated = remember(sections) { sections.maxOf { it.lastUpdated } }

    var showDeleteDialog by remember { mutableStateOf(false) }

    val isUrgent = heroPrediction != null && (heroPrediction.isDue ||
        heroPrediction.eta.replace(" min", "").trim().toIntOrNull()?.let { it <= 1 } == true)

    val glowAlpha by rememberInfiniteTransition(label = "board_fx").animateFloat(
        initialValue = 0.06f, targetValue = 0.18f,
        animationSpec = infiniteRepeatable(tween(3200, easing = EaseInOut), RepeatMode.Reverse),
        label = "glow"
    )

    // ── Outer column: chrome on the themed canvas, only the dot-matrix is dark ──
    Column(modifier = Modifier.fillMaxWidth()) {

        // Header (canvas): one pill per tracked line + delete trash
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Distinct lines only — two directions on one line share a pill,
                // otherwise "Victoria Line  Victoria Line" reads like a bug.
                rendered.distinctBy { it.section.selection.line }.forEach { r ->
                    LinePill(
                        line = r.section.selection.line,
                        lineColor = r.lineColor,
                        // With several pills the word "Line" on each is noise.
                        showSuffix = !isMulti,
                    )
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

        // Disruption banners (canvas, themed danger) — one per disrupted line.
        rendered.forEach { r ->
            val status = r.section.lineStatus
            val disrupted = status != null && !status.trim().lowercase().startsWith("good service")
            if (disrupted) {
                val (sev, reason) = splitLineStatus(status)
                DisruptionBanner(
                    // Only name the line when the card carries several — on a
                    // single-line card the pill above already said which.
                    lineLabel = if (isMulti) r.section.selection.line.replaceFirstChar { it.uppercase() } else null,
                    severity = sev ?: "",
                    reason = reason ?: "",
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        // Next departure hero (canvas, themed) — soonest across all lines here.
        if (heroPrediction != null) {
            NextDepartureRow(heroPrediction, hero.first.lineColor)
            Spacer(Modifier.height(10.dp))
        }

        // ── Dot-matrix board (the only dark section) with ambient glow ──
        Box(modifier = Modifier.fillMaxWidth()) {
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
                    showSectionHeaders = isMulti,
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

/** Line chip on the themed canvas above the panel. */
@Composable
private fun LinePill(line: String, lineColor: Color, showSuffix: Boolean) {
    Surface(color = lineColor.copy(alpha = 0.15f), shape = RoundedCornerShape(5.dp)) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(lineColor, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(
                line.replaceFirstChar { it.uppercase() } + if (showSuffix) " Line" else "",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Expandable "Severity : Reason" banner for one disrupted line. */
@Composable
private fun DisruptionBanner(lineLabel: String?, severity: String, reason: String) {
    var showFullReason by remember { mutableStateOf(false) }
    val danger = LocalThemeTokens.current.error
    Surface(
        color = danger.copy(alpha = 0.08f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, danger.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().clickable { showFullReason = !showFullReason }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Warning, null, tint = danger, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (lineLabel != null) "$lineLabel · $severity" else severity,
                        color = danger, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (reason.isNotEmpty()) {
                    Icon(
                        if (showFullReason) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp)
                    )
                }
            }
            AnimatedVisibility(
                visible = showFullReason && reason.isNotEmpty(),
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
            ) {
                Text(
                    reason, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
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
    showSectionHeaders: Boolean,
    lastUpdated: Long,
    homeConfig: Map<String, String>,
) {
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

        // Rows scroll independently inside the capped height — station strip
        // (above) and clock footer (below) stay pinned, matching the Android
        // board's ScrollView so a many-platform station (Bank, King's Cross)
        // never clips departures off the bottom of the panel. With several
        // lines this is also what keeps the card from growing without bound:
        // the panel height is fixed and the stacked sections scroll within it.
        Column(
            modifier = Modifier
                .heightIn(max = if (showSectionHeaders) 460.dp else 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            rendered.forEach { r ->
                // Line/direction header — only on multi-line cards. On a
                // single-line card the pill above the panel already names the
                // line, and adding a header here would change the long-standing
                // single-board layout for no information gain.
                if (showSectionHeaders) {
                    SectionHeaderStrip(
                        line = r.section.selection.line,
                        direction = r.section.selection.direction,
                        filterTag = if (r.section.isShowingUnfilteredFallback()) "NO MATCHES · SHOWING ALL"
                                    else r.section.selection.filterTag(),
                    )
                } else {
                    // Single-section cards have no header strip — the pill above
                    // the panel already names the line. But a FILTERED board must
                    // still say so, or it reads as a board that is quietly
                    // dropping trains for no reason.
                    val tag = if (r.section.isShowingUnfilteredFallback()) "NO MATCHES · SHOWING ALL"
                              else r.section.selection.filterTag()
                    tag?.let { FilterNoticeStrip(it) }
                }
                SectionRows(render = r, homeConfig = homeConfig)
                // Per-line status strip: severity : reason (marquee). Each line
                // has its own status, so this stays inside the section rather
                // than being one strip for the whole card.
                SectionStatusStrip(render = r, homeConfig = homeConfig)
            }
        }

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

/** Amber "Piccadilly · Westbound" divider between stacked line sections. */
@Composable
private fun SectionHeaderStrip(line: String, direction: String, filterTag: String? = null) {
    ActiveStrip {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                // `direction` is the option id ("inbound"/"outbound") and on some
                // modes it can be blank. Only append it when it says something —
                // a trailing " · " on a bus board reads as missing data.
                line.replaceFirstChar { it.uppercase() } +
                    direction.takeIf { it.isNotBlank() }
                        ?.let { " · " + it.replaceFirstChar { c -> c.uppercase() } }
                        .orEmpty(),
                color = BoardAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (filterTag != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    filterTag,
                    color = BoardAmber.copy(alpha = 0.72f),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
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
 * One line's departure rows: empty-board fallback message wins (parity with
 * Android's applyBoardFallbackToRows), else the SDUI payload, else the legacy
 * processor.
 */
@Composable
private fun SectionRows(render: SectionRender, homeConfig: Map<String, String>) {
    // The fallback is a bold title + normal detail lines, padded to
    // BOARD_FALLBACK_ROW_COUNT so a single-line panel keeps its size.
    val fallbackRows: List<Pair<String, Boolean>>? = remember(render.fallbackState, homeConfig) {
        render.fallbackState?.let { st ->
            val copy = resolveBoardFallbackCopy(st, homeConfig)
            buildList {
                add(copy.title to true)
                copy.detailLines.forEach { add(it to false) }
                while (size < BOARD_FALLBACK_ROW_COUNT) add("" to false)
            }
        }
    }
    val rows: List<BoardLine> = remember(render) {
        buildBoardLines(
            render.section.sduiPayload, render.ticked, render.section.selection,
            render.section.lineStatus, render.linePrefix
        )
    }

    if (fallbackRows != null) {
        // Departure-row style (15sp, 4dp horizontal pad, centered);
        // title bold, details normal — like Android's fallback rows.
        fallbackRows.forEach { (text, bold) ->
            ActiveStrip {
                Text(
                    text, color = BoardAmber, fontSize = 15.sp,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp)
                )
            }
        }
        return
    }

    rows.forEach { line ->
        when (line) {
            is BoardLine.Header -> ActiveStrip {
                Text(
                    // Match Android widget_platform_header.xml: 15sp bold,
                    // centered, 2dp horizontal padding, system font (no
                    // letter-spacing — the Android TextView sets none).
                    line.title, color = line.color ?: BoardAmber,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                )
            }
            is BoardLine.Departure -> ActiveStrip {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        // Match Android widget_departure_row.xml destination_text:
                        // 15sp, REGULAR weight (no textStyle), system font (no
                        // letter-spacing).
                        line.destination, color = BoardAmber, fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (line.eta.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        // ETA = bold, DEFAULT font (Android eta_text: textStyle=bold,
                        // no monospace). Monospace bold read heavier than Android.
                        Text(
                            line.eta, color = line.etaColor ?: BoardAmber, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            is BoardLine.Message -> ActiveStrip {
                Text(
                    line.text, color = line.color ?: BoardAmber, fontSize = 15.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp)
                )
            }
        }
    }
}

/** One line's "Severity : Reason" strip at the foot of its section. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SectionStatusStrip(render: SectionRender, homeConfig: Map<String, String>) {
    val severity: String
    val reason: String
    val lineStatus = render.section.lineStatus
    when {
        lineStatus != null -> {
            val (s, r) = splitLineStatus(lineStatus)
            severity = s ?: ""
            reason = r ?: ""
        }
        render.section.lineStatusFailed -> {
            severity = homeConfig["board.status_label"] ?: "Status"
            reason = homeConfig["board.status_failed_label"] ?: "Status unavailable — pull down to retry"
        }
        else -> {
            severity = homeConfig["board.good_service_label"] ?: "Good Service"
            reason = ""
        }
    }
    ActiveStrip {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(severity, color = BoardAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (reason.isNotBlank()) {
                Text(" : ", color = BoardAmber, fontSize = 12.sp)
                Text(
                    reason, color = BoardAmber, fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.weight(1f).basicMarquee()
                )
            }
        }
    }
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
            .drawBehind {
                val pitch = 3.dp.toPx()
                val r = 0.6.dp.toPx()
                val dot = Color.White.copy(alpha = 0.030f)
                var y = pitch / 2f
                while (y < size.height) {
                    var x = pitch / 2f
                    while (x < size.width) {
                        drawCircle(dot, radius = r, center = Offset(x, y))
                        x += pitch
                    }
                    y += pitch
                }
            }
    ) { content() }
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

// ── Unified board-line model: SDUI payload OR legacy processor → same render ──
private sealed class BoardLine {
    data class Header(val title: String, val color: Color?) : BoardLine()
    data class Departure(val destination: String, val eta: String, val etaColor: Color?) : BoardLine()
    data class Message(val text: String, val color: Color?) : BoardLine()
}

private fun buildBoardLines(
    sduiPayload: SduiWidgetPayload?,
    ticked: List<PredictionDisplay>,
    selection: UserSelection,
    lineStatus: String?,
    linePrefix: String,
): List<BoardLine> {
    if (sduiPayload != null) {
        return sduiPayload.components.mapNotNull { c ->
            when (c) {
                is SduiWidgetComponent.Header -> BoardLine.Header(
                    StationlyFormatters.platformHeaderText(linePrefix, c.title),
                    c.color?.let { parseColorSafe(it, null) }
                )
                is SduiWidgetComponent.Row -> BoardLine.Departure(
                    c.destination, c.eta,
                    // Match Android: ETA = SDUI etaColor, else amber default — never
                    // a client-side red for "Due" (Android has none; owner disliked it).
                    c.etaColor?.let { parseColorSafe(it, null) }
                )
                is SduiWidgetComponent.Message -> BoardLine.Message(c.text, c.color?.let { parseColorSafe(it, null) })
                else -> null
            }
        }
    }
    val (severity, reason) = splitLineStatus(lineStatus)
    return GlobalBoardProcessor.prepareLegacyRows(
        ticked, selection.line, true, lineStatusSeverity = severity, lineStatusReason = reason
    ).map { row ->
        when (row) {
            is LegacyRow.Header -> BoardLine.Header(StationlyFormatters.platformHeaderText(linePrefix, row.title), null)
            is LegacyRow.Departure -> BoardLine.Departure(
                // Match Android — "Due" stays amber, never client-side red.
                row.destination, row.eta, null
            )
            is LegacyRow.Message -> BoardLine.Message(row.text, null)
        }
    }
}

// ── Next departure hero (themed canvas card) ──
@Composable
private fun NextDepartureRow(prediction: PredictionDisplay, lineColor: Color) {
    val isDue = prediction.isDue || prediction.eta.trim().equals("Due", true)
    val countdown = if (isDue) 0 else prediction.eta.replace(" min", "").trim().toIntOrNull() ?: 0
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfMute = MaterialTheme.colorScheme.onSurfaceVariant
    val etaColor = if (isDue || countdown == 1) MaterialTheme.colorScheme.primary else onSurface

    val etaProgress by animateFloatAsState((countdown / 10f).coerceIn(0f, 1f), tween(800), label = "eta_progress")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, lineColor.copy(alpha = 0.20f)),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(lineColor.copy(alpha = 0.05f))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val livePulse by rememberInfiniteTransition(label = "live_dot").animateFloat(
                        0.4f, 1f, infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse), label = "live_dot_alpha"
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(4.dp).graphicsLayer { alpha = livePulse }.background(lineColor, CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text("NEXT DEPARTURE", color = onSurfMute, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text("→ ${prediction.destination}", color = onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (prediction.platform.isNotBlank() && prediction.platform != "null") {
                        Spacer(Modifier.height(4.dp))
                        Surface(color = onSurface.copy(alpha = 0.07f), shape = RoundedCornerShape(4.dp)) {
                            Text(
                                prediction.platform, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                color = onSurface.copy(alpha = 0.65f), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (isDue) "Due" else "$countdown",
                        color = etaColor, fontWeight = FontWeight.Black, fontSize = 28.sp, fontFamily = FontFamily.Monospace
                    )
                    if (!isDue) Text("min", color = etaColor.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth(etaProgress).height(2.dp).align(Alignment.BottomStart)
                    .background(Brush.horizontalGradient(listOf(etaColor.copy(alpha = 0.9f), etaColor.copy(alpha = 0.2f))))
            )
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

private fun parseColorSafe(hex: String, fallback: Color?): Color? {
    return try {
        val clean = hex.trim().removePrefix("#")
        val argb = when (clean.length) {
            6 -> "FF$clean".toLong(16)
            8 -> clean.toLong(16)
            else -> return fallback
        }
        Color(argb)
    } catch (_: Exception) {
        fallback
    }
}
