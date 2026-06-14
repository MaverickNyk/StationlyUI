package com.stationly.app.ui.summary.components

import com.stationly.app.resources.Res
import com.stationly.app.resources.dot_matrix
import com.stationly.app.resources.stationly_logo
import org.jetbrains.compose.resources.Font
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.theme.LocalThemeTokens
import com.stationly.app.ui.theme.TflAmber
import com.stationly.app.ui.util.rememberMinuteTick
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SduiWidgetComponent
import com.stationly.core.model.sdui.SduiWidgetPayload
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.LegacyRow
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// Fixed TfL dot-matrix amber — the signage board is locked to this regardless
// of app theme (matches android `@color/tfl_amber` = #FFC819).
private val BoardAmber = Color(0xFFFFC819)
// Dark LED substrate — near-black with a faint warm/olive bias, like the unlit
// phosphor of a real TfL / National Rail platform dot-matrix display.
private val PanelBg = Color(0xFF0A0B07)
// Lit "active row" cell — a subtle lift off the panel so each departure reads as
// its own illuminated strip (the TfL-board row highlighters the owner liked).
private val ActiveRowBg = Color(0xFF181818)
// Unlit matrix dots, drawn within each lit row — dim amber points so the row
// reads as an LED cell, not a flat chip.
private val UnlitDot = BoardAmber.copy(alpha = 0.06f)
private val StationlyRed = Color(0xFFE32017)

/**
 * The board face: a true dot-matrix LED font (DotGothic16, OFL — see
 * `licenses/DotGothic16-OFL.txt`) so every glyph is built from round lit
 * points, the authentic TfL / National Rail departure-board look. Falls back
 * to monospace until composeResources are bundled (same guard as
 * [com.stationly.app.ui.theme.DisplayFamily]) so the board never renders blank.
 */
private val BoardFont: FontFamily
    @Composable get() = if (com.stationly.app.platform.composeResourcesBundled) FontFamily(
        Font(Res.font.dot_matrix)
    ) else FontFamily.Monospace

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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Board(
    selection: UserSelection,
    predictions: List<PredictionDisplay>,
    hasPredictions: Boolean,
    lineStatus: String?,
    lineStatusFailed: Boolean = false,
    sduiPayload: SduiWidgetPayload? = null,
    lastUpdated: Long,
    onDelete: () -> Unit,
    nextPrediction: PredictionDisplay? = null,
    homeConfig: Map<String, String> = emptyMap(),
    isDeleting: Boolean = false
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val lineColor = lineColorForTheme(selection.line, isDark)

    // Self-tick ETAs each wall-clock minute so "5 min" drops to "4 min" between
    // FCM pushes (the 30s SummaryViewModel poll handles fresh data).
    val nowMin by rememberMinuteTick()
    val ticked = remember(predictions, nowMin) {
        predictions.mapNotNull { p ->
            val t = p.targetEpochMs ?: return@mapNotNull p
            if (t < nowMin - 30_000L) null
            else p.copy(
                eta = StationlyFormatters.formatMinutesRemaining(t, nowMin, p.eta),
                isDue = (t - nowMin) / 1000 < 30,
            )
        }
    }
    val effectiveNext = remember(ticked) { StationlyFormatters.sortPredictions(ticked).firstOrNull() }

    val linePrefix = remember(selection.mode, selection.line, homeConfig) {
        StationlyFormatters.formatLinePrefix(selection.mode, selection.line, homeConfig)
    }

    val isDisrupted = lineStatus != null && !lineStatus.trim().lowercase().startsWith("good service")
    val disruptionSeverity = if (isDisrupted && lineStatus?.contains(":") == true)
        lineStatus.substringBefore(":").trim() else lineStatus?.trim() ?: ""
    val disruptionReason = if (isDisrupted && lineStatus?.contains(":") == true)
        lineStatus.substringAfter(":").trim() else ""

    var showFullReason by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isUrgent = effectiveNext != null && (effectiveNext.isDue ||
        effectiveNext.eta.replace(" min", "").trim().toIntOrNull()?.let { it <= 1 } == true)

    // ── Outer column: chrome on the themed canvas, only the dot-matrix is dark ──
    Column(modifier = Modifier.fillMaxWidth()) {

        // Header (canvas): line pill + delete trash
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(color = lineColor.copy(alpha = 0.15f), shape = RoundedCornerShape(5.dp)) {
                Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(lineColor, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        selection.line.replaceFirstChar { it.uppercase() } + " Line",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                    )
                }
            }
            IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.DeleteOutline, "Delete board",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Disruption banner (canvas, themed danger)
        if (isDisrupted) {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Warning, null, tint = danger, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(disruptionSeverity, color = danger, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        if (disruptionReason.isNotEmpty()) {
                            Icon(
                                if (showFullReason) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = showFullReason && disruptionReason.isNotEmpty(),
                        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                    ) {
                        Text(
                            disruptionReason, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Next departure hero (canvas, themed)
        if (effectiveNext != null) {
            NextDepartureRow(effectiveNext, lineColor)
            Spacer(Modifier.height(10.dp))
        }

        // ── Dot-matrix board (the only dark section) — a crisp physical panel.
        // Real depth via a soft drop shadow instead of the old pulsing radial
        // halo, which bloomed the edges and was the main "not sharp" culprit.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = PanelBg,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 14.dp,
            border = BorderStroke(if (isUrgent) 1.5.dp else 1.dp, lineColor.copy(alpha = 0.30f))
        ) {
            DotMatrixPanel(
                selection = selection,
                ticked = ticked,
                sduiPayload = sduiPayload,
                lineStatus = lineStatus,
                lineStatusFailed = lineStatusFailed,
                lastUpdated = lastUpdated,
                linePrefix = linePrefix,
                homeConfig = homeConfig,
            )
        }
    }

    if (showDeleteDialog) {
        val danger = LocalThemeTokens.current.error
        val onSurf = MaterialTheme.colorScheme.onSurface
        val onSurfMute = onSurf.copy(alpha = 0.55f)
        val onSurfDim = onSurf.copy(alpha = 0.25f)
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = onSurf,
            textContentColor = onSurfMute,
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = danger, modifier = Modifier.size(28.dp)) },
            title = { Text("Delete This Board?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("You're about to remove your ${selection.stationName} board.", fontWeight = FontWeight.Medium)
                    BoardDeleteBullet("Live departure tracking will stop", danger, onSurfMute)
                    BoardDeleteBullet("Departure notifications will be unsubscribed", danger, onSurfMute)
                    BoardDeleteBullet("Widget will be cleared", danger, onSurfMute)
                    Spacer(Modifier.height(2.dp))
                    Text("You can always set up a new board from the home screen.", color = onSurfDim, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteDialog = false; onDelete() },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = danger)
                ) { Text("Delete Board", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                if (!isDeleting) TextButton(onClick = { showDeleteDialog = false }, colors = ButtonDefaults.textButtonColors(contentColor = onSurfMute)) { Text("Keep It") }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DotMatrixPanel(
    selection: UserSelection,
    ticked: List<PredictionDisplay>,
    sduiPayload: SduiWidgetPayload?,
    lineStatus: String?,
    lineStatusFailed: Boolean,
    lastUpdated: Long,
    linePrefix: String,
    homeConfig: Map<String, String>,
) {
    val boardFont = BoardFont
    // Each lit row (ActiveStrip) carries its own dot texture + highlight, so the
    // panel is just the dark substrate with tight padding — keeps the board
    // compact instead of bulky.
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {

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
                val modeIcon = remember(selection.mode) {
                    com.stationly.app.platform.ModeIconStore.cachedIconBitmap(selection.mode)
                }
                if (modeIcon != null) {
                    Image(
                        bitmap = modeIcon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    TflRoundel(modeRoundelColor(selection.mode), 22.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    selection.stationName,
                    color = BoardAmber, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    fontFamily = boardFont, letterSpacing = 0.2.sp, lineHeight = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ── Rows (SDUI payload preferred, else legacy processor) ──
        val rows: List<BoardLine> = remember(sduiPayload, ticked, lineStatus, linePrefix) {
            buildBoardLines(sduiPayload, ticked, selection, lineStatus, linePrefix)
        }
        // Rows scroll independently inside the capped height — station strip
        // (above) and status + clock footer (below) stay pinned, matching the
        // Android board's ScrollView so a many-platform station (Bank, King's
        // Cross) never clips departures off the bottom of the panel.
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            rows.forEach { line ->
                when (line) {
                    is BoardLine.Header -> ActiveStrip {
                        Text(
                            line.title, color = line.color ?: BoardAmber,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            fontFamily = boardFont, letterSpacing = 0.3.sp, lineHeight = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        )
                    }
                    is BoardLine.Departure -> ActiveStrip {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                line.destination, color = BoardAmber, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium, fontFamily = boardFont, letterSpacing = 0.2.sp, lineHeight = 14.sp,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (line.eta.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                // Fixed-pitch ETAs: every "N min" occupies identical
                                // width so the right column rags perfectly — the
                                // tabular look real departure boards have.
                                Text(
                                    line.eta, color = line.etaColor ?: BoardAmber, fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold, fontFamily = boardFont, lineHeight = 14.sp
                                )
                            }
                        }
                    }
                    is BoardLine.Message -> ActiveStrip {
                        Text(
                            line.text, color = line.color ?: BoardAmber, fontSize = 14.sp,
                            fontFamily = boardFont, lineHeight = 16.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // ── Status strip: severity : reason (marquee) ──
        val severity: String
        val reason: String
        when {
            lineStatus != null -> {
                severity = if (lineStatus.contains(":")) lineStatus.substringBefore(":").trim() else lineStatus.trim()
                reason = if (lineStatus.contains(":")) lineStatus.substringAfter(":").trim() else ""
            }
            lineStatusFailed -> { severity = homeConfig["board.status_label"] ?: "Status"; reason = homeConfig["board.status_failed_label"] ?: "Status unavailable — pull down to retry" }
            else -> { severity = homeConfig["board.good_service_label"] ?: "Good Service"; reason = "" }
        }
        ActiveStrip {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(severity, color = BoardAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = boardFont, lineHeight = 12.sp)
                if (reason.isNotBlank()) {
                    Text(" : ", color = BoardAmber, fontSize = 11.sp, fontFamily = boardFont, lineHeight = 12.sp)
                    Text(
                        reason, color = BoardAmber, fontSize = 11.sp, fontFamily = boardFont, lineHeight = 12.sp, maxLines = 1,
                        modifier = Modifier.weight(1f).basicMarquee()
                    )
                }
            }
        }

        // ── Footer: roundel mark + live clock + "X ago" ──
        BoardFooter(lastUpdated, boardFont)
    }
}

/**
 * A lit "active row" strip — a subtle illuminated cell off the dark panel with a
 * faint unlit-dot texture, so each departure reads as its own LED row (the
 * TfL-board highlighters). Square corners; only the outer panel is rounded.
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
                var y = pitch / 2f
                while (y < size.height) {
                    var x = pitch / 2f
                    while (x < size.width) {
                        drawCircle(UnlitDot, radius = r, center = Offset(x, y))
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
private fun BoardFooter(lastUpdated: Long, boardFont: FontFamily) {
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
                    .size(20.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(20.dp)
                    .background(StationlyRed, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("S", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
        // Live clock (center) — lit dot-matrix text directly on the panel grid,
        // like the "23:29:54" readout on a real platform board.
        Text(clock, color = BoardAmber, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = boardFont, lineHeight = 17.sp)
        // X ago (right)
        Text(
            ago, color = BoardAmber, fontSize = 11.sp, fontFamily = boardFont, lineHeight = 11.sp,
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
                    // Monochrome amber board: never recolour "Due" red — it read as
                    // alarm, not arrival (owner feedback). Honour an explicit
                    // backend etaColor if one is sent, else leave it amber.
                    c.etaColor?.let { parseColorSafe(it, null) }
                )
                is SduiWidgetComponent.Message -> BoardLine.Message(c.text, c.color?.let { parseColorSafe(it, null) })
                else -> null
            }
        }
    }
    val severity = lineStatus?.let { if (it.contains(":")) it.substringBefore(":").trim() else it.trim() }
    val reason = lineStatus?.let { if (it.contains(":")) it.substringAfter(":").trim().takeIf { r -> r.isNotBlank() } else null }
    return GlobalBoardProcessor.prepareLegacyRows(
        ticked, selection.line, true, lineStatusSeverity = severity, lineStatusReason = reason
    ).map { row ->
        when (row) {
            is LegacyRow.Header -> BoardLine.Header(StationlyFormatters.platformHeaderText(linePrefix, row.title), null)
            is LegacyRow.Departure -> BoardLine.Departure(
                // Monochrome amber board — "Due" stays amber, never red (owner feedback).
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
