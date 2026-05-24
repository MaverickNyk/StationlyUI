package com.stationly.mobile.ui.summary.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.stationly.mobile.ui.common.LocalOpenUrl
import com.stationly.mobile.ui.theme.LocalThemeTokens
import com.stationly.mobile.ui.util.rememberMinuteTick
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LONDON: ZoneId = ZoneId.of("Europe/London")
private val MORNING_PEAK_START: LocalTime = LocalTime.of(6, 30)
private val MORNING_PEAK_END:   LocalTime = LocalTime.of(9, 30)
private val EVENING_PEAK_START: LocalTime = LocalTime.of(16, 0)
private val EVENING_PEAK_END:   LocalTime = LocalTime.of(19, 0)
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// UK Bank Holidays (England & Wales) — used as a fallback when the homeConfig
// "explore.fares.bankHolidays" CSV is empty, so the Fares card stays accurate
// even before a backend deploy. Source: https://www.gov.uk/bank-holidays
// Top this list up roughly once a year; the remote CSV wins when populated.
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
            Instant.ofEpochMilli(nowMs).atZone(LONDON).toLocalDateTime(),
            bankHolidays,
        )
    }

    var showDialog by remember { mutableStateOf(false) }

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

private fun computeFareState(
    now: LocalDateTime,
    bankHolidays: Set<LocalDate>,
): FareState {
    val time = now.toLocalTime()
    val date = now.toLocalDate()
    val isPeakDay = isPeakChargingDay(date, bankHolidays)

    val inMorningPeak = isPeakDay && !time.isBefore(MORNING_PEAK_START) && time.isBefore(MORNING_PEAK_END)
    val inEveningPeak = isPeakDay && !time.isBefore(EVENING_PEAK_START) && time.isBefore(EVENING_PEAK_END)
    val isPeak = inMorningPeak || inEveningPeak

    val nextChange: LocalDateTime = when {
        inMorningPeak -> LocalDateTime.of(date, MORNING_PEAK_END)
        inEveningPeak -> LocalDateTime.of(date, EVENING_PEAK_END)
        isPeakDay && time.isBefore(MORNING_PEAK_START) ->
            LocalDateTime.of(date, MORNING_PEAK_START)
        isPeakDay && !time.isBefore(MORNING_PEAK_END) && time.isBefore(EVENING_PEAK_START) ->
            LocalDateTime.of(date, EVENING_PEAK_START)
        else -> {
            // Weekend, bank holiday, or weekday after 19:00 → next peak-charging day at 06:30.
            var d = date.plusDays(1)
            while (!isPeakChargingDay(d, bankHolidays)) {
                d = d.plusDays(1)
            }
            LocalDateTime.of(d, MORNING_PEAK_START)
        }
    }

    val label = formatUntilLabel(now, nextChange)
    return FareState(isPeak, label)
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
    val hhmm = change.toLocalTime().format(TIME_FMT)
    val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), change.toLocalDate())
    return when {
        daysBetween <= 0L -> hhmm
        daysBetween == 1L -> "tomorrow $hhmm"
        else -> {
            val dayShort = change.dayOfWeek
                .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
            "$dayShort $hhmm"
        }
    }
}
