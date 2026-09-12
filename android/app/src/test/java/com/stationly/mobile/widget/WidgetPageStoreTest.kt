package com.stationly.mobile.widget

import com.stationly.core.util.PlatformPages
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which platform a stepping widget is currently showing.
 *
 * The store itself is a `SharedPreferences` int keyed by `appWidgetId`, which
 * needs no test. What needs one is the arithmetic around it, because every
 * interesting case is a stored index meeting a board that has changed shape
 * since it was written — and a widget is redrawn from broadcasts that arrive
 * long after anybody looked at it.
 */
class WidgetPageStoreTest {

    private fun header(t: String) = com.stationly.core.util.MultiLineBoardProcessor.Row.PlatformHeader(t)
    private fun dep(d: String) = com.stationly.core.util.MultiLineBoardProcessor.Row.Departure("", d, "2 min")

    private val threePlatforms = listOf(
        header("Platform 1"), dep("A"),
        header("Platform 2"), dep("B"),
        header("Platform 3"), dep("C"),
    )

    /** The key is per WIDGET, not per station: two widgets on one station step independently. */
    @Test
    fun `each widget has its own page key`() {
        assertEquals("page_6", WidgetPageStore.keyFor(6))
        assertEquals("page_11", WidgetPageStore.keyFor(11))
    }

    /**
     * **The nightly one.** A widget parked on the third platform is redrawn
     * against a board that now has two, because TfL stopped serving one. It
     * must land on a real platform rather than draw nothing.
     */
    @Test
    fun `a page index outliving its board lands on the last real platform`() {
        val twoPlatforms = threePlatforms.dropLast(2)
        assertEquals(1, PlatformPages.clamp(2, PlatformPages.count(twoPlatforms)))
    }

    @Test
    fun `stepping forward through three platforms comes back round`() {
        val n = PlatformPages.count(threePlatforms)
        assertEquals(3, n)
        var page = 0
        page = PlatformPages.step(page, 1, n); assertEquals(1, page)
        page = PlatformPages.step(page, 1, n); assertEquals(2, page)
        page = PlatformPages.step(page, 1, n); assertEquals(0, page)
    }

    @Test
    fun `stepping back from the first goes to the last`() {
        assertEquals(2, PlatformPages.step(0, -1, PlatformPages.count(threePlatforms)))
    }

    /**
     * A widget rebound to another station starts at that station's first
     * platform. Keeping the old index would open a Bank widget on "the third
     * one", which means nothing about Bank.
     */
    @Test
    fun `rebinding resets to the first platform`() {
        assertEquals(0, WidgetPageStore.PAGE_ON_REBIND)
    }
}
