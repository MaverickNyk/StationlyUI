package com.stationly.app.ui.summary.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.theme.TflAmber
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SduiWidgetComponent
import com.stationly.core.model.sdui.SduiWidgetPayload
import com.stationly.core.util.GlobalBoardProcessor
import kotlinx.coroutines.delay

val TFL_LINE_COLORS = mapOf(
    "bakerloo"          to Color(0xFFB36305),
    "central"           to Color(0xFFE32017),
    "circle"            to Color(0xFFFFD300),
    "district"          to Color(0xFF00782A),
    "hammersmith-city"  to Color(0xFFF3A9BB),
    "jubilee"           to Color(0xFFA0A5A9),
    "metropolitan"      to Color(0xFF9B0056),
    "northern"          to Color(0xFF888888),
    "piccadilly"        to Color(0xFF003688),
    "victoria"          to Color(0xFF0098D4),
    "waterloo-city"     to Color(0xFF95CDBA),
    "dlr"               to Color(0xFF00A4A7),
    "elizabeth"         to Color(0xFF6950A1),
    "lioness"           to Color(0xFFE2A12B),
    "mildmay"           to Color(0xFF1A6DB4),
    "windrush"          to Color(0xFFE2231A),
    "weaver"            to Color(0xFF7B2D8B),
    "suffragette"       to Color(0xFF00843D),
    "liberty"           to Color(0xFF6B717E),
    "tram"              to Color(0xFF84B817),
    "cable-car"         to Color(0xFFE21836),
)

private fun modeEmoji(mode: String): String = when (mode.lowercase()) {
    "tube"             -> "🚇"
    "bus"              -> "🚌"
    "dlr"              -> "🚂"
    "overground"       -> "🚆"
    "elizabeth-line"   -> "🟣"
    "tram"             -> "🚋"
    "cable-car"        -> "🚡"
    else               -> "🚉"
}

@Composable
fun Board(
    selection: UserSelection,
    predictions: List<PredictionDisplay>,
    hasPredictions: Boolean,
    lineStatus: String?,
    lineStatusFailed: Boolean = false,
    sduiPayload: SduiWidgetPayload? = null,
    lastUpdated: Long,
    onDelete: () -> Unit,
    nextPrediction: PredictionDisplay? = null,
    homeConfig: Map<String, String> = emptyMap(),
    isDeleting: Boolean = false
) {
    val lineColor = TFL_LINE_COLORS[selection.line.lowercase()] ?: TflAmber

    val isDisrupted = lineStatus != null &&
        !lineStatus.trim().lowercase().startsWith("good service")
    val disruptionSeverity = if (isDisrupted && lineStatus?.contains(":") == true)
        lineStatus.substringBefore(":").trim() else lineStatus?.trim() ?: ""
    val disruptionReason = if (isDisrupted && lineStatus?.contains(":") == true)
        lineStatus.substringAfter(":").trim() else ""

    var showFullReason by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isUrgent = remember(nextPrediction) {
        nextPrediction != null && (nextPrediction.isDue ||
            nextPrediction.eta.replace(" min", "").trim().toIntOrNull()?.let { it <= 1 } == true)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "board_fx")

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.06f, targetValue = 0.18f,
        animationSpec = infiniteRepeatable(tween(3200, easing = EaseInOut), RepeatMode.Reverse),
        label = "glow"
    )

    val borderPulse by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse),
        label = "border_pulse"
    )
    val borderAlpha = if (isUrgent) 0.35f + borderPulse * 0.55f else 0.22f

    Box(modifier = Modifier.fillMaxWidth()) {
        // Ambient glow
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    clip = false
                    scaleX = 1.18f
                    scaleY = 1.22f
                    alpha = glowAlpha
                }
                .background(
                    Brush.radialGradient(listOf(lineColor.copy(alpha = 0.55f), Color.Transparent)),
                    RoundedCornerShape(20.dp)
                )
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF0C0C0C),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                width = if (isUrgent) 1.5.dp else 1.dp,
                color = lineColor.copy(alpha = borderAlpha)
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {

                // Header: mode emoji + line badge + station name + delete
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 10.dp, top = 13.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = modeEmoji(selection.mode),
                            fontSize = 18.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Surface(
                                color = lineColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(5.dp)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(7.dp).background(lineColor, CircleShape))
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = selection.line.replaceFirstChar { it.uppercase() } + " Line",
                                        color = lineColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = selection.stationName,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 19.sp,
                                letterSpacing = (-0.3).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Remove board",
                            tint = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Next departure strip
                if (nextPrediction != null) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                    NextDepartureRow(nextPrediction, lineColor)
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }

                // Disruption banner
                if (isDisrupted) {
                    Surface(
                        color = Color(0xFF1A0E00),
                        shape = RoundedCornerShape(0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showFullReason = !showFullReason }
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Warning,
                                        contentDescription = null,
                                        tint = TflAmber,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = disruptionSeverity,
                                        color = TflAmber,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                if (disruptionReason.isNotEmpty()) {
                                    Icon(
                                        if (showFullReason) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = showFullReason && disruptionReason.isNotEmpty(),
                                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                                exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                            ) {
                                Text(
                                    text = disruptionReason,
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }

                // Departure board content
                if (sduiPayload != null) {
                    SduiBoardContent(sduiPayload, lineColor)
                } else {
                    LegacyBoardContent(predictions, selection, lineStatus, homeConfig, lineColor)
                }

                // Status bar
                val statusText = when {
                    lineStatus != null -> lineStatus
                    lineStatusFailed  -> homeConfig["board.status_failed_label"] ?: "Status unavailable"
                    else              -> homeConfig["board.connecting_label"] ?: "Connecting to TfL…"
                }
                Surface(color = Color(0xFF0A0A0A)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .background(
                                    if (lineStatus != null && !isDisrupted) Color(0xFF4CAF50)
                                    else if (isDisrupted) TflAmber
                                    else Color.Gray,
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = statusText,
                            color = Color.Gray,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        val dangerRed = Color(0xFFFF4444)
        val white90 = Color.White.copy(alpha = 0.90f)
        val white55 = Color.White.copy(alpha = 0.55f)
        val white25 = Color.White.copy(alpha = 0.25f)
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color(0xFF1C1C1C),
            titleContentColor = white90,
            textContentColor = white55,
            icon = {
                Icon(Icons.Rounded.DeleteOutline, null, tint = dangerRed, modifier = Modifier.size(28.dp))
            },
            title = { Text("Delete This Board?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "You're about to remove your ${selection.stationName} board.",
                        fontWeight = FontWeight.Medium
                    )
                    BoardDeleteBullet("Live departure tracking will stop", dangerRed, white55)
                    BoardDeleteBullet("Departure notifications will be unsubscribed", dangerRed, white55)
                    BoardDeleteBullet("Widget will be cleared", dangerRed, white55)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "You can always set up a new board from the home screen.",
                        color = white25, fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteDialog = false; onDelete() },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = dangerRed)
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(color = dangerRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Text("Delete Board", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isDeleting) {
                    TextButton(
                        onClick = { showDeleteDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = white55)
                    ) { Text("Keep It") }
                }
            }
        )
    }
}

@Composable
private fun SduiBoardContent(payload: SduiWidgetPayload, lineColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C0C0C))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        payload.components.forEach { component ->
            when (component) {
                is SduiWidgetComponent.Header -> {
                    val headerColor = component.color
                    Text(
                        text = component.title,
                        color = if (!headerColor.isNullOrBlank())
                            parseColorSafe(headerColor, lineColor) else lineColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                is SduiWidgetComponent.Row -> {
                    val isDue = component.eta.trim().lowercase() == "due"
                    val rowEtaColor = component.etaColor
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = component.destination,
                            color = lineColor,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        val etaColor = when {
                            !rowEtaColor.isNullOrBlank() -> parseColorSafe(rowEtaColor, lineColor)
                            isDue -> Color(0xFFFF5252)
                            else -> lineColor
                        }
                        Text(
                            text = component.eta,
                            color = etaColor,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                is SduiWidgetComponent.Message -> {
                    val msgColor = component.color
                    Text(
                        text = component.text,
                        color = if (!msgColor.isNullOrBlank())
                            parseColorSafe(msgColor, lineColor) else lineColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun LegacyBoardContent(
    predictions: List<PredictionDisplay>,
    selection: UserSelection,
    lineStatus: String?,
    homeConfig: Map<String, String>,
    lineColor: Color
) {
    val legacySeverity = lineStatus?.let {
        if (it.contains(":")) it.substringBefore(":").trim() else it.trim()
    }
    val legacyReason = lineStatus?.let {
        if (it.contains(":")) it.substringAfter(":").trim().takeIf { r -> r.isNotBlank() } else null
    }
    val legacyRows = GlobalBoardProcessor.prepareLegacyRows(
        predictions, selection.line, true,
        lineStatusSeverity = legacySeverity,
        lineStatusReason = legacyReason
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C0C0C))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        legacyRows.forEach { row ->
            when (row) {
                is com.stationly.core.util.LegacyRow.Header -> {
                    Text(
                        text = row.title,
                        color = lineColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                is com.stationly.core.util.LegacyRow.Departure -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = row.destination,
                            color = lineColor,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (row.eta.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = row.eta,
                                color = lineColor,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                is com.stationly.core.util.LegacyRow.Message -> {
                    Text(
                        text = row.text,
                        color = lineColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun NextDepartureRow(prediction: PredictionDisplay, lineColor: Color) {
    val initialMinutes = remember(prediction) {
        when {
            prediction.isDue -> 0
            prediction.eta.trim().lowercase() == "due" -> 0
            else -> prediction.eta.replace(" min", "").trim().toIntOrNull() ?: 0
        }
    }
    var countdown by remember(prediction) { mutableIntStateOf(initialMinutes) }
    var isDeparted by remember(prediction) { mutableStateOf(false) }
    LaunchedEffect(prediction) {
        while (countdown > 0) { delay(60_000L); countdown = (countdown - 1).coerceAtLeast(0) }
        delay(90_000L)
        isDeparted = true
    }

    val isDue = countdown == 0 && !isDeparted
    val infiniteTransition = rememberInfiniteTransition(label = "due_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse),
        label = "due_alpha"
    )
    val rowAlpha by animateFloatAsState(
        targetValue = if (isDeparted) 0.35f else 1f,
        animationSpec = tween(600),
        label = "departed_fade"
    )
    val etaColor = when {
        isDeparted   -> Color.White.copy(alpha = 0.4f)
        isDue        -> Color(0xFFFF5252)
        countdown == 1 -> TflAmber
        else         -> Color.White
    }
    val etaProgress by animateFloatAsState(
        targetValue = (countdown / 10f).coerceIn(0f, 1f),
        animationSpec = tween(800),
        label = "eta_progress"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = rowAlpha }
            .background(lineColor.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val livePulse by rememberInfiniteTransition(label = "live_dot").animateFloat(
                    initialValue = 0.4f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
                    label = "live_dot_alpha"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .graphicsLayer { alpha = livePulse }
                            .background(lineColor, CircleShape)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "NEXT DEPARTURE",
                        color = Color.Gray.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "→ ${prediction.destination}",
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (prediction.platform.isNotBlank() && prediction.platform != "null") {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = prediction.platform,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when {
                        isDeparted -> "──"
                        isDue      -> "Due"
                        else       -> "$countdown"
                    },
                    color = etaColor.copy(alpha = if (isDue) pulseAlpha else 1f),
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (!isDue && !isDeparted) {
                    Text(
                        "min",
                        color = etaColor.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(etaProgress)
                .height(2.dp)
                .align(Alignment.BottomStart)
                .background(
                    Brush.horizontalGradient(
                        listOf(etaColor.copy(alpha = 0.9f), etaColor.copy(alpha = 0.2f))
                    )
                )
        )
    }
}

@Composable
private fun BoardDeleteBullet(text: String, dangerRed: Color, white55: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .background(dangerRed.copy(alpha = 0.6f), CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = white55, fontSize = 14.sp)
    }
}

private fun parseColorSafe(hex: String, fallback: Color): Color {
    return try {
        val clean = hex.trim().removePrefix("#")
        val argb = when (clean.length) {
            6 -> "FF$clean".toLong(16)
            8 -> clean.toLong(16)
            else -> return fallback
        }
        Color(argb.toInt())
    } catch (_: Exception) {
        fallback
    }
}
