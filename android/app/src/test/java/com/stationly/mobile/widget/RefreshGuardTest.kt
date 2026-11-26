package com.stationly.mobile.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresh button guards CONCURRENCY, not time.
 *
 * ## Android was on the side of this that iOS abandoned
 * `MANUAL_REFRESH_DEBOUNCE_MS` dropped every tap for N seconds after the last
 * refresh. `WidgetRefreshService.swift:27` records why iOS removed exactly that,
 * in its own words: a time lockout "refuses the tap a user makes because they
 * genuinely want newer numbers, and the control did nothing and said nothing,
 * which is indistinguishable from broken".
 *
 * It is also unnecessary. The thing worth preventing was never two refreshes
 * close together, it was two refreshes AT ONCE: a refresh that has COMPLETED
 * costs nothing to run again, and the fan-out is one request per unique naptan
 * either way.
 *
 * ## The ceiling is not a lockout
 * A fetch killed mid-flight would otherwise leave the guard raised and the
 * button permanently inert. The ceiling only decides when an in-flight stamp is
 * assumed dead, and it sits above the network timeout so a legitimately slow
 * fetch is never mistaken for a dead one.
 */
class RefreshGuardTest {

    private val ceiling = DepartureWidgetProvider.REFRESH_IN_FLIGHT_CEILING_MS

    @Test
    fun `a tap with nothing running goes through`() {
        assertTrue(DepartureWidgetProvider.mayRefresh(startedAt = 0L, now = 1_000L))
    }

    /** **The one the old rule got wrong.** */
    @Test
    fun `a tap straight after a completed refresh goes through`() {
        // startedAt is cleared on completion, so "just finished" looks like
        // "nothing running" — which is the entire point of moving the guard off
        // the clock.
        assertTrue(DepartureWidgetProvider.mayRefresh(startedAt = 0L, now = 1_000_001L))
    }

    @Test
    fun `a tap while a fetch is running is coalesced`() {
        val started = 1_000_000L
        assertFalse(DepartureWidgetProvider.mayRefresh(startedAt = started, now = started + 500))
    }

    /**
     * A fetch killed mid-flight (the process reclaimed, a crash) leaves its
     * stamp behind. Past the ceiling the next tap must work, or the button is
     * dead until something else happens to clear it.
     */
    @Test
    fun `a stale in-flight stamp expires`() {
        val started = 1_000_000L
        assertFalse(DepartureWidgetProvider.mayRefresh(startedAt = started, now = started + ceiling - 1))
        assertTrue(DepartureWidgetProvider.mayRefresh(startedAt = started, now = started + ceiling))
    }

    /**
     * A clock that went backwards (the user changed the time, or an NTP step)
     * must not lock the button out until it catches up. Treated as stale, which
     * errs towards the button working.
     */
    @Test
    fun `a stamp from the future does not wedge the button`() {
        assertTrue(DepartureWidgetProvider.mayRefresh(startedAt = 5_000_000L, now = 1_000L))
    }

    /**
     * The ceiling must sit ABOVE the network timeout or a slow but living fetch
     * is mistaken for a dead one and a second request piles onto it.
     */
    @Test
    fun `the ceiling clears the network timeout`() {
        assertTrue(ceiling > 10_000L)
    }
}
