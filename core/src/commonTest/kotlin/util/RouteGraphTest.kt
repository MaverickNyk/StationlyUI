package util

import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.core.model.sdui.SduiRoutePattern
import com.stationly.core.model.sdui.SduiRouteStop
import com.stationly.core.util.RouteGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The graph the map is drawn from.
 *
 * Every case here is a real one taken from TfL's route sequences, because the
 * whole class of bug this replaces came from a model that could not express what
 * the network actually does.
 */
class RouteGraphTest {

    private fun s(id: String, name: String) = SduiRouteStop(id, name)

    private val mtc = s("MTC", "Mornington Crescent")
    private val eus = s("EUS", "Euston")
    private val bnk = s("BNK", "Bank")
    private val lnb = s("LNB", "London Bridge")
    private val chx = s("CHX", "Charing Cross")
    private val wlo = s("WLO", "Waterloo")
    private val kng = s("KNG", "Kennington")
    private val ovl = s("OVL", "Oval")
    private val mdn = s("MDN", "Morden")
    private val nel = s("NEL", "Nine Elms")
    private val bps = s("BPS", "Battersea")

    private fun pattern(id: String, label: String, viaKey: String?, terminus: String, stops: List<SduiRouteStop>) =
        SduiRoutePattern(
            id = id, terminusId = terminus, terminusName = terminus,
            via = viaKey, viaKey = viaKey, label = label, stops = stops,
        )

    /** Northern southbound from Camden Town: splits, then merges at Kennington. */
    private val camdenSouthbound = SduiDropdownOption(
        id = "inbound",
        label = "Southbound",
        patterns = listOf(
            pattern("MDN:bank", "Morden via Bank", "bank", "MDN",
                listOf(eus, bnk, lnb, kng, ovl, mdn)),
            pattern("MDN:cx", "Morden via Charing Cross", "charingcross", "MDN",
                listOf(mtc, eus, chx, wlo, kng, ovl, mdn)),
            pattern("BPS", "Battersea", null, "BPS",
                listOf(mtc, eus, chx, wlo, kng, nel, bps)),
        ),
    )

    @Test
    fun `the merge point is drawn once`() {
        val g = RouteGraph.from(camdenSouthbound)
        val kennington = g.segments.flatMap { seg -> seg.stops.filter { it.id == kng.id } }
        assertEquals(1, kennington.size, "Kennington is where the line becomes one again")
    }

    @Test
    fun `a station both branches call at is one tappable place`() {
        val g = RouteGraph.from(camdenSouthbound)
        // Euston is where the branches meet and part again; Kennington is where
        // they meet and stay together. Both are ONE marker, because the question
        // this map answers is "which trains take me through here" and the answer
        // at Euston is "all of them". Two Eustons would be two ways to say that.
        assertEquals(1, g.segments.sumOf { seg -> seg.stops.count { it.id == eus.id } })
        assertEquals(setOf("MDN:bank", "MDN:cx", "BPS"), g.patternsFrom(eus.id))
        // And nothing is narrowed by picking it, so the filter stays open.
        assertTrue(g.repeatedStops.isEmpty(), "nothing here is called at twice")
    }

    @Test
    fun `columns respect every edge so no two adjacent stops collide`() {
        val g = RouteGraph.from(camdenSouthbound)
        // The bug this guards: taking each stop's index within its own pattern
        // put Euston (index 0 on the Bank branch, 1 on the Charing Cross one)
        // in the same column as Bank, drawing one on top of the other.
        val col = HashMap<String, Int>()
        g.segments.forEach { seg -> seg.stops.forEachIndexed { i, st -> col[st.id] = seg.startCol + i } }
        listOf(
            listOf(eus, bnk, lnb, kng, ovl, mdn),
            listOf(mtc, eus, chx, wlo, kng, ovl, mdn),
            listOf(mtc, eus, chx, wlo, kng, nel, bps),
        ).forEach { run ->
            run.zipWithNext { a, b ->
                assertTrue(
                    col.getValue(b.id) > col.getValue(a.id),
                    "${b.name} must sit right of ${a.name}",
                )
            }
        }
    }

    @Test
    fun `the shared tail past the merge is drawn once`() {
        val g = RouteGraph.from(camdenSouthbound)
        // Oval and Morden are on both Morden patterns. Before this they were
        // duplicated down each branch, so the map showed two Mordens.
        listOf(ovl, mdn).forEach { stop ->
            val n = g.segments.sumOf { seg -> seg.stops.count { it.id == stop.id } }
            assertEquals(1, n, "${stop.name} is past the merge and belongs on one line")
        }
    }

    @Test
    fun `a branch off the merge point stays separate`() {
        val g = RouteGraph.from(camdenSouthbound)
        // Kennington is a merge AND a split: only the Charing Cross side
        // continues to Battersea. Both must survive.
        assertEquals(1, g.segments.sumOf { seg -> seg.stops.count { it.id == bps.id } })
        assertTrue(g.patternsFrom(bps.id).contains("BPS"))
        assertTrue(g.patternsFrom(mdn.id).containsAll(listOf("MDN:bank", "MDN:cx")))
    }

    @Test
    fun `no two segments overlap on the same row`() {
        val g = RouteGraph.from(camdenSouthbound)
        g.segments.groupBy { it.row }.forEach { (row, segs) ->
            val sorted = segs.sortedBy { it.startCol }
            sorted.zipWithNext { a, b ->
                assertTrue(b.startCol > a.endCol, "segments overlap on row $row")
            }
        }
    }

    @Test
    fun `branch identity survives so the filter can use it`() {
        val g = RouteGraph.from(camdenSouthbound)
        assertEquals(setOf("bank"), g.viaKeysFrom(bnk.id))
        assertEquals(setOf("charingcross"), g.viaKeysFrom(chx.id))
        // Past the merge both branches qualify, so nothing is narrowed.
        assertEquals(setOf("bank", "charingcross"), g.viaKeysFrom(ovl.id))
    }

    @Test
    fun `a station the route calls at twice keeps both calls`() {
        // The Circle line's spiral: Edgware Road at index 9 and again at the end.
        val erc = s("ERC", "Edgware Road")
        val pad = s("PAD", "Paddington")
        val bst = s("BST", "Baker Street")
        val vic = s("VIC", "Victoria")
        val circle = SduiDropdownOption(
            id = "outbound", label = "Circle",
            patterns = listOf(
                pattern("ERC", "Edgware Road", null, "ERC", listOf(pad, erc, bst, vic, pad, erc)),
            ),
        )
        val g = RouteGraph.from(circle)
        assertEquals(2, g.segments.sumOf { seg -> seg.stops.count { it.id == erc.id } },
            "the train really does call at Edgware Road twice")
    }

    @Test
    fun `taking a whole branch picks the first stop only that branch reaches`() {
        val g = RouteGraph.from(camdenSouthbound)
        // The Battersea chip stores the first stop of its terminal segment.
        // That segment starts AFTER Kennington, so it is Nine Elms - the first
        // stop nothing else reaches. Everything past a divergence is only
        // reachable through it, so that one id already implies the branch.
        val terminal = g.segments.first { it.isTerminal && it.patternIds == setOf("BPS") }
        assertEquals(nel.id, terminal.stops.first().id)

        // And it is exact: picking it admits the Battersea branch and nothing
        // else. Picking a stop BEFORE the merge would have been wrong - Charing
        // Cross is shared with Morden via CX, so it would drag those in too.
        assertEquals(setOf("BPS"), g.patternsFrom(nel.id))
        assertEquals(setOf("MDN:cx", "BPS"), g.patternsFrom(chx.id))
    }

    @Test
    fun `a payload with no patterns still draws`() {
        val legacy = SduiDropdownOption(
            id = "inbound", label = "Southbound",
            destinations = listOf(
                SduiDropdownOption(id = "MDN", label = "Morden",
                    upcomingStops = listOf(eus, bnk, kng, mdn)),
                SduiDropdownOption(id = "BPS", label = "Battersea",
                    upcomingStops = listOf(mtc, eus, chx, kng, bps)),
            ),
        )
        val g = RouteGraph.from(legacy)
        assertTrue(g.segments.isNotEmpty())
        assertNotNull(g.positionOf(kng.id))
        assertEquals(2, g.patterns.size)
    }

    @Test
    fun `an empty payload does not crash`() {
        val g = RouteGraph.from(SduiDropdownOption(id = "inbound", label = "x"))
        assertEquals(emptyList(), g.segments)
        assertEquals(0, g.colCount)
    }
}
