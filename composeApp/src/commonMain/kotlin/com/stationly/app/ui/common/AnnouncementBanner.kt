package com.stationly.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.theme.TflAmber
import com.stationly.core.model.sdui.SduiAppComponent

@Composable
fun AnnouncementBanner(
    announcement: SduiAppComponent.Announcement,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val (bgColor, borderColor, iconColor) = when (announcement.variant) {
        "warning" -> Triple(Color(0xFF1A0E00), TflAmber.copy(alpha = 0.4f), TflAmber)
        "tip"     -> Triple(Color(0xFF001A0A), Color(0xFF4CAF50).copy(alpha = 0.4f), Color(0xFF4CAF50))
        else      -> Triple(Color(0xFF0A0E1A), Color(0xFF4A90D9).copy(alpha = 0.4f), Color(0xFF4A90D9))
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = when (announcement.variant) {
                    "warning" -> Icons.Outlined.Warning
                    "tip"     -> Icons.Outlined.Lightbulb
                    else      -> Icons.Outlined.Info
                },
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(18.dp).padding(top = 2.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    announcement.title,
                    color = iconColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    announcement.body,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                if (announcement.url != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Learn more",
                        color = iconColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            runCatching { uriHandler.openUri(announcement.url) }
                        }
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Dismiss",
                tint = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.size(16.dp).clickable { onDismiss() }
            )
        }
    }
}
