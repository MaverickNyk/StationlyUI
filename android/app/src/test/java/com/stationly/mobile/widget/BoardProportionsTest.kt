package com.stationly.mobile.widget

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardProportionsTest {

    private fun assertLadder(h: BoardProportions.Heights) {
        val platform = h.pagerDp ?: (h.rowDp * BoardProportions.PLATFORM)
        assertTrue("station ${h.stationDp} > platform $platform", h.stationDp > platform)
        assertTrue("platform $platform > row ${h.rowDp}", platform > h.rowDp)
        h.statusDp?.let { assertTrue("status $it <= row ${h.rowDp}", it <= h.rowDp + 0.01f) }
    }

    /** Every size class, a spread of real launcher heights, both layouts, two font scales. */
    @Test
    fun `the ladder holds everywhere the board stretches`() {
        var solved = 0
        for (size in WidgetSize.values())
            for (height in listOf(110, 150, 180, 214, 260, 320, 440, 600))
                for (fontScale in listOf(0.85f, 1f, 1.15f, 1.3f)) {
                    // Stepping: 3 rows under a pager.
                    BoardProportions.solve(height, size, fontScale, rows = 3, headers = 0, pager = true, status = true)
                        ?.let { assertLadder(it); solved++ }
                    // Fill: two platforms of two rows.
                    BoardProportions.solve(height, size, fontScale, rows = 4, headers = 2, pager = false, status = true)
                        ?.let { assertLadder(it); solved++ }
                }
        assertTrue("most combinations stretch", solved > 60)
    }

    @Test
    fun `the parts add up to the widget, so nothing is clipped`() {
        val height = 300
        val h = BoardProportions.solve(height, WidgetSize.MEDIUM, 1f, rows = 3, headers = 0, pager = true, status = true)!!
        val total = h.stationDp + h.pagerDp!! + h.statusDp!! + h.footerDp + 3 * h.rowDp + 6 * BoardProportions.GAP_DP
        assertTrue("total $total within ${height * BoardProportions.SLACK}", total <= height * BoardProportions.SLACK + 0.5f)
    }

    @Test
    fun `a crowded board keeps its natural heights`() =
        assertNull(BoardProportions.solve(120, WidgetSize.MEDIUM, 1.3f, rows = 6, headers = 2, pager = false, status = true))

    @Test
    fun `nothing to stretch, nothing to solve`() =
        assertNull(BoardProportions.solve(300, WidgetSize.MEDIUM, 1f, rows = 0, headers = 0, pager = false, status = true))
}
