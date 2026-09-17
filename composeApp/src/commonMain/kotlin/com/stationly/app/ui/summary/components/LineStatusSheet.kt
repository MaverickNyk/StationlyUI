package com.stationly.app.ui.summary.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.common.LocalOpenUrl
import com.stationly.app.ui.theme.LocalThemeTokens
import com.stationly.app.ui.theme.ThemeTokens
import com.stationly.core.util.LineStatusRanker
import com.stationly.core.util.StationlyFormatters

/**
 * The network status surface: every line the user follows, worst first.
 *
 * ## What it shows
 * Exactly the lines behind the user's selections. `SummaryViewModel` calls
 * `loadLineStatus` once per selection and keys the map `mode_line`, so two
 * stations on the Northern line contribute one entry, not two. It is
 * deliberately NOT every line serving those stations: this is the lines you
 * ride, not the lines you walk past.
 *
 * ## Why a sheet and not a Dialog
 * This used to be a centred `Dialog` whose list was boxed into
 * `heightIn(max = 320.dp)` with its own inner scroll — a small scrolling window
 * floating on a mostly empty screen, with a "Got it" button spending height to
 * do what a tap outside already did. Six followed lines did not fit. A
 * `ModalBottomSheet` gets the full screen, drags to dismiss, and matches
 * `BoardFilterSheet` — the pattern the app already uses for "more detail about
 * the thing you tapped".
 *
 * ## Why [ThemeTokens] and not `MaterialTheme.colorScheme`
 * The old dialog mixed the two, which is why it read as stock Material dropped
 * into a bespoke app: `surfaceVariant` on a cream canvas is not the warm
 * `cardElevated` the rest of the screen uses. Everything here draws from the
 * token set, so the sheet inherits an SDUI theme override the same way the
 * board does.
 *
 * ## Why [LineStatusRanker] and not local string checks
 * Severity semantics live in core, are unit-tested, and already drive the
 * widget's status strip. The old dialog re-implemented them badly: it sorted
 * ALPHABETICALLY within the disrupted group, so "Minor Delays" on the Circle
 * outranked a Part Closure on the Northern, and it treated severity as a
 * boolean, painting a closure and a two-minute delay the identical red. The
 * ranker's own rule is the one this file follows: amber still means a train is
 * coming, red means change your plan.
 *
 * That amber is worth a note. `ThemeTokens.warning` is the same value as
 * `primary` and `brandSignage` — TfL signage amber. The middle severity band
 * is therefore the app's own brand colour rather than a borrowed Material
 * yellow, which is why delays feel like part of the product and closures feel
 * like an intrusion. That is the correct emotional ranking.
 */

/** One line's status as this screen holds it, before grouping. */
@Immutable
internal data class LineStatus(
    /** The line id alone — "northern". Drives the identity colour. */
    val lineId: String,
    /** What the user reads — "Northern", "Bus 39". */
    val displayLine: String,
    val severity: String,
    val reason: String,
)

/**
 * An incident: one severity+reason, and every followed line showing it.
 *
 * Grouping matters because the sub-surface lines share track, so a single
 * signal failure arrives as four identical paragraphs. This is the same
 * de-duplication `LineStatusRanker.rotation` does for the widget strip, kept
 * local only because `rotation` joins its labels into one string and this
 * screen needs the line ids to keep drawing each line's own colour.
 */
@Immutable
internal data class Incident(
    val lines: List<LineStatus>,
    val severity: String,
    val reason: String,
) {
    /**
     * Resolved once, at construction. This was a `get()`, so every read walked
     * `LineStatusRanker.toneOf` again — and a single incident card reads it
     * three times per composition (rail, severity text, pill), on top of the
     * sort and the summary. Severity cannot change without rebuilding the
     * incident, so the work was pure repetition.
     */
    val tone: LineStatusRanker.Tone = LineStatusRanker.toneOf(severity)
}

/**
 * The parsed network picture: what is wrong, what is fine, and whether we have
 * heard from TfL at all.
 *
 * Built ONCE per `lineStatuses` map, at the top of [StationExploreSection], and
 * handed to both the card summary and the sheet. It used to be built twice —
 * `networkSummary` parsed and grouped the whole map for a two-line card, and
 * the sheet parsed and grouped it again on open — which is the same sort and
 * the same allocation done twice for one set of facts.
 */
@Immutable
internal data class NetworkStatus(
    val incidents: List<Incident>,
    val good: List<LineStatus>,
    /** False before any status has loaded, so the card can say so. */
    val hasData: Boolean,
) {
    /** Lines affected, not incidents — a shared closure counts once per line. */
    val affectedLines: Int = incidents.sumOf { it.lines.size }
}

/**
 * Split the raw `"Severity: reason"` values into lines, then group the
 * disrupted ones into incidents ordered worst first.
 *
 * Disrupted incidents and healthy lines come back separately: they get very
 * different treatment on screen, and nothing downstream wants them mixed.
 */
internal fun buildNetworkStatus(
    raw: Map<String, String>,
    strings: Map<String, String>,
): NetworkStatus {
    val all = raw.entries.map { (key, value) ->
        val mode = key.substringBefore('_', missingDelimiterValue = "")
        val lineId = key.substringAfter('_', missingDelimiterValue = key)
        // One `indexOf` rather than a `contains` plus two scans. No colon means
        // the whole value is the severity and there is no reason text.
        val colon = value.indexOf(':')
        LineStatus(
            lineId = lineId,
            displayLine = StationlyFormatters.formatLinePrefix(mode, lineId, strings)
                .ifEmpty { lineId.replaceFirstChar { it.uppercase() } },
            severity = (if (colon >= 0) value.substring(0, colon) else value).trim(),
            reason = if (colon >= 0) value.substring(colon + 1).trim() else "",
        )
    }

    val (healthy, disrupted) = all.partition { LineStatusRanker.isGoodService(it.severity) }

    val incidents = disrupted
        .groupBy { it.severity.lowercase() to it.reason.lowercase() }
        .map { (_, shared) ->
            Incident(
                lines = shared.sortedBy { it.displayLine.lowercase() },
                severity = shared.first().severity,
                reason = shared.first().reason,
            )
        }
        .sortedWith(
            compareBy(
                { LineStatusRanker.rankOf(it.severity) },
                { it.lines.first().displayLine.lowercase() },
            )
        )

    return NetworkStatus(
        incidents = incidents,
        good = healthy.sortedBy { it.displayLine.lowercase() },
        hasData = raw.isNotEmpty(),
    )
}

/**
 * Sentence-case passenger wording for a TfL severity.
 *
 * The feed's vocabulary is operational Title Case — "Part Closure", "Special
 * Service", "No Step Free Access" — and it was going straight onto the screen.
 * Title Case in a stacked list reads as shouting, and "Bus Service" as a
 * severity is actively confusing on an app that also shows bus departures:
 * it means replacement buses, so it says that.
 *
 * Overrides come from the same SDUI `strings` map as every other label, keyed
 * `explore.status.severity.<lowercased severity, spaces to underscores>`, so
 * wording is tunable from the backend without a release. The table below is
 * the offline default, and raw TfL text is the last fallback — an
 * unrecognised severity still says something true rather than nothing.
 */
private val SEVERITY_WORDS: Map<String, String> = mapOf(
    "closed" to "Closed",
    "suspended" to "Suspended",
    "part suspended" to "Part suspended",
    "planned closure" to "Planned closure",
    "part closure" to "Part closed",
    "part closed" to "Part closed",
    "severe delays" to "Severe delays",
    "service closed" to "Service closed",
    "not running" to "Not running",
    "reduced service" to "Reduced service",
    "bus service" to "Replacement buses",
    "diverted" to "Diverted",
    "minor delays" to "Minor delays",
    "change of frequency" to "Altered frequency",
    "special service" to "Special service",
    "exit only" to "Exit only",
    "no step free access" to "No step-free access",
    "issues reported" to "Issues reported",
    "information" to "Notice",
    "good service" to "Good service",
    "no issues" to "Good service",
)

private fun humanSeverity(severity: String, strings: Map<String, String>): String {
    val raw = severity.trim()
    val norm = raw.lowercase()
    strings["explore.status.severity." + norm.replace(' ', '_')]?.let { return it }
    return SEVERITY_WORDS[norm] ?: raw
}

/** SDUI template fill. `{n}` is the only placeholder every count string uses. */
private fun String.withCount(n: Int): String = replace("{n}", n.toString())

/**
 * The severity breakdown as a phrase — "1 closed · 3 delayed".
 *
 * Counted by LINE rather than by incident: this is the sentence the summary
 * says out loud, and "1 closed" has to mean one line you cannot ride.
 * Incidents are the right unit for the LIST (one event, one card); lines are
 * the right unit for the COUNT.
 *
 * This is what replaced a headline naming one arbitrary line. "Bus 17 ·
 * Special service" as the top-level summary of four separate problems read as
 * a random pick, because it WAS one: worst-first ordering makes a line the top
 * row, it does not make it the story. What a passenger wants first is the
 * shape of the disruption — how much is "wait" and how much is "you cannot go
 * this way".
 */
private fun breakdownPhrase(incidents: List<Incident>, strings: Map<String, String>): String {
    var red = 0
    var amber = 0
    var other = 0
    incidents.forEach { incident ->
        when (incident.tone) {
            LineStatusRanker.Tone.RED   -> red += incident.lines.size
            LineStatusRanker.Tone.AMBER -> amber += incident.lines.size
            // A non-"good service" severity the ranker still tones GREEN. Rare,
            // but it used to fall out of every bucket and leave the phrase
            // EMPTY — a card with a title and a blank second line, and a sheet
            // whose whole summary line vanished.
            LineStatusRanker.Tone.GREEN -> other += incident.lines.size
        }
    }
    return buildList {
        if (red > 0) add((strings["explore.status.count.blocked"] ?: "{n} closed").withCount(red))
        if (amber > 0) add((strings["explore.status.count.delayed"] ?: "{n} delayed").withCount(amber))
        if (isEmpty() && other > 0) {
            add((strings["explore.status.count.other"] ?: "{n} with notices").withCount(other))
        }
    }.joinToString(" · ")
}

/** The tone colour, resolved against the theme's semantic tokens. */
private fun ThemeTokens.toneColor(tone: LineStatusRanker.Tone): Color = when (tone) {
    LineStatusRanker.Tone.GREEN -> live
    LineStatusRanker.Tone.AMBER -> warning
    LineStatusRanker.Tone.RED   -> error
}

/**
 * How many line-colour bars a row draws before it stops.
 *
 * Unbounded, a user following a dozen lines pushed the label off its own row:
 * the bars have fixed width and the text has `weight(1f)`, so every extra bar
 * came straight out of the words. Six is where the strip still reads as "a few
 * lines" rather than a barcode.
 */
private const val MAX_LINE_BARS = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LineStatusSheet(
    status: NetworkStatus,
    strings: Map<String, String>,
    isDark: Boolean,
    onDismiss: () -> Unit,
) {
    val t = LocalThemeTokens.current
    val openUrl = LocalOpenUrl.current
    // NOT `skipPartiallyExpanded = true`. It was, and with four incidents the
    // sheet opened at full height with no title and no visible way back — it
    // read as a screen the app had navigated to and then lost its nav bar,
    // rather than as a panel over the home screen. Resting partially expanded
    // keeps the board visible behind it, which is the whole point of a sheet:
    // you can still see what you came from. Drag up for the rest.
    val sheetState = rememberModalBottomSheetState()
    val exit = rememberSheetExit(sheetState, onDismiss)
    SheetStateSync(sheetState, onDismiss)

    val incidents = status.incidents
    val good = status.good

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = t.canvas,
        scrimColor = t.scrim,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            SheetChrome(
                heading = strings["explore.status.sheet.heading"] ?: "Network status",
                onDismiss = { exit(null) },
            )

            Spacer(Modifier.height(14.dp))

            Headline(status, strings)

            Spacer(Modifier.height(18.dp))

            // Disrupted first, expanded. This is what the sheet was opened to
            // read, so nothing sits above it competing for the eye.
            incidents.forEachIndexed { i, incident ->
                IncidentCard(incident = incident, isDark = isDark, strings = strings)
                if (i != incidents.lastIndex || good.isNotEmpty()) Spacer(Modifier.height(10.dp))
            }

            // Healthy lines, collapsed to one row.
            //
            // Not hidden: a user following six lines who sees only the one
            // disrupted line cannot tell whether the other five are fine or
            // simply failed to load. One row says "we checked, they're fine"
            // in the height of a single line, and opens if you want the proof.
            //
            // Their REASON text is dropped at every level of expansion. TfL
            // routinely attaches prose to a good service ("a good service is
            // operating on all routes", engineering notes) and the old dialog
            // rendered it whenever it was non-blank — a paragraph per healthy
            // line, saying nothing, stacked above the disruption the user
            // actually came for.
            if (good.isNotEmpty()) {
                GoodServiceGroup(good = good, isDark = isDark, strings = strings)
            }

            // The escape hatch to the source.
            //
            // Worth having because our reason text is deliberately clipped:
            // `StationlyFormatters.formatStatusReason` keeps the first two
            // sentences and drops the rest, which is right for a board and a
            // widget but does lose the replacement-bus detail on a long
            // closure notice. Anyone who needs that detail is one tap from it
            // rather than stuck with a truncated paragraph.
            Spacer(Modifier.height(16.dp))
            SheetLinkButton(
                label = strings["explore.status.sheet.link"] ?: "See full status on TfL",
            ) {
                // Close the sheet, THEN open the page — same reasoning as the
                // fare sheet's link.
                exit {
                    openUrl(
                        strings["explore.status.tflUrl"] ?: "https://tfl.gov.uk/tube-dlr-overground/status/",
                        "TfL Status",
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The summary: the shape of the disruption, before any single line is named.
 *
 * Two earlier versions of this were wrong in opposite directions. The original
 * dialog counted LINES ("2 lines are disrupted"), which overstates a shared
 * incident — one closure across the sub-surface lines read as four. The
 * replacement named the worst line instead ("SPECIAL SERVICE / Bus 17"), which
 * looked arbitrary, because it was: worst-first ordering earns a line the top
 * ROW, it does not make that line the story when three other things are also
 * wrong.
 *
 * What actually helps is the shape — how many of the lines you follow are
 * "wait a bit" and how many are "you cannot go this way". That is one number
 * each, it never picks a favourite, and it is the thing that decides whether
 * you leave now or read on.
 */
@Composable
private fun Headline(status: NetworkStatus, strings: Map<String, String>) {
    val t = LocalThemeTokens.current
    val incidents = status.incidents
    val allGood = incidents.isEmpty()
    val tone = t.toneColor(incidents.firstOrNull()?.tone ?: LineStatusRanker.Tone.GREEN)
    val affected = status.affectedLines
    val total = affected + status.good.size

    val headline = when {
        allGood && status.good.isEmpty() ->
            strings["explore.status.sheet.title.empty"] ?: "Nothing to watch yet"
        allGood ->
            strings["explore.status.sheet.title.good"] ?: "All clear."
        affected == 1 ->
            strings["explore.status.sheet.title.one"] ?: "One line needs a look"
        else ->
            (strings["explore.status.sheet.title.many"] ?: "{n} of your {total} lines are affected")
                .withCount(affected)
                .replace("{total}", total.toString())
    }

    val sub = when {
        allGood && status.good.isEmpty() ->
            strings["explore.status.sheet.body.empty"]
                ?: "Add a station and we'll keep an eye on its lines."
        allGood ->
            (strings["explore.status.sheet.body.good"] ?: "Good service on all {n} lines you follow. Nothing to do.")
                .withCount(status.good.size)
        else -> breakdownPhrase(incidents, strings)
    }

    Column {
        Text(
            headline,
            color = t.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 21.sp,
            lineHeight = 26.sp,
        )
        Spacer(Modifier.height(6.dp))
        // The breakdown is toned and bolder than body text: it is a reading of
        // the situation, not a caption. On the all-good path it falls back to
        // plain muted prose, because there is no severity for it to carry.
        Text(
            sub,
            color = if (allGood) t.textMuted else tone,
            fontSize = 13.sp,
            fontWeight = if (allGood) FontWeight.Normal else FontWeight.Bold,
            lineHeight = 18.sp,
            letterSpacing = if (allGood) 0.sp else 0.3.sp,
        )
    }
}

/**
 * The stack of line-colour bars that fronts an incident or the healthy row.
 *
 * Shared because a shared incident's bars and the healthy row's bars are the
 * same signal at two opacities, and they were two near-identical loops that
 * had already drifted apart on height.
 */
@Composable
private fun LineBars(
    lines: List<LineStatus>,
    isDark: Boolean,
    barHeight: Int,
    alpha: Float = 1f,
) {
    lines.take(MAX_LINE_BARS).forEach { line ->
        Box(
            Modifier
                .width(3.dp)
                .height(barHeight.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(lineColorForTheme(line.lineId, isDark).copy(alpha = alpha)),
        )
        Spacer(Modifier.width(3.dp))
    }
    Spacer(Modifier.width(6.dp))
}

/**
 * One incident, with every affected line named on it.
 *
 * The tone paints a rail down the leading edge rather than tinting the whole
 * card: with several stacked RED cards a filled tint turns the sheet into a
 * wall of colour and stops distinguishing anything. The rail reads at a glance
 * and leaves the reason text on a neutral surface where it stays legible.
 */
@Composable
private fun IncidentCard(
    incident: Incident,
    isDark: Boolean,
    strings: Map<String, String>,
) {
    val t = LocalThemeTokens.current
    val tone = t.toneColor(incident.tone)
    val names = remember(incident) { incident.lines.joinToString(", ") { it.displayLine } }
    val severityWord = remember(incident, strings) { humanSeverity(incident.severity, strings) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(t.card)
            .border(1.dp, t.borderSubtle, RoundedCornerShape(14.dp)),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(tone),
        )
        Column(
            Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Every affected line's own colour, as short bars. A shared
                // incident shows several bars, which is the fastest signal that
                // this is ONE event and not several — the thing the old dialog
                // could never say, because it printed the same paragraph four
                // times instead.
                LineBars(incident.lines, isDark, barHeight = 13)
                Text(
                    names,
                    color = t.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    severityWord,
                    color = tone,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.3.sp,
                    maxLines = 1,
                )
            }
            if (incident.reason.isNotBlank()) {
                Text(
                    incident.reason,
                    color = t.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

/** The healthy lines: one quiet row, expandable, never any reason text. */
@Composable
private fun GoodServiceGroup(
    good: List<LineStatus>,
    isDark: Boolean,
    strings: Map<String, String>,
) {
    val t = LocalThemeTokens.current
    var expanded by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "chevron")

    val label = (strings["explore.status.sheet.good_count"] ?: "{n} more running normally")
        .replace("{n}", good.size.toString())

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Dimmed to match the ribbon's healthy segments: the same lines,
            // the same fade, so the eye connects the row to its slice of the bar.
            LineBars(good, isDark, barHeight = 11, alpha = 0.45f)
            Text(
                label,
                color = t.textSubtle,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = t.textSubtle,
                modifier = Modifier.size(18.dp).rotate(chevron),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(100)),
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                good.forEach { line ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(lineColorForTheme(line.lineId, isDark)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            line.displayLine,
                            color = t.textMuted,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            humanSeverity(line.severity, strings),
                            color = t.live,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * What the CARD on the home screen shows.
 * ────────────────────────────────────────────────────────────────────────── */

/**
 * The one-glance summary behind the network card.
 *
 * Lives here rather than in `ExploreSection` so the card and the sheet cannot
 * disagree — they used to. The card counted lines whose status did not start
 * with "good service"; the dialog did its own split and its own sort. One
 * closure across four sub-surface lines therefore showed "4 Disruptions" on the
 * card and four identical paragraphs behind it, when the honest answer was one
 * incident affecting four lines.
 */
@Immutable
internal data class NetworkSummary(
    /** Worst severity present, for the accent and icon. */
    val tone: LineStatusRanker.Tone,
    /** Card title — "Northern closed", "Good service". */
    val title: String,
    /** Card subtitle, one line, already truncated by the card itself. */
    val subtitle: String,
    /** False before any status has loaded, so the card can say so. */
    val hasData: Boolean,
)

internal fun networkSummary(
    status: NetworkStatus,
    strings: Map<String, String>,
): NetworkSummary {
    if (!status.hasData) {
        return NetworkSummary(
            tone = LineStatusRanker.Tone.GREEN,
            title = strings["explore.status.card.loading"] ?: "Checking lines",
            subtitle = strings["explore.status.card.loading_sub"] ?: "Fetching from TfL",
            hasData = false,
        )
    }

    val worst = status.incidents.firstOrNull()
        ?: return NetworkSummary(
            tone = LineStatusRanker.Tone.GREEN,
            title = strings["explore.good_service"] ?: "Good service",
            subtitle = (strings["explore.good_service_sub"] ?: "All {n} lines normal")
                .withCount(status.good.size),
            hasData = true,
        )

    val affected = status.affectedLines

    // Two lines, ~24 characters each, on a card that shares its row height with
    // the fares card. So it gets ONE job: how much is wrong, and how bad.
    //
    // "Bus 17 special service / 4 issues in total" failed at that. The title
    // spent both of its precious lines on one arbitrarily-chosen line — worst
    // by severity, but meaningless as a summary of four separate problems —
    // and the subtitle counted "issues", a unit the user never asked about and
    // cannot act on. Naming a line is only honest when there IS one line.
    val title = if (affected == 1) {
        val only = worst.lines.first()
        "${only.displayLine} ${humanSeverity(worst.severity, strings).replaceFirstChar { it.lowercase() }}"
    } else {
        (strings["explore.status.card.affected"] ?: "{n} lines affected").withCount(affected)
    }

    // The breakdown, always — "1 closed · 2 delayed" is the most useful 18
    // characters available, and it is the same phrase the sheet opens with, so
    // the tap confirms the card rather than restating it differently.
    return NetworkSummary(
        tone = worst.tone,
        title = title,
        subtitle = breakdownPhrase(status.incidents, strings),
        hasData = true,
    )
}

/** Accent for the card, from the same tone the sheet will open with. */
@Composable
internal fun networkAccent(tone: LineStatusRanker.Tone): Color =
    LocalThemeTokens.current.toneColor(tone)
