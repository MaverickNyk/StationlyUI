package com.stationly.app.ui.summary.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.theme.LocalThemeTokens
import com.stationly.app.ui.util.rememberMinuteTick
import com.stationly.core.config.BoardPolicyStore
import com.stationly.core.util.LineStatusRanker
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
 *   - network status (disruption count → opens a per-line status sheet)
 *   - TfL fares (peak / off-peak with a "until HH:mm" countdown)
 *
 * Every label is SDUI-driven through the same `strings` (homeConfig) map and
 * the same `explore.*` keys as Android, so one backend template set drives
 * both platforms. The only platform difference from the Android original is
 * `java.time` → `kotlinx.datetime` for the fare-window maths (Kotlin/Native
 * has no `java.time`).
 */
private val LONDON: TimeZone = TimeZone.of("Europe/London")
internal val MORNING_PEAK_START: LocalTime = LocalTime(6, 30)
internal val MORNING_PEAK_END:   LocalTime = LocalTime(9, 30)
internal val EVENING_PEAK_START: LocalTime = LocalTime(16, 0)
internal val EVENING_PEAK_END:   LocalTime = LocalTime(19, 0)

// UK Bank Holidays (England & Wales) — fallback when the homeConfig
// "explore.fares.bankHolidays" CSV is empty. Source: https://www.gov.uk/bank-holidays
private val FALLBACK_UK_BANK_HOLIDAYS: Set<LocalDate> = listOf(
    "2026-01-01", "2026-04-03", "2026-04-06", "2026-05-04", "2026-05-25",
    "2026-08-31", "2026-12-25", "2026-12-28",
    "2027-01-01", "2027-03-26", "2027-03-29", "2027-05-03", "2027-05-31",
    "2027-08-30", "2027-12-27", "2027-12-28",
).map(LocalDate::parse).toSet()

internal data class FareState(val isPeak: Boolean, val untilLabel: String)

@Composable
fun StationExploreSection(
    lineStatuses: Map<String, String> = emptyMap(),
    strings: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    // Parsed ONCE, here, and shared by the card and the sheet behind it — see
    // [NetworkStatus]. The card used to run its own `startsWith("good
    // service")` count, so one closure shared by four sub-surface lines
    // announced "4 Disruptions" over a sheet that then showed one event; the
    // fix made them agree but did the same grouping and sorting twice.
    val status = remember(lineStatuses, strings) { buildNetworkStatus(lineStatuses, strings) }
    val summary = remember(status, strings) { networkSummary(status, strings) }

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

    var showFares by remember { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }

    // Derived from the theme, so it is stable between theme changes — it was
    // recomputing a luminance every minute tick along with everything else in
    // this section.
    val background = MaterialTheme.colorScheme.background
    val isDark = remember(background) { background.luminance() < 0.5f }

    val tokens = LocalThemeTokens.current

    // NO "Network" heading. On a home screen whose whole point is fitting one
    // viewport, a label costs a line of height to name two cards that already
    // say what they are — a warning triangle over "2 Disruptions" needs no
    // header. The height it frees goes to the board.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ExploreCard(
            // Three states, not two. A closure and a minor delay used to
            // share one red triangle, which is the opposite of the rule the
            // rest of the app follows: amber means a train is still coming.
            icon = when (summary.tone) {
                LineStatusRanker.Tone.GREEN -> Icons.Outlined.CheckCircle
                LineStatusRanker.Tone.AMBER -> Icons.Outlined.Info
                LineStatusRanker.Tone.RED   -> Icons.Outlined.Warning
            },
            title = summary.title,
            subtitle = summary.subtitle,
            accentColor = networkAccent(summary.tone),
            // Tappable as soon as there is anything to show. It used to be
            // inert while `lineStatuses` was empty — which is exactly the
            // moment on a cold launch when a user taps it — and a dead card
            // reads as a broken one.
            onClick = if (summary.hasData) { { showStatus = true } } else null,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )

        val fareTitle = if (fareState.isPeak)
            strings["explore.fares.peak.title"] ?: "Peak Hours"
        else
            strings["explore.fares.offpeak.title"] ?: "Off-Peak"

        // Short form, because the subtitle now gets ONE line.
        //
        // "Cheaper fares · until tomorrow 06:30" wrapped to two lines and
        // made the card taller than it needed to be. The "cheaper/pricier"
        // half is already carried by the title ("Off-Peak") and the arrow
        // icon, so dropping it loses nothing — the fact worth keeping is the
        // time it changes. The full explanation is one tap away in the fare
        // sheet.
        //
        // New keys rather than the old `*_prefix` ones: a backend still
        // serving the long prefix would re-introduce the wrap.
        val farePrefix = if (fareState.isPeak)
            strings["explore.fares.peak.subtitle_short"] ?: "Until "
        else
            strings["explore.fares.offpeak.subtitle_short"] ?: "Until "

        ExploreCard(
            icon = if (fareState.isPeak) Icons.AutoMirrored.Outlined.TrendingUp
                   else Icons.AutoMirrored.Outlined.TrendingDown,
            title = fareTitle,
            subtitle = farePrefix + fareState.untilLabel,
            accentColor = if (fareState.isPeak) tokens.error else tokens.live,
            onClick = { showFares = true },
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }

    if (showFares) {
        FareSheet(
            fareState = fareState,
            strings = strings,
            onDismiss = { showFares = false }
        )
    }

    if (showStatus) {
        LineStatusSheet(
            status = status,
            strings = strings,
            isDark = isDark,
            onDismiss = { showStatus = false }
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
        modifier = if (onClick != null) {
            modifier.clickable(role = Role.Button, onClick = onClick)
        } else {
            modifier
        },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
    ) {
        // Tightened throughout: this card sits below the board on a screen that
        // has to fit one viewport, so every dp it gives up is a dp the
        // departures get. The icon and title carry the meaning; the padding was
        // just air.
        //
        // ── On weight ──
        // The title was `bodyMedium` at `FontWeight.Bold`, which put the pair
        // of cards at nearly the same visual weight as the departure rows
        // above them. They are not that important: they are two secondary
        // facts under the board, and shouting them made the screen feel like
        // it had three headlines competing. Title steps down to Medium at a
        // fixed 13sp, and the ACCENT carries the urgency instead — colour is
        // the cheaper signal, and it is already saying the same thing.
        //
        // Sizes are literal rather than typography roles because both cards
        // must agree to the dp: they share a row height, so a role that
        // resolves differently under an SDUI text scale would make one card
        // taller than its twin.
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                // ONE line, always. A wrapping subtitle silently changes the
                // card's height, and both cards share a row height — so one of
                // them wrapping made the pair taller. Truncated here, in full in
                // the sheet behind the tap.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Fare-window maths (kotlinx.datetime; Android original used java.time) ──

/**
 * How far ahead the "next peak-charging day" search will walk.
 *
 * A guard, not a rule. The bank-holiday set is SDUI-supplied, and a CSV that
 * happened to list every date in a fortnight would have spun this loop on the
 * main thread forever — a frozen home screen with no error and no way out.
 * Two weeks is well beyond the longest real run of non-charging days
 * (Christmas), and giving up lands on a plain weekday rather than hanging.
 */
private fun computeFareState(
    now: LocalDateTime,
    bankHolidays: Set<LocalDate>,
    maxDaysToPeak: Int = BoardPolicyStore.current.explorePeakHorizonDays,
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
            var walked = 1
            while (!isPeakChargingDay(d, bankHolidays) && walked < maxDaysToPeak) {
                d = d.plus(1, DateTimeUnit.DAY)
                walked++
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
    val parsed = csv.splitToSequence(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        .toSet()
    // A CSV that is present but yields nothing — a format change, a typo, a
    // placeholder — used to silently produce an EMPTY set, which makes every
    // bank holiday a peak-charging day. Falling back to the baked-in list is
    // wrong far less often than trusting a string we could not read.
    return parsed.ifEmpty { FALLBACK_UK_BANK_HOLIDAYS }
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

internal fun formatHhMm(time: LocalTime): String {
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
}
