package com.stationly.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The quota predicates, asked directly.
 *
 * These used to be inline `>=` checks inside two ViewModels that cannot be
 * constructed without a `Platform` behind them, which is why the first attempt
 * at testing them ended up wrapping half of both `init` blocks in `runCatching`
 * to keep the tests from crashing. Pulled out into [BoardQuota], the same rules
 * are answerable with no fixture at all.
 */
class BoardQuotaTest {

    private val policy = BoardPolicy.DEFAULT

    // ── Stations ──

    @Test
    fun `a station card is counted once however many lines it holds`() {
        // Four saved rows, two hubs: two cards on the home screen, two slots left.
        val rows = listOf("940GKINGX", "940GKINGX", "940GVICT", "940GVICT")
        assertEquals(2, BoardQuota.stationCount(rows))
        assertTrue(BoardQuota.canAddStation(rows, policy = policy))
    }

    @Test
    fun `blank grouping ids are dropped rather than counted against the user`() {
        assertEquals(1, BoardQuota.stationCount(listOf("940GKINGX", "", "  ")))
    }

    @Test
    fun `the fifth distinct station is refused`() {
        val four = listOf("a", "b", "c", "d")
        assertEquals(4, BoardQuota.stationCount(four))
        assertFalse(BoardQuota.canAddStation(four, policy = policy))
        assertFalse(BoardQuota.canAddStation(four, "e", policy))
    }

    @Test
    fun `a hub already held is always allowed through, so a full user can still edit`() {
        val four = listOf("a", "b", "c", "d")
        assertTrue(BoardQuota.canAddStation(four, "c", policy))
    }

    @Test
    fun `a served board limit replaces the compiled one`() {
        val two = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_BOARDS_MAX to "2"))
        assertTrue(BoardQuota.canAddStation(listOf("a"), policy = two))
        assertFalse(BoardQuota.canAddStation(listOf("a", "b"), policy = two))
    }

    // ── Lines ──

    @Test
    fun `the fifth line on one station is refused`() {
        assertTrue(BoardQuota.canAddLine(3, policy))
        assertFalse(BoardQuota.canAddLine(4, policy))
    }

    @Test
    fun `a served line limit replaces the compiled one`() {
        val two = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_LINES_PER_BOARD_MAX to "2"))
        assertTrue(BoardQuota.canAddLine(1, two))
        assertFalse(BoardQuota.canAddLine(2, two))
    }

    @Test
    fun `a null candidate asks the general question, so the plus is refused when full`() {
        // The home screen's "+" has no candidate yet: it is asking whether a
        // slot exists at all, and must not be let through by the
        // already-held shortcut.
        val four = listOf("a", "b", "c", "d")
        assertFalse(BoardQuota.canAddStation(four, candidate = null, policy = policy))
        assertTrue(BoardQuota.canAddStation(listOf("a", "b"), candidate = null, policy = policy))
    }
}
