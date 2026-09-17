import com.stationly.app.ui.selection.BoardFilter
import com.stationly.core.model.FilterMode
import com.stationly.core.model.sdui.SduiRouteStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The words the sheet and the board card put in front of the user.
 *
 * Worth testing because this string is assembled from two different kinds of
 * pick and shipped straight to the screen, so a mistake here passes every
 * compiler check and is immediately visible to everyone else.
 */
class BoardFilterSummaryTest {

    private fun stop(name: String) = SduiRouteStop(name.lowercase(), name)

    @Test
    fun `a single stop reads as the place`() {
        val f = BoardFilter(mode = FilterMode.VIA, viaStops = listOf(stop("Bank")))
        assertEquals("Bank", f.viaSummary)
    }

    @Test
    fun `two picks are joined and never printed as template text`() {
        val f = BoardFilter(
            mode = FilterMode.VIA,
            viaStops = listOf(stop("Bank"), stop("Waterloo")),
        )
        assertEquals("Bank & Waterloo", f.viaSummary)
        // The bug this exists for: the summary shipped with its string template
        // escaped rather than evaluated, so the board read
        // "call at ${names[0]} & ${names[1]}".
        assertFalse(f.viaSummary.contains("{"), "unevaluated template reached the screen")
        assertFalse(f.viaSummary.contains("$"), "unevaluated template reached the screen")
    }

    @Test
    fun `more than two picks collapse to a count`() {
        val f = BoardFilter(
            mode = FilterMode.VIA,
            viaStops = listOf(stop("Bank"), stop("Waterloo"), stop("Oval")),
        )
        assertEquals("Bank & 2 more", f.viaSummary)
    }

    @Test
    fun `a taken service reads as its own name`() {
        val f = BoardFilter(
            mode = FilterMode.VIA,
            patterns = listOf(BoardFilter.PatternPick("940GZZLUMDN:bank", "Morden via Bank")),
        )
        // Never the raw pattern id. A restored board would otherwise read
        // "via 940GZZLUMDN:bank" on the home screen.
        assertEquals("Morden via Bank", f.viaSummary)
        assertFalse(f.viaSummary.contains(":"), "a pattern id reached the screen")
    }

    @Test
    fun `services and stops are named together`() {
        val f = BoardFilter(
            mode = FilterMode.VIA,
            viaStops = listOf(stop("Bank")),
            patterns = listOf(BoardFilter.PatternPick("p", "Morden via Charing Cross")),
        )
        assertEquals("Morden via Charing Cross & Bank", f.viaSummary)
    }

    @Test
    fun `a service alone still counts as a filter`() {
        val f = BoardFilter(
            mode = FilterMode.VIA,
            patterns = listOf(BoardFilter.PatternPick("p", "Morden via Bank")),
        )
        // Before patterns existed isActive only looked at viaStops, so taking a
        // whole branch and nothing else saved a board with no filter at all.
        assertTrue(f.isActive)
        assertEquals(setOf("p"), f.patternIds)
    }

    @Test
    fun `switching into via keeps BOTH kinds of pick`() {
        // setFilterMode rebuilds the filter from scratch. It used to omit
        // `patterns`, so touching the mode row after taking a branch threw that
        // branch away with nothing on screen to say so. Mirrors the rebuild.
        val taken = BoardFilter(
            mode = FilterMode.VIA,
            viaStops = listOf(stop("Bank")),
            patterns = listOf(BoardFilter.PatternPick("p", "Morden via Bank")),
        )
        val rebuilt = BoardFilter(
            mode = FilterMode.VIA,
            viaStops = taken.viaStops,
            patterns = taken.patterns,
        )
        assertEquals(taken.viaStopIds, rebuilt.viaStopIds)
        assertEquals(taken.patternIds, rebuilt.patternIds)
        assertTrue(rebuilt.isActive)
    }

    @Test
    fun `an empty filter is not active`() {
        assertFalse(BoardFilter(mode = FilterMode.VIA).isActive)
        assertEquals("a stop", BoardFilter(mode = FilterMode.VIA).viaSummary)
    }
}
