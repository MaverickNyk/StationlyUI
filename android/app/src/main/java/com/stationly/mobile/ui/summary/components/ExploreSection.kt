package com.stationly.mobile.ui.summary.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stationly.mobile.ui.theme.TflAmber

@Composable
fun StationExploreSection() {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = "Station Intelligence",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ExploreCard(
                icon = Icons.Default.Star,
                title = "Crowd Levels",
                subtitle = "Expected light",
                modifier = Modifier.weight(1f)
            )
            ExploreCard(
                icon = Icons.Default.Notifications,
                title = "Alerts",
                subtitle = "None nearby",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ExploreCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xFF151515),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = TflAmber, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(text = subtitle, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
    }
}
