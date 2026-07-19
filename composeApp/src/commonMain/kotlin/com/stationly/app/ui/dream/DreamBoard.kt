package com.stationly.app.ui.dream

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.platform.ModeIconStore
import com.stationly.app.ui.util.BOARD_FALLBACK_ROW_COUNT
import com.stationly.app.ui.util.computeBoardFallbackState
import com.stationly.app.ui.util.rememberMinuteTick
import com.stationly.app.ui.util.rememberTickedPredictions
import com.stationly.app.ui.util.resolveBoardFallbackCopy
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.LegacyRow
import com.stationly.core.util.StaleColor
import com.stationly.core.util.StationlyFormatters
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The dream's departure board — pure-Compose port of Android
 * `dream/DreamBoard.kt`.
 *
 * Android inflates the SAME `widget_departure_board.xml` the home widget
 * uses; on iOS the pixel-verified Compose recreation of that XML lives in
 * `ui/summary/components/Board.kt` (DotMatrixPanel). This panel renders the
 * identical row model (`GlobalBoardProcessor.prepareLegacyRows`), the same
 * uniform ROW_BASE 14sp baseline × [textScale], the same fallback rows, the
 * same status strip and the same "X ago" staleness palette (`StaleColor`) —
 * wrapped in the dream's rounded amber-bordered signage card.
 */

// Dot-matrix signage palette — locked dark regardless of dream theme (the
// board IS the signage, not chrome). Same values as Board.kt.
private val BoardAmber  = Color(0xFFFFC819)
private val ActiveRowBg = Color(0xFF161616)
private val CardBg      = Color(0xFF050505)
private val StationlyRed = Color(0xFFE32017)

/**
 * Uniform baseline for every row on the dream board. The widget XML's tiered
 * 13sp/15sp hierarchy looks uneven at dream scale, so every row is forced to
 * this baseline before [textScale] multiplies (Android `ROW_BASE_SP`).
 */
private const val ROW_BASE_SP = 14f

@Composable
fun DreamBoard(
    snapshot: DreamSnapshot,
    modifier: Modifier = Modifier,
    /** Multiplier applied to every text size — the widget metrics are sized
     *  for a tiny home-screen tile; at dream scale text must read from
     *  across the room. */
    textScale: Float = 1.75f,
    /** Show the station-name header strip (hidden when a Compose station
     *  header is drawn above the board instead). */
    showHeader: Boolean = false,
    /** Show the built-in ticking clock at the bottom — fullscreen layout
     *  turns this on; the cluster layout has its own larger clock. */
    showClock: Boolean = false,
    /** SDUI string map (`board.fallback.*`, mode labels); hardcoded
     *  fallbacks keep the board functional when empty. */
    sduiStrings: Map<String, String> = emptyMap(),
    /**
     * Fullscreen-board styling: rows centred vertically in the viewport,
     * stronger amber border + larger radius, small inter-row margin.
     */
    fullscreen: Boolean = false,
) {
    val sel = snapshot.selection
    // Self-tick row ETAs once per minute — shared contract with home/widget.
    val predictions = rememberTickedPredictions(snapshot.predictions)
    val lineStatus = snapshot.lineStatus
    val lastUpdated = snapshot.lastUpdatedMs
    // Same wall-clock minute tick drives the ago-colour (amber → grey → red)
    // in lockstep with the home Board and the widget.
    val nowMs by rememberMinuteTick()

    val linePrefix = if (sel != null) {
        StationlyFormatters.formatLinePrefix(sel.mode, sel.line, sduiStrings)
    } else ""

    // Same fallback rules as home + widget so the three surfaces never
    // disagree about why the board is empty.
    val fallbackState = if (sel == null) null else remember(predictions, lastUpdated, nowMs, lineStatus) {
        val londonTime = Instant.fromEpochMilliseconds(nowMs)
            .toLocalDateTime(TimeZone.of("Europe/London")).time
        computeBoardFallbackState(
            hasPredictions = predictions.isNotEmpty(),
            isOnline = true,   // dream reads SQL only; age-based SIGNAL_LOST covers drops
            lastUpdatedMs = lastUpdated,
            nowMs = nowMs,
            londonTime = londonTime,
            lineStatusSeverity = lineStatus?.statusSeverityDescription,
            lineStatusReason = lineStatus?.reason,
        )
    }

    val legacyRows: List<LegacyRow> = remember(sel, predictions, lineStatus, nowMs) {
        if (sel == null) {
            listOf(LegacyRow.Header("Add a board on the home screen"))
        } else {
            GlobalBoardProcessor.prepareLegacyRows(
                predictions = predictions,
                lineName = sel.line,
                hasSelection = true,
                lineStatusSeverity = lineStatus?.statusSeverityDescription,
                lineStatusReason = lineStatus?.reason?.takeIf { it.isNotBlank() },
                currentHour = Instant.fromEpochMilliseconds(nowMs)
                    .toLocalDateTime(TimeZone.currentSystemDefault()).hour,
            )
        }
    }

    // Fallback copy rows (bold title + detail lines padded to the shared row
    // count) — override the legacy rows when active, exactly like Android's
    // applyBoardFallbackToRows.
    val fallbackRows: List<Pair<String, Boolean>>? = remember(fallbackState, sduiStrings) {
        fallbackState?.let { st ->
            val copy = resolveBoardFallbackCopy(st, sduiStrings)
            buildList {
                add(copy.title to true)
                copy.detailLines.forEach { add(it to false) }
                while (size < BOARD_FALLBACK_ROW_COUNT) add("" to false)
            }
        }
    }

    // Dream-scaled sizes — everything multiplies the uniform row baseline.
    val rowSp    = (ROW_BASE_SP * textScale).sp
    val statusSp = (ROW_BASE_SP * 0.9f * textScale).sp
    val agoSp    = (ROW_BASE_SP * 0.8f * textScale).sp
    val headerSp = (16f * textScale).sp
    val clockSp  = (19f * textScale).sp
    val rowGap   = if (fullscreen) 6.dp else 2.dp

    val cornerRadius = if (fullscreen) 28.dp else 20.dp
    val borderAlpha  = if (fullscreen) 0.32f else 0.18f
    val borderWidth  = if (fullscreen) 2.dp  else 1.dp
    val innerPad     = if (fullscreen) 14.dp else 6.dp

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        color = CardBg,
        border = BorderStroke(borderWidth, BoardAmber.copy(alpha = borderAlpha)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(innerPad),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // ── Station strip (widget header row) ──
            if (showHeader) {
                DreamActiveStrip {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val modeIcon = remember(sel?.mode) {
                            sel?.mode?.let { ModeIconStore.cachedIconBitmap(it) }
                        }
                        if (modeIcon != null) {
                            Image(
                                bitmap = modeIcon,
                                contentDescription = sel?.mode?.let {
                                    StationlyFormatters.formatModeName(it, sduiStrings)
                                },
                                modifier = Modifier.size((22f * textScale).dp)
                            )
                        } else {
                            DreamTflRoundel(BoardAmber, (22f * textScale).dp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            sel?.stationName ?: "Stationly",
                            color = BoardAmber, fontSize = headerSp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ── Rows — scroll independently; centred when short in
            //    fullscreen (Android's fillViewport + centre gravity). ──
            Column(
                modifier = Modifier
                    .weight(1f, fill = fullscreen)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(
                    rowGap,
                    if (fullscreen) Alignment.CenterVertically else Alignment.Top
                )
            ) {
                if (fallbackRows != null) {
                    fallbackRows.forEach { (text, bold) ->
                        DreamActiveStrip {
                            Text(
                                text, color = BoardAmber, fontSize = rowSp,
                                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                            )
                        }
                    }
                } else {
                    legacyRows.forEach { row ->
                        when (row) {
                            is LegacyRow.Header -> DreamActiveStrip {
                                Text(
                                    StationlyFormatters.platformHeaderText(linePrefix, row.title),
                                    color = BoardAmber, fontSize = rowSp, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                                )
                            }
                            is LegacyRow.Departure -> DreamActiveStrip {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        row.destination, color = BoardAmber, fontSize = rowSp,
                                        fontWeight = FontWeight.Normal,
                                        modifier = Modifier.weight(1f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (row.eta.isNotBlank()) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            row.eta, color = BoardAmber, fontSize = rowSp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            is LegacyRow.Message -> DreamActiveStrip {
                                Text(
                                    row.text, color = BoardAmber, fontSize = rowSp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Status strip: severity : reason ──
            val severity = lineStatus?.statusSeverityDescription?.takeIf { it.isNotBlank() }
                ?: "Good Service"
            val reason = lineStatus?.reason?.takeIf { it.isNotBlank() } ?: ""
            DreamActiveStrip {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(severity, color = BoardAmber, fontSize = statusSp, fontWeight = FontWeight.Bold)
                    if (reason.isNotBlank()) {
                        Text(" : ", color = BoardAmber, fontSize = statusSp)
                        Text(
                            reason, color = BoardAmber, fontSize = statusSp, maxLines = 1,
                            modifier = Modifier.weight(1f).basicMarquee()
                        )
                    }
                }
            }

            // ── Footer: brand mark + optional clock + "X ago" chronometer ──
            Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 6.dp)
                        .size((22f * textScale).dp)
                        .background(StationlyRed, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "S", color = Color.White,
                        fontSize = (12f * textScale).sp, fontWeight = FontWeight.Black
                    )
                }
                if (showClock) {
                    Box(
                        modifier = Modifier.align(Alignment.Center)
                            .background(ActiveRowBg).padding(horizontal = 6.dp)
                    ) {
                        val now by rememberClockNow()
                        Text(
                            "${now.hour.pad2()}:${now.minute.pad2()}:${now.second.pad2()}",
                            color = BoardAmber, fontSize = clockSp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                // "X ago" — StaleColor palette anchored to the SQL row's true
                // age (amber < 60s, grey < 180s, red beyond), shared with
                // home + widget.
                val ageMs = (nowMs - lastUpdated).coerceAtLeast(0L)
                val agoColor = Color(if (lastUpdated > 0L) StaleColor.colorForAge(ageMs) else StaleColor.AMBER)
                val agoSecs = ((nowMs - lastUpdated) / 1000).coerceAtLeast(0L)
                Text(
                    "${agoSecs / 60}:${(agoSecs % 60).toString().padStart(2, '0')} ago",
                    color = agoColor, fontSize = agoSp, fontStyle = FontStyle.Italic,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)
                )
            }
        }
    }
}

private fun Int.pad2(): String = toString().padStart(2, '0')

/**
 * A "lit cell" strip — same square-cornered `0xFF161616` strip + faint
 * unlit-dot lattice as the home board's ActiveStrip, so dream rows read as
 * LED matrix cells.
 */
@Composable
private fun DreamActiveStrip(content: @Composable () -> Unit) {
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

/** TfL roundel: a coloured ring with a horizontal bar (drawn fallback). */
@Composable
private fun DreamTflRoundel(color: Color, size: androidx.compose.ui.unit.Dp) {
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
