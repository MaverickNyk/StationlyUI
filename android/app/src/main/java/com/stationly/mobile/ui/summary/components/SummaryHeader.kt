package com.stationly.mobile.ui.summary.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.mobile.ui.theme.TflAmber
import kotlinx.coroutines.delay

@Composable
fun SummaryHeader(
    count: Int,
    lastUpdated: Long,
    userName: String? = null,
    strings: Map<String, String> = emptyMap()
) {
    val greeting = remember(strings) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when {
            hour < 12 -> strings["greeting.morning"]   ?: "Good morning"
            hour < 17 -> strings["greeting.afternoon"] ?: "Good afternoon"
            hour < 21 -> strings["greeting.evening"]   ?: "Good evening"
            else      -> strings["greeting.night"]     ?: "Good night"
        }
    }

    var currentTime by remember { mutableStateOf(clockString()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            currentTime = clockString()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // Left — greeting
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
                Box(
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

        // Right — station clock
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = currentTime,
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

private fun clockString(): String {
    val cal = java.util.Calendar.getInstance()
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val m = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
    return "$h:$m"
}
