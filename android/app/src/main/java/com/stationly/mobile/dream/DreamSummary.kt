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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    val themeColors   = LocalDreamColors.current
    val onCanvas      = themeColors.onCanvas
    val brandAmber    = themeColors.brandAccent

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
                        // Format matches the home screen's line pill exactly
                        // ("Piccadilly Line", "39 Line", etc.) so the same
                        // station looks the same on the home card and on
                        // the dream — no surprise re-formatting.
                        text = "${prettyLineName(selection.line)} Line",
                        color = onCanvas,
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
                // Disrupted states get the small warning glyph + danger
                // colour — same affordance as the home Board's disruption
                // banner. Good Service renders the live-pulse dot, matching
                // the "we're listening, everything's normal" cue used on
                // the home next-departure card.
                if (isDisrupted) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = themeColors.danger,
                        modifier = Modifier.size(statusSize.value.dp + 2.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(themeColors.live.copy(alpha = pulse))
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusLine.substringAfter("· ").ifBlank { statusLine },
                    // Always use the theme's `danger` token on disruption so
                    // the colour-semantics match the home Board exactly —
                    // "Severe Delays / Part Closure" reads as red on either
                    // theme, not as Piccadilly navy or Central red, which
                    // would read as "fine" alongside the line pill.
                    color = if (isDisrupted) themeColors.danger else onCanvas.copy(alpha = 0.70f),
                    fontSize = statusSize,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Station name — the headline, painted in the theme's brand
        // amber. Dark theme uses the bright TfL amber that matches the
        // dot-matrix board; light theme uses a deep amber that stays
        // legible on warm off-white.
        Text(
            text = selection.stationName,
            color = brandAmber,
            fontWeight = FontWeight.ExtraBold,
            fontSize = titleSize,
            letterSpacing = (-0.4).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Direction (Inbound/Outbound) intentionally omitted from the dream
        // header. The next-train card below already shows "→ Destination",
        // which is the more meaningful cue for an at-a-glance screensaver —
        // direction is redundant and was visual clutter the user flagged.
    }
}

/**
 * Next-train card. Visually mirrors the home screen's NextDepartureRow:
 * a line-tinted surface card with a pulsing live dot, "NEXT DEPARTURE"
 * label, → destination + ETA on one line, a small platform subtitle pill,
 * and an ETA depletion bar at the bottom that empties as the train
 * approaches.
 *
 * Typography scales with [DreamDims] so it adapts from phone landscape
 * (small slot) up to tablet portrait (chunky), keeping the same visual
 * rhythm as the home card the user sees on their phone.
 */
@Composable
internal fun NextTrainHero(
    prediction: PredictionDisplay,
    lineColor: Color,
    dim: DreamDims,
) {
    val labelSize  = dim.labelSize
    val destSize   = dim.destSize
    val platSize   = dim.platSize
    val etaSize    = dim.etaSize
    val minSize    = dim.minSize
    val themeColors = LocalDreamColors.current
    val onCanvas    = themeColors.onCanvas
    val onCanvasMute = onCanvas.copy(alpha = 0.55f)
    val isLightCanvas = themeColors === LightDreamColors

    // Drive the countdown from the prediction's absolute target time
    // plus a minute-aligned wall-clock tick — shared with the home
    // hero and the dot-matrix rows so all surfaces flip from 5→4 at
    // the same instant. A local `delay(60_000)` loop seeded from the
    // eta string used to reset every time the Syncer republished
    // (every ~30s), keeping the visible number frozen.
    val nowMs by com.stationly.mobile.ui.util.rememberMinuteTick()
    val secondsRemaining: Long = prediction.targetEpochMs?.let { (it - nowMs) / 1000 }
        ?: run {
            val parsed = when {
                prediction.isDue -> 0
                prediction.eta.trim().equals("Due", ignoreCase = true) -> 0
                else -> prediction.eta.replace(" min", "").trim().toIntOrNull() ?: 0
            }
            parsed.toLong() * 60
        }
    val countdown = when {
        secondsRemaining < 30 -> 0
        secondsRemaining < 60 -> 1
        else -> ((secondsRemaining + 30) / 60).toInt()
    }
    // Upstream tick layer (PredictionTicker.tickPredictions) drops
    // departed predictions before they reach this composable — DreamHost
    // resolves the hero from the post-tick list. So a "departed" branch
    // here is unreachable; the hero simply shifts to the next upcoming
    // train when the current one is gone.
    val isDue = countdown == 0

    // ETA tile colour — semantic in dark theme (line colour as signage cue),
    // brand amber in light (where line yellows/greys would vanish). Due
    // always wins with a danger red.
    val dueRed       = themeColors.danger
    val brandHi      = themeColors.brandAccent
    val etaBase      = if (isLightCanvas) brandHi else lineColor
    val etaColor = when {
        isDue        -> dueRed
        countdown == 1 -> brandHi
        else         -> etaBase
    }

    // Pulse for the live dot + a calmer pulse for the "Due" ETA. Both
    // intentionally slow (1.4s cycle) with a narrow alpha range so the
    // card breathes without flickering at the user from across the room.
    val infiniteTransition = rememberInfiniteTransition(label = "dream_next_train")
    val livePulse by infiniteTransition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = EaseInOut), RepeatMode.Reverse,
        ),
        label = "live_dot_alpha",
    )
    val duePulse by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = EaseInOut), RepeatMode.Reverse,
        ),
        label = "due_alpha",
    )

    // Depletion bar progress — full at 10+ min, empty at 0.
    val etaProgress = (countdown / 10f).coerceIn(0f, 1f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = onCanvas.copy(alpha = 0.04f),  // subtle "card" tint on canvas
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, lineColor.copy(alpha = 0.30f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(lineColor.copy(alpha = 0.06f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left column — live dot + label, destination, platform subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size((labelSize.value * 0.45f).dp.coerceAtLeast(5.dp))
                                .graphicsLayer { alpha = livePulse }
                                .background(lineColor, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "NEXT DEPARTURE",
                            color = onCanvasMute,
                            fontSize = labelSize,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "→ ${prediction.destination}",
                        color = onCanvas,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = destSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        letterSpacing = (-0.2).sp,
                    )
                    // Platform subtitle — mirrors home logic: show the raw
                    // platform value (e.g. "Stop not assigned", "Platform 2",
                    // "12") without prefixing "Platform" ourselves.
                    val platform = prediction.platform.takeIf {
                        it.isNotBlank() && !it.equals("null", true)
                    }
                    if (platform != null) {
                        Spacer(Modifier.height(5.dp))
                        Surface(
                            color = onCanvas.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(5.dp),
                        ) {
                            Text(
                                text = platform,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                color = onCanvas.copy(alpha = 0.75f),
                                fontSize = platSize,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp,
                            )
                        }
                    }
                }

                // Right column — ETA hero + min label
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isDue) "Due" else "$countdown",
                        color = etaColor.copy(alpha = if (isDue) duePulse else 1f),
                        fontWeight = FontWeight.Black,
                        fontSize = etaSize,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = (-1).sp,
                    )
                    if (!isDue) {
                        Text(
                            "min",
                            color = etaColor.copy(alpha = 0.60f),
                            fontSize = minSize,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }
            }

            // Depletion bar — runs left-to-right along the card's bottom edge,
            // shrinking as the train gets closer.
            Box(
                modifier = Modifier
                    .fillMaxWidth(etaProgress)
                    .height(2.dp)
                    .align(Alignment.BottomStart)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(etaColor.copy(alpha = 0.85f), etaColor.copy(alpha = 0.20f))
                        )
                    )
            )
        }
    }
}

/**
 * Shown when the user has no saved stations yet — keeps the dream from looking
 * broken on a fresh install.
 */
@Composable
internal fun EmptyStatePanel() {
    val themeColors = LocalDreamColors.current
    val onCanvas    = themeColors.onCanvas
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Brand wordmark — theme-aware amber. Fresh-install empty state
        // doesn't yet have a "line" to colour with, so the brand accent
        // (deep amber on light, bright TfL amber on dark) is the right
        // signage cue.
        Text(
            text = "STATIONLY",
            color = themeColors.brandAccent.copy(alpha = 0.85f),
            fontWeight = FontWeight.Black,
            fontSize = 36.sp,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Add a board on the home screen\nto see live arrivals here.",
            color = onCanvas.copy(alpha = 0.50f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
    }
}

/* ─── helpers ────────────────────────────────────────────────────────────── */

/**
 * Theme-aware line colour for dream canvas composables. Reads the current
 * [LocalDreamColors] to decide whether to use the canonical TfL palette
 * (light canvas — dark lines like Piccadilly navy still pop) or the
 * brightened dark-theme overrides (so Piccadilly/Suffragette/Metropolitan
 * etc. don't muddy into a near-black canvas).
 */
@Composable
internal fun lineColorOf(line: String?): Color {
    val isDark = LocalDreamColors.current === DarkDreamColors
    return com.stationly.mobile.ui.summary.components.lineColorForTheme(line, isDark)
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
