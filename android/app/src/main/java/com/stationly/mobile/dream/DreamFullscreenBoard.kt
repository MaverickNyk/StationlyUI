package com.stationly.mobile.dream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Fullscreen departure-board dream.
 *
 * NOT literally edge-to-edge — that read as "stretched widget" rather than
 * "signage panel". Instead we render the inflated widget as a rounded card
 * (beefier amber border + larger corner radius than the cluster dream),
 * sized so it never exceeds the available canvas, centred on a dark
 * background with generous breathing room around it.
 *
 *   ┌──────────────────────────────────────────┐
 *   │   ← dark canvas padding (orientation-adapted)
 *   │   ┌────────────────────────────────┐    │
 *   │   │ ◉ Station name + Stationly     │    │  ← amber-bordered card
 *   │   │ Platform · Westbound           │    │
 *   │   │ Departure 1            2 min   │    │  ← rows centred vertically
 *   │   │ Departure 2            5 min   │    │     inside ScrollView
 *   │   │ Departure 3            8 min   │    │     (no top-gap when short;
 *   │   │ Good Service : status reason   │    │      scrolls when long)
 *   │   │ 17:23:42        00:08 ago      │    │  ← ticking clock + chrono
 *   │   └────────────────────────────────┘    │
 *   └──────────────────────────────────────────┘
 *
 * Sizing — adaptive, no fixed numbers per device class:
 *   - textScale derived from short edge, with a small portrait penalty so
 *     stacked rows don't feel crammed compared to landscape (the user's
 *     direct feedback when both orientations used the same scale).
 *   - Card max width / height capped so a wide tablet doesn't stretch the
 *     board across 1280dp — capped at ~60% short-edge in landscape and
 *     fuller-width in portrait.
 *   - Outer padding scales with the short edge — phone hugs tighter,
 *     tablet leaves more breathing room.
 *
 * Behaviour:
 *   - Short board (few rows)   → card wraps content, rows centre inside
 *                                the ScrollView, footer pins to bottom.
 *   - Tall board (many rows)   → card hits its heightIn cap, rows scroll
 *                                naturally inside via the existing widget
 *                                ScrollView wrap.
 */
@Composable
fun FullscreenBoardLayout(snapshot: DreamSnapshot) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        val shortEdgeDp = minOf(maxWidth, maxHeight).value
        val longEdgeDp  = maxOf(maxWidth, maxHeight).value

        // Base textScale anchored on short edge. The first iteration of this
        // formula targeted ~3.2× on a tablet, which the user (correctly)
        // flagged as too big — the dream is meant to read like signage from
        // a few feet away, not a billboard from across the room.
        //
        //   short ≈ 360dp (small phone)   → ~1.41
        //   short ≈ 412dp (Pixel)         → ~1.50
        //   short ≈ 800dp (10" tablet)    → ~2.20
        // Rows multiply 15sp by this → 21sp / 23sp / 33sp respectively.
        val baseScale = (shortEdgeDp * 0.0018f + 0.76f).coerceIn(1.3f, 2.3f)
        // Portrait penalty — same physical device feels more crammed when
        // rows stack on a narrower canvas. ~10% shrink reads as the right
        // amount of breathing room.
        val textScale = if (isLandscape) baseScale else baseScale * 0.90f

        // Card dimensional caps. Landscape gets a generous 75% of the long
        // edge so a tablet spreads horizontally enough for the text to
        // breathe (the user's direct ask: "the length of the dream can be a
        // bit bigger horizontally"). Portrait the card is naturally narrow
        // already so we just keep it near-full short edge.
        val cardMaxWidth = if (isLandscape) {
            (longEdgeDp * 0.75f).dp.coerceAtMost(1300.dp)
        } else {
            (shortEdgeDp * 0.94f).dp.coerceAtMost(680.dp)
        }
        // Card never taller than ~92% of the screen — keeps a halo of dark
        // canvas around the card on every side, even on tablets.
        val cardMaxHeight = maxHeight * 0.92f

        // Outer canvas padding — generous on tablets, modest on phones.
        val canvasPad = (shortEdgeDp * 0.03f).coerceIn(14f, 40f).dp

        // Column with verticalArrangement = Center and horizontalAlignment =
        // CenterHorizontally is the bulletproof centering pattern when the
        // child uses chained widthIn + fillMaxWidth modifiers — Box's
        // contentAlignment can race with measure constraints there.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(canvasPad),
            verticalArrangement   = Arrangement.Center,
            horizontalAlignment   = Alignment.CenterHorizontally,
        ) {
            DreamBoard(
                snapshot   = snapshot,
                modifier   = Modifier
                    .widthIn(max = cardMaxWidth)
                    .heightIn(max = cardMaxHeight)
                    .fillMaxWidth(),
                textScale  = textScale,
                showHeader = true,
                showClock  = true,
                fullscreen = true,
            )
        }
    }
}
