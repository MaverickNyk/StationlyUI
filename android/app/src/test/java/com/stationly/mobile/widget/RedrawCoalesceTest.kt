package com.stationly.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Push-driven redraws gather into one per burst (owner, 2026-11-23: the status
 * marquee restarted on every render, so a burst of pushes made it stutter).
 *
 * The wait is a trailing quiet window, capped from the burst's FIRST request so
 * a steady stream of pushes can never starve a widget of its redraw.
 */
class RedrawCoalesceTest {

    @Test
    fun `a fresh burst waits the quiet window`() {
        assertEquals(300L, DepartureWidgetProvider.coalesceDelay(0L))
    }

    @Test
    fun `a request inside the burst restarts the quiet window`() {
        assertEquals(300L, DepartureWidgetProvider.coalesceDelay(500L))
    }

    @Test
    fun `the window shrinks as the burst nears its cap`() {
        assertEquals(200L, DepartureWidgetProvider.coalesceDelay(1_000L))
    }

    @Test
    fun `past the cap it draws at once`() {
        assertEquals(0L, DepartureWidgetProvider.coalesceDelay(1_200L))
        assertEquals(0L, DepartureWidgetProvider.coalesceDelay(5_000L))
    }
}
