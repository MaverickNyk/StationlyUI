package com.stationly.app.ui.sdui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.theme.TflAmber
import com.stationly.core.model.sdui.SduiAppComponent

private val Surface1 = Color(0xFF141414)
private val White90 = Color.White.copy(alpha = 0.90f)
private val White55 = Color.White.copy(alpha = 0.55f)
private val White25 = Color.White.copy(alpha = 0.25f)
private val White08 = Color.White.copy(alpha = 0.08f)

/**
 * Renders any SduiAppComponent in the profile/about SDUI context.
 * For login/selection forms, the dedicated screen composables handle rendering inline.
 */
@Composable
fun SduiRenderer(
    component: SduiAppComponent,
    onLinkOpen: (String) -> Unit = {},
    onAction: (String) -> Unit = {}
) {
    when (component) {
        is SduiAppComponent.Text -> SduiText(component)
        is SduiAppComponent.Card -> SduiCard(component, onLinkOpen, onAction)
        is SduiAppComponent.Section -> SduiSection(component, onLinkOpen, onAction)
        is SduiAppComponent.LinkRow -> SduiLinkRow(component, onLinkOpen)
        is SduiAppComponent.Button -> SduiButton(component, onAction)
        is SduiAppComponent.Divider -> HorizontalDivider(color = White08, modifier = Modifier.padding(vertical = 4.dp))
        is SduiAppComponent.Spacer -> Spacer(Modifier.height(component.size.dp))
        is SduiAppComponent.Announcement -> SduiAnnouncementBanner(component, onLinkOpen)
        else -> {} // Dropdown, Input, Location, FlowPicker, Image handled inline in their screens
    }
}

@Composable
private fun SduiText(component: SduiAppComponent.Text) {
    val (color, size, weight) = when (component.style) {
        "title"    -> Triple(White90, 20.sp, FontWeight.Bold)
        "subtitle" -> Triple(White55, 16.sp, FontWeight.SemiBold)
        "caption"  -> Triple(White25, 12.sp, FontWeight.Normal)
        "amber"    -> Triple(TflAmber, 16.sp, FontWeight.SemiBold)
        else       -> Triple(White55, 14.sp, FontWeight.Normal)
    }
    val align = when (component.textAlign) {
        "center" -> TextAlign.Center
        "end"    -> TextAlign.End
        else     -> TextAlign.Start
    }
    Text(
        text = component.text,
        color = color,
        fontSize = size,
        fontWeight = weight,
        textAlign = align,
        lineHeight = (size.value * 1.5f).sp,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun SduiCard(
    component: SduiAppComponent.Card,
    onLinkOpen: (String) -> Unit = {},
    onAction: (String) -> Unit = {}
) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            component.title?.let {
                Text(it, color = if (component.style == "brand") TflAmber else White90,
                    fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            component.body?.let {
                Text(it, color = White55, fontSize = 13.sp, lineHeight = 19.sp)
            }
            component.components.forEach { child ->
                SduiRenderer(component = child, onLinkOpen = onLinkOpen, onAction = onAction)
            }
        }
    }
}

@Composable
fun SduiSection(
    component: SduiAppComponent.Section,
    onLinkOpen: (String) -> Unit = {},
    onAction: (String) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        component.title?.let {
            Text(
                it.uppercase(), color = White25,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        component.components.forEach { child ->
            SduiRenderer(component = child, onLinkOpen = onLinkOpen, onAction = onAction)
        }
    }
}

@Composable
private fun SduiLinkRow(component: SduiAppComponent.LinkRow, onLinkOpen: (String) -> Unit) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, White08),
        modifier = Modifier.fillMaxWidth().clickable { onLinkOpen(component.url) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(component.title, color = White90, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                component.subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, color = White55, fontSize = 12.sp)
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.OpenInNew, null,
                tint = White25, modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun SduiButton(component: SduiAppComponent.Button, onAction: (String) -> Unit) {
    val isEnabled = component.enabled
    when (component.variant) {
        "secondary" -> OutlinedButton(
            onClick = { onAction(component.action) },
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (isEnabled) TflAmber.copy(alpha = 0.5f) else White08),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TflAmber)
        ) { Text(component.label, fontWeight = FontWeight.SemiBold) }

        "ghost" -> TextButton(
            onClick = { onAction(component.action) },
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = White55)
        ) { Text(component.label) }

        "danger" -> Button(
            onClick = { onAction(component.action) },
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444), contentColor = Color.White)
        ) { Text(component.label, fontWeight = FontWeight.Bold) }

        else -> Button( // primary
            onClick = { onAction(component.action) },
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TflAmber, contentColor = Color.Black)
        ) { Text(component.label, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SduiAnnouncementBanner(
    component: SduiAppComponent.Announcement,
    onLinkOpen: (String) -> Unit
) {
    val borderColor = when (component.variant) {
        "warning" -> Color(0xFFFFB300).copy(alpha = 0.5f)
        "tip"     -> Color(0xFF4CAF50).copy(alpha = 0.5f)
        else      -> TflAmber.copy(alpha = 0.3f)
    }
    val accentColor = when (component.variant) {
        "warning" -> Color(0xFFFFB300)
        "tip"     -> Color(0xFF4CAF50)
        else      -> TflAmber
    }
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = component.url?.let { url -> Modifier.fillMaxWidth().clickable { onLinkOpen(url) } }
                   ?: Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(6.dp).padding(top = 5.dp)
                    .background(accentColor, RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(component.title, color = White90, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(3.dp))
                Text(component.body, color = White55, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}
