package com.stationly.app.ui.summary.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.theme.TflAmber
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private fun clockHM(): Pair<String, String> {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val h = now.hour.toString().padStart(2, '0')
    val m = now.minute.toString().padStart(2, '0')
    return h to m
}

private fun currentHour(): Int =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour

@Composable
fun SummaryHeader(
    count: Int,
    lastUpdated: Long,
    userName: String? = null,
    strings: Map<String, String> = emptyMap()
) {
    val greeting = remember(strings) {
        val hour = currentHour()
        when {
            hour < 12 -> strings["greeting.morning"]   ?: "Good morning"
            hour < 17 -> strings["greeting.afternoon"] ?: "Good afternoon"
            hour < 21 -> strings["greeting.evening"]   ?: "Good evening"
            else      -> strings["greeting.night"]     ?: "Good night"
        }
    }

    var clockParts by remember { mutableStateOf(clockHM()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            clockParts = clockHM()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column {
            Text(
                text = if (userName != null) "$greeting,\n$userName" else greeting,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(7.dp)
                        .background(Color(0xFF4CAF50), CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Live · $count board${if (count > 1) "s" else ""} active",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            val (h, m) = clockParts
            Text(
                text = "$h:$m",
                color = TflAmber,
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Text(
                text = strings["header.location"] ?: "London",
                color = Color.Gray,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
    }
}
