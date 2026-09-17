package util

import com.stationly.core.model.FilterMode
import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.core.model.sdui.SduiRoutePattern
import com.stationly.core.model.sdui.SduiRouteStop
import com.stationly.core.repository.SqlStorage
import com.stationly.core.util.BoardFilterResolver
import com.stationly.core.util.RouteGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rejoin case, end to end.
 *
 * Southbound from Camden Town the Northern line splits into a Charing Cross
 * branch and a Bank branch and MERGES AGAIN at Kennington. Both branches run to
 * Morden, so both report naptan 940GZZLUMDN, and a filter that matches on
 * destination id alone cannot tell them apart — it admitted Charing Cross trains
 * for a "via Bank" board and hid half the Morden service on a "via Charing
 * Cross" one.
 *
 * The shape and the stop ids below are the real payload from
 * `/lines/northern/route?station=940GZZLUCTN`, trimmed to the stops the
 * assertions turn on.
 */
class BranchFilterTest {

    private fun stop(id: String, name: String) = SduiRouteStop(id, name)

    private val euston = stop("940GZZLUEUS", "Euston")
    private val bank = stop("940GZZLUBNK", "Bank")
    private val londonBridge = stop("940GZZLULNB", "London Bridge")
    private val charingCross = stop("940GZZLUCHX", "Charing Cross")
    private val waterloo = stop("940GZZLUWLO", "Waterloo")
    private val kennington = stop("940GZZLUKNG", "Kennington")
    private val oval = stop("940GZZLUOVL", "Oval")
    private val morden = stop("940GZZLUMDN", "Morden")
    private val nineElms = stop("940GZZLUNEL", "Nine Elms")
    private val battersea = stop("940GZZBPSUST", "Battersea Power Station")

    private val viaBank = SduiRoutePattern(
        id = "940GZZLUMDN:bank",
        terminusId = morden.id,
        terminusName = "Morden",
        via = "Bank",
        viaKey = "bank",
        label = "Morden via Bank",
        stops = listOf(euston, bank, londonBridge, kennington, oval, morden),
    )
    private val viaCharingCross = SduiRoutePattern(
        id = "940GZZLUMDN:charingcross",
        terminusId = morden.id,
        terminusName = "Morden",
        via = "Charing Cross",
        viaKey = "charingcross",
        label = "Morden via Charing Cross",
        stops = listOf(euston, charingCross, waterloo, kennington, oval, morden),
    )
    private val toBattersea = SduiRoutePattern(
        id = "940GZZBPSUST",
        terminusId = battersea.id,
        terminusName = "Battersea Power Station",
        via = null,
        viaKey = null,
        label = "Battersea Power Station",
        stops = listOf(euston, charingCross, waterloo, kennington, nineElms, battersea),
    )

    private val southbound = SduiDropdownOption(
        id = "inbound",
        label = "Southbound",
        patterns = listOf(viaBank, viaCharingCross, toBattersea),
        // The terminus-keyed shape an older client reads. Morden appears ONCE,
        // which is the collapse this whole change exists to stop relying on.
        destinations = listOf(
            SduiDropdownOption(id = morden.id, label = "Morden", upcomingStops = viaBank.stops),
            SduiDropdownOption(id = battersea.id, label = "Battersea Power Station", upcomingStops = toBattersea.stops),
        ),
    )

    private fun resolveVia(stopId: String) =
        BoardFilterResolver.resolve(FilterMode.VIA, southbound, viaStopIds = setOf(stopId))

    /** A departure as the board receives it: where it ends and which way it went. */
    private fun shown(destId: String, viaKey: String?, res: BoardFilterResolver.Resolution) =
        SqlStorage.matchesFilter(destId, res.destinationIds.toSet(), viaKey, res.viaKeys.toSet())

    @Test
    fun `via Bank rejects a Morden train that went via Charing Cross`() {
        val res = resolveVia(bank.id)
        // Both trains end at the same naptan, so the destination list alone says
        // yes to both — this is exactly the case it cannot decide.
        assertTrue(morden.id in res.destinationIds)
        assertEquals(listOf("bank"), res.viaKeys)

        assertTrue(shown(morden.id, "bank", res), "the Bank train must be shown")
        assertFalse(shown(morden.id, "charingcross", res), "the Charing Cross train must not be")
    }

    @Test
    fun `via Bank rejects a short-turn that stops at the merge point going the other way`() {
        val res = resolveVia(bank.id)
        // "Kennington via CX" is a real service. Kennington is downstream of
        // Bank, so the id check passes and only the branch check can catch it.
        assertTrue(kennington.id in res.destinationIds)
        assertFalse(shown(kennington.id, "charingcross", res))
    }

    @Test
    fun `via Charing Cross keeps the Morden trains that run that way`() {
        val res = resolveVia(charingCross.id)
        // The old resolution could not even offer this: Morden-via-Charing-Cross
        // was deleted before the client saw it, so Morden was not downstream of
        // Charing Cross at all and every one of those trains was hidden.
        assertTrue(morden.id in res.destinationIds, "Morden must be reachable via Charing Cross")
        assertTrue(shown(morden.id, "charingcross", res))
        assertTrue(shown(battersea.id, "charingcross", res))
        assertFalse(shown(morden.id, "bank", res))
    }

    @Test
    fun `a stop past the merge accepts both branches`() {
        // Oval is south of Kennington, where the line is one again. Every train
        // reaching it passes it whichever way it came, so nothing is narrowed.
        val res = resolveVia(oval.id)
        assertTrue(shown(morden.id, "bank", res))
        assertTrue(shown(morden.id, "charingcross", res))
    }

    @Test
    fun `a stop every branch serves does not narrow by branch`() {
        // Euston is on all three patterns. Collecting all their tokens would
        // narrow nothing and could only hide a train whose label we failed to
        // parse, so the resolution drops them.
        val res = resolveVia(euston.id)
        assertEquals(emptyList(), res.viaKeys)
        assertTrue(shown(morden.id, "bank", res))
        assertTrue(shown(morden.id, "charingcross", res))
    }

    @Test
    fun `an unlabelled departure is always shown`() {
        // Old backend, unbranched service, or a `towards` string TfL never
        // labelled. Hiding it would cost someone the train they wanted.
        val res = resolveVia(bank.id)
        assertTrue(shown(morden.id, null, res))
    }

    @Test
    fun `a payload with no patterns behaves exactly as it did before`() {
        val legacy = southbound.copy(patterns = null)
        val res = BoardFilterResolver.resolve(FilterMode.VIA, legacy, viaStopIds = setOf(bank.id))
        assertEquals(emptyList(), res.viaKeys, "nothing to narrow with")
        assertTrue(morden.id in res.destinationIds)
        // Still wrong on the rejoin, and unavoidably so — the branch was already
        // deleted upstream. It must not be wrong in some NEW way.
        assertTrue(shown(morden.id, "charingcross", res))
    }

    @Test
    fun `taking a whole service means where it goes not where it passes`() {
        // The terminus chip's own words: "All Morden via Bank trains".
        val res = BoardFilterResolver.resolve(
            FilterMode.VIA, southbound, chosenPatternIds = setOf("940GZZLUMDN:bank"),
        )
        assertEquals(listOf("bank"), res.viaKeys)
        assertTrue(shown(morden.id, "bank", res))
        assertFalse(shown(morden.id, "charingcross", res))
        // A service that turns short down the same branch is still that service.
        assertTrue(shown(kennington.id, "bank", res))
        // And the other branch is not, even though it ends in the same place.
        assertFalse(shown(battersea.id, "charingcross", res))
    }

    @Test
    fun `taking a service excludes a turn-back that never reaches it`() {
        // "All Battersea trains". Kennington is ON the Battersea run, so a
        // whole-pattern allow-list matched every "Kennington via CX" service —
        // trains that stop two stations short of Battersea and turn round.
        //
        // Only stops past the divergence count. Battersea becomes its own branch
        // at Nine Elms, so that is where its allow-list starts.
        val res = BoardFilterResolver.resolve(
            FilterMode.VIA, southbound, chosenPatternIds = setOf("940GZZBPSUST"),
        )
        assertTrue(battersea.id in res.destinationIds)
        assertTrue(nineElms.id in res.destinationIds)
        assertFalse(kennington.id in res.destinationIds, "Kennington is before the divergence")
        assertTrue(shown(battersea.id, "charingcross", res))
        assertFalse(shown(kennington.id, "charingcross", res), "this one turns back short")
    }

    @Test
    fun `a service still admits a short working down its own branch`() {
        // The other half of the same rule: past the divergence, a train that
        // turns short is still on this branch and still useful to someone
        // travelling along it.
        val res = BoardFilterResolver.resolve(
            FilterMode.VIA, southbound, chosenPatternIds = setOf("940GZZLUMDN:bank"),
        )
        assertTrue(londonBridge.id in res.destinationIds)
        assertTrue(shown(londonBridge.id, "bank", res))
    }

    @Test
    fun `taking every service narrows nothing`() {
        val res = BoardFilterResolver.resolve(
            FilterMode.VIA, southbound,
            chosenPatternIds = setOf("940GZZLUMDN:bank", "940GZZLUMDN:charingcross", "940GZZBPSUST"),
        )
        assertEquals(emptyList(), res.viaKeys, "nothing to narrow by")
        assertTrue(shown(morden.id, "bank", res))
        assertTrue(shown(morden.id, "charingcross", res))
    }

    @Test
    fun `mixing a stop and an unlabelled service fails open rather than guessing`() {
        // "Anything through Bank, plus the Battersea service."
        //
        // Both picks are admitted, which is the point. What this canNOT do is
        // exclude everything else, and the reason is structural: a board stores
        // ONE flat pair (destination ids, branch tokens), which can express one
        // "these places AND these branches" and not a union of two different
        // ones. The Battersea service carries no branch token — TfL labels it
        // only by destination — so its clause means "these places, any branch",
        // and merging that with a token-bearing clause has to drop the tokens.
        //
        // The alternative is storing a list of clauses and OR-ing them, which
        // means a new column and a change to the synced board payload. Not worth
        // it for this combination, and the failure is in the safe direction: the
        // board shows a train too many rather than hiding one the user needed.
        val res = BoardFilterResolver.resolve(
            FilterMode.VIA, southbound,
            viaStopIds = setOf(bank.id),
            chosenPatternIds = setOf("940GZZBPSUST"),
        )
        assertTrue(shown(morden.id, "bank", res), "via Bank still admitted")
        assertTrue(shown(battersea.id, "charingcross", res), "the Battersea service too")
        assertEquals(emptyList(), res.viaKeys, "no branch narrowing survives the merge")
        assertTrue(shown(morden.id, "charingcross", res), "and so this one slips through")
    }

    @Test
    fun `mixing a stop and a labelled service keeps narrowing by branch`() {
        // Where both clauses DO carry tokens, the union is exact.
        val res = BoardFilterResolver.resolve(
            FilterMode.VIA, southbound,
            viaStopIds = setOf(bank.id),
            chosenPatternIds = setOf("940GZZLUMDN:charingcross"),
        )
        assertEquals(setOf("bank", "charingcross"), res.viaKeys.toSet())
        assertTrue(shown(morden.id, "bank", res))
        assertTrue(shown(morden.id, "charingcross", res))
    }

    @Test
    fun `an unknown pattern id resolves to no filter rather than an empty board`() {
        // A saved board whose branch no longer exists — engineering works, a
        // renumbered route. It must show everything, never nothing.
        val res = BoardFilterResolver.resolve(
            FilterMode.VIA, southbound, chosenPatternIds = setOf("gone"),
        )
        assertTrue(res.isEmpty)
        assertTrue(shown(morden.id, "charingcross", res))
    }

    @Test
    fun `a branch-only filter resolves to something`() {
        // The sheet's live preview calls resolve() with the same arguments the
        // save path does. It was omitting chosenPatternIds, so taking a branch
        // resolved an empty via-stop set and the sheet announced "Nothing
        // matches. All trains will be shown." for a filter about to save fine.
        val res = BoardFilterResolver.resolve(
            FilterMode.VIA, southbound, chosenPatternIds = setOf("940GZZLUMDN:bank"),
        )
        assertFalse(res.isEmpty, "a branch pick alone must resolve to a real filter")
    }

    @Test
    fun `the map keeps every branch`() {
        val graph = RouteGraph.from(southbound)
        assertEquals(
            listOf("Morden via Bank", "Morden via Charing Cross", "Battersea Power Station").sorted(),
            graph.patterns.map { it.label }.sorted(),
            "all three service patterns must be drawable and selectable",
        )
    }

    @Test
    fun `two branches to one terminus are not treated as the same branch`() {
        val graph = RouteGraph.from(southbound)
        // The "one pick per branch" rule replaces a pick when one stop's
        // reachable set nests inside another's. Keyed on terminus, both Morden
        // patterns look identical and picking Bank would silently drop Charing
        // Cross; keyed on pattern they stay distinct.
        val fromBank = graph.patternsFrom(bank.id)
        val fromCharingCross = graph.patternsFrom(charingCross.id)
        assertTrue(fromBank.isNotEmpty() && fromCharingCross.isNotEmpty())
        assertFalse(
            fromBank.containsAll(fromCharingCross) || fromCharingCross.containsAll(fromBank),
            "Bank and Charing Cross are different branches",
        )
    }
}
