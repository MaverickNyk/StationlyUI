package com.stationly.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A new widget is the full width of the home screen and 2 rows (owner, 2026-11-24). */
class WidgetDefaultSizeTest {

    private fun info(dir: String, file: String = "departure_widget_info"): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/$dir/$file.xml")).documentElement

    private val older = info("xml")
    private val android12 = info("xml-v31")
    private val sixColumns = info("xml", "departure_widget_info_6")

    private fun Element.value(name: String) = getAttribute("android:$name").removeSuffix("dp").ifBlank { "0" }.toInt()

    /** A home screen. Cell sizes in dp; [landscape] is null where the launcher stays upright. */
    private class Grid(val name: String, val width: Int, val columns: Int, val cell: Int, val landscape: Int? = null)

    private val grids = listOf(
        Grid("Pixel 11 Pro, 5x6", 427, 5, 100, 60),
        Grid("Pixel, 5x5", 393, 5, 118, 66),
        Grid("Pixel, 4 columns", 411, 4, 118, 66),
        Grid("4x5, tall cells", 360, 4, 118),
        Grid("4x6", 360, 4, 100),
        Grid("4x6, wide", 393, 4, 102),
        Grid("5x6", 384, 5, 100),
        Grid("small 4x5", 320, 4, 90),
        Grid("Pixel 10 Pro Fold, half", 364, 4, 90),
        Grid("wide 4 columns", 430, 4, 110),
        Grid("wide 5 columns", 430, 5, 105, 62),
        Grid("narrow 5x6", 360, 5, 96),
        // What a target of 6 for everyone got wrong: rows left to the fallback.
        Grid("5x6, small sideways cells", 411, 5, 96, 57),
        Grid("5x4, tall cells", 411, 5, 140),
        Grid("wide 5 columns, small display size", 448, 5, 110, 64),
        Grid("tablet, 5 columns", 800, 5, 130, 100),
    )

    /** AOSP's `initSpans`, with [margin] for the padding launchers added before Android 12. */
    private fun spans(info: Element, grid: Grid, margin: Int): Pair<Int, Int> {
        fun cells(size: Int, cell: Double) = max(1, ceil((size + margin) / cell).toInt())
        val cellWidth = (grid.width - 16.0) / grid.columns
        fun rows(size: Int) = listOfNotNull(grid.cell, grid.landscape).maxOf { cells(size, it.toDouble()) }

        val minColumns = cells(info.value("minResizeWidth"), cellWidth)
        val minRows = rows(info.value("minResizeHeight"))
        val targetColumns = info.value("targetCellWidth")
        val targetRows = info.value("targetCellHeight")
        val targetFits = targetColumns in minColumns..grid.columns && targetRows >= minRows
        return if (targetFits) targetColumns to targetRows
        else min(cells(info.value("minWidth"), cellWidth), grid.columns) to rows(info.value("minHeight"))
    }

    @Test
    fun `Android 12 and later start full width and 2 rows`() {
        for (g in grids) assertEquals(g.name, g.columns to 2, spans(android12, g, margin = 0))
    }

    /** 360dp and 6 columns: the Redmi 13C 5G this was found on, and a tablet. */
    @Test
    fun `six columns are filled where the app can tell`() {
        for (g in listOf(Grid("Redmi 13C 5G, 6x7", 360, 6, 89), Grid("6x8", 411, 6, 92, 60))) {
            assertEquals(g.name, 6 to 2, spans(sixColumns, g, margin = 0))
            assertEquals("${g.name}, by default", 5 to 2, spans(android12, g, margin = 0))
        }
    }

    @Test
    fun `only a known six column grid on Android 12 changes the provider`() {
        assertEquals(6, WidgetDefaultSize.columns("6x7"))
        for (unreadable in listOf(null, "", "6", "x7", "6x", "six", "6x7x8")) assertEquals(null, WidgetDefaultSize.columns(unreadable))
        assertEquals("com.stationly.mobile.widget.SIX_COLUMNS", WidgetDefaultSize.variant(31, 6))
        assertEquals(null, WidgetDefaultSize.variant(30, 6))
        for (other in listOf(null, 4, 5, 7)) assertEquals(null, WidgetDefaultSize.variant(34, other))
    }

    /**
     * Rows are the phone's own here: 3 on a dense grid, and 1 where cells are over
     * 126dp. The sizes are v1's, kept as they are: phones only, and not those.
     */
    @Test
    fun `older Android starts full width too`() {
        for (g in grids.filter { it.width <= 430 && it.cell <= 126 }) {
            val (columns, rows) = spans(older, g, margin = 16)
            assertEquals(g.name, g.columns, columns)
            assertEquals("${g.name}: $rows rows", true, rows in 2..3)
        }
    }

    @Test
    fun `the files differ only in the default size`() {
        fun Element.rest() = (0 until attributes.length).map { attributes.item(it) }
            .associate { it.nodeName to it.nodeValue }
            .filterKeys { it !in setOf("android:minWidth", "android:minHeight", "android:targetCellWidth", "android:targetCellHeight") }
        assertEquals(older.rest(), android12.rest())
        assertEquals(android12.rest(), sixColumns.rest())
    }
}
