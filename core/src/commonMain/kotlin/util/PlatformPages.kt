package com.stationly.core.util

/**
 * A board, cut into one page per platform.
 *
 * ## Why this exists
 * [MultiLineBoardProcessor] returns a FLAT list — a header, its departures, the
 * next header — because that is what a scrolling surface draws, and scrolling
 * is what the home screen does. The widget and the screensaver are not the home
 * screen: one is a fixed box on somebody's wallpaper and the other is a panel
 * across a room, and on both, "scroll to see the rest" is a gesture nobody is
 * going to make. Those surfaces can step through platforms instead, which needs
 * the same rows cut at the headers.
 *
 * Pure, and separate from the processor, because the cut is a PRESENTATION
 * decision. The board is the same board either way; only the amount of it you
 * can see at once changes.
 */
object PlatformPages {

    /**
     * Split at the platform headers.
     *
     * Rows before the first header become a page of their own rather than being
     * dropped, and a board with NO headers becomes one page holding everything
     * rather than none. That second case is not hypothetical: the fallback copy
     * ("no upcoming departures", "signal lost") goes through the same renderer
     * and carries no headers, so returning zero pages would draw an empty board
     * over the one message the user actually needs to read.
     */
    fun split(rows: List<MultiLineBoardProcessor.Row>): List<List<MultiLineBoardProcessor.Row>> {
        if (rows.isEmpty()) return emptyList()
        val pages = mutableListOf<MutableList<MultiLineBoardProcessor.Row>>()
        rows.forEach { row ->
            if (row is MultiLineBoardProcessor.Row.PlatformHeader || pages.isEmpty()) {
                pages += mutableListOf(row)
            } else {
                pages.last() += row
            }
        }
        return pages
    }

    /**
     * Force an index onto a real page.
     *
     * **The case this exists for is a stored index outliving the board it was
     * stored against.** A widget remembers "the third platform" and is redrawn
     * against a board that now has two, because TfL stopped serving one — which
     * happens nightly. Reading the index without this draws nothing and looks
     * like the widget broke.
     *
     * An empty board clamps to 0 rather than to -1, so callers never index a
     * list with it.
     */
    fun clamp(index: Int, pageCount: Int): Int =
        if (pageCount <= 0) 0 else index.coerceIn(0, pageCount - 1)

    /**
     * Move by [delta] pages, WRAPPING at both ends.
     *
     * Wrapping rather than stopping, and the reason is the widget. A
     * `RemoteViews` chevron cannot show "disabled" convincingly — a greyed
     * arrow on a home-screen widget reads as a broken button rather than as an
     * edge — so an arrow that does nothing at the end is worse than one that
     * comes round. On the two- and three-platform boards this is mostly used
     * for, wrapping also means both arrows always do something, which is what
     * anyone expects from a pair of chevrons on a small control.
     */
    fun step(current: Int, delta: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        val next = (current + delta) % pageCount
        return if (next < 0) next + pageCount else next
    }

    /** The rows to draw for [index], made safe first. Empty when there is nothing. */
    fun page(
        rows: List<MultiLineBoardProcessor.Row>,
        index: Int,
    ): List<MultiLineBoardProcessor.Row> {
        val pages = split(rows)
        if (pages.isEmpty()) return emptyList()
        return pages[clamp(index, pages.size)]
    }

    /** How many platforms this board has to step through. */
    fun count(rows: List<MultiLineBoardProcessor.Row>): Int = split(rows).size

    /**
     * The title to put beside the chevrons.
     *
     * The page's own header when it has one; blank when it does not, which is
     * the fallback-copy page — where a heading invented from nothing would be
     * the renderer making something up.
     */
    fun title(page: List<MultiLineBoardProcessor.Row>): String =
        (page.firstOrNull() as? MultiLineBoardProcessor.Row.PlatformHeader)?.title.orEmpty()
}
