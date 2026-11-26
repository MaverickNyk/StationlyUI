package platform

import com.stationly.core.platform.TopicLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reconcile arithmetic, and the three ways getting it wrong is silent.
 *
 * Every failure this pins produces no error, no log and nothing on screen: a
 * board that quietly stops updating, a device that keeps taking pushes for a
 * station nobody tracks, or an install that loses the broadcast channel and goes
 * on believing it has it. None of them are visible from a development device,
 * all of them are visible on somebody's phone.
 */
class TopicLedgerTest {

    private val kingsCross = "Station_940GZZLUKSX"
    private val piccadilly = "LineStatus_tube_piccadilly"
    private val victoria = "LineStatus_tube_victoria"

    // ── the empty list is not an instruction ────────────────────────────────

    /**
     * **The one that would hurt most.** `getAllSelections()` answers empty
     * during a login restore, between a wipe and its refill, and before the
     * database has opened. Reading any of those as "the user has no boards"
     * unsubscribes a working device in the middle of signing in, and the boards
     * come back from the cloud seconds later with no subscriptions behind them.
     */
    @Test
    fun `an empty desired set never unsubscribes anything`() {
        val plan = TopicLedger.plan(setOf(kingsCross, piccadilly), emptyList())

        assertTrue(plan.isEmpty, "an empty list must read as 'I do not know', not as 'delete everything'")
    }

    // ── the common case costs nothing ───────────────────────────────────────

    /**
     * Runs on every app foreground. If the no-op case were not free this would
     * be a network call per resume, per device.
     *
     * It is also upgrade day: a v1 install arrives with its ledger already
     * written under the same key by the same class, so the first reconcile after
     * the update has nothing to do (risk R6).
     */
    @Test
    fun `a ledger that already matches plans no calls`() {
        val plan = TopicLedger.plan(setOf(kingsCross, piccadilly), listOf(piccadilly, kingsCross))

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `duplicates in the desired list are not a difference`() {
        val plan = TopicLedger.plan(setOf(kingsCross), listOf(kingsCross, kingsCross))

        assertTrue(plan.isEmpty)
    }

    // ── the repair, in both directions ──────────────────────────────────────

    /**
     * The leak AV2-4.1 found on the Pixel: two `Station_*` subscriptions with no
     * selection behind either. Pushes arrive every ~30s, match nothing, and are
     * dropped — backend fan-out and radio time for a phone that stopped caring.
     */
    @Test
    fun `a topic no board wants is released`() {
        val plan = TopicLedger.plan(setOf(kingsCross, piccadilly), listOf(piccadilly))

        assertEquals(listOf(kingsCross), plan.unsubscribe)
        assertEquals(emptyList(), plan.subscribe)
    }

    /**
     * The other direction, and the one a naive diff introduces: a board added
     * while this device was off, or one whose subscription was lost with a
     * token. The ledger is behind reality, so the reconcile has to push forward
     * as well as back.
     */
    @Test
    fun `a board with no subscription gets one`() {
        val plan = TopicLedger.plan(setOf(kingsCross), listOf(kingsCross, victoria))

        assertEquals(listOf(victoria), plan.subscribe)
        assertEquals(emptyList(), plan.unsubscribe)
    }

    @Test
    fun `both directions in one pass`() {
        val plan = TopicLedger.plan(setOf(kingsCross, piccadilly), listOf(kingsCross, victoria))

        assertEquals(listOf(victoria), plan.subscribe)
        assertEquals(listOf(piccadilly), plan.unsubscribe)
    }

    // ── the broadcast topic is not a board ──────────────────────────────────

    /**
     * `stationly_all` is how an `audience: {type:"all"}` push fans out with zero
     * Firestore reads. It is subscribed once per install and tracked by its own
     * boolean, so it is not in the ledger today — this asserts what happens if
     * it ever gets there, because the failure would be every admin broadcast
     * silently ending, on a device that still reports itself subscribed.
     */
    @Test
    fun `a reconcile never unsubscribes the global broadcast topic`() {
        val plan = TopicLedger.plan(setOf(kingsCross, "stationly_all"), listOf(kingsCross))

        assertTrue(plan.isEmpty, "stationly_all is not a board and must survive a reconcile")
    }

    @Test
    fun `board topics are exactly the two families`() {
        assertTrue(TopicLedger.isBoardTopic(kingsCross))
        assertTrue(TopicLedger.isBoardTopic(piccadilly))
        assertTrue(!TopicLedger.isBoardTopic("stationly_all"))
        assertTrue(!TopicLedger.isBoardTopic("Station"))
    }
}
