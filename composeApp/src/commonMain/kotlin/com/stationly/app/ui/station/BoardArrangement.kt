package com.stationly.app.ui.station

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.app.ui.common.SegmentedRow
import com.stationly.app.ui.common.SettingsCaption
import com.stationly.app.ui.common.SettingsSectionLabel
import com.stationly.app.ui.common.pressScale
import com.stationly.app.ui.summary.components.lineColorForTheme
import com.stationly.app.ui.theme.isDarkTheme
import com.stationly.core.util.BoardDisplayPrefs
import com.stationly.core.util.BoardPin
import com.stationly.core.util.BoardSort
import com.stationly.core.util.LineShortNames
import kotlin.math.roundToInt

/**
 * The three settings that arrange ONE station's departure board: what it is
 * ordered by, how many departures each platform shows, and which block leads.
 *
 * ## What is deliberately NOT here
 * **The platform grouping.** It is the one thing about the board that is not
 * negotiable, and `BoardDisplayPrefs` in core says why at length: everything
 * under a header is one queue in one place you can walk to, and a flat re-sort
 * of the whole board would leave the user reading the platform off every
 * individual row to know where to stand. Every control below therefore operates
 * either on the order of the BLOCKS or on the order INSIDE one, never across
 * them.
 *
 * **A drawn preview**, unlike the layout picker above it on the same screen.
 * That one shows two fictional boards because "hide the countdown" is a shape
 * the user would otherwise have to imagine. These three are different: the real
 * board is one back-tap away and changes the moment you get there, so the honest
 * preview is the board itself. Drawing one from the SQLite cache was the
 * alternative and was rejected — cached ETAs are minutes or hours old, and a
 * settings screen showing "Brixton 2 min" over stale data is indistinguishable
 * from showing it over live data.
 *
 * @param platforms the platform/stop labels this station has actually been
 *   showing, from the cache — see `StationSettingsViewModel.platforms`.
 * @param lines the lines tracked here, canonical ids.
 * @param isBus buses have stops rather than platforms, and the words follow.
 */
@Composable
fun BoardArrangementSection(
    prefs: BoardDisplayPrefs,
    platforms: List<String>,
    lines: List<String>,
    isBus: Boolean,
    onSort: (BoardSort) -> Unit,
    onRowsPerPlatform: (Int) -> Unit,
    onPin: (BoardPin?) -> Unit,
) {
    // "Stop" everywhere "Platform" would be, on a bus board. The board itself
    // has never said "platform" at a bus stop and neither should the screen that
    // configures it.
    val place = if (isBus) "stop" else "platform"

    SettingsSectionLabel("Order")
    SegmentedRow(
        options = BoardSort.entries,
        selected = prefs.sort,
        onSelect = { sort ->
            if (sort != prefs.sort) {
                performHaptic(HapticType.TAP)
                onSort(sort)
            }
        },
        label = { sort ->
            when (sort) {
                BoardSort.TIME -> "Time"
                BoardSort.PLATFORM -> if (isBus) "Stop" else "Platform"
                BoardSort.DESTINATION -> "Destination"
            }
        },
    )
    // One caption that changes rather than three lines of explanation. Each says
    // what MOVES and what stays put, because the thing people get wrong about
    // this control is assuming it re-sorts the whole board.
    SettingsCaption(
        when (prefs.sort) {
            BoardSort.TIME ->
                "Whichever $place has the soonest train leads the board."
            BoardSort.PLATFORM ->
                if (isBus) "Stops in a fixed order. Trains inside each stay in time order."
                else "Platforms in number order. Trains inside each stay in time order."
            BoardSort.DESTINATION ->
                "Trains to the same place sit together inside each $place."
        }
    )

    Spacer(Modifier.height(28.dp))

    SettingsSectionLabel("Departures per $place")
    RowsPerPlatformSlider(rows = prefs.rowCap, onChange = onRowsPerPlatform)
    SettingsCaption("A ceiling, not a promise. A quiet $place shows what TfL sends.")

    // ── Show first ──
    //
    // Hidden entirely when there is nothing to promote: no platform has been
    // seen yet (a station whose board has never loaded) and only one line is
    // tracked, so every block on the board carries it. A picker whose only
    // option is "Nothing" is a control that cannot do anything, and a caption
    // explaining why is worse than the section not being there.
    //
    // Shown anyway if a pin is already SET, however the options have since
    // shrunk — otherwise a setting the user made would be silently in force
    // with no way to reach it.
    val pin = prefs.pin
    if (platforms.isNotEmpty() || lines.size > 1 || pin != null) {
        Spacer(Modifier.height(28.dp))

        SettingsSectionLabel("Show first")
        PinPicker(
            pin = pin,
            platforms = platforms,
            lines = lines,
            onPin = { next ->
                performHaptic(HapticType.TAP)
                onPin(next)
            },
        )
        SettingsCaption(
            when (pin?.kind) {
                // The line case is the one worth spelling out: a line at an
                // interchange is on more than one platform, and the user should
                // know it brings all of them rather than picking one.
                BoardPin.Kind.LINE ->
                    "Every $place ${LineShortNames.displayName(pin.id)} calls at leads the board."
                BoardPin.Kind.PLATFORM -> "${pin.id} leads the board. The rest keep their order."
                null -> "Pin one $place or line to the top. Everything else keeps its order."
            }
        )
    }
}

/**
 * How deep each platform goes, on a slider rather than a stepper.
 *
 * It is a quantity with a small fixed range, which is the one case a slider is
 * genuinely better at than +/- buttons: the whole scale is visible, so the user
 * can see that five is the most there is before they start dragging.
 *
 * Snapped to whole steps, and each step announces itself with a tap. Without
 * that the drag is a continuous smear with a number changing somewhere else on
 * the screen; with it the control has detents, and you can set it without
 * watching it.
 */
/**
 * Half a Material thumb (10dp) less half a digit (~3.5dp) — the inset that puts
 * a tick label's centre on the thumb stop it names. See the label row below.
 */
private val TICK_INSET = 6.5.dp

@Composable
private fun RowsPerPlatformSlider(rows: Int, onChange: (Int) -> Unit) {
    val min = BoardDisplayPrefs.MIN_ROWS_PER_PLATFORM
    val max = BoardDisplayPrefs.MAX_ROWS_PER_PLATFORM
    val accent = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = rows.toFloat(),
            onValueChange = { raw ->
                val next = raw.roundToInt().coerceIn(min, max)
                if (next != rows) {
                    performHaptic(HapticType.TAP)
                    onChange(next)
                }
            },
            valueRange = min.toFloat()..max.toFloat(),
            // Intermediate stops only — the two ends are not steps.
            steps = (max - min) - 1,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        // The numbers sit UNDER the track, one per stop, so the whole scale is
        // read off the control itself. A value bubble on the thumb was the
        // alternative and is worse here: it is under the finger at exactly the
        // moment it is being read.
        //
        // Two details that look like nothing and are the difference between a
        // scale and a row of digits near a slider:
        //
        //  - **They line up with the THUMB's travel, not the row's edges.** A
        //    Material thumb's centre stops half a thumb inside each end, so
        //    labels spread across the full width sit left of the value they
        //    name — worst at the ends, which is where the eye checks. Spread
        //    edge to edge and inset by that half-thumb less half a digit, each
        //    label's centre lands on its stop.
        //  - **The size never changes.** Scaling the selected number up reflows
        //    its neighbours under the finger mid-drag. Selection is weight and
        //    colour, which cost no width.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TICK_INSET),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            (min..max).forEach { value ->
                val selected = value == rows
                val tint by animateColorAsState(
                    if (selected) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    label = "rows_tick",
                )
                Text(
                    value.toString(),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = tint,
                )
            }
        }
    }
}

/**
 * Which block leads the board: nothing, one platform, or one line.
 *
 * ## One row of chips, and two kinds of chip in it
 * Platforms and lines are different sorts of thing to pin — one is a place, the
 * other is a service calling at several places — so the line chips carry their
 * line's colour and the platform chips do not. That difference is what stops
 * "Platform 4" and "Victoria" reading as two items on one flat list, without a
 * pair of sub-headings for what is at most half a dozen chips.
 *
 * ## Why the options are not a fixed list
 * The platforms come from what the board has actually shown, so this picker can
 * never offer one that does not exist. It also means it is EMPTY on a station
 * whose departures have not been cached yet, which is honest: there is nothing
 * to pin until the board has run once. The lines are always there, because those
 * the user chose themselves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinPicker(
    pin: BoardPin?,
    platforms: List<String>,
    lines: List<String>,
    onPin: (BoardPin?) -> Unit,
) {
    val isDark = isDarkTheme()
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PinChip(
            label = "Nothing",
            selected = pin == null,
            onClick = { if (pin != null) onPin(null) },
        )
        platforms.forEach { platform ->
            val selected = pin?.kind == BoardPin.Kind.PLATFORM && pin.id == platform
            PinChip(
                label = platform,
                selected = selected,
                onClick = { onPin(if (selected) null else BoardPin(BoardPin.Kind.PLATFORM, platform)) },
            )
        }
        // Only past one line. With a single line every block on the board
        // carries it, so pinning it is guaranteed to change nothing.
        if (lines.size > 1) {
            lines.forEach { line ->
                val selected = pin?.kind == BoardPin.Kind.LINE && pin.id == line
                PinChip(
                    label = LineShortNames.shortName(line),
                    dot = lineColorForTheme(line, isDark),
                    selected = selected,
                    onClick = { onPin(if (selected) null else BoardPin(BoardPin.Kind.LINE, line)) },
                )
            }
        }
    }
}

/** One choice in [PinPicker]. Tapping the selected one clears it. */
@Composable
private fun PinChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    dot: Color? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val border by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
        label = "chip_border",
    )
    val fill by animateFloatAsState(if (selected) 0.14f else 0f, label = "chip_fill")

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(accent.copy(alpha = fill))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(11.dp))
            .pressScale(onClick = onClick, scale = 0.94f)
            // Selection is carried by colour and weight alone, which VoiceOver
            // cannot see. These chips are one exclusive choice, so they are
            // announced as what they are.
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(modifier = Modifier.size(7.dp).background(dot, CircleShape))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
