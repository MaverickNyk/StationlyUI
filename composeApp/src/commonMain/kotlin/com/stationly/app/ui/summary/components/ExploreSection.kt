package com.stationly.app.ui.summary.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.stationly.app.ui.common.LocalOpenUrl
import com.stationly.app.ui.theme.LocalThemeTokens
import com.stationly.app.ui.theme.TflAmber
import com.stationly.app.ui.util.rememberMinuteTick
import com.stationly.core.util.StationlyFormatters
import com.stationly.core.util.TflLineColors
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Compose-Multiplatform port of the Android
 * `ui/summary/components/ExploreSection.kt`. Two cards under the boards:
 *   - network status (disruption count → opens a per-line status dialog)
 *   - TfL fares (peak / off-peak with a "until HH:mm" countdown)
 *
 * Every label is SDUI-driven through the same `strings` (homeConfig) map and
 * the same `explore.*` keys as Android, so one backend template set drives
 * both platforms. The only platform difference from the Android original is
 * `java.time` → `kotlinx.datetime` for the fare-window maths (Kotlin/Native
 * has no `java.time`).
 */
private val LONDON: TimeZone = TimeZone.of("Europe/London")
private val MORNING_PEAK_START: LocalTime = LocalTime(6, 30)
private val MORNING_PEAK_END:   LocalTime = LocalTime(9, 30)
private val EVENING_PEAK_START: LocalTime = LocalTime(16, 0)
private val EVENING_PEAK_END:   LocalTime = LocalTime(19, 0)

// UK Bank Holidays (England & Wales) — fallback when the homeConfig
// "explore.fares.bankHolidays" CSV is empty. Source: https://www.gov.uk/bank-holidays
private val FALLBACK_UK_BANK_HOLIDAYS: Set<LocalDate> = listOf(
    "2026-01-01", "2026-04-03", "2026-04-06", "2026-05-04", "2026-05-25",
    "2026-08-31", "2026-12-25", "2026-12-28",
    "2027-01-01", "2027-03-26", "2027-03-29", "2027-05-03", "2027-05-31",
    "2027-08-30", "2027-12-27", "2027-12-28",
).map(LocalDate::parse).toSet()

private data class FareState(val isPeak: Boolean, val untilLabel: String)

@Composable
fun StationExploreSection(
    lineStatuses: Map<String, String> = emptyMap(),
    strings: Map<String, String> = emptyMap()
) {
    val disruptions = remember(lineStatuses) {
        lineStatuses.values.count { !it.trim().lowercase().startsWith("good service") }
    }

    val nowMs by rememberMinuteTick()
    val bankHolidays = remember(strings["explore.fares.bankHolidays"]) {
        parseBankHolidays(strings["explore.fares.bankHolidays"])
    }
    val fareState = remember(nowMs, bankHolidays) {
        // TfL fares are anchored to London time — a user in another timezone
        // still pays peak based on when they tap in London.
        computeFareState(
            Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(LONDON),
            bankHolidays,
        )
    }

    var showDialog by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = strings["explore.title"] ?: "Network",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val disruptionLabel = strings["explore.disruptions_label"] ?: "Disruption"
            ExploreCard(
                icon = if (disruptions == 0) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                title = if (disruptions == 0) strings["explore.good_service"] ?: "Good Service"
                        else "$disruptions $disruptionLabel${if (disruptions > 1) "s" else ""}",
                subtitle = if (disruptions == 0) strings["explore.good_service_sub"] ?: "All lines running normally"
                           else strings["explore.disruptions_sub"] ?: "Delays on network",
                accentColor = if (disruptions == 0) LocalThemeTokens.current.live
                              else LocalThemeTokens.current.error,
                onClick = if (lineStatuses.isNotEmpty()) { { showStatusDialog = true } } else null,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            val fareTitle = if (fareState.isPeak)
                strings["explore.fares.peak.title"] ?: "Peak Hours"
            else
                strings["explore.fares.offpeak.title"] ?: "Off-Peak"

            val farePrefix = if (fareState.isPeak)
                strings["explore.fares.peak.subtitle_prefix"] ?: "Pricier fares · until "
            else
                strings["explore.fares.offpeak.subtitle_prefix"] ?: "Cheaper fares · until "

            ExploreCard(
                icon = if (fareState.isPeak) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown,
                title = fareTitle,
                subtitle = farePrefix + fareState.untilLabel,
                accentColor = if (fareState.isPeak) LocalThemeTokens.current.error
                              else LocalThemeTokens.current.live,
                onClick = { showDialog = true },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }

    if (showDialog) {
        FareInfoDialog(
            isPeak = fareState.isPeak,
            strings = strings,
            onDismiss = { showDialog = false }
        )
    }

    if (showStatusDialog) {
        LineStatusDialog(
            lineStatuses = lineStatuses,
            strings = strings,
            onDismiss = { showStatusDialog = false }
        )
    }
}

@Composable
private fun ExploreCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
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
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                lineHeight = MaterialTheme.typography.labelSmall.lineHeight
            )
        }
    }
}

@Composable
private fun FareInfoDialog(
    isPeak: Boolean,
    strings: Map<String, String>,
    onDismiss: () -> Unit,
) {
    val openUrl = LocalOpenUrl.current
    val accent = if (isPeak) LocalThemeTokens.current.error else LocalThemeTokens.current.live

    val title = if (isPeak)
        strings["explore.fares.dialog.title.peak"] ?: "Rush hour for your wallet too."
    else
        strings["explore.fares.dialog.title.offpeak"] ?: "You're riding cheap."

    val body = if (isPeak)
        strings["explore.fares.dialog.body.peak"]
            ?: "Tap in right now and you'll pay TfL's peak fare. Prices drop at 09:30 (or at 19:00 in the evening) — and weekends are always off-peak.\n\nPeak windows are Mon–Fri, 06:30–09:30 and 16:00–19:00. Same trains either side, just a few quid lighter outside the window."
    else
        strings["explore.fares.dialog.body.offpeak"]
            ?: "Right now London's letting you off easy — every Tube tap is at the off-peak rate.\n\nPeak fares only apply Mon–Fri, 06:30–09:30 and 16:00–19:00. Weekends and bank holidays are off-peak all day. Same trains, less money. Stationly approves."

    val linkLabel = strings["explore.fares.dialog.link"] ?: "See TfL fares"
    val dismissLabel = strings["explore.fares.dialog.dismiss"] ?: "Got it"
    val tflUrl = strings["explore.fares.tflUrl"]
        ?: "https://tfl.gov.uk/fares/find-fares/tube-and-rail-fares"

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = accent.copy(alpha = 0.14f),
                        modifier = Modifier.matchParentSize()
                    ) {}
                    Icon(
                        imageVector = if (isPeak) Icons.AutoMirrored.Outlined.TrendingUp
                                      else Icons.AutoMirrored.Outlined.TrendingDown,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    body,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { openUrl(tflUrl, "TfL Fares"); onDismiss() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor   = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(linkLabel, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        dismissLabel,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

private data class LineStatusEntry(
    val key: String,
    val displayLine: String,
    val mode: String,
    val severity: String,
    val reason: String,
    val isGood: Boolean,
)

private fun parseLineStatuses(
    raw: Map<String, String>,
    strings: Map<String, String>,
): List<LineStatusEntry> =
    raw.entries
        .map { (key, value) ->
            val parts = key.split("_", limit = 2)
            val mode = parts.getOrNull(0).orEmpty()
            val line = parts.getOrNull(1).orEmpty()
            val severity = if (value.contains(":")) value.substringBefore(":").trim() else value.trim()
            val reason = if (value.contains(":")) value.substringAfter(":").trim() else ""
            // Use the shared formatLinePrefix so the dialog stays in lockstep
            // with the widget / dream labels driven by the same SDUI templates.
            val displayLine = StationlyFormatters
                .formatLinePrefix(mode, line, strings)
                .ifEmpty { line.replaceFirstChar { it.uppercase() } }
            val modeLabel = StationlyFormatters.formatModeName(mode, strings)
            LineStatusEntry(
                key = key,
                displayLine = displayLine,
                mode = modeLabel,
                severity = severity,
                reason = reason,
                isGood = severity.lowercase().startsWith("good service"),
            )
        }
        .sortedWith(compareBy({ it.isGood }, { it.displayLine.lowercase() }))

@Composable
private fun LineStatusDialog(
    lineStatuses: Map<String, String>,
    strings: Map<String, String>,
    onDismiss: () -> Unit,
) {
    val entries = remember(lineStatuses, strings) { parseLineStatuses(lineStatuses, strings) }
    val disruptedCount = entries.count { !it.isGood }
    val allGood = disruptedCount == 0
    val accent = if (allGood) LocalThemeTokens.current.live else LocalThemeTokens.current.error

    val title = if (allGood)
        strings["explore.status.dialog.title.good"] ?: "All running normally"
    else
        strings["explore.status.dialog.title.disrupted"]
            ?: ("$disruptedCount " + (if (disruptedCount > 1) "lines are" else "line is") + " disrupted")

    val body = if (allGood)
        strings["explore.status.dialog.body.good"]
            ?: "Every line you're watching is on time. We'll flag changes the moment TfL does."
    else
        strings["explore.status.dialog.body.disrupted"]
            ?: "Here's the live picture from TfL across the lines you follow."

    val dismissLabel = strings["explore.status.dialog.dismiss"] ?: "Got it"

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = accent.copy(alpha = 0.14f),
                        modifier = Modifier.matchParentSize(),
                    ) {}
                    Icon(
                        imageVector = if (allGood) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    body,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )
                if (entries.isNotEmpty()) {
                    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        entries.forEach { entry ->
                            LineStatusRow(entry = entry, isDark = isDark)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        dismissLabel,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LineStatusRow(entry: LineStatusEntry, isDark: Boolean) {
    val lineColor = lineColorForTheme(entry.key.substringAfter('_', missingDelimiterValue = ""))
    val statusColor = if (entry.isGood) LocalThemeTokens.current.live else LocalThemeTokens.current.error
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(lineColor),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    entry.displayLine,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = 0.14f),
                ) {
                    Text(
                        entry.severity,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.2.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            if (entry.reason.isNotBlank()) {
                Text(
                    entry.reason,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

/**
 * Line-identity colour for the status dialog dot. Reuses the compose
 * `TFL_LINE_COLORS` map (same package, defined in Board.kt) first, then falls
 * back to the shared `core` hex palette, then brand amber for bus / unknown.
 */
private fun lineColorForTheme(lineId: String): Color {
    if (lineId.isBlank()) return TflAmber
    TFL_LINE_COLORS[lineId.lowercase()]?.let { return it }
    TflLineColors.hexFor(lineId)?.let { hex ->
        return Color(("ff" + hex.removePrefix("#")).toLong(16))
    }
    return TflAmber
}

// ── Fare-window maths (kotlinx.datetime; Android original used java.time) ──

private fun computeFareState(
    now: LocalDateTime,
    bankHolidays: Set<LocalDate>,
): FareState {
    val time = now.time
    val date = now.date
    val isPeakDay = isPeakChargingDay(date, bankHolidays)

    val inMorningPeak = isPeakDay && time >= MORNING_PEAK_START && time < MORNING_PEAK_END
    val inEveningPeak = isPeakDay && time >= EVENING_PEAK_START && time < EVENING_PEAK_END
    val isPeak = inMorningPeak || inEveningPeak

    val nextChange: LocalDateTime = when {
        inMorningPeak -> LocalDateTime(date, MORNING_PEAK_END)
        inEveningPeak -> LocalDateTime(date, EVENING_PEAK_END)
        isPeakDay && time < MORNING_PEAK_START ->
            LocalDateTime(date, MORNING_PEAK_START)
        isPeakDay && time >= MORNING_PEAK_END && time < EVENING_PEAK_START ->
            LocalDateTime(date, EVENING_PEAK_START)
        else -> {
            // Weekend, bank holiday, or weekday after 19:00 → next peak-charging day at 06:30.
            var d = date.plus(1, DateTimeUnit.DAY)
            while (!isPeakChargingDay(d, bankHolidays)) {
                d = d.plus(1, DateTimeUnit.DAY)
            }
            LocalDateTime(d, MORNING_PEAK_START)
        }
    }

    return FareState(isPeak, formatUntilLabel(now, nextChange))
}

private fun isPeakChargingDay(date: LocalDate, bankHolidays: Set<LocalDate>): Boolean {
    val dow = date.dayOfWeek
    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false
    return date !in bankHolidays
}

private fun parseBankHolidays(csv: String?): Set<LocalDate> {
    if (csv.isNullOrBlank()) return FALLBACK_UK_BANK_HOLIDAYS
    return csv.splitToSequence(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        .toSet()
}

private fun formatUntilLabel(now: LocalDateTime, change: LocalDateTime): String {
    val hhmm = formatHhMm(change.time)
    val daysBetween = now.date.daysUntil(change.date)
    return when {
        daysBetween <= 0 -> hhmm
        daysBetween == 1 -> "tomorrow $hhmm"
        else -> "${shortDayName(change.dayOfWeek)} $hhmm"
    }
}

private fun formatHhMm(time: LocalTime): String {
    val h = time.hour.toString().padStart(2, '0')
    val m = time.minute.toString().padStart(2, '0')
    return "$h:$m"
}

private fun shortDayName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY    -> "Mon"
    DayOfWeek.TUESDAY   -> "Tue"
    DayOfWeek.WEDNESDAY -> "Wed"
    DayOfWeek.THURSDAY  -> "Thu"
    DayOfWeek.FRIDAY    -> "Fri"
    DayOfWeek.SATURDAY  -> "Sat"
    DayOfWeek.SUNDAY    -> "Sun"
    else                -> day.name.take(3)
}
