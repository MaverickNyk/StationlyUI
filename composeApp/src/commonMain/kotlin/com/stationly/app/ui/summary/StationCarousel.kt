package com.stationly.app.ui.summary

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.core.model.UserSelection
import kotlin.math.abs

/** Dot height plus the gap above it — what the carousel costs the board. */
internal val PAGER_DOTS_BLOCK = 22.dp

/**
 * One station per page, swiped left and right.
 *
 * The alternative to stacking every station down one scroll. A stack answers
 * "what is happening across my stations" and pays for it in board height: with
 * three open boards nobody can read any of them, which is why all but the top
 * one collapse. A carousel answers "what is happening at THIS station" and
 * spends the entire screen on one board, at the cost of a swipe to see the next.
 *
 * The page order IS the station order from home settings, so a user who has
 * arranged their list has arranged their carousel.
 *
 * Fixed height, taken from the same budget a single open card would get in the
 * list. Pages must not resize as they scroll past — a board that grows and
 * shrinks mid-swipe is the same instability the whole layout exists to prevent —
 * so a short station simply leaves space below itself rather than shrinking the
 * page.
 */
@Composable
internal fun StationCarousel(
    stationGroups: List<Pair<String, List<UserSelection>>>,
    pageHeight: Dp,
    page: @Composable (String, List<UserSelection>) -> Unit,
) {
    val pagerState = rememberPagerState { stationGroups.size }

    // A tick as each page lands, not while it is moving. This is the same
    // acknowledgement iOS gives at the end of a paged scroll, and it is most of
    // why a carousel feels attached to the finger rather than watched.
    LaunchedEffect(pagerState) {
        var last = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { current ->
            if (current != last) {
                last = current
                performHaptic(HapticType.TAP)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(pageHeight),
            // A real gutter. Pages are cards with borders and their own
            // colour rail, and two of those meeting with a hairline between
            // them reads as one torn card rather than two whole ones.
            pageSpacing = 18.dp,
            verticalAlignment = Alignment.Top,
            // Compose the neighbours before they are needed.
            //
            // A page here is a whole departure board — pills, hero, dot-matrix
            // panel, status strip. At the default of 0 that work happens on the
            // frame the next page first pokes into the viewport, which is the
            // first frame of the swipe: the gesture starts with a stutter every
            // single time. One page either side is two extra boards in memory,
            // which the list layout composes anyway.
            beyondViewportPageCount = 1,
            // How the page settles once the finger leaves.
            //
            // The stock spring is stiff enough to arrive with a visible stop.
            // This one is slightly under-damped and slower, so the card glides
            // the last stretch and comes to rest rather than halting — the
            // difference between a page that snaps and a page that ARRIVES,
            // which is most of what "premium" means on a gesture like this.
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        ) { index ->
            val (stationId, group) = stationGroups[index]
            Box(
                modifier = Modifier.graphicsLayer {
                    // Where this page is relative to the viewport: 0 centred,
                    // ±1 one page away. Read INSIDE `graphicsLayer`, so the
                    // whole effect is a draw-phase transform — the card is not
                    // recomposed or re-laid-out on a single frame of the swipe,
                    // which is what keeps a full departure board at 60fps while
                    // it moves.
                    val offset = (pagerState.currentPage - index) +
                        pagerState.currentPageOffsetFraction
                    val away = abs(offset).coerceIn(0f, 1f)
                    // Two cues, both small: the page you are leaving falls back
                    // into the deck, the one arriving comes forward to meet you.
                    //
                    // Deliberately gentle. A carousel that scales to 0.8 and
                    // fades to 0.3 is a showreel; this has a live departure
                    // board on it, and the board has to stay legible the whole
                    // way across.
                    val settle = 1f - away
                    scaleX = 0.94f + 0.06f * settle
                    scaleY = 0.94f + 0.06f * settle
                    alpha = 0.40f + 0.60f * settle
                    // ⚠️ NO `translationX` HERE, AND NO EDGE PIVOT.
                    //
                    // Both were tried and both are wrong, in the same way: the
                    // pager has already placed this page at its own offset, and
                    // anything that moves the page horizontally moves it
                    // relative to that placement — straight into its neighbour.
                    //
                    // The parallax was `offset * width * 0.10`. For the page one
                    // to the right, `offset` is -1, so it was pulled LEFT by a
                    // tenth of a screen: ~39pt over a 14pt gutter, which is
                    // exactly the overlap that showed up on device — the next
                    // card's edge sitting on top of the current card, dimmed.
                    // Pivoting at the trailing edge compounded it, shrinking
                    // each neighbour TOWARDS the page in the middle rather than
                    // away from it.
                    //
                    // Scale about the CENTRE (the default) is the whole answer:
                    // a shrinking page pulls away from both its neighbours, so
                    // the gutter grows during a swipe instead of closing. If
                    // horizontal parallax is ever wanted here, it has to move
                    // the page's CONTENT inside a clipped page, never the page.
                },
            ) {
                page(stationId, group)
            }
        }
        Spacer(Modifier.height(10.dp))
        PagerDots(
            state = pagerState,
            count = stationGroups.size,
            activeColor = MaterialTheme.colorScheme.primary,
            idleColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.20f),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Dot geometry. The row's total width is constant — see [PagerDots]. */
private val DOT_SIZE = 6.dp
private val DOT_GAP = 7.dp
private val DOT_STRETCH = 12.dp

/**
 * Which page of how many.
 *
 * The current dot stretches into a short pill rather than only changing colour:
 * colour alone is the cue that fails on a glance, in bright sun, and for a
 * colour-blind user, and this indicator is small enough that it needs the shape
 * as well.
 *
 * **It tracks the finger, not the settled page.** Each dot's width is a function
 * of its distance from the pager's live position, so as a swipe crosses the
 * halfway point one pill is already shrinking while the next grows. An indicator
 * that waits for the gesture to end and then animates is a report on what
 * happened; this one is part of the gesture.
 *
 * The widths are `6 + 12 × max(0, 1 - distance)`, and those weights always sum to
 * exactly 1 across the row, so the total width never changes and the row never
 * shifts under a partial swipe.
 *
 * Drawn in one [Canvas] reading the pager inside the draw lambda: zero
 * recomposition per frame, for an element that changes on every one of them.
 */
@Composable
private fun PagerDots(
    state: PagerState,
    count: Int,
    activeColor: Color,
    idleColor: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val totalWidth = remember(count) { DOT_SIZE * count + DOT_GAP * (count - 1) + DOT_STRETCH }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.width(totalWidth).height(DOT_SIZE)) {
            val dot = with(density) { DOT_SIZE.toPx() }
            val gap = with(density) { DOT_GAP.toPx() }
            val stretch = with(density) { DOT_STRETCH.toPx() }
            val position = state.currentPage + state.currentPageOffsetFraction
            var x = 0f
            repeat(count) { index ->
                val near = (1f - abs(index - position)).coerceIn(0f, 1f)
                val width = dot + stretch * near
                drawRoundRect(
                    color = lerp(idleColor, activeColor, near),
                    topLeft = Offset(x, 0f),
                    size = Size(width, dot),
                    cornerRadius = CornerRadius(dot / 2f, dot / 2f),
                )
                x += width + gap
            }
        }
    }
}
