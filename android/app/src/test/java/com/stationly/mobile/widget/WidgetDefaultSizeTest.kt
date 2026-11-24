package com.stationly.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** A new widget is 4x2 on every Android version (owner, 2026-11-24). */
class WidgetDefaultSizeTest {

    private val info = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/xml/departure_widget_info.xml")).documentElement

    private fun attr(name: String) = info.getAttribute("android:$name")
    private fun dp(name: String) = attr(name).removeSuffix("dp").toInt()

    @Test
    fun `Android 12 and later start at 4x2`() {
        assertEquals("4", attr("targetCellWidth"))
        assertEquals("2", attr("targetCellHeight"))
    }

    /**
     * Before Android 12 a launcher takes as many columns as `minWidth` needs.
     * An estimate (16dp of margin, 8dp each side), so it checks the property that
     * matters: 4, or the full width of a narrow 5-column grid, and never 3.
     */
    @Test
    fun `older Android never starts narrower than 4 columns`() {
        for (width in listOf(360, 393, 411, 430)) for (columns in listOf(4, 5)) {
            val cell = (width - 16.0) / columns
            val taken = Math.ceil((dp("minWidth") + 16) / cell).toInt()
            assertTrue("$width dp, $columns columns: $taken", taken in 4..columns)
        }
    }

    @Test
    fun `it can still be made smaller by hand`() {
        assertTrue(dp("minResizeWidth") <= dp("minWidth"))
        assertTrue(dp("minResizeHeight") <= dp("minHeight"))
    }
}
