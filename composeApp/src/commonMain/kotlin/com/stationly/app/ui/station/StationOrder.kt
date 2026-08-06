package com.stationly.app.ui.station

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.platform.ModeIconStore
import com.stationly.app.ui.common.ReorderBox
import com.stationly.app.ui.common.SettingsCard
import com.stationly.app.ui.summary.components.lineColorForTheme
import com.stationly.app.ui.util.HomeLayout
import com.stationly.app.ui.theme.isDarkTheme
import com.stationly.core.util.LineShortNames

/**
 * The two ways the user arranges their stations, and the row and chip they
 * arrange.
 *
 * Split out of `HomeSettingsScreen` because it is a self-contained interaction
 * with a long story behind it, and the settings screen it lives on is a list of
 * unrelated settings. The gesture itself is generic and lives in
 * [ReorderBox]; what is here is what a STATION looks like while being dragged.
 */

/** Height of one row in the order list. The drag maths depends on it being fixed. */
private val ORDER_ROW_HEIGHT = 62.dp

/** One tracked station, as the order list needs it. */
internal data class HomeStation(
    val id: String,
    val name: String,
    val mode: String,
    /** Line ids, in the order the user picked them — displayed, so keep it stable. */
    val lines: List<String>,
)

/**
 * The station list for [HomeLayout.LIST]: a stack of rows, held and dragged into
 * order.
 *
 * The gesture — and the five attempts it took to get one that responds on
 * device — is [ReorderBox]. Nothing about it is repeated here; this is only what
 * a station looks like in that list.
 */
@Composable
internal fun StationOrderCard(
    stations: List<HomeStation>,
    onReorder: (List<HomeStation>) -> Unit,
    onOpen: (HomeStation) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
) {
    SettingsCard {
        ReorderBox(
            items = stations,
            key = { it.id },
            horizontal = false,
            slotExtent = ORDER_ROW_HEIGHT,
            onReorder = onReorder,
            onOpen = onOpen,
            onDraggingChange = onDraggingChange,
            modifier = Modifier.fillMaxWidth().height(ORDER_ROW_HEIGHT * stations.size),
        ) { station, position, itemModifier, liftFraction ->
            StationOrderRow(
                station = station,
                position = position,
                modifier = itemModifier.fillMaxWidth(),
                liftFraction = liftFraction,
            )
        }
    }
}

/**
 * Narrowest a chip may get before the strip starts scrolling instead.
 *
 * Two lines of real text need real width: at 92dp "King's Cross St. Pancras"
 * was three characters and an ellipsis. Past four or five stations the strip
 * scrolls, which is the honest outcome — the alternative is a row of chips none
 * of which can be told apart.
 */
private val ORDER_CHIP_MIN_WIDTH = 132.dp

/**
 * The same ordering for [HomeLayout.CAROUSEL], laid out the way a carousel reads
 * it: left to right.
 *
 * A vertical list under a setting called "Carousel" asks the user to hold two
 * pictures at once and map one onto the other. The pages run sideways, so the
 * control that arranges them runs sideways, and dragging a station left is
 * literally dragging its page left.
 *
 * Chips share the width evenly and do not scroll while they fit, which is the
 * whole strip for anyone tracking a handful of stations. Past that it scrolls,
 * and that scroller is shut off while a chip is held: [ReorderBox] claims the
 * gesture on the `Initial` pass regardless, but here the two want the SAME axis
 * rather than different ones, so the second lock earns its keep.
 */
@Composable
internal fun StationOrderStrip(
    stations: List<HomeStation>,
    onReorder: (List<HomeStation>) -> Unit,
    onOpen: (HomeStation) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    val stripScroll = rememberScrollState()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val chipWidth = (maxWidth / stations.size).coerceAtLeast(ORDER_CHIP_MIN_WIDTH)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(stripScroll, enabled = !dragging)
        ) {
            ReorderBox(
                items = stations,
                key = { it.id },
                horizontal = true,
                slotExtent = chipWidth,
                onReorder = onReorder,
                onOpen = onOpen,
                onDraggingChange = {
                    dragging = it
                    onDraggingChange(it)
                },
                modifier = Modifier.width(chipWidth * stations.size),
            ) { station, _, itemModifier, liftFraction ->
                StationOrderChip(
                    station = station,
                    modifier = itemModifier.width(chipWidth),
                    liftFraction = liftFraction,
                )
            }
        }
    }
}

/**
 * One station as a page in the carousel's order strip.
 *
 * Two lines, in the order the eye wants them: **roundel and station name**, then
 * **the lines that station carries**. Same reading order as the station card
 * itself and as the list row, so all three describe a station the same way.
 *
 * It was briefly a centred stack — page number, roundel, name, coloured dots —
 * which looked tidy and said less: the dots alone cannot tell Circle from
 * Hammersmith & City, which at an interchange is the only thing distinguishing
 * two otherwise identical chips. The names are the content; the dots stay
 * because they survive the truncation the names may not.
 *
 * There is no page number. In a strip the ORDER IS THE POSITION, left to right,
 * and a number on each chip is that same fact printed twice.
 */
@Composable
private fun StationOrderChip(
    station: HomeStation,
    modifier: Modifier,
    liftFraction: () -> Float,
) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    Box(modifier = modifier.padding(4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    BorderStroke(1.dp, onBackground.copy(alpha = 0.10f)),
                    RoundedCornerShape(14.dp),
                )
                .drawLiftBackground(MaterialTheme.colorScheme.primary, liftFraction)
                .padding(vertical = 10.dp, horizontal = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = remember(station.mode) { ModeIconStore.cachedIconBitmap(station.mode) }
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(20.dp))
                } else {
                    Box(
                        modifier = Modifier.size(20.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    station.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                station.lines.take(3).forEach { line ->
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(lineColorForTheme(line, isDarkTheme()), CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    station.lines.joinToString(" · ") { LineShortNames.shortName(it) }
                        .ifBlank { "No lines yet" },
                    fontSize = 11.sp,
                    color = onBackground.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One row of the order list: position, mode roundel, station name, its lines. */
@Composable
private fun StationOrderRow(
    station: HomeStation,
    position: Int,
    modifier: Modifier,
    /** 0 at rest, 1 while this row is held. Read in the draw phase only. */
    liftFraction: () -> Float,
) {
    val surface = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ORDER_ROW_HEIGHT)
            // The held row gets a tint under it so it reads as picked up off the
            // card rather than merely nudged. Drawn from a lambda, so the lift
            // animation never recomposes the row's text or roundel.
            .drawLiftBackground(surface, liftFraction)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Position, stated plainly. The order is the entire point of this list,
        // and a number says where a row sits without the user counting. It
        // updates DURING a drag, so the answer to "where will this land" is on
        // the row itself.
        Text(
            "$position",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.width(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        // The real backend roundel — the same cached bitmap the card header and
        // the widget draw, so a station looks like itself everywhere. The tinted
        // disc is the pre-first-sync fallback, not a second design.
        val icon = remember(station.mode) { ModeIconStore.cachedIconBitmap(station.mode) }
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(26.dp))
        } else {
            Box(
                modifier = Modifier.size(26.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                station.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A dot per line in its own colour, then the names. The dots
                // survive the truncation the names may not, so a station with
                // four lines still shows that it has four.
                station.lines.take(4).forEach { line ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(lineColorForTheme(line, isDarkTheme()), CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    station.lines.joinToString(" · ") { LineShortNames.displayName(it) }
                        .ifBlank { "No lines yet" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The affordance. Not a hit target of its own: the hold works anywhere on
        // the row, and a handle that is the ONLY way to start a drag asks for a
        // precision the gesture does not need.
        Icon(
            Icons.Rounded.DragIndicator,
            null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The held row's backing tint, evaluated in the draw phase. */
private fun Modifier.drawLiftBackground(color: Color, fraction: () -> Float): Modifier =
    drawBehind {
        val f = fraction()
        if (f <= 0.01f) return@drawBehind
        drawRoundRect(
            color = color.copy(alpha = 0.07f * f),
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
        )
    }
