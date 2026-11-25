package com.stationly.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSizeTest {
    @Test
    fun `a 2x2 is compact`() = assertEquals(WidgetSize.COMPACT, WidgetSize.of(widthDp = 170, heightDp = 180))

    @Test
    fun `a short 2x2 is compact with tight padding`() =
        assertEquals(WidgetSize.COMPACT_SHORT, WidgetSize.of(widthDp = 158, heightDp = 158))

    /** The Pixel 10 Pro Fold 4x2 the board was tuned on. */
    @Test
    fun `a 4x2 is medium`() = assertEquals(WidgetSize.MEDIUM, WidgetSize.of(widthDp = 394, heightDp = 214))

    /** Two rows of a Redmi 13C 5G's 6x7 home screen. */
    @Test
    fun `a wide widget too short for medium is short`() =
        assertEquals(WidgetSize.SHORT, WidgetSize.of(widthDp = 329, heightDp = 158))

    @Test
    fun `a board medium can hold keeps medium`() {
        for (height in 184..319) assertEquals("$height", WidgetSize.MEDIUM, WidgetSize.of(widthDp = 394, heightDp = height))
    }

    @Test
    fun `whatever is chosen holds the board, down to the Redmi's two rows`() {
        for (width in listOf(150, 170, 329, 394, 600)) for (height in 158..600) for (pager in listOf(false, true)) {
            val size = WidgetSize.of(width, height, pager = pager)
            assertTrue("$width x $height pager=$pager $size needs ${size.needsDp(1f, pager)}", size.needsDp(1f, pager) <= height)
        }
    }

    @Test
    fun `larger text never gets a bigger class, and only arrows shrink for it`() {
        for (height in 110..600) for (width in listOf(170, 394)) {
            assertEquals(WidgetSize.of(width, height), WidgetSize.of(width, height, fontScale = 1.5f, pager = false))
            val normal = WidgetSize.of(width, height, 1f, pager = true)
            for (scale in listOf(1.15f, 1.3f, 1.5f)) {
                assertTrue(WidgetSize.of(width, height, scale, pager = true).row <= normal.row)
            }
        }
    }

    /** Messages and the arrows' pages: neither can scroll. */
    @Test
    fun `rows that cannot scroll fit at every text size`() {
        for (sdk in listOf(29, 31)) for (height in 158..330) for (width in listOf(170, 394))
            for (scale in listOf(0.85f, 1f, 1.15f, 1.3f)) for (pager in listOf(false, true)) {
                val rows = if (pager) 3 else 4
                val size = WidgetSize.of(width, height, scale, pager, sdk = sdk)
                val status = !(pager && size.dropsStatus(height, scale, rows, pager, sdk))
                val sp = size.fixedRowSp(height, scale, rows, pager, status, sdk)
                val needed = size.fixedDp(scale, pager, status, sdk) + rows * (2f + sp * scale * WidgetSize.TEXT_LINE)
                assertTrue("$width x $height at $scale pager=$pager sdk=$sdk $size $sp: $needed", needed <= height + 0.01f)
                assertTrue(sp <= size.row)
            }
        assertEquals("with room it is untouched", WidgetSize.MEDIUM.row, WidgetSize.MEDIUM.fixedRowSp(214, 1f, 4, pager = false, status = true))
    }

    @Test
    fun `a message without a status row has its room`() = assertTrue(
        WidgetSize.SHORT.fixedRowSp(158, 1.3f, 4, pager = false, status = false) >
            WidgetSize.SHORT.fixedRowSp(158, 1.3f, 4, pager = false, status = true),
    )

    @Test
    fun `below Android 12 the header keeps its height and the fit allows for it`() {
        assertEquals(8f, WidgetSize.SHORT.fixedDp(1f, false, sdk = 30) - WidgetSize.SHORT.fixedDp(1f, false, sdk = 31), 0.01f)
        assertEquals(WidgetSize.MEDIUM, WidgetSize.SHORT.headerPadding(30))
        assertEquals(WidgetSize.SHORT, WidgetSize.SHORT.headerPadding(31))
    }

    @Test
    fun `an untrusted height keeps the ordinary class`() =
        assertEquals(WidgetSize.MEDIUM, WidgetSize.of(widthDp = 394, heightDp = 120, trustHeight = false))

    @Test
    fun `the Redmi's board fills rather than scrolls`() {
        assertTrue(ScrollFit.allRowsFit(rowCount = 4, widgetHeightDp = 158, rowScale = WidgetSize.SHORT.rowScale, headerCount = 1, size = WidgetSize.SHORT))
        assertFalse(ScrollFit.allRowsFit(rowCount = 4, widgetHeightDp = 158, headerCount = 1))
    }

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
