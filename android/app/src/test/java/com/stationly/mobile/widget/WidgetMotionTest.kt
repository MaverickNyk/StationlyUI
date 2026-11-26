package com.stationly.mobile.widget

import com.stationly.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion on a home-screen widget means "you did that".
 *
 * ## The measurement behind every test here
 * One refresh tap produced **132 redraws** across four placed widgets — pushes
 * answering the fetch, a status push per line, the minute tick, and the
 * `ACTION_UPDATE_WIDGET` broadcast `ProcessPredictionsUseCase` sends per
 * processed payload. When a redraw animated, that is 132 flashes, which is what
 * the owner reported as "almost like multiple flashes" and "zoo zoo zoo".
 *
 * Scoping the redraws helped and did not fix it, because the real problem was
 * that ONE redraw was already one flash. So the rule these tests hold is the
 * other half: a redraw is silent unless a finger asked for it.
 */
class WidgetMotionTest {

    // ── Which flipper, which animation ───────────────────────────────────────

    /**
     * `setInAnimation` is not a `@RemotableViewMethod`, so an animation cannot
     * be chosen at update time — only at inflate time, per view. Three distinct
     * motions therefore need three distinct flippers, and if two of them ever
     * share one, two of the owner's three requests silently become the same
     * animation.
     */
    @Test
    fun `stepping forward, stepping back and refreshing are three different animations`() {
        assertNotEquals(
            "a forward step and a back step must not share a flipper, or the " +
                "board slides the same way whichever chevron is pressed",
            WidgetMotion.NEXT.flipperId,
            WidgetMotion.PREV.flipperId,
        )
        assertNotEquals(
            "the refresh flip must not share a flipper with a step, or the " +
                "board slides sideways when it should turn over",
            WidgetMotion.REFRESH.flipperId,
            WidgetMotion.NEXT.flipperId,
        )
        assertNotEquals(
            WidgetMotion.REFRESH.flipperId,
            WidgetMotion.PREV.flipperId,
        )
    }

    /** A board at rest lives where a forward step will want it: no swap needed. */
    @Test
    fun `resting and stepping forward share the slot`() {
        assertEquals(WidgetMotion.NONE.flipperId, WidgetMotion.NEXT.flipperId)
    }

    @Test
    fun `every flipper in the stack is known, so an update can hide the rest`() {
        assertEquals(
            WidgetMotion.entries.map { it.flipperId }.distinct().sorted(),
            WidgetMotion.flippers.sorted(),
        )
        assertTrue(R.id.platform_flipper in WidgetMotion.flippers)
        assertTrue(R.id.platform_flipper_prev in WidgetMotion.flippers)
        assertTrue(R.id.platform_flipper_refresh in WidgetMotion.flippers)
    }

    @Test
    fun `only a deliberate motion moves anything`() {
        assertFalse("an ambient redraw is silent", WidgetMotion.NONE.animates)
        assertTrue(WidgetMotion.NEXT.animates)
        assertTrue(WidgetMotion.PREV.animates)
        assertTrue(WidgetMotion.REFRESH.animates)
    }

    // ── Direction ────────────────────────────────────────────────────────────

    @Test
    fun `the right chevron goes forward and the left goes back`() {
        assertEquals(WidgetMotion.NEXT, WidgetMotion.step(1))
        assertEquals(WidgetMotion.PREV, WidgetMotion.step(-1))
    }

    /**
     * A delta of 0 is not a direction, and there is no motion that means "stay".
     * Forward is the safe reading: the board still moves somewhere the user can
     * see, rather than the update falling through to a branch that draws two
     * frames and animates between identical ones.
     */
    @Test
    fun `a delta that says nothing is treated as forward`() {
        assertEquals(WidgetMotion.NEXT, WidgetMotion.step(0))
    }

    // ── The one-shot refresh flag ────────────────────────────────────────────

    /**
     * The press is what knows. By the time the fetch lands and the board is
     * redrawn, a refresh redraw and a push redraw are the same call with the
     * same data, so the tap has to write down that it happened.
     */
    @Test
    fun `a refresh just armed is owed its flip`() {
        assertTrue(WidgetMotion.isArmed(armedAt = 1_000L, now = 1_050L))
    }

    /**
     * **The one that stops a board flipping for no reason.** If the redraw the
     * tap was waiting for never comes — no network, the fetch threw, the process
     * was killed — the flag must not sit there and spend itself on whatever
     * ambient push happens to land next.
     */
    @Test
    fun `a refresh whose redraw never came expires instead of waiting forever`() {
        val armedAt = 1_000L
        assertTrue(
            "still inside the window",
            WidgetMotion.isArmed(armedAt, armedAt + WidgetMotion.ARMED_FOR_MS),
        )
        assertFalse(
            "a minute later is a different press, or no press at all",
            WidgetMotion.isArmed(armedAt, armedAt + WidgetMotion.ARMED_FOR_MS + 1),
        )
    }

    /** Nothing armed is the overwhelmingly common case and must be silent. */
    @Test
    fun `a widget nobody pressed is never owed an animation`() {
        assertFalse(WidgetMotion.isArmed(armedAt = 0L, now = 5_000L))
    }

    /**
     * A stamp in the future is a clock that moved — a timezone change, an NTP
     * correction, the user setting the date — and treating it as armed would
     * flip the board on every redraw until the wall clock caught up. Same rule
     * and same reason as [DepartureWidgetProvider.mayRefresh], which learned it
     * first.
     */
    @Test
    fun `a stamp from the future is stale, not armed`() {
        assertFalse(WidgetMotion.isArmed(armedAt = 10_000L, now = 1_000L))
    }

    /**
     * The window is the refresh guard's window, not a second number. A tap is
     * owed its animation for exactly as long as it is still considered to be in
     * flight; two independent constants would drift and the gap between them
     * would be a press that refreshed and did not move.
     */
    @Test
    fun `the animation is owed for exactly as long as the refresh is in flight`() {
        assertEquals(
            DepartureWidgetProvider.REFRESH_IN_FLIGHT_CEILING_MS,
            WidgetMotion.ARMED_FOR_MS,
        )
    }
}
