package com.stationly.mobile.ui.summary.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.core.model.PredictionDisplay
import com.stationly.mobile.ui.theme.TflAmber
import kotlinx.coroutines.delay

/**
 * Glanceable card showing the next departure above the departure board.
 * Counts down live every minute — resets when predictions refresh.
 */
@Composable
fun NextDepartureCard(
    prediction: PredictionDisplay,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    // Parse eta string like "3 min" → 3, "Due" / isDue → 0
    val initialMinutes = remember(prediction) {
        when {
            prediction.isDue -> 0
            prediction.eta.trim().lowercase() == "due" -> 0
            else -> prediction.eta.replace(" min", "").trim().toIntOrNull() ?: 0
        }
    }

    var countdown by remember(prediction) { mutableIntStateOf(initialMinutes) }

    LaunchedEffect(prediction) {
        while (countdown > 0) {
            delay(60_000L)
            countdown = (countdown - 1).coerceAtLeast(0)
        }
    }

    val isDue = countdown == 0
    val etaLabel = if (isDue) "Due" else countdown.toString()
    val unitLabel = if (isDue) "" else "min"

    val etaColor = when {
        isDue -> Color(0xFFFF4444)
        countdown == 1 -> TflAmber
        else -> Color.White
    }

    // Pulse animation when "Due"
    val infiniteTransition = rememberInfiniteTransition(label = "due_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse),
        label = "due_alpha"
    )

    Surface(
        color = Color(0xFF0D0D0D),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, lineColor.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Line colour dot
            Box(
                Modifier
                    .size(10.dp)
                    .background(lineColor, androidx.compose.foundation.shape.CircleShape)
            )
            Spacer(Modifier.width(10.dp))

            // Destination + platform
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NEXT DEPARTURE",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "→ ${prediction.destination}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (prediction.platform.isNotBlank() && prediction.platform != "null") {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = prediction.platform,
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }

            // ETA number — big and colour-coded
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(start = 16.dp)
            ) {
                Text(
                    text = etaLabel,
                    color = etaColor.copy(alpha = if (isDue) pulseAlpha else 1f),
                    fontWeight = FontWeight.Black,
                    fontSize = 38.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 38.sp
                )
                if (unitLabel.isNotEmpty()) {
                    Text(
                        text = unitLabel,
                        color = etaColor.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}
