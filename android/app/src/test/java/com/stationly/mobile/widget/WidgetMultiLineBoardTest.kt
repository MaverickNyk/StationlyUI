package com.stationly.mobile.widget

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.util.MultiLineBoardProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One widget shows the WHOLE station, not the first board at it.
 *
 * ## What this replaced, and why it was invisible
 * The widget resolved its binding to `selections.first { it.groupingId == bound }`
 * and drew that one board. A binding names a HUB, and a hub routinely carries
 * several boards — both directions of one line, four lines at one interchange —
 * so a user tracking Royal Victoria in both directions had a widget showing half
 * of what the app showed them, with nothing on it to say the other half existed.
 *
 * It looked correct in every screenshot ever taken of it, because a widget
 * showing one platform's departures looks exactly like a widget that only knows
 * about one platform.
 *
 * ## What the widget relies on now
 * `MultiLineBoardProcessor` — the same grouping the home screen and the
 * screensaver render from. These tests pin the contract the widget depends on:
 * one `Feed` per (pole, line, direction) in, platform blocks out. If this file
 * fails, the widget is about to start disagreeing with the app about what a
 * station looks like.
 */
class WidgetMultiLineBoardTest {

    private val royalVictoria = "940GZZDLRVC"

    private fun pred(destination: String, platform: String, minutes: Int) = PredictionDisplay(
        destination = destination,
        platform = platform,
        eta = "$minutes min",
        isDue = false,
        targetEpochMs = 1_700_000_000_000 + minutes * 60_000L,
    )

    private fun feed(direction: String, platform: String, vararg destinations: String) =
        MultiLineBoardProcessor.Feed(
            stationId = royalVictoria,
            line = "dlr",
            direction = direction,
            predictions = destinations.mapIndexed { i, d -> pred(d, platform, i + 2) },
        )

    /**
     * **The one the old widget got wrong.** Two directions of one line at one
     * station are two boards and two platforms, and both belong on the widget
     * bound to that station.
     */
    @Test
    fun `both directions of one line reach the board`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("inbound", "Platform 1", "Stratford International"),
                feed("outbound", "Platform 2", "Beckton"),
            ),
            isBus = false,
        )

        val destinations = rows.filterIsInstance<MultiLineBoardProcessor.Row.Departure>()
            .map { it.destination }
        assertTrue(
            "the inbound board is missing — this is the defect the widget shipped with",
            destinations.contains("Stratford International"),
        )
        assertTrue(
            "the outbound board is missing",
            destinations.contains("Beckton"),
        )

        assertEquals(
            "two platforms should produce two headers",
            2,
            rows.filterIsInstance<MultiLineBoardProcessor.Row.PlatformHeader>().size,
        )
    }

    /**
     * A station with one board is unchanged — the common case, and the one every
     * screenshot of this widget has ever shown. The multi-line path must not
     * make a single-board widget look different.
     */
    @Test
    fun `a station with one board still renders one block`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("inbound", "Platform 1", "Stratford International", "Beckton")),
            isBus = false,
        )

        assertEquals(1, rows.filterIsInstance<MultiLineBoardProcessor.Row.PlatformHeader>().size)
        assertTrue(rows.filterIsInstance<MultiLineBoardProcessor.Row.Departure>().isNotEmpty())
    }

    /**
     * Three per platform, and it does not vary.
     *
     * This briefly followed the widget's height — 2 rows on a short widget, 4 on
     * a tall one — which was a nice idea and the wrong one. The rule is a product
     * rule and it is the same three the home screen and the screensaver draw, so
     * a widget showing fewer departures than the app for the same platform is
     * not a smaller widget, it is a widget missing trains. Height decides how
     * many BLOCKS fit on screen, which the scroll already handles.
     */
    @Test
    fun `three departures per platform, whatever the widget's size`() {
        assertEquals(3, DepartureWidgetProvider.ROWS_PER_PLATFORM)

        val rows = MultiLineBoardProcessor.rowsFrom(
            MultiLineBoardProcessor.buildGroups(
                feeds = listOf(
                    feed("inbound", "Platform 1", "Stratford", "Stratford", "Stratford", "Stratford"),
                ),
                isBus = false,
                rowCap = DepartureWidgetProvider.ROWS_PER_PLATFORM,
            ),
            rowCap = DepartureWidgetProvider.ROWS_PER_PLATFORM,
        )

        assertEquals(
            "a platform with four trains draws three of them",
            3,
            rows.filterIsInstance<MultiLineBoardProcessor.Row.Departure>().size,
        )
    }
}
