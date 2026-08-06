package com.stationly.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The departure board in miniature, for previews.
 *
 * Two screens ask the user to choose a layout by looking at it — the station
 * card's countdown-or-board picker, and the screensaver's clock-and-board or
 * fullscreen picker — and both used to draw their own idea of what a board looks
 * like. One had grown a private copy of the signage palette under three
 * different constant names; the other was four grey bars.
 *
 * The palette is FIXED in every theme, exactly as the real board is. A preview
 * whose panel went white in light mode would be showing the user a board that
 * does not exist. Values mirror `Board.kt` and `DreamBoard.kt`; if those ever
 * move, this moves with them or the previews start lying.
 *
 * The rows are fictional on purpose and shared between both callers. A preview
 * has to render identically for every user, including one whose last train has
 * gone, and a preview reading "Service ended for tonight" would be describing
 * tonight rather than the layout.
 */
object MiniBoardPalette {
    /** The signage card. Slightly darker than the panel on a live card. */
    val Card = Color(0xFF050505)
    /** The home board's panel, which sits inside a lighter surround. */
    val Panel = Color(0xFF0C0C0C)
    /** A lit row. */
    val Row = Color(0xFF161616)
    /** Everything written on the board, in every state. */
    val Amber = Color(0xFFFFC819)
}

/** Destination and ETA of one preview departure. */
data class MiniDeparture(val destination: String, val eta: String)

/**
 * The departures every preview shows, in the order they are taken.
 *
 * A shared list rather than one per caller: two previews of the same board that
 * disagree about what is on it look like two different products.
 */
val MINI_DEPARTURES = listOf(
    MiniDeparture("Brixton", "2 min"),
    MiniDeparture("Walthamstow Central", "5 min"),
    MiniDeparture("Brixton", "9 min"),
    MiniDeparture("Seven Sisters", "12 min"),
)

/**
 * A miniature signage panel: an optional header strip, some departures, and
 * whatever the caller adds underneath.
 *
 * [textSize] is the one dial. Everything scales off the caller's tile size
 * otherwise, and the two callers sit at noticeably different scales.
 */
@Composable
fun MiniBoard(
    modifier: Modifier = Modifier,
    background: Color = MiniBoardPalette.Card,
    borderColor: Color? = MiniBoardPalette.Amber.copy(alpha = 0.30f),
    corner: Dp = 8.dp,
    padding: Dp = 5.dp,
    textSize: TextUnit = 8.sp,
    /** Centred strip at the top, usually the station name. Omitted when null. */
    header: String? = null,
    departures: List<MiniDeparture> = MINI_DEPARTURES.take(3),
    /** Rows added under the departures — a status strip, a clock, a second platform. */
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(background)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, RoundedCornerShape(corner))
                } else Modifier
            )
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (header != null) {
            MiniBoardStrip {
                Text(
                    header,
                    color = MiniBoardPalette.Amber,
                    fontSize = textSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
                )
            }
        }
        departures.forEach { departure ->
            MiniBoardDeparture(departure, textSize)
        }
        footer()
    }
}

/** A centred header strip inside a [MiniBoard] — "PLATFORM 3", a station name. */
@Composable
fun MiniBoardHeader(text: String, textSize: TextUnit = 7.sp) {
    MiniBoardStrip {
        Text(
            text,
            color = MiniBoardPalette.Amber,
            fontSize = textSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One departure row: destination left, ETA right, exactly as the real board. */
@Composable
fun MiniBoardDeparture(departure: MiniDeparture, textSize: TextUnit = 8.sp) {
    MiniBoardStrip {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                departure.destination,
                color = MiniBoardPalette.Amber,
                fontSize = textSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                departure.eta,
                color = MiniBoardPalette.Amber,
                fontSize = textSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** The status strip every real board carries at its foot. */
@Composable
fun MiniBoardStatus(text: String = "Good Service", textSize: TextUnit = 7.sp) {
    MiniBoardStrip {
        Text(
            text,
            color = MiniBoardPalette.Amber,
            fontSize = textSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 3.dp),
        )
    }
}

/** Clock left, freshness right — the fullscreen dream board's own footer. */
@Composable
fun MiniBoardClock(time: String = "08:42", ago: String = "30s ago") {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time,
            color = MiniBoardPalette.Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Text(ago, color = MiniBoardPalette.Amber.copy(alpha = 0.55f), fontSize = 7.sp)
    }
}

/** The lit background every row on a dot-matrix board sits on. */
@Composable
fun MiniBoardStrip(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiniBoardPalette.Row)
            .padding(vertical = 1.dp),
    ) { content() }
}
