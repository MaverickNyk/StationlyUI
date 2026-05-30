package com.stationly.mobile.dream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
    // Read the camera-cutout safe insets via the proper Android insets
    // dispatch — `OnApplyWindowInsetsListener` fires whenever the system
    // pushes a new WindowInsets (first attach, rotation, fold, etc.).
    // We mirror max(left, right) on both sides so the board sits
    // symmetrically against the camera punch-hole on either rotation,
    // matching the user's "identify the camera block on the camera side
    // then on the opposite side leave the same amount of space" ask.
    val view = LocalView.current
    val density = LocalDensity.current
    var cutoutInsets by remember { mutableStateOf(intArrayOf(0, 0, 0, 0)) }
    DisposableEffect(view) {
        val listener = androidx.core.view.OnApplyWindowInsetsListener { _, insets: WindowInsetsCompat ->
            val cutout = insets.displayCutout
            cutoutInsets = intArrayOf(
                cutout?.safeInsetLeft   ?: 0,
                cutout?.safeInsetRight  ?: 0,
                cutout?.safeInsetTop    ?: 0,
                cutout?.safeInsetBottom ?: 0,
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(view, listener)
        // Force an immediate dispatch in case insets already landed before
        // we registered.
        ViewCompat.requestApplyInsets(view)
        onDispose { ViewCompat.setOnApplyWindowInsetsListener(view, null) }
    }

    val horizDp = with(density) {
        maxOf(cutoutInsets[0], cutoutInsets[1]).toDp()
    }
    val vertDp = with(density) {
        maxOf(cutoutInsets[2], cutoutInsets[3]).toDp()
    }
    // Min 6dp baseline so the amber border doesn't fuse with the bezel
    // on a device that reports zero cutout (no punch-hole).
    val canvasPad = PaddingValues(
        start  = maxOf(horizDp, 6.dp),
        end    = maxOf(horizDp, 6.dp),
        top    = maxOf(vertDp,  6.dp),
        bottom = maxOf(vertDp,  6.dp),
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(canvasPad),
    ) {
        val isLandscape = maxWidth > maxHeight
        val shortEdgeDp = minOf(maxWidth, maxHeight).value

        // Base textScale anchored on short edge. The first iteration of this
        // formula targeted ~3.2× on a tablet, which the user (correctly)
        // flagged as too big — billboard-feeling, not signage-feeling.
        // Cap raised back to 2.8× so larger tablets actually use the
        // canvas instead of having tiny text floating in a sparse board,
        // while staying short of the "billboard" mark.
        //
        //   short ≈ 360dp (small phone)   → ~1.41
        //   short ≈ 412dp (Pixel)         → ~1.50
        //   short ≈ 800dp (10" tablet)    → ~2.20
        //   short ≈ 1200dp (12" tablet)   → clamped 2.80 (was 2.30)
        //   short ≈ 1500dp+ (huge tab)    → clamped 2.80
        // Rows multiply 15sp by this → 21sp / 23sp / 33sp / 42sp respectively.
        val baseScale = (shortEdgeDp * 0.0018f + 0.76f).coerceIn(1.3f, 2.8f)
        // Portrait penalty — same physical device feels more crammed when
        // rows stack on a narrower canvas. ~10% shrink reads as the right
        // amount of breathing room.
        val textScale = if (isLandscape) baseScale else baseScale * 0.90f

        // BoxWithConstraints already measured INSIDE the symmetric safe-area
        // pad, so maxWidth/maxHeight here are the centred-rectangle bounds.
        // Let the card fill them.
        val cardMaxWidth  = maxWidth
        val cardMaxHeight = maxHeight

        // Column with verticalArrangement = Center and horizontalAlignment =
        // CenterHorizontally is the bulletproof centering pattern when the
        // child uses chained widthIn + fillMaxWidth modifiers — Box's
        // contentAlignment can race with measure constraints there.
        Column(
            modifier = Modifier.fillMaxSize(),
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
                // Card width is the BoxWithConstraints' bounds (already
                // measured INSIDE the symmetric cutout pad), so that's
                // the strip's effective slot.
                slotWidthDp = cardMaxWidth.value.toInt(),
            )
        }
    }
}
