package com.stationly.app.ui.dream

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.util.StationlyFormatters
import com.stationly.app.ui.summary.components.lineColorForTheme

/**
 * Dream summary group — line/station header, next-train hero, empty state.
 * Port of Android `dream/DreamSummary.kt`.
 */

/**
 * Floating header — no boxed Surface. Line pill (left) + live status (right).
 */
@Composable
internal fun StationHeader(
    selection: UserSelection,
    lineColor: Color,
    isDisrupted: Boolean,
    statusLine: String,
    dim: DreamDims,
) {
    val directionSize = dim.directionSize
    val statusSize    = dim.statusSize
    val themeColors   = LocalDreamColors.current
    val onCanvas      = themeColors.onCanvas

    Column(modifier = Modifier.fillMaxWidth()) {
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
                        // ("Piccadilly Line") so the same station looks the
                        // same on the home card and on the dream.
                        text = "${prettyLineName(selection.line)} Line",
                        color = onCanvas,
                        fontSize = directionSize,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // Subtle live-pulse on the status dot — tells the user the dream
            // is actually listening, not just frozen on the last paint.
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
                    // `danger` on disruption so the colour-semantics match the
                    // home Board exactly on either theme.
                    color = if (isDisrupted) themeColors.danger else onCanvas.copy(alpha = 0.70f),
                    fontSize = statusSize,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Station name intentionally omitted here — the dot-matrix board
        // below already shows it. Direction likewise (the next-train card's
        // "→ Destination" is the meaningful cue). Matches Android.
    }
}

/**
 * Next-train card. Visually mirrors the home screen's NextDepartureRow:
 * line-tinted surface, pulsing live dot, "NEXT DEPARTURE" label,
 * → destination, platform pill, monospace ETA hero and an ETA depletion bar.
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

    // `prediction.eta` is already through tickPredictions (minute-aligned
    // re-derive + per-platform bump) upstream in DreamHost — re-running the
    // rounding here would miss the bump. That rule is now shared: see
    // StationlyFormatters.displayedMinutes, which exists to state it once.
    val countdown = StationlyFormatters.displayedMinutes(prediction)
    val isDue = countdown == 0

    // ETA tile colour — semantic in dark theme (line colour as signage cue),
    // brand amber in light. "Due"/"1 min" use the brand amber, no pulse.
    val brandHi      = themeColors.brandAccent
    val etaBase      = if (isLightCanvas) brandHi else lineColor
    val etaColor = when {
        isDue          -> brandHi
        countdown == 1 -> brandHi
        else           -> etaBase
    }

    // Slow live-dot pulse only, so the card breathes without flickering.
    val infiniteTransition = rememberInfiniteTransition(label = "dream_next_train")
    val livePulse by infiniteTransition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = EaseInOut), RepeatMode.Reverse,
        ),
        label = "live_dot_alpha",
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
                // Left column — live dot + label, destination, platform pill
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
                    // Platform subtitle — raw platform value, no "Platform"
                    // prefixing (mirrors home logic).
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
                        color = etaColor,
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

            // Depletion bar — along the card's bottom edge, shrinking as the
            // train gets closer.
            Box(
                modifier = Modifier
                    .fillMaxWidth(etaProgress)
                    .height(2.dp)
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.horizontalGradient(
                            listOf(etaColor.copy(alpha = 0.85f), etaColor.copy(alpha = 0.20f))
                        )
                    )
            )
        }
    }
}

/**
 * Shown when the user has no saved stations yet — keeps the dream from
 * looking broken on a fresh install.
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
        Text(
            text = "STATIONLY",
            color = themeColors.brandAccent.copy(alpha = 0.85f),
            fontWeight = FontWeight.Black,
            fontSize = 36.sp,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Add a board on the home screen\nto see live departures here.",
            color = onCanvas.copy(alpha = 0.50f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
    }
}

/* ─── helpers ────────────────────────────────────────────────────────────── */

/**
 * Theme-aware line colour for dream canvas composables — canonical TfL
 * palette on the light canvas, brightened dark-theme overrides on the dark
 * canvas (via the shared [lineColorForTheme]).
 */
@Composable
internal fun lineColorOf(line: String?): Color {
    val isDark = LocalDreamColors.current === DarkDreamColors
    return lineColorForTheme(line, isDark)
}

internal fun prettyLineName(line: String): String =
    line.replaceFirstChar { it.uppercase() }
