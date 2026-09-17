package com.stationly.app.ui.selection

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.core.model.sdui.SduiRouteStop
import com.stationly.core.util.RouteGraph
import kotlin.math.roundToInt

/* Geometry — one place, so the canvas, the nodes and the search-scroll can never
   disagree about where a stop actually is. */
internal val ROUTE_STOP_W = 76.dp
internal val ROUTE_ORIGIN_W = 104.dp
/** Where the origin's own dot sits inside its slot — at the right edge, so the
 *  boxed label reads as a caption to its LEFT and the route starts at the dot. */
private val ORIGIN_DOT_INSET = 13.dp
private val ROW_H = 66.dp
private val TERMINUS_W = 92.dp
/**
 * Leading/trailing gutter that scrolls with the map (see [RouteGraphPicker]).
 *
 * `internal` because it is part of the map's coordinate system, not just its
 * padding: anything scrolling the map to a column — the sheet's search — has to
 * offset by it or every hit lands a gutter's width off.
 */
internal val ROUTE_EDGE_GUTTER = 20.dp

/**
 * A terminus chip: the segment it sits past, the services that end there, and
 * what to call them.
 *
 * Resolved once per graph rather than per recomposition — working out which
 * services end at a segment means walking the pattern list, which is graph work
 * and not render work.
 */
private data class ChipSpec(
    val segment: RouteGraph.Segment,
    val patterns: List<RouteGraph.Pattern>,
    val label: String,
)

/**
 * Horizontal tube-map picker for "show me trains that go through here".
 *
 * Left-to-right like real signage: you on the left, the line running right,
 * dividing wherever the route divides AND COMING BACK TOGETHER wherever it
 * rejoins. The geometry IS the information — past a split a Uxbridge train never
 * reaches Heathrow, and no list can show that.
 *
 * ## Why it draws from a graph and not a tree
 * The earlier model was a tree, which can only ever divide. Real lines merge:
 * the Northern splits at Camden Town and is one line again from Kennington. A
 * tree can express that only by repeating the shared tail down both branches,
 * which drew two Kenningtons and two Mordens and told the user they were
 * different places. [RouteGraph] gives every stop ONE node, so a merge is drawn
 * as a merge and the picture matches the printed map.
 *
 * Line-work is one [Canvas] with the stops positioned on top: connectors cross
 * rows diagonally, which stacked row composables cannot draw.
 */
@Composable
fun RouteGraphPicker(
    graph: RouteGraph,
    originName: String,
    selectedIds: Set<String>,
    /** Whole services already taken, by pattern id — fills the terminus chip. */
    selectedPatternIds: Set<String> = emptySet(),
    /** Stop the search matched — ringed for attention, deliberately NOT selected. */
    focusedId: String?,
    /** The TfL colour of the line being drawn; branches are shades of it. */
    lineColor: Color,
    /** "Trains" / "Buses" / "Trams" — the terminus chips are not always trains. */
    vehiclePlural: String,
    onToggleStop: (SduiRouteStop) -> Unit,
    /** Every service the tapped chip stands for — a shared tail names several. */
    onToggleBranch: (List<RouteGraph.Pattern>) -> Unit,
    /** Fired on any touch of the map, so the search field can fold itself away. */
    onInteract: () -> Unit = {},
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    val rowCount = graph.rowCount.coerceAtLeast(1)
    val colCount = graph.colCount

    // Sized exactly to the graph — a fixed height left dead space on a
    // two-branch line and clipped a four-branch one.
    val totalW = ROUTE_ORIGIN_W + ROUTE_STOP_W * colCount + TERMINUS_W
    val totalH = ROW_H * rowCount

    fun x(col: Int): Dp = ROUTE_ORIGIN_W + ROUTE_STOP_W * col + ROUTE_STOP_W / 2
    fun y(row: Int): Dp = ROW_H * row + ROW_H / 2

    val byId = remember(graph) { graph.segments.associateBy { it.id } }

    // What the current picks actually claim. Choosing a stop admits every train
    // that gets that far, so the whole route beyond it is covered — and showing
    // that is what stops "All Battersea trains" from looking like it ticked one
    // arbitrary station. The chip picks the first stop only that branch reaches;
    // the branch then lights up behind it.
    val coveredIds = remember(graph, selectedIds, selectedPatternIds) {
        val out = selectedIds.flatMapTo(mutableSetOf()) { graph.downstreamOf(it) }
        // A taken service claims its whole line, which is the point of tapping
        // the chip. Without this the chip filled and nothing else moved, so the
        // map gave no sign of what had just been chosen.
        graph.segments.forEach { seg ->
            if (seg.patternIds.any { it in selectedPatternIds }) {
                seg.stops.forEach { out.add(it.id) }
            }
        }
        out
    }

    // The line's own colour, solid, on every branch.
    //
    // Deliberately NOT shaded per branch. A tube line diagram — the one on the
    // platform and in the carriage — draws the whole line in one colour and lets
    // the GEOMETRY carry the branching. Fading a branch reads as "this part is
    // less real", which is the opposite of true: a Charing Cross train is as
    // frequent as a Bank one. The terminus chips carry the distinction instead,
    // exactly as on the printed map.

    /**
     * Everything derived from the graph, resolved once per graph.
     *
     * All three were being recomputed inside the draw lambda or the render loop,
     * so a scroll or a single tap re-walked the segment list several times over.
     * The graph only changes when the user picks a different direction.
     */
    val sources = remember(graph) { graph.segments.filter { it.prevIds.isEmpty() } }

    // The origin sits level with the branches leaving it.
    val originRow = remember(graph) {
        if (sources.isEmpty()) 0 else sources.sumOf { it.row } / sources.size
    }

    /** Terminal segments with their chip text, resolved once rather than per draw. */
    val chips = remember(graph) {
        graph.segments
            .filter { it.isTerminal && it.stops.isNotEmpty() }
            .map { seg ->
                val ending = graph.patterns.filter { it.id in seg.patternIds }
                // A tail shared by several services is named for the place they
                // all reach ("Morden"); a branch only one takes carries its own
                // name ("Morden via Bank"), because that is the only thing
                // distinguishing it from its sibling.
                val label = when {
                    ending.isEmpty() -> seg.stops.last().name
                    ending.size == 1 -> ending.first().label
                    else -> ending.map { it.terminusName }.distinct()
                        .singleOrNull() ?: seg.stops.last().name
                }
                ChipSpec(seg, ending, label)
            }
    }

    // Scrolling the map is also "I'm done searching".
    val collapseOnScroll = remember(onInteract) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.x != 0f) onInteract()
                return Offset.Zero
            }
        }
    }

    Box(modifier.nestedScroll(collapseOnScroll).horizontalScroll(scrollState)) {
        // Gutters live INSIDE the scrolling content, not on the viewport. At rest
        // the map is inset like everything else on the sheet; drag it and the
        // line runs right off both edges instead of stopping at a margin.
        Box(Modifier.padding(horizontal = ROUTE_EDGE_GUTTER)) {
          Box(Modifier.width(totalW).height(totalH)) {

            Canvas(Modifier.width(totalW).height(totalH)) {
                val stroke = 3.5.dp.toPx()

                /**
                 * A divergence or a convergence, drawn the same way.
                 *
                 * An S-curve is the tube-map idiom for one line becoming two —
                 * and, run the other way, for two becoming one. A right-angle
                 * elbow reads as a different line rather than the same one
                 * splitting or joining.
                 */
                fun link(fromX: Float, fromY: Float, toX: Float, toY: Float, tint: Color) {
                    val path = Path().apply {
                        moveTo(fromX, fromY)
                        val mid = (fromX + toX) / 2f
                        cubicTo(mid, fromY, mid, toY, toX, toY)
                    }
                    drawPath(path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
                }

                // Every segment is one straight run on one row, by construction:
                // a segment ends exactly where the set of services on it changes.
                graph.segments.forEach { seg ->
                    val yPx = y(seg.row).toPx()
                    val fromX = x(seg.startCol).toPx()
                    val toX = x(seg.endCol).toPx()
                    if (toX > fromX) {
                        drawLine(
                            lineColor, Offset(fromX, yPx), Offset(toX, yPx),
                            strokeWidth = stroke, cap = StrokeCap.Round,
                        )
                    }
                }

                // Links out of the origin, to every service leaving it.
                val originX = (ROUTE_ORIGIN_W - ORIGIN_DOT_INSET).toPx()
                val originY = y(originRow).toPx()
                sources.forEach { seg ->
                    link(originX, originY, x(seg.startCol).toPx(), y(seg.row).toPx(), lineColor)
                }

                // Links between segments. A merge falls out for free: two
                // parents both point at one child, so two curves arrive at the
                // same node instead of the child being drawn twice.
                graph.segments.forEach { seg ->
                    seg.nextIds.forEach { childId ->
                        val child = byId[childId] ?: return@forEach
                        link(
                            x(seg.endCol).toPx(), y(seg.row).toPx(),
                            x(child.startCol).toPx(), y(child.row).toPx(),
                            // Tinted by the segment being entered, so a branch
                            // takes its own weight the moment it leaves.
                            lineColor,
                        )
                    }
                }
            }

            /* ── Origin ── */
            NodeSlot(x = ROUTE_ORIGIN_W / 2, y = y(originRow),
                     width = ROUTE_ORIGIN_W, density = density) {
                // Boxed label on the LEFT, the route's root dot on the RIGHT.
                // The label is a caption for where you are; the dot is where the
                // line actually begins, so the two must not be the same thing.
                Row(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(lineColor)
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                originName,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "You're here",
                            color = lineColor,
                            fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    // Solid, not ringed. The origin and the branch ends are the
                    // two CAPS of the route, and a filled disc is the signage
                    // idiom for "the line starts/stops here". Intermediate stops
                    // stay hollow rings, so the shape alone says which is which
                    // without another colour or label.
                    Box(Modifier.size(15.dp).background(lineColor, CircleShape))
                }
            }

            /* ── Stops ── */
            graph.segments.forEach { seg ->
                seg.stops.forEachIndexed { i, stop ->
                    StopNode(
                        stop = stop,
                        x = x(seg.startCol + i),
                        y = y(seg.row),
                        accent = lineColor,
                        selected = stop.id in selectedIds,
                        covered = stop.id in coveredIds,
                        focused = stop.id == focusedId,
                        // The last stop of a segment nothing continues from is
                        // where that service ends — drawn solid to match the
                        // origin, so both caps of the route read as endpoints. A
                        // last stop before a split or a merge stays a ring: the
                        // line carries on through it.
                        density = density,
                        onClick = { onInteract(); onToggleStop(stop) },
                    )
                }

            }

            /* ── Terminus chips ── */
            //
            // A separate pass, not a branch inside the stop loop: the chip is a
            // property of the SERVICE, and resolving which services end at a
            // segment is graph work that has no business rerunning on every
            // recomposition. See `chips`.
            chips.forEach { chip ->
                val seg = chip.segment
                val chipX = x(seg.endCol) + ROUTE_STOP_W / 2 + TERMINUS_W / 2
                // The chip fills when the SERVICE is taken, not when some stop
                // along it happens to be selected. Those are different answers,
                // and conflating them is what made the map's feedback unreadable.
                //
                // All-or-nothing across `patterns`: a tail past a merge ends
                // several services — Oval to Morden is reached both via Bank and
                // via Charing Cross — and the chip there says "Morden", so it
                // stands for all of them.
                val taken = chip.patterns.isNotEmpty() &&
                    chip.patterns.all { it.id in selectedPatternIds }
                NodeSlot(x = chipX, y = y(seg.row), width = TERMINUS_W, density = density) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (taken) lineColor else lineColor.copy(alpha = 0.16f))
                            .clickable {
                                onInteract()
                                if (chip.patterns.isNotEmpty()) onToggleBranch(chip.patterns)
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "All ${chip.label} ${vehiclePlural.lowercase()}",
                            color = if (taken) MaterialTheme.colorScheme.onPrimary else lineColor,
                            fontSize = 9.sp, lineHeight = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

        }
        }
    }
}

/**
 * Absolutely-positioned cell centred on (x, y).
 *
 * Compose offsets from the top-left, so everything is shifted back by half its
 * own size — that keeps every call site thinking in centre coordinates, which is
 * what the canvas geometry uses too.
 */
@Composable
private fun NodeSlot(
    x: Dp,
    y: Dp,
    width: Dp,
    density: Density,
    content: @Composable () -> Unit,
) {
    val dx = with(density) { (x - width / 2).toPx() }.roundToInt()
    val dy = with(density) { (y - ROW_H / 2).toPx() }.roundToInt()
    Box(
        Modifier.offset { IntOffset(dx, dy) }.width(width).height(ROW_H),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Label above, marker centred on the line.
 *
 * The marker must sit exactly at the slot's vertical centre because that is
 * where the canvas draws the route — equal weights above and below are what
 * guarantee it, so the dot reads as being ON the line rather than beside it.
 */
@Composable
private fun StackedNode(
    label: String,
    labelColor: Color,
    marker: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxHeight().fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    color = labelColor,
                    fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        marker()
        Box(Modifier.weight(1f))
    }
}

/**
 * One station on the map: a ring sitting on the line, its name above.
 *
 * The tap target is the whole [ROW_H]-tall cell — a 14dp circle is far below a
 * comfortable touch target, and mis-taps are costly on a 30-stop line.
 */
@Composable
private fun StopNode(
    stop: SduiRouteStop,
    x: Dp,
    y: Dp,
    accent: Color,
    selected: Boolean,
    /** Downstream of a pick, so this train is already included by it. */
    covered: Boolean,
    focused: Boolean,
    density: Density,
    onClick: () -> Unit,
) {
    NodeSlot(x = x, y = y, width = ROUTE_STOP_W, density = density) {
        // Tap target stays the whole cell (a 14dp dot is far below a comfortable
        // touch target), but the RIPPLE is small, circular and centred — the
        // full-cell rounded-square highlight read as selecting a block rather
        // than tapping a station on a line.
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxHeight().fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(bounded = false, radius = 19.dp),
                    onClick = onClick,
                )
        ) {
            StackedNode(
                label = stop.name,
                labelColor = when {
                    selected -> MaterialTheme.colorScheme.onSurface
                    focused -> accent
                    covered -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Search focus: a pulsing halo that draws the eye WITHOUT
                    // selecting. Finding a stop and choosing it are separate
                    // decisions — auto-selecting a search hit would filter the
                    // board on a stop the user was only looking for.
                    if (focused && !selected) {
                        val pulse by rememberInfiniteTransition("focus").animateFloat(
                            initialValue = 0.35f, targetValue = 0.9f,
                            animationSpec = infiniteRepeatable(tween(820), RepeatMode.Reverse),
                            label = "focusPulse",
                        )
                        Box(
                            Modifier.size(30.dp)
                                .background(accent.copy(alpha = pulse * 0.22f), CircleShape)
                                .border(1.5.dp, accent.copy(alpha = pulse), CircleShape)
                        )
                    }
                    // THREE states, and no more. Every extra circle on this map
                    // was read as selection feedback, because that is what a
                    // marker changing shape means to someone tapping stations.
                    // Structure — where the line ends, where it divides — is
                    // carried by the geometry and the terminus chips, which is
                    // where a printed diagram carries it too.
                    when {
                        // The stop you actually chose.
                        selected -> Box(
                            Modifier.size(19.dp).background(accent, CircleShape),
                            Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Check, null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        // Branch end: solid disc, same as the origin. Reads as a
                        // terminus rather than "one more stop the line passes
                        // through", which a ring cannot say on its own.
                        // Included by a pick further back. A filled centre
                        // says "already covered" without claiming you chose it:
                        // the tick belongs to the stop you actually tapped.
                        covered -> Box(
                            Modifier.size(14.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .border(3.dp, accent, CircleShape),
                            Alignment.Center,
                        ) { Box(Modifier.size(6.dp).background(accent, CircleShape)) }
                        else -> Box(
                            Modifier.size(14.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .border(3.dp, accent, CircleShape)
                        )
                    }
                }
            }
        }
    }
}
