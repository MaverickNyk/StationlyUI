package com.stationly.app.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.core.util.ListReorder.moved
import com.stationly.core.util.ListReorder.slotOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How long a finger must rest on an item before it lifts.
 *
 * Shorter than Material's 500ms long press. This is a pickup, not a secondary
 * action: the user is already looking at a drag handle, and half a second of
 * nothing happening reads as the gesture not being supported. Long enough that a
 * scroll started on an item still scrolls.
 */
private const val PICKUP_MS = 260L

/**
 * Hold-and-drag reordering along one axis.
 *
 * The home settings station list and its carousel strip are the same interaction
 * along different axes, so this is written once and given the axis. Everything
 * five attempts at drag-to-reorder cost — `docs/IOS_HANDOVER.md` §6d and
 * `BOARD_AND_DREAM_UI.md` §19 — is concentrated here, so there is exactly one
 * copy of it to keep right.
 *
 * ## The three things that make it work
 *  1. **The detector is on the CONTAINER, never on an item.** A
 *     `PointerInputChange`'s position is in the coordinates of the node
 *     receiving it, so an item that moves to follow the finger sees a
 *     stationary finger and the drag delivers nothing.
 *  2. **It claims the gesture on the `Initial` pass.** An ancestor scroller
 *     detects drags on `Main`, and once it consumes a move the child's drag
 *     ends silently — an item that lifts and then refuses to budge. `Initial`
 *     runs before `Main` in its entirety, so the scroller never sees one.
 *  3. **`pointerInput` is keyed on `Unit`.** Keyed on the data it tore the
 *     modifier down and cancelled the in-flight gesture on the first change;
 *     the live list is read through [rememberUpdatedState].
 *
 * ## Positions, not reordering
 * The committed list is frozen for the whole drag — reordering it reorders the
 * composables under the finger — and each item is placed at
 * `ListReorder.slotOf(...) * slotExtent`. `slotOf` and `moved` agree (there is a
 * test in core pinning that), so by the time the finger lifts every item is
 * already standing where the committed list is about to put it and the commit
 * changes no pixel. The lifted item flies to its slot BEFORE the commit, for the
 * same reason.
 *
 * ## One detector owns the tap too
 * A `Modifier.clickable` beside this would fire on release however long the
 * press was, so every drop would also trigger it. A release before the item
 * lifts is the tap, and a hold that never moved is treated as one as well.
 *
 * [slotExtent] is the pitch along the drag axis — row height, or chip width.
 * The caller must size [modifier] to `slotExtent * items.size` along that axis;
 * the cross axis wraps its content. [onDraggingChange] exists so the caller can
 * shut off any scroller it lives inside: the `Initial`-pass claim already
 * handles the arbitration, and this is the second lock.
 */
@Composable
internal fun <T> ReorderBox(
    items: List<T>,
    key: (T) -> Any,
    horizontal: Boolean,
    slotExtent: Dp,
    onReorder: (List<T>) -> Unit,
    onOpen: (T) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    item: @Composable (
        value: T,
        position: Int,
        modifier: Modifier,
        liftFraction: () -> Float,
    ) -> Unit,
) {
    val slotPx = with(LocalDensity.current) { slotExtent.toPx() }
    val scope = rememberCoroutineScope()

    // Read inside the gesture, which outlives any single composition.
    val live by rememberUpdatedState(items)
    val reorder by rememberUpdatedState(onReorder)
    val open by rememberUpdatedState(onOpen)
    val draggingChanged by rememberUpdatedState(onDraggingChange)
    val axis by rememberUpdatedState(horizontal)

    // Index the drag picked up at, or -1 for no drag. Snapshot state because the
    // items' slots are derived from it, and it changes once per gesture.
    val fromIndex = remember { mutableIntStateOf(-1) }
    // Which slot the lifted item is currently over. Changes a handful of times
    // per drag, at the moments the others are supposed to move.
    val overIndex = remember { mutableIntStateOf(-1) }
    // The lifted item's leading edge along the drag axis, in container pixels.
    // Written on every pointer move and read only inside `offset {}`, so
    // following the finger costs a layout pass and never a recomposition.
    val lift = remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // A drag, or the flight at the end of one, is still in the air.
                // Its coroutine is going to clear the drag state when it lands,
                // and a second gesture arming in the meantime would have that
                // clear pulled out from under it.
                if (fromIndex.intValue >= 0) return@awaitEachGesture
                fun along(offset: androidx.compose.ui.geometry.Offset) =
                    if (axis) offset.x else offset.y
                val startIndex = (along(down.position) / slotPx).toInt()
                if (startIndex !in live.indices) return@awaitEachGesture

                // ── Phase 1: is this a tap, a scroll, or a pickup? ──
                //
                // Nothing is consumed here, so a drag that starts on an item
                // still scrolls whatever is around it. A finger that holds still
                // produces no events at all, which is precisely what lets the
                // timeout fire.
                val settled = withTimeoutOrNull(PICKUP_MS) {
                    var released = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { released = true; break }
                        val moved = (change.position - down.position).getDistance()
                        if (moved > viewConfiguration.touchSlop) break
                    }
                    released
                }
                if (settled != null) {
                    // Left before the item could lift: a tap opens it, a drag
                    // belongs to the surrounding scroller.
                    //
                    // `getOrNull`, not `[]`: the list is read live, and a station
                    // deleted on another screen inside the hold window would
                    // otherwise index past its end.
                    if (settled) live.getOrNull(startIndex)?.let(open)
                    return@awaitEachGesture
                }

                // ── Phase 2: it is up ──
                val count = live.size
                val maxLift = (count - 1) * slotPx
                val grab = along(down.position) - startIndex * slotPx
                performHaptic(HapticType.TAP)
                fromIndex.intValue = startIndex
                overIndex.intValue = startIndex
                lift.floatValue = (along(down.position) - grab).coerceIn(0f, maxLift)
                draggingChanged(true)

                var dragged = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) { change.consume(); break }
                    change.consume()
                    val position = (along(change.position) - grab).coerceIn(0f, maxLift)
                    if (abs(position - lift.floatValue) > 0.5f) dragged = true
                    lift.floatValue = position
                    val slot = ((position + slotPx / 2f) / slotPx).toInt().coerceIn(0, count - 1)
                    if (slot != overIndex.intValue) {
                        overIndex.intValue = slot
                        performHaptic(HapticType.TAP)
                    }
                }

                // ── Phase 3: the drop ──
                //
                // It flies to its slot BEFORE the list is committed. Everything
                // else is already standing in its post-commit position, so once
                // this one lands the commit changes no pixel — which is the whole
                // reason the list was frozen.
                val landed = overIndex.intValue
                val startedAt = lift.floatValue
                scope.launch {
                    animate(
                        initialValue = startedAt,
                        targetValue = landed * slotPx,
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = Spring.StiffnessMedium,
                            visibilityThreshold = 0.5f,
                        ),
                    ) { value, _ -> lift.floatValue = value }
                    if (landed != startIndex) {
                        performHaptic(HapticType.SUCCESS)
                        reorder(live.moved(startIndex, landed))
                    } else if (!dragged) {
                        // Held, never dragged, put back down. Someone who
                        // presses slowly still meant to tap.
                        live.getOrNull(startIndex)?.let(open)
                    }
                    fromIndex.intValue = -1
                    overIndex.intValue = -1
                    draggingChanged(false)
                }
            }
        }
    ) {
        items.forEachIndexed { index, value ->
            key(key(value)) {
                val from = fromIndex.intValue
                val over = overIndex.intValue
                val isLifted = from == index

                // Read inside `offset {}` below, so an item settling into its new
                // place re-places itself without recomposing its contents.
                val slot = animateFloatAsState(
                    targetValue = slotOf(index, from, over) * slotPx,
                    animationSpec = spring(
                        dampingRatio = 0.9f,
                        stiffness = Spring.StiffnessMedium,
                        visibilityThreshold = 0.5f,
                    ),
                    label = "slot",
                )
                // Lifts off the surface: tinted, slightly larger, above its
                // neighbours. Everything here is draw-phase only.
                val liftFraction = animateFloatAsState(
                    targetValue = if (isLifted) 1f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "lift",
                )
                item(
                    value,
                    slotOf(index, from, over) + 1,
                    Modifier
                        .zIndex(if (isLifted) 1f else 0f)
                        .offset {
                            val placed = (if (isLifted) lift.floatValue else slot.value).roundToInt()
                            if (horizontal) IntOffset(placed, 0) else IntOffset(0, placed)
                        }
                        .graphicsLayer {
                            val l = liftFraction.value
                            scaleX = 1f + 0.03f * l
                            scaleY = 1f + 0.03f * l
                        },
                ) { liftFraction.value }
            }
        }
    }
}
