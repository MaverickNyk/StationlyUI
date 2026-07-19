package com.stationly.app.ui.dream

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dream clocks — kotlinx.datetime port of Android `dream/DreamClock.kt`.
 * Digital signage numerals + TfL-roundel-inspired analog dial + the shared
 * ClockPanel chooser and ambient glow.
 */

/**
 * Ticks on every second boundary without drift; emits the local wall time.
 */
@Composable
internal fun rememberClockNow(): State<LocalDateTime> {
    val tz = TimeZone.currentSystemDefault()
    val now = remember { mutableStateOf(Clock.System.now().toLocalDateTime(tz)) }
    LaunchedEffect(Unit) {
        while (true) {
            val instant = Clock.System.now()
            now.value = instant.toLocalDateTime(tz)
            // Sleep just past the next second boundary
            delay(1000L - (instant.toEpochMilliseconds() % 1000L))
        }
    }
    return now
}

private fun Int.pad2(): String = toString().padStart(2, '0')

/**
 * Digital clock — signage-style amber numerals in a monospaced font. The
 * day-and-date is rendered separately by `DateAndWeatherStrip` so it appears
 * below both analog and digital variants identically.
 */
@Composable
fun DigitalClock(
    modifier: Modifier = Modifier,
    timeSize: TextUnit,
) {
    val now by rememberClockNow()
    // Theme-aware brand colour — TfL amber on dark, deep amber on light.
    val amber = LocalDreamColors.current.brandAccent

    Text(
        modifier = modifier,
        text = "${now.hour.pad2()}:${now.minute.pad2()}",
        color = amber,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        fontSize = timeSize,
        letterSpacing = (-3).sp,
        // Always render as "HH:mm" on a single line — never break to two
        // lines when the container is narrow.
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * Analog clock — TfL-roundel inspired. Amber ring + hour markers, themed
 * minute hand, thin amber seconds hand, amber centre cap.
 */
@Composable
fun AnalogClock(
    modifier: Modifier = Modifier,
    size: Dp,
) {
    val now by rememberClockNow()
    val themeColors = LocalDreamColors.current
    val amber = themeColors.brandAccent
    val onCanvas = themeColors.onCanvas
    // No dial fill — the canvas IS the dial (see Android original).
    val ringColor    = amber
    val secondsColor = amber.copy(alpha = 0.80f)
    val minuteHandColor = onCanvas.copy(alpha = 0.90f)
    val tickDim = onCanvas.copy(alpha = 0.18f)

    val seconds = now.second + now.nanosecond / 1_000_000_000f
    val minutes = now.minute + seconds / 60f
    val hours   = (now.hour % 12) + minutes / 60f

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val radius = (this.size.minDimension / 2f) - 8.dp.toPx()

            // Outer ring only — no dial fill.
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

            // Seconds hand — thinnest, amber tinted down
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

            // Centre cap — simple amber dot
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
 * Renders whichever clock style the user picked, sized to fill its slot up
 * to the [DreamDims] caps — phone landscape gets a small clock because 30%
 * of a small screen IS small; tablet portrait caps at the dim maximum.
 */
@Composable
internal fun ClockPanel(style: ClockStyle, dim: DreamDims) {
    BoxWithConstraints {
        when (style) {
            ClockStyle.DIGITAL -> {
                // "HH:mm" monospaced is ≈ 3.0 × sp wide. Use 92% of the slot
                // for breathing room; 56sp floor keeps phone landscape readable.
                val fillSp = (maxWidth.value * 0.92f) / 3.0f
                val safeSp = fillSp
                    .coerceAtMost(dim.digitalTimeSize.value)
                    .coerceAtLeast(56f)
                DigitalClock(timeSize = safeSp.sp)
            }
            ClockStyle.ANALOG -> {
                // Analog dial fills 85% of the slot's shorter side, capped.
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
