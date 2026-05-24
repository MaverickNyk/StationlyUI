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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
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
import com.stationly.mobile.ui.theme.LocalThemeTokens
import com.stationly.mobile.ui.theme.TflAmber
import com.stationly.mobile.util.SduiThemeManager

// Official TfL line colours — canonical palette as published by TfL. Use
// [lineColorForTheme] in UI code so dark-mode-unfriendly lines (deep navy,
// dark green, dark magenta, dark purple) get a brightened variant instead
// of disappearing against a near-black canvas.
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

/**
 * Lightened variants of TfL line colours for use against the dark app/
 * dream canvas (≈ #0A0A0A). Only the lines whose canonical colour has
 * low enough luminance to muddle into the background are overridden;
 * everything else falls back to [TFL_LINE_COLORS] unchanged.
 *
 * Hues are preserved, lightness boosted enough to read clearly at small
 * pill/dot sizes without losing the line identity.
 */
val TFL_LINE_COLORS_DARK = mapOf(
    "piccadilly"   to Color(0xFF3B7AE0),  // #003688 → brighter navy
    "suffragette"  to Color(0xFF1FB54E),  // #00843D → brighter green
    "metropolitan" to Color(0xFFD14990),  // #9B0056 → brighter magenta
    "weaver"       to Color(0xFFB069BE),  // #7B2D8B → brighter purple
    "mildmay"      to Color(0xFF4C95D8),  // #1A6DB4 → brighter mid-blue
    "district"     to Color(0xFF2BB55D),  // #00782A → brighter green
    "bakerloo"     to Color(0xFFD17F2A),  // #B36305 → brighter umber
    "elizabeth"    to Color(0xFF9482D0),  // #6950A1 → brighter purple
)

/**
 * Warmed-up variants of the grey TfL lines for use on the cream light
 * canvas. The canonical greys (#888, #A0A5A9, #6B717E) all but vanish
 * against the warm off-white background — a hint of warm-grey lift
 * keeps the line identity visible without going off-brand.
 */
val TFL_LINE_COLORS_LIGHT = mapOf(
    "northern"  to Color(0xFF6E6A66),  // #888888 → warm darker grey
    "jubilee"   to Color(0xFF7A7E83),  // #A0A5A9 → mid grey with hint of warmth
    "liberty"   to Color(0xFF5A6068),  // #6B717E → deeper warm grey
)

/**
 * Pick the right TfL line colour for the current theme.
 *  - Light theme uses the canonical palette, but substitutes warmer/
 *    deeper variants for the otherwise-invisible greys.
 *  - Dark theme substitutes brightened variants for the handful of
 *    lines that would otherwise lose contrast on the near-black canvas.
 */
fun lineColorForTheme(line: String?, isDark: Boolean): Color {
    val key = line?.lowercase() ?: return TflAmber
    if (isDark) TFL_LINE_COLORS_DARK[key]?.let { return it }
    if (!isDark) TFL_LINE_COLORS_LIGHT[key]?.let { return it }
    return TFL_LINE_COLORS[key] ?: TflAmber
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
    onFullscreen: () -> Unit = {},
    homeConfig: Map<String, String> = emptyMap(),
    isDeleting: Boolean = false
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val lineColor = lineColorForTheme(selection.line, isDarkTheme)

    // Self-tick the dot-matrix rows so "5 min" visibly drops to "4 min", "3
    // min" etc. between FCM pushes. Applied to both SDUI and legacy paths
    // so home/dream/widget all flip at the same wall-clock minute boundary
    // even when FCM goes silent.
    val tickedPredictions = com.stationly.mobile.ui.util.rememberTickedPredictions(predictions)
    // The hero card should always point at the actual NEXT train across
    // ALL platforms — not at whichever row happens to be first in SQL
    // insertion order. After enough ticking, that first row may no longer
    // be the soonest (e.g. Platform 1's earliest train departed, but
    // Platform 2's next train is sooner than Platform 1's next remaining).
    // sortPredictions handles the "Due < 1 min < 5 min" cross-platform
    // ordering so the hero stays honest. Null when the ticked list is
    // empty (no upcoming trains right now), in which case the hero is
    // hidden by the `if` block below.
    val effectiveNextPrediction = com.stationly.core.util.StationlyFormatters
        .sortPredictions(tickedPredictions)
        .firstOrNull()
    // Re-bind the SDUI template with ticked predictions so SDUI-driven
    // rows pick up the refreshed eta strings too. Cheap — the binder just
    // walks the components and copies eta from the matching prediction.
    val tickedSduiPayload = remember(sduiPayload, tickedPredictions, lineStatus) {
        sduiPayload?.let {
            com.stationly.core.util.GlobalBoardProcessor.bindSduiTemplate(
                template = it,
                predictions = tickedPredictions,
                lineStatusSeverity = lineStatus?.substringBefore(":")?.trim(),
                lineStatusReason = lineStatus?.takeIf { s -> s.contains(":") }
                    ?.substringAfter(":")?.trim()
                    ?.takeIf { r -> r.isNotBlank() },
            )
        }
    }

    val isDisrupted = lineStatus != null &&
        !lineStatus.trim().lowercase().startsWith("good service")
    val disruptionSeverity = if (isDisrupted && lineStatus?.contains(":") == true)
        lineStatus.substringBefore(":").trim() else lineStatus?.trim() ?: ""
    val disruptionReason = if (isDisrupted && lineStatus?.contains(":") == true)
        lineStatus.substringAfter(":").trim() else ""

    var showFullReason by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // ── Urgency: train arriving in ≤1 min ──
    val isUrgent = remember(effectiveNextPrediction) {
        effectiveNextPrediction != null && (effectiveNextPrediction.isDue ||
            effectiveNextPrediction.eta.replace(" min", "").trim().toIntOrNull()?.let { it <= 1 } == true)
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

    // Stable lambda — only recreated when data changes, not on every animation frame.
    // Without remember(), infiniteTransition recompositions recreate this reference
    // ~60fps, causing AndroidView to call update() continuously and reset the
    // Chronometer base and marquee scroll on every frame.
    val boardUpdate: (View) -> Unit = remember(tickedPredictions, lineStatus, lineStatusFailed, tickedSduiPayload, lastUpdated, homeConfig) { { view ->
        val context = view.context
        view.findViewById<View>(R.id.btn_settings).visibility = View.GONE
        view.findViewById<View>(R.id.btn_refresh).visibility = View.GONE

        val chrono = view.findViewById<Chronometer>(R.id.last_updated_timer)
        chrono.visibility = View.VISIBLE
        chrono.format = "%s ago"
        // "X ago" semantics: time since the LAST FRESH SYNC from the
        // backend, NOT time since this view was last redrawn. lastUpdated
        // is the wall-clock millis when SyncPredictionsUseCase persisted
        // the latest FCM payload — so base = (elapsedRealtime now) minus
        // (wall-clock age of the data) gives a chronometer that's honest
        // even on first paint after a cold start, when the data may be
        // minutes old. We only re-anchor when lastUpdated actually
        // changes, so the per-minute auto-tick doesn't jolt the counter.
        val previousLastUpdated = chrono.tag as? Long
        if (previousLastUpdated != lastUpdated) {
            // lastUpdated == 0L is the "no data yet" sentinel — start the
            // chronometer at "now" so it ticks up from 0:00 instead of
            // claiming the data is 56 years old (epoch).
            val ageMs = if (lastUpdated > 0L) {
                (System.currentTimeMillis() - lastUpdated).coerceAtLeast(0L)
            } else 0L
            chrono.stop()
            chrono.base = SystemClock.elapsedRealtime() - ageMs
            chrono.start()
            chrono.tag = lastUpdated
        }

        val statusContainer = view.findViewById<View>(R.id.status_container)
        val severityText = view.findViewById<TextView>(R.id.status_severity)
        val reasonText = view.findViewById<TextView>(R.id.status_reason)
        statusContainer.visibility = View.VISIBLE
        val newSeverity: String
        val newReason: String
        if (lineStatus != null) {
            newSeverity = if (lineStatus.contains(":")) lineStatus.substringBefore(":") else lineStatus
            newReason = if (lineStatus.contains(":")) lineStatus.substringAfter(":") else ""
        } else if (lineStatusFailed) {
            newSeverity = homeConfig["board.status_label"] ?: "Status"
            newReason = homeConfig["board.status_failed_label"] ?: "Status unavailable — pull down to retry"
        } else {
            newSeverity = homeConfig["board.status_label"] ?: "Status"
            newReason = homeConfig["board.connecting_label"] ?: "Connecting to TfL signals..."
        }
        severityText.text = newSeverity
        // Only reset marquee scroll when the text changes — continuous refreshes (e.g. during
        // a "Due" blink cycle) would otherwise interrupt the scroll on every update.
        if (reasonText.text.toString() != newReason) {
            reasonText.text = newReason
            reasonText.isSelected = false
            reasonText.post { reasonText.isSelected = true }
        }

        val rowsContainer = view.findViewById<LinearLayout>(R.id.rows_container)
        val waitingContainer = view.findViewById<LinearLayout>(R.id.waiting_container)
        rowsContainer.removeAllViews()
        var dynTextColor = context.getColor(R.color.tfl_amber)

        // Hide entire header row — station name is shown in the Compose layer above
        view.findViewById<View>(R.id.header_row)?.visibility = View.GONE

        if (tickedSduiPayload != null) {
            val sduiPayload = tickedSduiPayload
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
                        val dTv = row.findViewById<TextView>(R.id.destination_text)
                        val eTv = row.findViewById<TextView>(R.id.eta_text)
                        dTv.text = component.destination
                        eTv.text = component.eta
                        dTv.setTextColor(dynTextColor)
                        eTv.setTextColor(SduiThemeManager.parseColor(component.etaColor, dynTextColor))
                        // No "Due" pulse animation: with multiple platforms
                        // the per-row alpha animation can interact badly with
                        // diff-rendering between FCM updates (rows briefly
                        // appearing to overlap as one's animation overshoots
                        // its target). The colour cue from the urgency state
                        // is enough to signal imminent arrival.
                        rowsContainer.addView(row)
                    }
                    is SduiWidgetComponent.Message -> {
                        val row = LayoutInflater.from(context).inflate(
                            R.layout.widget_departure_row, rowsContainer, false
                        )
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
            val legacySeverity = lineStatus?.let {
                if (it.contains(":")) it.substringBefore(":").trim() else it.trim()
            }
            val legacyReason = lineStatus?.let {
                if (it.contains(":")) it.substringAfter(":").trim().takeIf { r -> r.isNotBlank() } else null
            }
            val legacyRows = com.stationly.core.util.GlobalBoardProcessor.prepareLegacyRows(
                tickedPredictions, selection.line, true,
                lineStatusSeverity = legacySeverity,
                lineStatusReason = legacyReason,
                currentHour = java.time.LocalTime.now().hour
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
    } }

    // ── Outer column ──
    // Header + next-departure + disruption sit on the app canvas (theme-aware).
    // ONLY the dot-matrix departure board itself stays dark — that's the
    // signage panel, the brand cue. The previous design wrapped EVERYTHING
    // in one dark Surface; that overrode the app theme for chrome that
    // should belong to it.
    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Header (canvas): line badge + station name + delete ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 0.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Line colour pill — coloured dot + tinted background carry
                // the line identity. Text colour is onBackground so the
                // line NAME stays readable even when the line itself is a
                // light hue (Circle yellow, Bus yellow, Northern grey) that
                // would disappear on a cream canvas. The chip background
                // and dot still convey the line; the text just needs to
                // be legible.
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
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = selection.stationName,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 19.sp,
                    letterSpacing = (-0.3).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Delete button — outlined trash icon at very low alpha. The
            // old Close (×) read as "dismiss this card" rather than
            // "delete this board"; the trash glyph makes the destructive
            // nature obvious without making the icon shout.
            IconButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete board",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // ── Disruption banner — sits ABOVE the next-departure card so
        // the user sees the status disclaimer BEFORE the live train info,
        // not as a footnote after the board. Uses the danger token
        // (deep amber-red), NOT the brand primary, so "Severe Delays"
        // reads as bad-news rather than brand-news.
        if (isDisrupted) {
            val danger = LocalThemeTokens.current.error
            Surface(
                color = danger.copy(alpha = 0.08f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, danger.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showFullReason = !showFullReason }
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = danger,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = disruptionSeverity,
                                color = danger,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        if (disruptionReason.isNotEmpty()) {
                            Icon(
                                if (showFullReason) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ── Next departure (canvas) ──
        if (effectiveNextPrediction != null) {
            NextDepartureRow(effectiveNextPrediction, lineColor)
            Spacer(Modifier.height(10.dp))
        }

        // ── Dot-matrix departure board (the ONLY dark section) ──
        // Wrapped in a Box so the ambient line-coloured glow can sit
        // behind it without affecting the surrounding canvas chrome.
        Box(modifier = Modifier.fillMaxWidth()) {
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

    }

    // ── Delete board confirmation dialog ──
    // Theme-aware: container/text colours now read from MaterialTheme so the
    // dialog flips with the app. Previously was hardcoded white-on-dark,
    // which rendered as invisible white text on white in light mode.
    if (showDeleteDialog) {
        val dangerRed = LocalThemeTokens.current.error
        val onSurface  = MaterialTheme.colorScheme.onSurface
        val onSurfMute = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        val onSurfDim  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = onSurface,
            textContentColor = onSurfMute,
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
                    BoardDeleteBullet("Live departure tracking will stop", dangerRed, onSurfMute)
                    BoardDeleteBullet("Departure notifications will be unsubscribed", dangerRed, onSurfMute)
                    BoardDeleteBullet("Widget will be cleared", dangerRed, onSurfMute)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "You can always set up a new board from the home screen.",
                        color = onSurfDim, fontSize = 12.sp
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
                        colors = ButtonDefaults.textButtonColors(contentColor = onSurfMute)
                    ) { Text("Keep It") }
                }
            }
        )
    }
}

// Parse the rendered eta string back to minutes for the defensive
// fallback case: an FCM payload whose ISO timestamp didn't parse,
// leaving `targetEpochMs == null`. Returns 0 for "Due" / unparseable.
private fun parseFallbackMinutes(prediction: PredictionDisplay): Int = when {
    prediction.isDue -> 0
    prediction.eta.trim().equals("Due", ignoreCase = true) -> 0
    else -> prediction.eta.replace(" min", "").trim().toIntOrNull() ?: 0
}

// ── Compact next departure strip ──
@Composable
private fun NextDepartureRow(prediction: PredictionDisplay, lineColor: Color) {
    // Drive the countdown from the prediction's absolute target time
    // plus a minute-aligned wall-clock tick. Earlier versions used a
    // local `delay(60_000)` loop seeded from the parsed eta string,
    // which RESET every time a fresh FCM landed (the Syncer publishes
    // every ~30s, so `targetEpochMs` drifts by a handful of ms and the
    // `remember(prediction)` key buster fires) — that's why the visible
    // number used to sit frozen instead of ticking down.
    val nowMs by com.stationly.mobile.ui.util.rememberMinuteTick()
    val secondsRemaining = remember(prediction.targetEpochMs, nowMs) {
        prediction.targetEpochMs?.let { (it - nowMs) / 1000 }
            ?: parseFallbackMinutes(prediction).toLong() * 60
    }
    val countdown = remember(secondsRemaining) {
        when {
            secondsRemaining < 30 -> 0           // "Due"
            secondsRemaining < 60 -> 1           // round up under a minute
            else -> ((secondsRemaining + 30) / 60).toInt()
        }
    }
    // No "departed" state needed here: the upstream tick layer
    // (PredictionTicker.tickPredictions) filters out any prediction
    // whose targetEpochMs is past DEPARTED_GRACE_MS, so the hero
    // shifts to the next upcoming train before a row ever reaches
    // this composable in a departed state.
    val isDue = countdown == 0
    val infiniteTransition = rememberInfiniteTransition(label = "due_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse),
        label = "due_alpha"
    )
    val onSurface  = MaterialTheme.colorScheme.onSurface
    val onSurfMute = MaterialTheme.colorScheme.onSurfaceVariant
    val tokens = LocalThemeTokens.current
    val etaColor = when {
        isDue        -> tokens.due
        countdown == 1 -> MaterialTheme.colorScheme.primary
        else         -> onSurface
    }

    // ETA depletion progress (0 = empty/due, 1 = 10+ min)
    val etaProgress by animateFloatAsState(
        targetValue = (countdown / 10f).coerceIn(0f, 1f),
        animationSpec = tween(800),
        label = "eta_progress"
    )

    // Themed canvas card — the next-departure hero now sits on the app
    // canvas with a line-coloured wash, not inside the dark dot-matrix
    // surface. Same line-tint background, but text colours come from
    // the theme so light mode reads properly.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, lineColor.copy(alpha = 0.20f)),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(lineColor.copy(alpha = 0.05f))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: label + destination + platform — sizes intentionally
                // small/subtle. The card is supplementary info above the
                // dot-matrix board, not the headline.
                Column(modifier = Modifier.weight(1f)) {
                    val livePulse by rememberInfiniteTransition(label = "live_dot").animateFloat(
                        initialValue = 0.4f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
                        label = "live_dot_alpha"
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(4.dp)
                                .graphicsLayer { alpha = livePulse }
                                .background(lineColor, CircleShape)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "NEXT DEPARTURE",
                            color = onSurfMute,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "→ ${prediction.destination}",
                        color = onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (prediction.platform.isNotBlank() && prediction.platform != "null") {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = onSurface.copy(alpha = 0.07f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = prediction.platform,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                color = onSurface.copy(alpha = 0.65f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.2.sp
                            )
                        }
                    }
                }

                // Right: ETA — focal number, bumped so it reads at arm's length.
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when {
                            isDue -> "Due"
                            else  -> "$countdown"
                        },
                        color = etaColor.copy(alpha = if (isDue) pulseAlpha else 1f),
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (!isDue) {
                        Text(
                            "min",
                            color = etaColor.copy(alpha = 0.55f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.4.sp
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
