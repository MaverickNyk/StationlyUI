package com.stationly.mobile.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.mobile.ui.theme.TflAmber

private val Surface1 = Color(0xFF141414)
private val White90 = Color.White.copy(alpha = 0.90f)
private val White55 = Color.White.copy(alpha = 0.55f)
private val White25 = Color.White.copy(alpha = 0.25f)
private val White08 = Color.White.copy(alpha = 0.08f)

/**
 * Renders a list of SDUI components for non-form screens (Profile, Summary).
 * Handles: Card, Section, LinkRow, Text, Divider, Spacer, Announcement.
 */
@Composable
fun SduiComponentRenderer(
    components: List<SduiAppComponent>,
    primaryColor: Color = TflAmber
) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    components.forEach { component ->
        when (component) {
            is SduiAppComponent.Card -> SduiCard(component, primaryColor)
            is SduiAppComponent.Section -> SduiSection(component, primaryColor, ::openUrl)
            is SduiAppComponent.LinkRow -> SduiLinkRow(component, ::openUrl)
            is SduiAppComponent.Divider -> HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 0.5.dp,
                color = White08
            )
            is SduiAppComponent.Spacer -> Spacer(Modifier.height(component.size.dp))
            is SduiAppComponent.Text -> SduiText(component)
            is SduiAppComponent.Announcement -> {} // handled separately via AnnouncementBanner
            else -> {}
        }
    }
}

@Composable
fun SduiCard(component: SduiAppComponent.Card, primaryColor: Color = TflAmber) {
    val isSubtle = component.style == "subtle"
    val isBrand = component.style == "brand"
    val title = component.title
    val body = component.body
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            if (isBrand && title != null) {
                Text(
                    title,
                    color = primaryColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(6.dp))
            } else if (title != null) {
                Text(
                    title,
                    color = White90,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(6.dp))
            }
            if (body != null) {
                Text(
                    body,
                    color = if (isSubtle) White25 else White55,
                    fontSize = if (isSubtle) 11.sp else 13.sp,
                    lineHeight = if (isSubtle) 15.sp else 19.sp
                )
            }
            // Nested components
            component.components.forEach { child ->
                when (child) {
                    is SduiAppComponent.Text -> SduiText(child)
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun SduiSection(
    component: SduiAppComponent.Section,
    primaryColor: Color = TflAmber,
    onOpenUrl: (String) -> Unit
) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Column {
            component.components.forEachIndexed { index, child ->
                when (child) {
                    is SduiAppComponent.LinkRow -> SduiLinkRow(child, onOpenUrl)
                    is SduiAppComponent.Divider -> HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = White08
                    )
                    else -> {}
                }
                // Auto-divider between link rows
                if (index < component.components.size - 1 &&
                    child is SduiAppComponent.LinkRow &&
                    component.components.getOrNull(index + 1) is SduiAppComponent.LinkRow
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = White08
                    )
                }
            }
        }
    }
}

@Composable
fun SduiLinkRow(component: SduiAppComponent.LinkRow, onOpenUrl: (String) -> Unit) {
    val icon = iconFromString(component.icon)
    val subtitle = component.subtitle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(component.url) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(White08, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(icon, null, tint = White55, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(component.title, color = White90, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            if (subtitle != null) {
                Text(subtitle, color = White25, fontSize = 12.sp)
            }
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = White25, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SduiText(component: SduiAppComponent.Text) {
    val isBold = component.style in listOf("bold", "title")
    Text(
        text = component.text,
        color = if (isBold) White90 else White55,
        fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = if (component.style == "title") 16.sp else 13.sp
    )
}

/**
 * Renders a dismissible announcement banner on the home screen.
 * Call this at the top of the board list when the ViewModel exposes a non-null announcement.
 */
@Composable
fun AnnouncementBanner(
    announcement: SduiAppComponent.Announcement,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
                    color = White55,
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
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(announcement.url))) }
                        }
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Dismiss",
                tint = White25,
                modifier = Modifier.size(16.dp).clickable { onDismiss() }
            )
        }
    }
}

private fun iconFromString(name: String?): ImageVector? = when (name) {
    "public"      -> Icons.Outlined.Public
    "privacy_tip" -> Icons.Outlined.PrivacyTip
    "description" -> Icons.Outlined.Description
    "email"       -> Icons.Outlined.Email
    "star"        -> Icons.Outlined.Star
    "info"        -> Icons.Outlined.Info
    "warning"     -> Icons.Outlined.Warning
    "settings"    -> Icons.Outlined.Settings
    else          -> null
}
