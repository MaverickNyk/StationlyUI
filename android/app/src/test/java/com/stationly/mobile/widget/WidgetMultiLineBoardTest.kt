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

    // ── depth from the widget's own size ────────────────────────────────────

    /**
     * The thing iOS cannot do. WidgetKit gives a FAMILY and a layout per family;
     * Android's home screen is a free grid, so the same station at two sizes is
     * two different boards and the resize gesture is the user saying which one
     * they meant.
     *
     * Three is what this widget has always drawn and stays the default, so an
     * untouched widget looks exactly as it did.
     */
    @Test
    fun `depth follows the height the user dragged to`() {
        assertEquals("a one-cell strip cannot hold three per platform", 2, cap(100))
        assertEquals("two cells is the shipped default", 3, cap(150))
        assertEquals("a tall widget earns a fourth row", 4, cap(300))
    }

    @Test
    fun `an unknown height answers the default rather than guessing`() {
        // A host that never reported a size. Being wrong costs a scroll on API
        // 31+ and a clipped row below it; neither is worth a worse default.
        assertEquals(3, cap(0))
        assertEquals(3, cap(-1))
    }

    /**
     * The rule takes the Int, not the `Bundle` it came from — a `Bundle` throws
     * in a plain JVM unit test, and this decision is arithmetic rather than
     * Android. The platform lookup stays at the call site, the same split
     * `TopicLedger` and `WidgetRedrawTargets` use.
     */
    private fun cap(minHeightDp: Int): Int = DepartureWidgetProvider.rowCapForHeight(minHeightDp)
}
