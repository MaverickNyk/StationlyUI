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

    // ── the body of a page ──────────────────────────────────────────────────

    /**
     * **Seen on the phone.** A stepping widget drew "Mildmay Platform 2" in the
     * pager bar and then again as the first row of the list underneath, because
     * the page still carried the header the bar was already showing.
     *
     * The bar names the platform; the list shows its departures. Whichever
     * surface draws the chevrons owns the name, so the page hands back its body
     * without it.
     */
    @Test
    fun `a page's body drops the header the pager bar is already showing`() {
        val page = PlatformPages.page(twoPlatforms, 0)
        assertEquals(3, page.size)
        val body = PlatformPages.body(page)
        assertEquals(2, body.size)
        assertTrue(body.none { it is Row.PlatformHeader })
    }

    /**
     * The fallback-copy page has no header to drop, and its rows are the
     * message — returning nothing would blank the one thing worth reading.
     */
    @Test
    fun `a page with no header keeps every row`() {
        val rows = listOf(dep("No upcoming departures", ""), dep("Check back shortly", ""))
        assertEquals(2, PlatformPages.body(PlatformPages.page(rows, 0)).size)
    }

    @Test
    fun `an empty page has an empty body`() {
        assertTrue(PlatformPages.body(emptyList()).isEmpty())
    }

    // ── every page is the same height ───────────────────────────────────────

    /**
     * **The owner's rule, and the reason for it.** A paged widget must not
     * change size as you step through it.
     *
     * `MIN_BOARD_ROWS` is a floor for the WHOLE board, which is right for a
     * scrolling surface: three platforms with two trains each already clears it
     * and needs no padding. Page that same board and each page is two rows, so
     * stepping from a platform with three trains to one with two shrinks the
     * widget on somebody's home screen. Android resizes the host cell and the
     * whole layout jumps.
     *
     * So a PAGE has its own floor, and it is the same three: pad with blank
     * departures until the page is exactly that tall. This is the iOS widget
     * principle — a widget occupies the space it claimed, whatever the data
     * does — and it is why the widget has no depth SETTING at all. A control
     * that changes the height of a home-screen widget is a control for
     * breaking somebody's layout.
     */
    @Test
    fun `a short page is padded to the fixed height`() {
        val page = listOf(header("Platform 10"), dep("Lewisham", "Due"))
        val body = PlatformPages.bodyPadded(page, rows = 3)
        assertEquals(3, body.size)
        assertEquals("Lewisham", (body[0] as Row.Departure).destination)
        assertTrue(body.drop(1).all { (it as Row.Departure).destination.isBlank() })
    }

    @Test
    fun `a page already at the height is untouched`() {
        val page = listOf(header("P1"), dep("A", "1"), dep("B", "2"), dep("C", "3"))
        val body = PlatformPages.bodyPadded(page, rows = 3)
        assertEquals(3, body.size)
        assertTrue(body.none { (it as Row.Departure).destination.isBlank() })
    }

    /**
     * A page longer than the height is TRIMMED, so one busy platform cannot
     * make the widget taller than the others either. The cap and the floor are
     * the same number here on purpose: every page is that tall, always.
     */
    @Test
    fun `a long page is trimmed to the fixed height`() {
        val page = listOf(header("P1"), dep("A", "1"), dep("B", "2"), dep("C", "3"), dep("D", "4"))
        assertEquals(3, PlatformPages.bodyPadded(page, rows = 3).size)
    }

    /**
     * The fallback-copy page has no header and its rows are the message. It is
     * padded like any other page, because it is on the same widget and the
     * widget is still the same size.
     */
    @Test
    fun `a headerless page is padded too, and keeps its message`() {
        val page = listOf(dep("No upcoming departures", ""))
        val body = PlatformPages.bodyPadded(page, rows = 3)
        assertEquals(3, body.size)
        assertEquals("No upcoming departures", (body[0] as Row.Departure).destination)
    }
}
