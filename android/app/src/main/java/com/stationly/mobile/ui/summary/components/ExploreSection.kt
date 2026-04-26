package com.stationly.mobile.ui.summary.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stationly.mobile.ui.theme.TflAmber

@Composable
fun StationExploreSection(
    lineStatuses: Map<String, String> = emptyMap(),
    strings: Map<String, String> = emptyMap()
) {
    val disruptions = remember(lineStatuses) {
        lineStatuses.values.count { !it.trim().lowercase().startsWith("good service") }
    }

    val travelPeriodKey = remember(strings) {
        val hour = java.time.LocalTime.now().hour
        fun bound(key: String, default: Int) = strings[key]?.toIntOrNull() ?: default
        when {
            hour in bound("explore.period.morning.start", 7)..bound("explore.period.morning.end", 9)         -> "morning"
            hour in bound("explore.period.evening.start", 17)..bound("explore.period.evening.end", 19)       -> "evening"
            hour in bound("explore.period.late_night.start", 22)..bound("explore.period.late_night.end", 23) -> "late_night"
            hour in bound("explore.period.night.start", 0)..bound("explore.period.night.end", 5)             -> "night"
            else -> "offpeak"
        }
    }

    val travelPeriod    = strings["explore.period.$travelPeriodKey"]     ?: travelPeriodKey.replace('_', ' ')
    val travelPeriodSub = strings["explore.period.${travelPeriodKey}_sub"] ?: ""

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = strings["explore.title"] ?: "Network",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val disruptionLabel = strings["explore.disruptions_label"] ?: "Disruption"
            ExploreCard(
                icon = if (disruptions == 0) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                title = if (disruptions == 0) strings["explore.good_service"] ?: "Good Service"
                        else "$disruptions $disruptionLabel${if (disruptions > 1) "s" else ""}",
                subtitle = if (disruptions == 0) strings["explore.good_service_sub"] ?: "All lines running normally"
                           else strings["explore.disruptions_sub"] ?: "Delays on network",
                accentColor = if (disruptions == 0) Color(0xFF4CAF50) else TflAmber,
                modifier = Modifier.weight(1f)
            )
            ExploreCard(
                icon = Icons.Outlined.Schedule,
                title = travelPeriod,
                subtitle = travelPeriodSub,
                accentColor = Color.Gray,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ExploreCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF0F0F0F),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                lineHeight = MaterialTheme.typography.labelSmall.lineHeight
            )
        }
    }
}
