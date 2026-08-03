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
import androidx.compose.foundation.layout.Arrangement
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
import com.stationly.core.util.RouteTree
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

/** A tree node with its resolved grid position. */
private data class Placed(val node: RouteTree, val startCol: Int, val row: Float)

/**
 * Assign every node a column and a row.
 *
 * Columns follow the route: a node starts where its parent's stops end. Leaves
 * take the next free row and an internal node centres on its children, which is
 * what makes a split read as one line dividing rather than several unrelated
 * lines stacked up.
 */
private fun place(
    node: RouteTree,
    startCol: Int,
    out: MutableList<Placed>,
    nextLeafRow: IntArray,
): Float {
    val childStart = startCol + node.stops.size
    val row = if (node.isLeaf) {
        (nextLeafRow[0]++).toFloat()
    } else {
        node.children.map { place(it, childStart, out, nextLeafRow) }.average().toFloat()
    }
    out.add(Placed(node, startCol, row))
    return row
}

/**
 * Horizontal tube-map picker for "show me trains that go through here".
 *
 * Left-to-right like real signage: you on the left, the line running right,
 * dividing wherever the route divides. The geometry IS the information — past a
 * split a Uxbridge train never reaches Heathrow, and no list can show that.
 *
 * Line-work is one [Canvas] with the stops positioned on top: connectors cross
 * rows diagonally, which stacked row composables cannot draw.
 */
@Composable
fun RouteGraphPicker(
    tree: RouteTree,
    originName: String,
    selectedIds: Set<String>,
    /** Stop the search matched — ringed for attention, deliberately NOT selected. */
    focusedId: String?,
    /** The TfL colour of the line being drawn; branches are shades of it. */
    lineColor: Color,
    /** "Trains" / "Buses" / "Trams" — the terminus chips are not always trains. */
    vehiclePlural: String,
    onToggleStop: (SduiRouteStop) -> Unit,
    onToggleBranch: (RouteTree) -> Unit,
    /** Fired on any touch of the map, so the search field can fold itself away. */
    onInteract: () -> Unit = {},
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val placed = remember(tree) {
        mutableListOf<Placed>().also { place(tree, 0, it, intArrayOf(0)) }
    }

    val rowCount = tree.leafCount.coerceAtLeast(1)
    val colCount = placed.maxOfOrNull { it.startCol + it.node.stops.size } ?: 0

    // Sized exactly to the tree — a fixed height left dead space on a
    // two-branch line and clipped a four-branch one.
    val totalW = ROUTE_ORIGIN_W + ROUTE_STOP_W * colCount + TERMINUS_W
    val totalH = ROW_H * rowCount

    fun x(col: Int): Dp = ROUTE_ORIGIN_W + ROUTE_STOP_W * col + ROUTE_STOP_W / 2
    fun y(row: Float): Dp = ROW_H * row + ROW_H / 2

    /** Branch shade: same hue as the line, lightened down the tree so siblings separate. */
    fun shadeFor(depth: Int, sibling: Int): Color =
        lineColor.copy(alpha = (1f - (depth * 0.06f) - (sibling * 0.10f)).coerceIn(0.45f, 1f))

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

                fun drawNode(p: Placed, depth: Int, sibling: Int, isRoot: Boolean) {
                    val tint = shadeFor(depth, sibling)
                    val yPx = y(p.row).toPx()

                    // Segment across this node's own stops.
                    //
                    // Only the ROOT reaches back to the origin marker. A child
                    // must start at its FIRST stop: the junction S-curve already
                    // covers the gap from the parent, so extending back a column
                    // drew a stray stub sticking out to the left of every
                    // post-split node.
                    val fromX = if (isRoot) (ROUTE_ORIGIN_W - ORIGIN_DOT_INSET).toPx()
                                else x(p.startCol).toPx()
                    val toX = if (p.node.stops.isEmpty()) fromX
                              else x(p.startCol + p.node.stops.size - 1).toPx()
                    if (toX > fromX) {
                        drawLine(
                            tint, Offset(fromX, yPx), Offset(toX, yPx),
                            strokeWidth = stroke, cap = StrokeCap.Round,
                        )
                    }

                    // Junction out to each child: an S-curve, the way a tube map
                    // draws a divergence. A right-angle elbow reads as a
                    // different line rather than the same one splitting.
                    val childCol = p.startCol + p.node.stops.size
                    p.node.children.forEachIndexed { i, child ->
                        val cp = placed.first { it.node === child }
                        val cy = y(cp.row).toPx()
                        val cx = x(childCol).toPx()
                        val path = Path().apply {
                            moveTo(toX, yPx)
                            val mid = (toX + cx) / 2f
                            cubicTo(mid, yPx, mid, cy, cx, cy)
                        }
                        drawPath(
                            path,
                            color = shadeFor(depth + 1, i),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                        drawNode(cp, depth + 1, i, isRoot = false)
                    }
                }

                placed.firstOrNull { it.node === tree }?.let { drawNode(it, 0, 0, isRoot = true) }
            }

            /* ── Origin ── */
            NodeSlot(x = ROUTE_ORIGIN_W / 2, y = y(placed.first { it.node === tree }.row),
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
            placed.forEach { p ->
                val depth = depthOf(placed, tree, p)
                val tint = shadeFor(depth, siblingIndexOf(placed, p))
                p.node.stops.forEachIndexed { i, stop ->
                    StopNode(
                        stop = stop,
                        x = x(p.startCol + i),
                        y = y(p.row),
                        accent = tint,
                        selected = stop.id in selectedIds,
                        focused = stop.id == focusedId,
                        // The last stop of a LEAF is where that branch ends —
                        // drawn solid to match the origin, so both caps of the
                        // route read as endpoints. A last stop on an internal
                        // node is only the point before a split, and stays a
                        // ring: the line carries on through it.
                        isEndpoint = p.node.isLeaf && i == p.node.stops.lastIndex,
                        density = density,
                        onClick = { onInteract(); onToggleStop(stop) },
                    )
                }
                // Terminus chip past the end of a leaf — the tube-map idiom, and
                // a one-tap way to take the whole branch.
                // `stops.isNotEmpty()` guard: a destination whose whole run is a
                // prefix of its siblings' becomes a leaf with no stops of its
                // own. Its chip would be positioned one column early and could
                // never be selected, since selecting a branch means selecting
                // its first stop.
                if (p.node.isLeaf && p.node.terminusName != null && p.node.stops.isNotEmpty()) {
                    val lastCol = p.startCol + p.node.stops.size - 1
                    val chipX = x(lastCol) + ROUTE_STOP_W / 2 + TERMINUS_W / 2
                    val taken = p.node.stops.firstOrNull()?.id?.let { it in selectedIds } == true
                    NodeSlot(x = chipX, y = y(p.row), width = TERMINUS_W, density = density) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (taken) tint else tint.copy(alpha = 0.16f))
                                .clickable { onInteract(); onToggleBranch(p.node) }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "All ${p.node.terminusName} ${vehiclePlural.lowercase()}",
                                color = if (taken) MaterialTheme.colorScheme.onPrimary else tint,
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
}

private fun depthOf(placed: List<Placed>, root: RouteTree, target: Placed): Int {
    var depth = 0
    var current = root
    while (current !== target.node) {
        val next = current.children.firstOrNull { child ->
            child === target.node || containsNode(child, target.node)
        } ?: return depth
        current = next
        depth++
    }
    return depth
}

private fun containsNode(node: RouteTree, target: RouteTree): Boolean =
    node === target || node.children.any { containsNode(it, target) }

private fun siblingIndexOf(placed: List<Placed>, target: Placed): Int {
    placed.forEach { p ->
        val i = p.node.children.indexOfFirst { it === target.node }
        if (i >= 0) return i
    }
    return 0
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
    sublabel: String? = null,
    labelColor: Color,
    sublabelColor: Color = labelColor,
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
                if (sublabel != null) {
                    Text(
                        sublabel,
                        color = sublabelColor,
                        fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, maxLines = 1,
                    )
                }
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
    focused: Boolean,
    /** Last stop of a branch — drawn solid, like the origin, to cap the line. */
    isEndpoint: Boolean = false,
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
                    when {
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
                        isEndpoint -> Box(Modifier.size(14.dp).background(accent, CircleShape))
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
