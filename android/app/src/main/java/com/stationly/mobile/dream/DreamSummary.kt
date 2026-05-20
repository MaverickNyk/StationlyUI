package com.stationly.mobile.dream

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.mobile.ui.summary.components.TFL_LINE_COLORS
import com.stationly.mobile.ui.theme.TflAmber

/* ─────────────────────────────────────────────────────────────────────────
 * SUMMARY GROUP — line/station/direction header, next-train hero, empty state.
 * Composed by both LandscapeLayout and PortraitLayout above the DreamBoard.
 * ───────────────────────────────────────────────────────────────────────── */

/**
 * Floating header — no boxed Surface. Line pill, station name, direction,
 * status dot. Sits at the top of the right panel and lets the canvas breathe.
 */
@Composable
internal fun StationHeader(
    selection: UserSelection,
    lineColor: Color,
    isDisrupted: Boolean,
    statusLine: String,
    dim: DreamDims,
) {
    val titleSize     = dim.titleSize
    val directionSize = dim.directionSize
    val statusSize    = dim.statusSize

    Column(modifier = Modifier.fillMaxWidth()) {
        // Line pill (left) + live status (right)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = lineColor.copy(alpha = 0.18f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(lineColor)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "${prettyLineName(selection.line)} · ${modeLabel(selection.mode)}",
                        color = lineColor,
                        fontSize = directionSize,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // Subtle live-pulse on the status dot — tells the user the dream is
            // actually listening, not just frozen on the last paint.
            val transition = rememberInfiniteTransition(label = "status_pulse")
            val pulse by transition.animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(durationMillis = 1100, easing = EaseInOut),
                    RepeatMode.Reverse,
                ),
                label = "pulse_alpha"
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            (if (isDisrupted) lineColor else Color(0xFF4ADE80))
                                .copy(alpha = pulse)
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusLine.substringAfter("· ").ifBlank { statusLine },
                    color = if (isDisrupted) lineColor else Color.White.copy(alpha = 0.70f),
                    fontSize = statusSize,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Station name — the headline, painted in TfL amber to match the
        // dot-matrix departure board text. Brand consistency with the widget.
        Text(
            text = selection.stationName,
            color = TflAmber,
            fontWeight = FontWeight.ExtraBold,
            fontSize = titleSize,
            letterSpacing = (-0.4).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (selection.direction.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            // Direction in a line-coloured pill — distinct from the platform
            // pill below (which is white). Together they create a clear
            // visual rhythm: "station/direction" group (line colour) vs
            // "next train/platform" group (white).
            Surface(
                color = lineColor.copy(alpha = 0.10f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, lineColor.copy(alpha = 0.45f)),
            ) {
                Text(
                    text = "→ ${selection.direction.replaceFirstChar { it.uppercase() }}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    color = lineColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = directionSize,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

/**
 * Two-line next-train block.
 *
 *   Line 1:  ● NEXT TRAIN → Destination               7 min
 *   Line 2:  Platform 1
 *
 * Designed to read like an indicator strip — quick scan from left (what
 * matters first) to right (when it's coming).
 */
@Composable
internal fun NextTrainHero(
    prediction: PredictionDisplay,
    lineColor: Color,
    dim: DreamDims,
) {
    val labelSize = dim.labelSize
    val destSize  = dim.destSize
    val platSize  = dim.platSize
    val etaSize   = dim.etaSize
    val minSize   = dim.minSize

    Column(modifier = Modifier.fillMaxWidth()) {
        // Line 1 — label + destination + ETA on one line.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(lineColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "NEXT TRAIN",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = labelSize,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "→",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = destSize,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = prediction.destination,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = destSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.weight(1f),
            )
            // ETA — right-aligned, line-coloured.
            Row(verticalAlignment = Alignment.Bottom) {
                val etaText = if (prediction.isDue) "Due" else
                    prediction.eta.replace(" min", "").trim()
                Text(
                    text = etaText,
                    color = lineColor,
                    fontWeight = FontWeight.Black,
                    fontSize = etaSize,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = (-1).sp,
                )
                if (!prediction.isDue) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "min",
                        color = lineColor.copy(alpha = 0.70f),
                        fontSize = minSize,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }

        // Line 2 — platform in a clearly visible bordered box, white text,
        // attached to the left edge (no indent).
        val platform = prediction.platform.takeIf {
            it.isNotBlank() && !it.equals("null", true) && !it.equals("Unknown", true)
        }
        if (platform != null) {
            Spacer(Modifier.height(8.dp))
            val label = if (platform.startsWith("Platform", ignoreCase = true)) platform
                        else "Platform $platform"
            Surface(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    color = Color.White,
                    fontSize = platSize,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

/**
 * Shown when the user has no saved stations yet — keeps the dream from looking
 * broken on a fresh install.
 */
@Composable
internal fun EmptyStatePanel(lineColor: Color) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "STATIONLY",
            color = lineColor.copy(alpha = 0.75f),
            fontWeight = FontWeight.Black,
            fontSize = 36.sp,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Add a board on the home screen\nto see live arrivals here.",
            color = Color.White.copy(alpha = 0.50f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
    }
}

/* ─── helpers ────────────────────────────────────────────────────────────── */

internal fun lineColorOf(line: String?): Color {
    val key = line?.lowercase() ?: return TflAmber
    return TFL_LINE_COLORS[key] ?: TflAmber
}

private fun prettyLineName(line: String): String =
    line.replaceFirstChar { it.uppercase() }

private fun modeLabel(mode: String): String = when (mode.lowercase()) {
    "tube"           -> "UNDERGROUND"
    "overground"     -> "OVERGROUND"
    "elizabeth-line" -> "ELIZABETH"
    "dlr"            -> "DLR"
    "tram"           -> "TRAM"
    "bus"            -> "BUS"
    "national-rail"  -> "RAIL"
    "river-bus"      -> "RIVER"
    else             -> mode.uppercase()
}

/** First non-empty prediction the snapshot can show in the headline strip. */
internal val DreamSnapshot.nextDeparture: PredictionDisplay?
    get() = predictions.firstOrNull { it.destination.isNotBlank() && it.eta.isNotBlank() }
