package com.stationly.app.ui.station

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.app.ui.common.SettingsCaption
import com.stationly.app.ui.common.SettingsSectionLabel
import com.stationly.app.ui.common.pressScale
import com.stationly.app.ui.summary.components.lineColorForTheme
import com.stationly.app.ui.theme.isDarkTheme
import com.stationly.core.util.BoardDisplayPrefs
import com.stationly.core.util.BoardPin
import com.stationly.core.util.LineShortNames
import com.stationly.core.util.MultiLineBoardProcessor.StopOption
import kotlin.math.roundToInt

/**
 * The two settings that arrange ONE station's departure board: how deep each
 * platform goes, and which block leads.
 *
 * ## What is deliberately NOT here
 * **The platform grouping.** It is the one thing about the board that is not
 * negotiable, and `BoardDisplayPrefs` in core says why at length: everything
 * under a header is one queue in one place you can walk to, and a flat re-sort
 * of the whole board would leave the user reading the platform off every
 * individual row to know where to stand.
 *
 * **An "order by" control.** There was one — Time / Platform / Destination — and
 * core carries the argument for why it went. In short: its three segments acted
 * at two different levels, it was inert on any bus board, and the pin below
 * already answers the question it was justified by, more directly.
 *
 * **A drawn preview**, unlike the layout picker above it on the same screen.
 * That one shows two fictional boards because "hide the countdown" is a shape
 * the user would otherwise have to imagine. These two are different: the real
 * board is one back-tap away and changes the moment you get there, so the honest
 * preview is the board itself. Drawing one from the SQLite cache was the
 * alternative and was rejected — cached ETAs are minutes or hours old, and a
 * settings screen showing "Brixton 2 min" over stale data is indistinguishable
 * from showing it over live data.
 *
 * @param platforms the platform labels this station has actually been showing,
 *   from the cache — see `StationSettingsViewModel.platforms`. Empty on bus.
 * @param stops the poles at a bus hub, named by where they go. Empty on rail.
 * @param lines the lines tracked here, canonical ids.
 * @param isBus buses have stops rather than platforms, and the words follow.
 */
@Composable
fun BoardArrangementSection(
    prefs: BoardDisplayPrefs,
    platforms: List<String>,
    stops: List<StopOption>,
    lines: List<String>,
    isBus: Boolean,
    onRowsPerPlatform: (Int) -> Unit,
    onPin: (BoardPin?) -> Unit,
) {
    // "Stop" everywhere "Platform" would be, on a bus board. The board itself
    // has never said "platform" at a bus stop and neither should the screen that
    // configures it.
    val place = if (isBus) "stop" else "platform"
    // "Line" is rail vocabulary. A bus user has routes, and the pin picker below
    // offers them by number.
    val service = if (isBus) "route" else "line"

    // The heading IS the readout. "Departures per stop" needed a caption to
    // explain that the number was a maximum; putting the live value in the
    // sentence says it in the words themselves, and leaves the caption free to
    // say something the user could not work out by looking.
    SettingsSectionLabel("Show up to ${prefs.rowCap} per $place")
    DepthSlider(
        rows = prefs.rowCap,
        label = "Departures per $place",
        onChange = onRowsPerPlatform,
    )
    SettingsCaption(
        "A quiet $place shows fewer — TfL sends what it sends. " +
            "Applies once the station is open."
    )

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
    if (platforms.isNotEmpty() || stops.isNotEmpty() || lines.size > 1 || pin != null) {
        Spacer(Modifier.height(28.dp))

        // "Show first" named an OUTCOME and left the mechanism unsaid, so its
        // off state had to be a chip reading "Nothing" — show nothing first,
        // which is not a thing anyone asks for. "Pin to top" names the action,
        // and an action has a natural absence: "None".
        SettingsSectionLabel("Pin to top")
        PinPicker(
            pin = pin,
            platforms = platforms,
            stops = stops,
            lines = lines,
            onPin = { next ->
                performHaptic(HapticType.TAP)
                onPin(next)
            },
        )
        // Two reserved lines rather than the measured overload: these sentences
        // are built from live data — a line's display name, a pole's
        // destination — so there is no fixed set of variants to measure. Two is
        // what every one of them wraps to.
        SettingsCaption(
            minLines = 2,
            text = when (pin?.kind) {
                // The line case is the one worth spelling out: a line at an
                // interchange is on more than one platform, and the user should
                // know it brings all of them rather than picking one.
                BoardPin.Kind.LINE ->
                    "Every $place ${LineShortNames.displayName(pin.id)} calls at leads the board."
                BoardPin.Kind.PLATFORM -> "${pin.id} leads the board. The rest keep their order."
                // Named by where it goes, because a naptan is not something the
                // user has ever seen — see BoardPin.Kind.STOP.
                BoardPin.Kind.STOP -> {
                    val towards = stops.firstOrNull { it.key == pin.id }?.towards
                    if (towards != null) "The $place towards $towards leads the board."
                    else "One $place leads the board. The rest keep their order."
                }
                // Says what happens WITHOUT a pin, which is the one thing the
                // other three branches cannot tell you.
                null -> "Nothing pinned. Whichever $place has the soonest " +
                    "departure leads, and a $service can be pinned instead."
            },
        )
    }
}

/** The bar itself, and the thumb that rides it. */
private val TRACK_HEIGHT = 4.dp
private val THUMB_SIZE = 26.dp
private val DETENT_SIZE = 3.dp

/**
 * Half the thumb (13dp) less half a digit (~3.5dp) — the inset that puts a tick
 * label's centre on the thumb stop it names. See the label row below.
 */
private val TICK_INSET = 9.5.dp

/**
 * How deep each platform goes, on a slider rather than a stepper.
 *
 * It is a quantity with a small fixed range, which is the one case a slider is
 * genuinely better at than +/- buttons: the whole scale is visible, so the user
 * can see that five is the most there is before they start dragging.
 *
 * ## Hand-built, because Material's is unmistakably not iOS
 * `Slider` brings a pill thumb, its own ripple and a track that stops short of
 * its own ends, and no combination of `SliderDefaults.colors` gets it to the
 * shape iOS has trained every user of this app to expect: a thin full-bleed
 * track, and a round white thumb sitting ON it with a real shadow. That thumb is
 * white in both themes on purpose — it is the ONE piece of an iOS slider that
 * never takes the tint, and it is what separates the control from the tinted
 * track behind it.
 *
 * ## The detents are the whole feel
 * Four values is too few to drag freely across. The thumb snaps to the nearest
 * stop and springs into it, the stops are marked on the track so the snapping is
 * predicted rather than discovered, and each one crossed fires
 * [HapticType.SELECTION] — Apple's own generator for a value moving between
 * discrete stops, which is drier than the impact used for taps. Together they
 * make the control settable without looking at it, which is the point: the
 * number the user is setting is up in the heading, not under their thumb.
 *
 * The thumb also grows slightly while held. It is 12%, it lasts as long as the
 * touch, and it is the difference between a control that is being operated and
 * a graphic that happens to move.
 */
@Composable
private fun DepthSlider(rows: Int, label: String, onChange: (Int) -> Unit) {
    val min = BoardDisplayPrefs.MIN_ROWS_PER_PLATFORM
    val max = BoardDisplayPrefs.MAX_ROWS_PER_PLATFORM
    val accent = MaterialTheme.colorScheme.primary
    val onBackground = MaterialTheme.colorScheme.onBackground
    val onAccent = MaterialTheme.colorScheme.onPrimary

    // Read inside the gesture handlers, which must NOT be re-keyed on the value:
    // restarting a `pointerInput` mid-drag cancels the drag, so a slider keyed on
    // its own value would die the instant it first moved.
    val currentRows by rememberUpdatedState(rows)
    val emit by rememberUpdatedState(onChange)
    var held by remember { mutableStateOf(false) }

    val fraction by animateFloatAsState(
        targetValue = (rows - min).toFloat() / (max - min).toFloat(),
        // Under-damped, so the thumb arrives in a detent with a hint of overshoot
        // instead of stopping dead — the same spring the segmented control's pill
        // travels on, because they are the same gesture answered.
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
        label = "depth_fraction",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (held) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh),
        label = "depth_thumb_scale",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            // The whole strip is the target, not just the 4dp bar. A hairline
            // track is impossible to hit deliberately, and iOS sliders have
            // always accepted a touch anywhere across their height.
            modifier = Modifier.fillMaxWidth().height(THUMB_SIZE + 12.dp),
        ) {
            val density = LocalDensity.current
            val thumbPx = with(density) { THUMB_SIZE.toPx() }
            val travelPx = with(density) { maxWidth.toPx() } - thumbPx

            // Where the thumb's CENTRE sits for a given stop, and the inverse.
            // Both ends are half a thumb inside the strip, which is what the tick
            // labels below are inset by.
            fun centreOf(step: Int): Float =
                thumbPx / 2f + (step - min).toFloat() / (max - min).toFloat() * travelPx

            fun stepAt(x: Float): Int {
                if (travelPx <= 0f) return currentRows
                val f = ((x - thumbPx / 2f) / travelPx).coerceIn(0f, 1f)
                return (min + f * (max - min)).roundToInt().coerceIn(min, max)
            }

            fun moveTo(x: Float) {
                val next = stepAt(x)
                if (next != currentRows) {
                    performHaptic(HapticType.SELECTION)
                    emit(next)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // ── The accessibility Material's Slider used to supply ──
                    //
                    // Hand-building the control silently dropped ALL of it: a
                    // VoiceOver user traversing this screen went straight from
                    // the heading to "Pin to top" with no way to reach or adjust
                    // the depth at all. `progressSemantics` announces the value
                    // and the range, `setProgress` is what the rotor's increment
                    // and decrement actually call, and the label is spelled out
                    // because the heading above is a separate node that says
                    // "Show up to 3 per platform" — a number, with no statement
                    // that this control is what changes it.
                    .progressSemantics(
                        value = rows.toFloat(),
                        valueRange = min.toFloat()..max.toFloat(),
                        steps = (max - min) - 1,
                    )
                    .semantics {
                        contentDescription = label
                        setProgress { target ->
                            val next = target.roundToInt().coerceIn(min, max)
                            if (next != currentRows) {
                                performHaptic(HapticType.SELECTION)
                                emit(next)
                            }
                            true
                        }
                    }
                    // Tap and drag are separate detectors on purpose: a tap
                    // anywhere on the strip jumps to that stop, which is how a
                    // four-value control is most often set, and dragging is for
                    // when the user wants to feel the range.
                    .pointerInput(travelPx, thumbPx) {
                        detectTapGestures { position -> moveTo(position.x) }
                    }
                    .pointerInput(travelPx, thumbPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { position ->
                                held = true
                                moveTo(position.x)
                            },
                            onDragEnd = { held = false },
                            onDragCancel = { held = false },
                        ) { change, _ -> moveTo(change.position.x) }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TRACK_HEIGHT)
                        .align(Alignment.CenterStart)
                        .drawBehind {
                            val radius = CornerRadius(size.height / 2f, size.height / 2f)
                            drawRoundRect(
                                color = onBackground.copy(alpha = 0.12f),
                                cornerRadius = radius,
                            )
                            // Filled up to the thumb's centre, so the fill ends
                            // UNDER the thumb rather than beside it. Clamped
                            // because `travelPx` goes negative if this is ever
                            // laid out narrower than the thumb, and a negative
                            // width is a draw-time crash rather than a squashed
                            // slider.
                            val filled = (thumbPx / 2f + fraction * travelPx)
                                .coerceIn(0f, size.width)
                            drawRoundRect(
                                color = accent,
                                size = Size(filled, size.height),
                                cornerRadius = radius,
                            )
                            // The stops, marked so the snap is expected. Each is
                            // drawn against whichever half of the track it falls
                            // on — a dot in the track colour vanishes on the
                            // filled side, which is where the user is looking.
                            (min..max).forEach { step ->
                                val x = centreOf(step)
                                if (kotlin.math.abs(x - filled) < thumbPx / 2f) return@forEach
                                drawCircle(
                                    color = if (x < filled) onAccent.copy(alpha = 0.55f)
                                    else onBackground.copy(alpha = 0.30f),
                                    radius = DETENT_SIZE.toPx() / 2f,
                                    center = Offset(x, size.height / 2f),
                                )
                            }
                        }
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset((fraction * travelPx).roundToInt(), 0) }
                        .size(THUMB_SIZE)
                        .graphicsLayer {
                            scaleX = thumbScale
                            scaleY = thumbScale
                        }
                        // The shadow is what makes a white disc read as sitting
                        // ON the track rather than as a hole punched through it,
                        // and it is the only thing separating thumb from
                        // background in light theme. It deepens while held.
                        .shadow(if (held) 6.dp else 3.dp, CircleShape)
                        .background(Color.White, CircleShape)
                )
            }
        }
        // The numbers sit UNDER the track, one per stop, so the whole scale is
        // read off the control itself. A value bubble on the thumb was the
        // alternative and is worse here: it is under the finger at exactly the
        // moment it is being read.
        //
        // Two details that look like nothing and are the difference between a
        // scale and a row of digits near a slider:
        //
        //  - **They line up with the THUMB's travel, not the row's edges.** The
        //    thumb's centre stops half a thumb inside each end, so labels spread
        //    across the full width sit left of the value they name — worst at
        //    the ends, which is where the eye checks. Spread edge to edge and
        //    inset by that half-thumb less half a digit ([TICK_INSET]), each
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
 *
 * ## A bus hub offers POLES, and it is the only pin worth having there
 * [stops] replaces [platforms] on bus, and both are never populated at once. A
 * pole has no label to pin — TfL letters stops only at multi-stop interchanges —
 * so it is pinned by naptan and shown as where its buses go, "→ Putney Bridge".
 * That arrow is the same one the next-departure hero uses for a destination, so
 * the chip reads as a direction rather than as a place you are choosing to be.
 *
 * Without it a hub could only offer its routes, and both sides of the road
 * usually run the same routes — so every chip on offer promoted every block and
 * changed nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinPicker(
    pin: BoardPin?,
    platforms: List<String>,
    stops: List<StopOption>,
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
            label = "None",
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
        stops.forEach { stop ->
            val selected = pin?.kind == BoardPin.Kind.STOP && pin.id == stop.key
            PinChip(
                label = "→ ${stop.towards}",
                selected = selected,
                onClick = { onPin(if (selected) null else BoardPin(BoardPin.Kind.STOP, stop.key)) },
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
