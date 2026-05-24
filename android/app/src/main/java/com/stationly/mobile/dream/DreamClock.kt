package com.stationly.mobile.dream

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Suspends until the next second-boundary, then emits true forever every second.
 * Used by the clocks so they tick cleanly without drift.
 */
@Composable
internal fun rememberClockNow(): State<Date> {
    val now = remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.value = Date()
            // Sleep just past the next second boundary
            delay(1000L - (System.currentTimeMillis() % 1000L))
        }
    }
    return now
}

/**
 * Digital clock — signage-style amber numerals in a monospaced font. The
 * day-and-date is rendered separately by `DateAndWeatherStrip` so it appears
 * below both analog and digital variants identically.
 */
@Composable
fun DigitalClock(
    modifier: Modifier = Modifier,
    timeSize: androidx.compose.ui.unit.TextUnit,
) {
    val now by rememberClockNow()
    // Theme-aware brand colour — TfL amber on dark, deep amber on light.
    val amber = LocalDreamColors.current.brandAccent
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.UK) }

    Text(
        modifier = modifier,
        text = timeFmt.format(now),
        color = amber,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        fontSize = timeSize,
        letterSpacing = (-3).sp,
        // Always render as "HH:mm" on a single line — never break to two
        // lines when the container is narrow. softWrap=false + maxLines=1
        // is the right combination: it tells Compose to lay the text out
        // in one line and never split, even if it means hugging horizontal
        // edges. The clock container is full-width on both portrait and
        // landscape so this fits comfortably.
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * Analog clock — TfL-roundel inspired. Red ring on dark, amber hour markers
 * at the 12/3/6/9 positions, white minute hand, amber hour hand, narrow red
 * seconds hand. Centre cap is amber matching the brand.
 */
@Composable
fun AnalogClock(
    modifier: Modifier = Modifier,
    size: Dp,
) {
    val now by rememberClockNow()
    val cal = remember(now) { Calendar.getInstance().apply { time = now } }
    // Theme-aware colours. The roundel red stays constant (it's the TfL
    // brand mark). Everything else (dial fill, hour markers, minute hand,
    // STATIONLY caption) flips with the canvas so an analog clock on a
    // light dream actually reads light.
    val themeColors = LocalDreamColors.current
    val amber = themeColors.brandAccent
    val onCanvas = themeColors.onCanvas
    // No dial fill — the canvas IS the dial. The brand-amber ring frames
    // the hour markers and hands directly on the canvas, so the clock
    // feels integrated with the dream rather than punching a separate
    // panel through it.
    val ringColor    = amber
    val secondsColor = amber.copy(alpha = 0.80f)
    val minuteHandColor = onCanvas.copy(alpha = 0.90f)
    val tickDim = onCanvas.copy(alpha = 0.18f)
    val px = with(LocalDensity.current) { size.toPx() }

    val seconds = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
    val minutes = cal.get(Calendar.MINUTE) + seconds / 60f
    val hours   = (cal.get(Calendar.HOUR) % 12) + minutes / 60f

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val radius = (this.size.minDimension / 2f) - 8.dp.toPx()

            // Outer ring only — no dial fill. The canvas shows through,
            // so the clock reads as a brand-amber circle drawn on the
            // dream's surface rather than a separate panel.
            drawCircle(
                color = ringColor,
                radius = radius,
                center = center,
                style = Stroke(width = 6.dp.toPx())
            )

            // Hour markers — 12 short ticks, with the four cardinal ones longer/amber
            for (i in 0 until 12) {
                val a = (i * 30 - 90) * PI.toFloat() / 180f
                val isCardinal = i % 3 == 0
                val tickInner = if (isCardinal) radius - 18.dp.toPx() else radius - 10.dp.toPx()
                val tickOuter = radius - 4.dp.toPx()
                drawLine(
                    color = if (isCardinal) amber else tickDim,
                    start = Offset(
                        center.x + cos(a) * tickInner,
                        center.y + sin(a) * tickInner
                    ),
                    end = Offset(
                        center.x + cos(a) * tickOuter,
                        center.y + sin(a) * tickOuter
                    ),
                    strokeWidth = if (isCardinal) 3.dp.toPx() else 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Hour hand — short, amber, thick
            run {
                val a = (hours * 30 - 90) * PI.toFloat() / 180f
                val len = radius * 0.55f
                drawLine(
                    color = amber,
                    start = center,
                    end = Offset(center.x + cos(a) * len, center.y + sin(a) * len),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Minute hand — longer, themed onCanvas
            run {
                val a = (minutes * 6 - 90) * PI.toFloat() / 180f
                val len = radius * 0.78f
                drawLine(
                    color = minuteHandColor,
                    start = center,
                    end = Offset(center.x + cos(a) * len, center.y + sin(a) * len),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Seconds hand — thinnest, brand amber tinted down so it
            // doesn't compete with the cardinal markers and hour hand.
            run {
                val a = (seconds * 6 - 90) * PI.toFloat() / 180f
                val len = radius * 0.85f
                drawLine(
                    color = secondsColor,
                    start = center,
                    end = Offset(center.x + cos(a) * len, center.y + sin(a) * len),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Centre cap — simple amber dot, no pin-hole (the cap reads
            // cleanly without one now that the dial is the canvas).
            drawCircle(
                color = amber,
                radius = 5.dp.toPx(),
                center = center
            )
        }

        // STATIONLY label inside the dial at the bottom — subtle product mark
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = size * 0.18f)
        ) {
            Text(
                text = "STATIONLY",
                color = amber.copy(alpha = 0.55f),
                fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.045f).sp,
                letterSpacing = 2.sp
            )
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────────
 * CLOCK PANEL — chooses between digital and analog, fills its slot.
 * ───────────────────────────────────────────────────────────────────────── */

/**
 * Slow-breathing line-coloured ambient glow drawn behind the clock. Very
 * subtle — alpha caps out around 12% — but it ties the clock panel to the
 * line you're watching.
 */
@Composable
internal fun ClockAmbientGlow(lineColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "clock_glow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.04f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 4500, easing = EaseInOut),
            RepeatMode.Reverse,
        ),
        label = "glow_alpha"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(
                Brush.radialGradient(
                    colors = listOf(lineColor, Color.Transparent),
                    radius = 480f,
                )
            )
    )
}

/**
 * Renders whichever clock style the user picked, sized to fill its slot.
 *
 * Sizing strategy — fill the slot, capped at a sensible maximum:
 *
 *   The clock grows to comfortably fill whatever box it lives in (full
 *   width in portrait; the 30% column in landscape), so:
 *     - phone landscape (small 30% column) → small clock automatically
 *     - tablet landscape (big 30% column)  → big clock automatically
 *     - tablet portrait  (huge full strip) → big clock, capped at dim max
 *
 *   The dim caps stop a wide slot from producing a billboard-sized clock
 *   that would crowd the date strip below or look comical on a desk.
 */
@Composable
internal fun ClockPanel(style: ClockStyle, dim: DreamDims) {
    BoxWithConstraints {
        when (style) {
            ClockStyle.DIGITAL -> {
                // "HH:mm" monospaced is ≈ 3.0 × sp wide. Use 92% of the slot
                // for breathing room. The dim cap keeps tablet portrait from
                // running away with itself; a 56sp floor keeps phone landscape
                // readable when 30% of the screen is genuinely small.
                val fillSp = (maxWidth.value * 0.92f) / 3.0f
                val safeSp = fillSp
                    .coerceAtMost(dim.digitalTimeSize.value)
                    .coerceAtLeast(56f)
                DigitalClock(timeSize = safeSp.sp)
            }
            ClockStyle.ANALOG -> {
                // Analog dial fills 85% of the slot's shorter side (so it
                // never clips on either axis), capped at the dim maximum.
                val shorter = minOf(maxWidth.value, maxHeight.value)
                val fillDp = shorter * 0.85f
                val safeDp = fillDp
                    .coerceAtMost(dim.analogClockDp.value)
                    .coerceAtLeast(120f)
                AnalogClock(size = safeDp.dp)
            }
        }
    }
}
