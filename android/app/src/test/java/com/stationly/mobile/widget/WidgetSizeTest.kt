package com.stationly.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSizeTest {
    @Test
    fun `a 2x2 is compact`() = assertEquals(WidgetSize.COMPACT, WidgetSize.of(widthDp = 170, heightDp = 180))

    /** The Pixel 10 Pro Fold 4x2 the board was tuned on. */
    @Test
    fun `a 4x2 is medium`() = assertEquals(WidgetSize.MEDIUM, WidgetSize.of(widthDp = 394, heightDp = 214))

    @Test
    fun `a 4x4 is large`() = assertEquals(WidgetSize.LARGE, WidgetSize.of(widthDp = 394, heightDp = 440))

    @Test
    fun `a narrow tall widget stays compact`() = assertEquals(WidgetSize.COMPACT, WidgetSize.of(widthDp = 170, heightDp = 440))

    @Test
    fun `an unknown size falls back to medium, the XML defaults`() =
        assertEquals(WidgetSize.MEDIUM, WidgetSize.of(widthDp = 0, heightDp = 0))

    @Test
    fun `medium is the XML defaults, and compact follows iOS small`() {
        assertEquals(15f, WidgetSize.MEDIUM.row)
        assertEquals(1f, WidgetSize.MEDIUM.rowScale)
        assertFalse(WidgetSize.COMPACT.showsClock)
        assertFalse(WidgetSize.COMPACT.centresStation)
        assertTrue(WidgetSize.LARGE.row > WidgetSize.MEDIUM.row)
    }
}
