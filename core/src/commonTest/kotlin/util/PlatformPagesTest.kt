package util

import com.stationly.core.util.MultiLineBoardProcessor.Row
import com.stationly.core.util.PlatformPages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Splitting a board into one page per platform, for the surfaces that step
 * through them instead of scrolling.
 *
 * `MultiLineBoardProcessor` hands back a FLAT list — a header, its departures,
 * the next header, and so on — because that is what a scrolling surface draws.
 * A widget or a screensaver showing one platform at a time needs the same rows
 * cut at the headers, and needs it to behave when the cut is not clean.
 *
 * The awkward cases are all real: a board can have no headers at all (the
 * fallback copy rows go through the same renderer), the platform count changes
 * under a stored page index every time TfL reshuffles, and a widget is redrawn
 * from a broadcast that may arrive after the board it was counting on shrank.
 */
class PlatformPagesTest {

    private fun header(t: String) = Row.PlatformHeader(t)
    private fun dep(d: String, eta: String) = Row.Departure("", d, eta)

    private val twoPlatforms = listOf(
        header("DLR Platform 9"),
        dep("Lewisham", "Due"),
        dep("Lewisham", "5 min"),
        header("DLR Platform 10"),
        dep("Check Front of Train", "3 min"),
    )

    @Test
    fun `a board splits at its platform headers`() {
        val pages = PlatformPages.split(twoPlatforms)
        assertEquals(2, pages.size)
        assertEquals(3, pages[0].size)
        assertEquals(2, pages[1].size)
        assertEquals("DLR Platform 9", (pages[0].first() as Row.PlatformHeader).title)
        assertEquals("DLR Platform 10", (pages[1].first() as Row.PlatformHeader).title)
    }

    /**
     * The fallback copy ("no upcoming departures", "signal lost") goes through
     * the same renderer and carries no headers. One page, holding everything —
     * NOT zero pages, which would draw an empty board over a message the user
     * needs to read.
     */
    @Test
    fun `rows with no header at all are one page`() {
        val rows = listOf(dep("No upcoming departures", ""), dep("Check back shortly", ""))
        val pages = PlatformPages.split(rows)
        assertEquals(1, pages.size)
        assertEquals(2, pages[0].size)
    }

    /** Anything before the first header belongs to a page of its own. */
    @Test
    fun `departures before the first header are not swallowed`() {
        val rows = listOf(dep("Orphan", "1 min")) + twoPlatforms
        val pages = PlatformPages.split(rows)
        assertEquals(3, pages.size)
        assertEquals("Orphan", (pages[0].single() as Row.Departure).destination)
    }

    @Test
    fun `no rows is no pages`() {
        assertTrue(PlatformPages.split(emptyList()).isEmpty())
    }

    // ── stepping ────────────────────────────────────────────────────────────

    /**
     * It WRAPS, and that is a deliberate choice for the widget.
     *
     * The alternative is clamping with disabled arrows at the ends, and a
     * RemoteViews arrow cannot show "disabled" convincingly — it would read as
     * a broken button rather than an edge. On a board with two or three
     * platforms wrapping also means both arrows always do something, which is
     * what a person expects from a pair of chevrons on a small control.
     */
    @Test
    fun `stepping past the last platform wraps to the first`() {
        assertEquals(0, PlatformPages.step(current = 1, delta = 1, pageCount = 2))
        assertEquals(1, PlatformPages.step(current = 0, delta = -1, pageCount = 2))
    }

    @Test
    fun `stepping within range is ordinary`() {
        assertEquals(1, PlatformPages.step(current = 0, delta = 1, pageCount = 3))
        assertEquals(1, PlatformPages.step(current = 2, delta = -1, pageCount = 3))
    }

    /**
     * **The one a stored index causes.** A widget remembers "platform 3" and is
     * redrawn against a board that now has two, because TfL stopped serving one
     * of them. Reading the stored index must land on a real page rather than
     * off the end.
     */
    @Test
    fun `an index left over from a bigger board lands on a real page`() {
        assertEquals(1, PlatformPages.clamp(index = 7, pageCount = 2))
        assertEquals(0, PlatformPages.clamp(index = -3, pageCount = 2))
        assertEquals(0, PlatformPages.clamp(index = 7, pageCount = 0))
    }

    @Test
    fun `stepping a board with one platform stays put`() {
        assertEquals(0, PlatformPages.step(current = 0, delta = 1, pageCount = 1))
        assertEquals(0, PlatformPages.step(current = 0, delta = -1, pageCount = 1))
    }

    @Test
    fun `stepping a board with no platforms cannot move`() {
        assertEquals(0, PlatformPages.step(current = 0, delta = 1, pageCount = 0))
    }

    /** The page a surface should actually draw, index already made safe. */
    @Test
    fun `page returns the rows for the clamped index`() {
        assertEquals(2, PlatformPages.page(twoPlatforms, 9).size)
        assertEquals(3, PlatformPages.page(twoPlatforms, 0).size)
        assertTrue(PlatformPages.page(emptyList(), 0).isEmpty())
    }
}
