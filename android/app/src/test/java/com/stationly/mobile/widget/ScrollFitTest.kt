package com.stationly.mobile.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFitTest {
    /** The case that was reported: 213dp widget, one platform header + 3 trains. */
    @Test
    fun `a single platform fits a standard widget`() {
        assertTrue(ScrollFit.allRowsFit(rowCount = 4, widgetHeightDp = 213))
    }

    /** The same widget on the device it was measured on, font scale 1.1. */
    @Test
    fun `the reported widget fits at the device font scale`() {
        assertTrue(ScrollFit.allRowsFit(rowCount = 4, widgetHeightDp = 214, fontScale = 1.1f))
        assertFalse(ScrollFit.allRowsFit(rowCount = 5, widgetHeightDp = 214, fontScale = 1.1f))
    }

    @Test
    fun `two full platforms do not fit, so the list stays`() {
        assertFalse(ScrollFit.allRowsFit(rowCount = 8, widgetHeightDp = 213))
    }

    @Test
    fun `a larger font needs more room`() {
        assertFalse(ScrollFit.allRowsFit(rowCount = 4, widgetHeightDp = 213, fontScale = 1.3f))
    }

    /** Headers are deeper than rows: counting them as rows over-promised. */
    @Test
    fun `platform headers take more room than departure rows`() {
        // 6 rows fit at 225dp as rows, but not when 2 of them are headers.
        assertTrue(ScrollFit.allRowsFit(rowCount = 6, widgetHeightDp = 225))
        assertFalse(ScrollFit.allRowsFit(rowCount = 6, widgetHeightDp = 225, headerCount = 2))
    }

    @Test
    fun `nothing to draw or no size is not a fit`() {
        assertFalse(ScrollFit.allRowsFit(rowCount = 0, widgetHeightDp = 213))
        assertFalse(ScrollFit.allRowsFit(rowCount = 3, widgetHeightDp = 0))
    }
}
