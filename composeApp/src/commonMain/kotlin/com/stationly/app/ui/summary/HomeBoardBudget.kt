package com.stationly.app.ui.summary

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stationly.core.util.MultiLineBoardProcessor

/**
 * How much room a station card gets, and why.
 *
 * The home screen's hardest constraint is that a departure board must be
 * READABLE and must not move: the page never scrolls to reveal a board, and
 * nothing on it changes height on its own. Both of those are decided here, by
 * giving the board whatever is genuinely left after everything else has taken
 * what it needs — rather than by guessing a fraction of the screen, which
 * earlier versions did and got wrong in both directions at once.
 *
 * Kept apart from `SummaryScreen` because it is arithmetic with a long
 * justification and no Compose in it, and because every number here is a
 * measurement of something drawn somewhere else. When a card's chrome changes,
 * this file is the one that has to change with it.
 */

/** Top/bottom padding inside the home scroll, applied at each end. */
internal val HOME_PADDING_V = 16.dp

/** Vertical gap between the home screen's blocks (chrome, boards, Network). */
internal val HOME_GAP = 20.dp

/**
 * Heights of a collapsed card's parts, mirroring `HEADER_HEIGHT` and
 * `LEG_HEIGHT` in Board.kt.
 *
 * Duplicated deliberately rather than exposed: these are the height budget's
 * ASSUMPTIONS about a collapsed card, and the layout must not silently re-flow
 * if the header's internals change — a mismatch shows up as the open board being
 * a few dp off, not as a crash.
 */
internal val STATION_HEADER_HEIGHT = 44.dp
internal val LEG_HEIGHT = 22.dp

/**
 * What the card CONTAINER costs before any of its content: the colour rail, the
 * inner padding above and below, and the hairline border top and bottom.
 *
 * Mirrors `CARD_RAIL_HEIGHT` plus the container padding in Board.kt. A station
 * card became a real bounded surface rather than loose content on the canvas, and
 * every dp of that surround comes off the panel's share — left out of the budget,
 * the floor below silently stops being three departures.
 */
internal val CARD_CHROME_HEIGHT = 20.dp

/** One departure row inside the panel, including the 2dp gap under it. */
internal val BOARD_ROW_HEIGHT = 26.dp

/** What "a usable departure board" means, in rows. Feeds [MIN_BOARD_HEIGHT]. */
internal const val MIN_VISIBLE_ROWS = 3

/**
 * Floor for a card, sized so its panel can always show [MIN_VISIBLE_ROWS]
 * departures.
 *
 * A CARD is not a board: by the time the panel gets its share, the card has
 * already spent its height on the station header, the line pills, the hero, the
 * status strip and the footer. The old 280dp floor was picked against the card
 * and left the panel with a single row once several stations were open —
 * technically "fitting one viewport", but a departure board showing one
 * departure is not a departure board.
 *
 * Written as the sum of the parts rather than as one number, so a change to any
 * of them can be reflected here without re-deriving the total by hand.
 *
 * Budgeted WITH the hero even though a station can hide it: the floor has to
 * cover the worst case, and a hero-hidden card simply spends the same height on
 * more rows.
 *
 * Overflowing this is deliberate. Past it the PAGE scrolls, which is the honest
 * outcome when the user has opened more boards than a screen holds — the
 * alternative is several boards none of which can be read.
 */
internal val MIN_BOARD_HEIGHT =
    CARD_CHROME_HEIGHT +         // rail + container padding + border
    STATION_HEADER_HEIGHT +      // the card's own nameplate
    34.dp +                      // line pills row (2 top + 22 pill + 10 bottom)
    104.dp +                     // hero (HERO_HEIGHT) + its 10dp spacer
    16.dp +                      // panel padding, 8 top and 8 bottom
    26.dp +                      // one platform header
    (BOARD_ROW_HEIGHT * MIN_VISIBLE_ROWS) +
    22.dp +                      // status strip
    34.dp                        // footer: clock + maker mark

/**
 * The tallest a station card may be: exactly the room left after everything else
 * on the home screen has taken what it needs.
 *
 * This is DERIVED, not a fraction of the screen. Earlier versions guessed —
 * "viewport minus padding", then 82% of the viewport — and both were wrong in
 * both directions at once: too tall when a promo card was showing, too short when
 * none were, and never right on a device whose proportions differed from the one
 * being tested on. The board is the last thing to be given space, so it can just
 * be told what is genuinely left.
 *
 * [chromeHeight] and [exploreHeight] are measured (`onSizeChanged`) rather than
 * assumed, because both change at runtime: promos are dismissible, and the
 * Network section grows with the number of live disruptions.
 *
 * Collapsed stations cost a known [STATION_HEADER_HEIGHT] (plus legs) each, so
 * with one board open the whole home screen still fits one viewport however many
 * stations are tracked. This returns the cap for the TOP open board — every
 * other open board is held to [MIN_BOARD_HEIGHT] by the caller.
 */
internal fun boardMaxHeight(
    viewportHeight: Dp,
    expandedCount: Int,
    collapsedCount: Int,
    chromeHeight: Dp,
    exploreHeight: Dp,
    bottomInset: Dp,
    /**
     * Anything else in the scroll that is neither chrome nor Network — the
     * carousel's page dots, and nothing else so far. Not folded into
     * [chromeHeight], which is measured from the promos and would then be wrong
     * the moment the last one is dismissed.
     */
    extraChrome: Dp = 0.dp,
): Dp {
    // Blocks in the scroll: [chrome?] + boards + Network. Each pair costs one gap.
    val gapCount = if (chromeHeight > 0.dp) 2 else 1
    // Collapsed stations cost a known amount, so they come out of the budget
    // before it is shared — this is what replaced the old viewport-fraction cap.
    //
    // Budgeted at the MAXIMUM leg count rather than the actual one. The real
    // number depends on live predictions, so budgeting on it would re-flow every
    // open board each time a train departs — the height churn this whole layout
    // exists to prevent. Over-reserving costs the open board a few dp and is
    // stable; under-reserving pushes it off screen.
    val collapsedCost =
        (CARD_CHROME_HEIGHT + STATION_HEADER_HEIGHT +
            LEG_HEIGHT * MultiLineBoardProcessor.MAX_COLLAPSED_LEGS + HOME_GAP) *
            collapsedCount.coerceAtLeast(0)
    val budget = viewportHeight -
        (HOME_PADDING_V * 2) - bottomInset -
        chromeHeight - exploreHeight - collapsedCost - extraChrome -
        (HOME_GAP * gapCount)
    // Everything else that is open is held to the floor, so this is what the TOP
    // open board may take.
    //
    // Not an equal share. Dividing the viewport between three open boards gives
    // three boards nobody can read, and the user has already said which station
    // matters by pinning it to the top. One board gets the room; the others get
    // three departures each and the page scrolls, which is the honest outcome of
    // opening more than a screen holds.
    val others = (expandedCount - 1).coerceAtLeast(0)
    val forOthers = (MIN_BOARD_HEIGHT + HOME_GAP) * others
    return (budget - forOthers).coerceAtLeast(MIN_BOARD_HEIGHT)
}

/**
 * Widest a board is allowed to get, regardless of the window.
 *
 * Everything sized from the viewport scales up happily EXCEPT line length. A
 * departure row is destination-left / ETA-right, so on a full-width iPad the two
 * end up a hand's width apart with nothing between them, and the eye loses the
 * pairing — the one thing the row exists to convey.
 *
 * Roughly the width of a large phone, which is what the board's type sizes were
 * drawn against. Wider windows centre the board and leave margin rather than
 * stretching it. Height still uses the whole window (see [boardMaxHeight]) —
 * more vertical space means more departures, which IS useful.
 */
internal val MAX_BOARD_WIDTH = 480.dp
