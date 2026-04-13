package com.stationly.mobile.ui.summary.components

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SduiWidgetComponent
import com.stationly.core.model.sdui.SduiWidgetPayload
import com.stationly.mobile.R
import com.stationly.mobile.ui.theme.TflAmber
import com.stationly.mobile.util.SduiThemeManager
import kotlinx.coroutines.delay

// Official TfL line colours
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

@Composable
fun Board(
    selection: UserSelection,
    predictions: List<PredictionDisplay>,
    hasPredictions: Boolean,
    lineStatus: String?,
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

    // ── Urgency: train arriving in ≤1 min ──
    val isUrgent = remember(nextPrediction) {
        nextPrediction != null && (nextPrediction.isDue ||
            nextPrediction.eta.replace(" min", "").trim().toIntOrNull()?.let { it <= 1 } == true)
    }

    // ── Shared animation transition ──
    val infiniteTransition = rememberInfiniteTransition(label = "board_fx")

    // Breathing ambient glow (slow, subtle)
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.06f, targetValue = 0.18f,
        animationSpec = infiniteRepeatable(tween(3200, easing = EaseInOut), RepeatMode.Reverse),
        label = "glow"
    )

    // Border urgency pulse (fast when urgent)
    val borderPulse by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse),
        label = "border_pulse"
    )
    val borderAlpha = if (isUrgent) 0.35f + borderPulse * 0.55f else 0.22f

    val boardUpdate: (View) -> Unit = { view ->
        val context = view.context
        view.findViewById<View>(R.id.btn_settings).visibility = View.GONE

        val chrono = view.findViewById<Chronometer>(R.id.last_updated_timer)
        chrono.visibility = View.VISIBLE
        chrono.format = "%s ago"
        // Call stop/base/start unconditionally. If the view isn't attached yet,
        // mStarted is set to true; Chronometer.onWindowVisibilityChanged(VISIBLE)
        // will call updateRunning() automatically once the window becomes visible.
        chrono.stop()
        chrono.base = SystemClock.elapsedRealtime()
        chrono.start()

        val statusContainer = view.findViewById<View>(R.id.status_container)
        val severityText = view.findViewById<TextView>(R.id.status_severity)
        val reasonText = view.findViewById<TextView>(R.id.status_reason)
        statusContainer.visibility = View.VISIBLE
        if (lineStatus != null) {
            val severity = if (lineStatus.contains(":")) lineStatus.substringBefore(":") else lineStatus
            val reason = if (lineStatus.contains(":")) lineStatus.substringAfter(":") else ""
            severityText.text = severity
            reasonText.text = reason
        } else {
            severityText.text = homeConfig["board.status_label"] ?: "Status"
            reasonText.text = homeConfig["board.connecting_label"] ?: "Connecting to TfL signals..."
        }
        // Reset then post the marquee re-arm so it runs after the layout pass.
        // isSelected=true needs getWidth()>0 (canMarquee), which is only true after
        // the view has been measured. post() defers to after the current frame.
        reasonText.isSelected = false
        reasonText.post { reasonText.isSelected = true }

        val rowsContainer = view.findViewById<LinearLayout>(R.id.rows_container)
        val waitingContainer = view.findViewById<LinearLayout>(R.id.waiting_container)
        rowsContainer.removeAllViews()
        var dynTextColor = context.getColor(R.color.tfl_amber)

        // Hide header row — shown in Compose header above
        (view.findViewById<TextView>(R.id.line_name).parent as? View)?.visibility = View.GONE

        if (sduiPayload != null) {
            val theme = sduiPayload.theme
            theme?.primaryColor?.let {
                dynTextColor = SduiThemeManager.parseColor(it, dynTextColor)
                view.findViewById<TextView>(R.id.line_name).setTextColor(dynTextColor)
                chrono.setTextColor(dynTextColor)
            }
            theme?.backgroundColor?.let {
                val dynBgColor = SduiThemeManager.parseColor(it, android.graphics.Color.BLACK)
                view.findViewById<LinearLayout>(R.id.departure_board).setBackgroundColor(dynBgColor)
            }
            waitingContainer.visibility = View.GONE

            sduiPayload.components.forEach { component ->
                when (component) {
                    is SduiWidgetComponent.Header -> {
                        val header = LayoutInflater.from(context).inflate(
                            R.layout.widget_platform_header, rowsContainer, false
                        )
                        val pTv = header.findViewById<TextView>(R.id.platform_name)
                        pTv.text = component.title
                        pTv.setTextColor(SduiThemeManager.parseColor(component.color, dynTextColor))
                        rowsContainer.addView(header)
                    }
                    is SduiWidgetComponent.Row -> {
                        val row = LayoutInflater.from(context).inflate(
                            R.layout.widget_departure_row, rowsContainer, false
                        )
                        val nTv = row.findViewById<TextView>(R.id.departure_number)
                        val dTv = row.findViewById<TextView>(R.id.destination_text)
                        val eTv = row.findViewById<TextView>(R.id.eta_text)
                        nTv.text = component.index
                        dTv.text = component.destination
                        eTv.text = component.eta
                        nTv.setTextColor(dynTextColor)
                        dTv.setTextColor(dynTextColor)
                        eTv.setTextColor(SduiThemeManager.parseColor(component.etaColor, dynTextColor))
                        if (component.animation == "pulse" && component.eta == "Due") {
                            val anim = android.view.animation.AlphaAnimation(1f, 0.4f).apply {
                                duration = 1000
                                repeatMode = android.view.animation.Animation.REVERSE
                                repeatCount = android.view.animation.Animation.INFINITE
                            }
                            row.startAnimation(anim)
                        } else {
                            row.clearAnimation()
                        }
                        rowsContainer.addView(row)
                    }
                    is SduiWidgetComponent.Message -> {
                        val row = LayoutInflater.from(context).inflate(
                            R.layout.widget_departure_row, rowsContainer, false
                        )
                        row.findViewById<TextView>(R.id.departure_number).apply {
                            text = "-"; setTextColor(dynTextColor)
                        }
                        val dTv = row.findViewById<TextView>(R.id.destination_text)
                        dTv.text = component.text
                        dTv.setTextColor(SduiThemeManager.parseColor(component.color, dynTextColor))
                        row.findViewById<TextView>(R.id.eta_text).text = ""
                        rowsContainer.addView(row)
                    }
                    else -> {}
                }
            }
        } else {
            waitingContainer.visibility = View.GONE
            val legacyRows = com.stationly.core.util.GlobalBoardProcessor.prepareLegacyRows(
                predictions, selection.line, true
            )
            legacyRows.forEach { row ->
                when (row) {
                    is com.stationly.core.util.LegacyRow.Header -> {
                        val header = LayoutInflater.from(context).inflate(
                            R.layout.widget_platform_header, rowsContainer, false
                        )
                        header.findViewById<TextView>(R.id.platform_name).text = row.title
                        rowsContainer.addView(header)
                    }
                    is com.stationly.core.util.LegacyRow.Departure -> {
                        val dep = LayoutInflater.from(context).inflate(
                            R.layout.widget_departure_row, rowsContainer, false
                        )
                        dep.findViewById<TextView>(R.id.departure_number).apply {
                            text = if (row.index > 0) row.index.toString() else ""
                            setTextColor(dynTextColor)
                        }
                        dep.findViewById<TextView>(R.id.destination_text).apply {
                            text = row.destination; setTextColor(dynTextColor)
                        }
                        dep.findViewById<TextView>(R.id.eta_text).apply {
                            text = row.eta; setTextColor(dynTextColor)
                        }
                        rowsContainer.addView(dep)
                    }
                    is com.stationly.core.util.LegacyRow.Message -> {
                        val header = LayoutInflater.from(context).inflate(
                            R.layout.widget_platform_header, rowsContainer, false
                        )
                        header.findViewById<TextView>(R.id.platform_name).text = row.text
                        rowsContainer.addView(header)
                    }
                }
            }
        }
    }

    // ── Outer card ──
    Box(modifier = Modifier.fillMaxWidth()) {
        // Ambient glow layer — breathes in the line's colour
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

                // ── Header: line badge + station name + delete ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 10.dp, top = 13.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        // Line colour pill
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

                    // Subtle delete button
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

                // ── Next departure strip ──
                if (nextPrediction != null) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                    NextDepartureRow(nextPrediction, lineColor)
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }

                // ── Disruption banner ──
                if (isDisrupted) {
                    Surface(
                        color = Color(0xFF1A0E00),
                        shape = RoundedCornerShape(0.dp),
                        border = BorderStroke(0.dp, Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(0.dp))
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

                // ── Departure Board ──
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        LayoutInflater.from(context)
                            .inflate(R.layout.widget_departure_board, null, false) as LinearLayout
                    },
                    update = boardUpdate,
                    onRelease = { view ->
                        view.findViewById<Chronometer>(R.id.last_updated_timer)?.stop()
                    }
                )
            }
        }
    } // end ambient glow Box

    // ── Delete board confirmation dialog ──
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
                        CircularProgressIndicator(
                            color = dangerRed,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
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

// ── Compact next departure strip ──
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
    LaunchedEffect(prediction) {
        while (countdown > 0) { delay(60_000L); countdown = (countdown - 1).coerceAtLeast(0) }
    }

    val isDue = countdown == 0
    val infiniteTransition = rememberInfiniteTransition(label = "due_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse),
        label = "due_alpha"
    )
    val etaColor = when {
        isDue        -> Color(0xFFFF5252)
        countdown == 1 -> TflAmber
        else         -> Color.White
    }

    // ETA depletion progress (0 = empty/due, 1 = 10+ min)
    val etaProgress by animateFloatAsState(
        targetValue = (countdown / 10f).coerceIn(0f, 1f),
        animationSpec = tween(800),
        label = "eta_progress"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(lineColor.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: label + destination + platform
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
                    // Platform styled as a station sign badge
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

            // Right: ETA large and prominent
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (isDue) "Due" else "$countdown",
                    color = etaColor.copy(alpha = if (isDue) pulseAlpha else 1f),
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (!isDue) {
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

        // ETA depletion bar — runs left-to-right, empties as train approaches
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
