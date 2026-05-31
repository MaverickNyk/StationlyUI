package com.stationly.mobile.ui.summary.components

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.LegacyRow
import com.stationly.core.util.StaleColor
import com.stationly.core.util.StationlyFormatters
import com.stationly.mobile.util.HomeConfigStore
import com.stationly.mobile.util.ModeColors
import com.stationly.mobile.util.ModeIconCache
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

    // Fallback message (Offline / Live-updates-paused / Service-ended-for-tonight
    // etc.) — shared across home, dream, and widget. Re-evaluated on every
    // minute tick so "Live updates paused · last refresh 6 min ago" stays
    // honest without the user touching the screen.
    val isOnline by com.stationly.mobile.ui.util.NetworkState.isOnline.collectAsState()
    val nowMs by com.stationly.mobile.ui.util.rememberMinuteTick()
    val fallbackState = remember(tickedPredictions, isOnline, lastUpdated, nowMs, homeConfig, lineStatus) {
        val londonTime = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(java.time.ZoneId.of("Europe/London"))
            .toLocalTime()
        // Split TfL's "Severity: Reason" into the two parts the fallback
        // computation cares about, so a disruption can produce a DISRUPTED
        // state instead of the generic "Nothing departing right now".
        val statusSeverity = lineStatus
            ?.let { if (it.contains(":")) it.substringBefore(":") else it }?.trim()
        val statusReason = lineStatus
            ?.takeIf { it.contains(":") }?.substringAfter(":")?.trim()
        com.stationly.mobile.ui.util.computeBoardFallbackState(
            hasPredictions = tickedPredictions.isNotEmpty(),
            isOnline = isOnline,
            lastUpdatedMs = lastUpdated,
            nowMs = nowMs,
            londonTime = londonTime,
            lineStatusSeverity = statusSeverity,
            lineStatusReason = statusReason,
            signalLostMin = homeConfig["board.fallback.signalLostMin"]?.toLongOrNull()
                ?: com.stationly.mobile.ui.util.BoardFallbackDefaults.SIGNAL_LOST_MIN,
            lateNightStart = com.stationly.mobile.ui.util.parseHHmm(
                homeConfig["board.fallback.lateNightStart"],
                com.stationly.mobile.ui.util.BoardFallbackDefaults.LATE_NIGHT_START,
            ),
            lateNightEnd = com.stationly.mobile.ui.util.parseHHmm(
                homeConfig["board.fallback.lateNightEnd"],
                com.stationly.mobile.ui.util.BoardFallbackDefaults.LATE_NIGHT_END,
            ),
            earlyMorningEnd = com.stationly.mobile.ui.util.parseHHmm(
                homeConfig["board.fallback.earlyMorningEnd"],
                com.stationly.mobile.ui.util.BoardFallbackDefaults.EARLY_MORNING_END,
            ),
        )
    }
    // The hero card should always point at the actual NEXT train across
    // ALL platforms — not at whichever row happens to be first in SQL
    // insertion order. After enough ticking, that first row may no longer
    // be the soonest (e.g. Platform 1's earliest train departed, but
    // Platform 2's next train is sooner than Platform 1's next remaining).
    // sortPredictions handles the "Due < 1 min < 5 min" cross-platform
    // ordering so the hero stays honest. Null when the ticked list is
    // empty (no upcoming trains right now), in which case the hero is
    // hidden by the `if` block below.
    val effectiveNextPrediction = StationlyFormatters
        .sortPredictions(tickedPredictions)
        .firstOrNull()
    // Re-bind the SDUI template with ticked predictions so SDUI-driven
    // rows pick up the refreshed eta strings too. Cheap — the binder just
    // walks the components and copies eta from the matching prediction.
    val tickedSduiPayload = remember(sduiPayload, tickedPredictions, lineStatus) {
        sduiPayload?.let {
            GlobalBoardProcessor.bindSduiTemplate(
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

    // Static border — no urgency pulse (the fast flash read as alarming). The
    // slightly thicker border width when urgent (below) is enough emphasis.
    val borderAlpha = 0.22f

    // Stable lambda — only recreated when data changes, not on every animation frame.
    // Without remember(), infiniteTransition recompositions recreate this reference
    // ~60fps, causing AndroidView to call update() continuously and reset the
    // Chronometer base and marquee scroll on every frame.
    //
    // `nowMs` is in the key list so the lambda re-runs at every wall-clock
    // minute tick — that's what drives the chronometer colour transitions
    // (amber → grey at 60s, → red at 180s) without needing alarms on the
    // Compose surface. Widget uses AlarmManager for the same job; we share
    // the thresholds + palette via `StaleColor`.

    // Effective width the dot-matrix card occupies inside the LazyColumn's
    // 20dp horizontal padding. Used by the station strip's `maxEms` so the
    // name uses all the room it has on this device class (small phone
    // ~330dp, Pixel ~380dp, 10" tablet ~760dp). Recomputes when the
    // Configuration changes (rotation, fold), since LocalConfiguration is
    // a Compose state itself.
    val boardSlotWidthDp = (LocalConfiguration.current.screenWidthDp - 40).coerceAtLeast(200)

    val boardUpdate: (View) -> Unit = remember(tickedPredictions, lineStatus, lineStatusFailed, tickedSduiPayload, lastUpdated, homeConfig, fallbackState, nowMs, boardSlotWidthDp) { { view ->
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

        // Chronometer colour — recomputed on every minute tick (nowMs is
        // a `remember` key, so the lambda re-runs at xx:00:00 wall-clock
        // boundaries). Anchored to lastUpdated, so the amber → grey at
        // 60s and grey → red at 180s transitions reflect the SQL row's
        // true age regardless of how many times this composable has
        // re-rendered. Sentinel `lastUpdated == 0` stays amber (no real
        // data to age).
        if (lastUpdated > 0L) {
            val ageMs = (nowMs - lastUpdated).coerceAtLeast(0L)
            chrono.setTextColor(StaleColor.colorForAge(ageMs))
        } else {
            chrono.setTextColor(StaleColor.AMBER)
        }

        // SDUI string overrides — used by the mode-icon contentDescription
        // below AND by the line-prefix template just below that. Read once
        // per lambda fire so we don't hit disk on every label substitution.
        val sduiStrings = HomeConfigStore.read(context)

        // Platform-header line prefix — same shape the widget + dream use:
        // "Piccadilly: Platform 1 (Eastbound)". Cross-surface consistency
        // requirement; without this the home Board's platform headers read
        // just "Platform 1 (Eastbound)" while every other surface carries
        // the line context. Falls back to "" so the prefix block is a
        // no-op when the selection's mode/line can't produce a prefix
        // (rare — only an empty UserSelection).
        val linePrefix = StationlyFormatters
            .formatLinePrefix(selection.mode, selection.line, sduiStrings)

        // Status row: always visible to keep the board size stable across
        // surfaces. Reason text follows: real line status > "Status unavailable"
        // on fetch failure > "Good Service" as the default.
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
            newSeverity = homeConfig["board.good_service_label"] ?: "Good Service"
            newReason = ""
        }
        severityText.text = newSeverity
        // Only reset marquee scroll when the text changes — continuous refreshes
        // would otherwise interrupt the scroll on every update.
        if (reasonText.text.toString() != newReason) {
            reasonText.text = newReason
            reasonText.isSelected = false
            reasonText.post { reasonText.isSelected = true }
        }

        val rowsContainer = view.findViewById<LinearLayout>(R.id.rows_container)
        // Wrap rows_container in a ScrollView so platform headers + dep
        // rows scroll independently while the station strip stays pinned
        // at top and the status row + clock stay pinned at bottom. Same
        // helper the cluster + fullscreen dreams use; idempotent so it's
        // safe to call on every update lambda fire.
        com.stationly.mobile.ui.util.ensureRowsScrollWrapped(rowsContainer)
        val waitingContainer = view.findViewById<LinearLayout>(R.id.waiting_container)
        rowsContainer.removeAllViews()
        var dynTextColor = context.getColor(R.color.tfl_amber)

        // Dot-matrix station strip — same row the widget and the fullscreen
        // dream show. Per cross-surface consistency feedback, the rider sees
        // the same station + mode lockup on every Stationly surface; the
        // Compose chrome above (line pill / delete button) carries additional
        // app-canvas info but the dot-matrix strip is the constant.
        // Refresh/settings buttons inside the strip stay hidden on home —
        // the canvas already provides pull-to-refresh + the delete affordance.
        view.findViewById<View>(R.id.header_row)?.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.line_name).apply {
            text = selection.stationName
            // Size `maxEms` to the home Board's actual width — the XML's
            // hardcoded 18 was calibrated for the widget's smallest cell
            // and truncates station names with room to spare on phones
            // and especially tablets. The board card is the screen width
            // minus the LazyColumn's 20dp horizontal padding on each side.
            maxEms = com.stationly.mobile.ui.util.StationStripFitter
                .maxEmsForWidthDp(boardSlotWidthDp)
        }

        // Mode roundel — uses the `sduiStrings` already read above.
        view.findViewById<ImageView>(R.id.mode_icon)?.apply {
            visibility = View.VISIBLE
            contentDescription = StationlyFormatters
                .formatModeName(selection.mode, sduiStrings)
            val cached = ModeIconCache
                .cachedBitmap(context, selection.mode)
            if (cached != null) {
                clearColorFilter()
                setImageBitmap(cached)
            } else {
                // Tint precedence: backend-shipped tintHex via /modes → hardcoded fallback.
                val tint = ModeIconCache.tintFor(context, selection.mode)
                    ?: ModeColors.forMode(selection.mode)
                setImageResource(R.drawable.mode_roundel)
                setColorFilter(tint)
            }
        }

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
                        // Prefix every platform header with the line
                        // context so the home reads "Piccadilly: Platform
                        // 1 (Eastbound)" — same as widget + dream.
                        pTv.text = StationlyFormatters.platformHeaderText(linePrefix, component.title)
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
            val legacyRows = GlobalBoardProcessor.prepareLegacyRows(
                tickedPredictions, selection.line, true,
                lineStatusSeverity = legacySeverity,
                lineStatusReason = legacyReason,
                currentHour = java.time.LocalTime.now().hour
            )
            legacyRows.forEach { row ->
                when (row) {
                    is LegacyRow.Header -> {
                        val header = LayoutInflater.from(context).inflate(
                            R.layout.widget_platform_header, rowsContainer, false
                        )
                        // Same "Line: Platform" prefix as widget + dream
                        // (blank platform → just the line, via the shared helper).
                        header.findViewById<TextView>(R.id.platform_name).text =
                            StationlyFormatters.platformHeaderText(linePrefix, row.title)
                        rowsContainer.addView(header)
                    }
                    is LegacyRow.Departure -> {
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
                    is LegacyRow.Message -> {
                        val header = LayoutInflater.from(context).inflate(
                            R.layout.widget_platform_header, rowsContainer, false
                        )
                        header.findViewById<TextView>(R.id.platform_name).text = row.text
                        rowsContainer.addView(header)
                    }
                }
            }
        }

        // Shared fallback rows — called LAST so its rows_container.removeAllViews()
        // wins over whatever the SDUI/legacy rendering branches just inflated.
        // Renders inside the existing rows stream using `widget_departure_row`
        // (title in bold, detail in normal weight, both centred) so the
        // dot-matrix look stays consistent across home + dream + widget.
        com.stationly.mobile.ui.util.applyBoardFallbackToRows(view, fallbackState, homeConfig)
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
                // Station name intentionally omitted here — the dot-matrix
                // board below already shows it (its line_name strip), so a
                // second copy above the hero was redundant chrome.
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
        // reads as bad-news rather than brand-news. Always renders when
        // the line is disrupted — the dot-matrix fallback rows only show
        // a short summary, this banner carries the full reason.
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

            // Cap the dark card's height so the page never has to scroll
            // ONLY because of a many-platform station. Without a cap, a
            // station like Bank with 4-6 platforms produces a card that
            // pushes the Network section off-screen and dominates the
            // home page.
            //
            // The cap is responsive: 50% of the screen height, clamped
            // between 380dp (small phone — keep ≥ 3 dep rows + status
            // + clock visible without forcing scroll on a tiny screen)
            // and 720dp (large tablet — past this the board starts to
            // feel billboard-rather-than-signage). On Pixel 11 Pro this
            // lands ≈ 400dp; on a 12" tablet ≈ 720dp.
            //
            // Inside the card, the XML rows_container gets wrapped in a
            // ScrollView (see boardUpdate lambda) so the station strip
            // + status row + clock stay pinned while the platform rows
            // scroll independently. Same pattern the dream uses.
            val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
            val maxBoardHeight = (screenHeightDp * 0.50f).coerceIn(380.dp, 720.dp)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBoardHeight),
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
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            // NB: the in-flight loader lives in the screen-level LoadingOverlay
            // (driven by isDeletingBoard), not in this dialog — the board card
            // (and this dialog with it) unmounts the moment the selection is
            // removed from the list, so an in-dialog spinner never renders.
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
                    // Dismiss immediately and let the screen-level
                    // LoadingOverlay show the in-flight state.
                    onClick = { showDeleteDialog = false; onDelete() },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = dangerRed)
                ) {
                    Text("Delete Board", fontWeight = FontWeight.Bold)
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
    // Read the label directly from `prediction.eta` — that string has
    // already been through `tickPredictions` (minute-aligned re-derive
    // + per-platform bump) by the time the parent recomposed and passed
    // this prediction down. Re-running the rounding formula here would
    // miss the bump (so the hero could read "1 min" while the board
    // shows "2 min" for the very same train).
    //
    // The parent Board already subscribes to `rememberMinuteTick` via
    // `rememberTickedPredictions`, so when the wall-clock minute flips
    // the parent re-emits a fresh `prediction` and this composable
    // recomposes — no need for a second minute-tick subscription here.
    //
    // No "departed" branch: the upstream tick layer drops any prediction
    // whose targetEpochMs is past DEPARTED_GRACE_MS, so the hero shifts
    // to the next upcoming train before a row ever reaches this
    // composable in a departed state.
    val countdown = parseFallbackMinutes(prediction)
    val isDue = prediction.isDue ||
        prediction.eta.trim().equals("Due", ignoreCase = true)
    val onSurface  = MaterialTheme.colorScheme.onSurface
    val onSurfMute = MaterialTheme.colorScheme.onSurfaceVariant
    // "Due" and "1 min" both use the brand primary (amber) — NOT the alarming
    // red. No alpha pulse on "Due" either (the flashing read as stressful).
    val etaColor = when {
        isDue          -> MaterialTheme.colorScheme.primary
        countdown == 1 -> MaterialTheme.colorScheme.primary
        else           -> onSurface
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
                        color = etaColor,
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

// Scroll-wrap helper moved to `ui/util/ScrollWrap.ensureRowsScrollWrapped`
// — shared with `dream/DreamBoard`'s scroll path so both surfaces stay in
// lockstep on scrollbar styling + layout-param surgery.
