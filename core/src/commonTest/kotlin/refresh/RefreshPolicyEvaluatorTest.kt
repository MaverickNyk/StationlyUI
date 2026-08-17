package refresh

import com.stationly.core.model.refresh.BoostState
import com.stationly.core.model.refresh.BudgetLedger
import com.stationly.core.model.refresh.RefreshBudget
import com.stationly.core.model.refresh.RefreshPolicy
import com.stationly.core.model.refresh.RefreshPolicyDefaults
import com.stationly.core.model.refresh.RefreshTier
import com.stationly.core.model.refresh.RefreshWindow
import com.stationly.core.refresh.RefreshPolicyEvaluator
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The schedule, proven without a device.
 *
 * Everything interesting about this feature happens at times you cannot sit and
 * wait for — 03:00 on a Saturday, the minute a boost lapses, the point in a day
 * where the quota runs short. All of it is reachable here because the evaluator
 * takes `now` as an argument rather than reading a clock.
 */
class RefreshPolicyEvaluatorTest {

    private val london = TimeZone.of("Europe/London")

    /** Epoch millis for a wall-clock London time. */
    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int = 0,
    ): Long = LocalDateTime(year, month, day, hour, minute)
        .toInstant(london).toEpochMilliseconds()

    // 2026-08-10 is a Monday; 2026-08-15 a Saturday.
    private fun monday(hour: Int, minute: Int = 0) = at(2026, 8, 10, hour, minute)
    private fun saturday(hour: Int, minute: Int = 0) = at(2026, 8, 15, hour, minute)

    private val policy = RefreshPolicyDefaults.POLICY

    /** A ledger with [spent] reloads already used, window opened at [openedAt]. */
    private fun ledger(spent: Int, openedAt: Long) =
        BudgetLedger(windowStartEpochMs = openedAt, reloadCount = spent)

    // ── Tier selection ────────────────────────────────────────────────────

    @Test
    fun weekdayMorningPeakSelectsRushTier() {
        val d = RefreshPolicyEvaluator.decide(policy, monday(8, 0))
        assertEquals(RefreshPolicyDefaults.TIER_RUSH, d.tierId)
        assertEquals(15, d.intervalMinutes)
        assertEquals(15, d.backgroundTaskMinutes)
    }

    @Test
    fun weekdayMiddaySelectsOffPeakTier() {
        val d = RefreshPolicyEvaluator.decide(policy, monday(12, 0))
        assertEquals(RefreshPolicyDefaults.TIER_DAY, d.tierId)
        assertEquals(45, d.intervalMinutes)
    }

    @Test
    fun weekendDaytimeUsesItsOwnTierNotWeekdayRush() {
        // The rush windows are MON-FRI only, so a Saturday 08:00 glance must
        // never buy weekday peak cadence — that is the day-matching working.
        // It gets the weekend tier rather than weekday off-peak, because a
        // weekend has no commuter peak to reserve budget for.
        val d = RefreshPolicyEvaluator.decide(policy, saturday(8, 0))
        assertEquals(RefreshPolicyDefaults.TIER_WEEKEND, d.tierId)
        assertEquals(30, d.intervalMinutes)
    }

    @Test
    fun theOvernightBandsButtAgainstTheirNeighboursWithNoGap() {
        // Every boundary from the small hours to the first daytime tier, with no
        // hole anywhere. A hole here does not fail loudly: it falls through to
        // `defaultTierId`, so a stretch of the morning silently runs at a cadence
        // unrelated to either side of it, which is what this exists to catch.
        //
        // Night now ends at 05:00 rather than 06:30, with P5 covering the gap —
        // see `RefreshPolicyDefaults.PREDAWN` for why the pre-dawn hour earns a
        // tier of its own.
        assertEquals(RefreshPolicyDefaults.TIER_NIGHT,
            RefreshPolicyEvaluator.decide(policy, saturday(4, 59)).tierId)
        assertEquals(RefreshPolicyDefaults.TIER_PREDAWN,
            RefreshPolicyEvaluator.decide(policy, saturday(5, 1)).tierId)
        assertEquals(RefreshPolicyDefaults.TIER_PREDAWN,
            RefreshPolicyEvaluator.decide(policy, saturday(6, 29)).tierId)
        assertEquals(RefreshPolicyDefaults.TIER_WEEKEND,
            RefreshPolicyEvaluator.decide(policy, saturday(6, 31)).tierId)
        // The same seam on a weekday hands over to the rush tier instead.
        assertEquals(RefreshPolicyDefaults.TIER_PREDAWN,
            RefreshPolicyEvaluator.decide(policy, monday(6, 29)).tierId)
        assertEquals(RefreshPolicyDefaults.TIER_RUSH,
            RefreshPolicyEvaluator.decide(policy, monday(6, 31)).tierId)
    }

    @Test
    fun theQuietHourBeforeTheRushRefreshesDataWithoutSpendingReloads() {
        // The point of P5, and the two numbers that make it worth having. The
        // failure it replaces: P3 asked 180 minutes AND disabled the background
        // wake, so nothing at all refreshed between 23:00 and 06:30 and the
        // commuter's first glance got the stalest board of the day.
        //
        // Asserted as a RELATIONSHIP rather than as literals, so tuning either
        // number keeps the property: the background wake (separate budget, data
        // only) must be far denser than the timeline reload (metered).
        val predawn = RefreshPolicyEvaluator.decide(policy, monday(5, 30))
        val night = RefreshPolicyEvaluator.decide(policy, monday(2, 0))

        assertEquals(RefreshPolicyDefaults.TIER_PREDAWN, predawn.tierId)
        assertTrue(predawn.backgroundTaskMinutes in 1..30,
            "pre-dawn must actually fetch data, unlike the night band it split off")
        assertEquals(0, night.backgroundTaskMinutes,
            "the deep night must still cost no battery at all")
        assertTrue(predawn.backgroundTaskMinutes * 2 < predawn.intervalMinutes,
            "the cheap layer must be the dense one, or this spends metered reloads")
    }

    @Test
    fun uncoveredTimeFallsBackToDefaultTier() {
        // Built deliberately rather than relying on a hole in the shipped
        // schedule: this tests the FALLBACK MECHANISM, and tying it to an
        // incidental gap meant closing that gap silently deleted the coverage.
        val gapped = policy.copy(
            windows = listOf(
                RefreshWindow(days = emptyList(), from = "09:00", to = "17:00",
                    tierId = RefreshPolicyDefaults.TIER_RUSH, priority = 1),
            ),
        )
        val d = RefreshPolicyEvaluator.decide(gapped, monday(3, 0))
        assertEquals(gapped.defaultTierId, d.tierId)
    }

    // ── Midnight wrap ─────────────────────────────────────────────────────

    @Test
    fun nightTierAppliesBothSidesOfMidnight() {
        val before = RefreshPolicyEvaluator.decide(policy, monday(23, 30))
        val after = RefreshPolicyEvaluator.decide(policy, at(2026, 8, 11, 2, 0))
        assertEquals(RefreshPolicyDefaults.TIER_NIGHT, before.tierId)
        assertEquals(RefreshPolicyDefaults.TIER_NIGHT, after.tierId)
        assertEquals(0, after.backgroundTaskMinutes, "night must not schedule background wakes")
    }

    @Test
    fun wrappingWindowMatchesTheDayItStartedOn() {
        // A FRI-only overnight window is still in force at 02:00 on SATURDAY,
        // because that stretch began on Friday. Getting this wrong silently
        // drops half of every overnight window — the half people are awake for.
        val friNight = RefreshPolicy(
            timezone = "Europe/London",
            tiers = listOf(
                RefreshTier(id = "LATE", intervalMinutes = 20),
                RefreshTier(id = "BASE", intervalMinutes = 90),
            ),
            windows = listOf(
                RefreshWindow(days = listOf("FRI"), from = "23:00", to = "04:00", tierId = "LATE"),
            ),
            defaultTierId = "BASE",
        )
        // 2026-08-14 is a Friday.
        val fridayLate = at(2026, 8, 14, 23, 30)
        val saturdayEarly = at(2026, 8, 15, 2, 0)
        val sundayEarly = at(2026, 8, 16, 2, 0)

        assertEquals("LATE", RefreshPolicyEvaluator.decide(friNight, fridayLate).tierId)
        assertEquals("LATE", RefreshPolicyEvaluator.decide(friNight, saturdayEarly).tierId)
        assertEquals("BASE", RefreshPolicyEvaluator.decide(friNight, sundayEarly).tierId,
            "Saturday night is not Friday night")
    }

    // ── Priority and malformed input ──────────────────────────────────────

    @Test
    fun higherPriorityWindowWinsAnOverlap() {
        val overlaid = policy.copy(
            windows = policy.windows + RefreshWindow(
                days = emptyList(), from = "00:00", to = "23:59",
                tierId = RefreshPolicyDefaults.TIER_RUSH, priority = 99,
            )
        )
        // 03:00 would normally be the night tier; the festival overlay wins.
        val d = RefreshPolicyEvaluator.decide(overlaid, at(2026, 8, 11, 3, 0))
        assertEquals(RefreshPolicyDefaults.TIER_RUSH, d.tierId)
    }

    @Test
    fun windowNamingAnUnknownTierIsSkippedNotFatal() {
        // How a policy referencing a tier only newer clients know must behave:
        // inert here, never a blank schedule.
        val withFutureTier = policy.copy(
            windows = policy.windows + RefreshWindow(
                days = emptyList(), from = "00:00", to = "23:59",
                tierId = "P9_FROM_THE_FUTURE", priority = 99,
            )
        )
        val d = RefreshPolicyEvaluator.decide(withFutureTier, monday(12, 0))
        assertEquals(RefreshPolicyDefaults.TIER_DAY, d.tierId)
    }

    @Test
    fun malformedTimesAndTimezoneDegradeInsteadOfThrowing() {
        val broken = policy.copy(
            timezone = "Not/AZone",
            windows = listOf(RefreshWindow(days = emptyList(), from = "9am", to = "25:99", tierId = "P1")),
        )
        val d = RefreshPolicyEvaluator.decide(broken, monday(8, 0))
        assertEquals(policy.defaultTierId, d.tierId)
    }

    @Test
    fun intervalNeverGoesBelowTheFloorHoweverBadThePolicy() {
        val reckless = policy.copy(
            tiers = listOf(RefreshTier(id = "OOPS", intervalMinutes = 1)),
            windows = emptyList(),
            defaultTierId = "OOPS",
            budget = RefreshBudget(dailyReloadCeiling = 55, reserveForBoost = 12, minIntervalMinutes = 10),
        )
        val d = RefreshPolicyEvaluator.decide(reckless, monday(12, 0))
        assertTrue(d.intervalMinutes >= 10, "got ${d.intervalMinutes}")
    }

    // ── Boost ─────────────────────────────────────────────────────────────

    @Test
    fun activeBoostOverridesTheScheduledTier() {
        val now = at(2026, 8, 11, 3, 0)   // would be night
        val boost = RefreshPolicyEvaluator.startBoost(policy, now, reason = "match")
        val d = RefreshPolicyEvaluator.decide(policy, now, boost = boost)
        assertEquals(RefreshPolicyDefaults.TIER_RUSH, d.tierId)
        assertTrue(d.boostActive)
    }

    @Test
    fun boostSelfExpiresWithoutAStopPush() {
        // The load-bearing guarantee: a device that never receives `boost.stop`
        // must fall back on its own. Nothing here delivers a stop.
        val start = at(2026, 8, 11, 3, 0)
        val boost = RefreshPolicyEvaluator.startBoost(policy, start)

        val during = RefreshPolicyEvaluator.decide(policy, start + 89 * 60_000L, boost = boost)
        assertTrue(during.boostActive)
        assertEquals(RefreshPolicyDefaults.TIER_RUSH, during.tierId)

        val after = RefreshPolicyEvaluator.decide(policy, start + 91 * 60_000L, boost = boost)
        assertFalse(after.boostActive)
        assertEquals(RefreshPolicyDefaults.TIER_NIGHT, after.tierId)
    }

    @Test
    fun boostDurationIsCappedByPolicyNotByTheSender() {
        val now = monday(12, 0)
        val boost = RefreshPolicyEvaluator.startBoost(policy, now, requestedMinutes = 8 * 60)
        val heldMinutes = (boost.expiresAtEpochMs - now) / 60_000L
        assertEquals(policy.boost.maxDurationMinutes.toLong(), heldMinutes)
    }

    @Test
    fun refreshIsScheduledForTheBoostExpiryWhenThatComesFirst() {
        val start = monday(12, 0)
        // 5 minutes left of a boost, against an interval far longer than that.
        val boost = BoostState(
            tierId = RefreshPolicyDefaults.TIER_DAY,
            startedAtEpochMs = start - 85 * 60_000L,
            expiresAtEpochMs = start + 5 * 60_000L,
        )
        val d = RefreshPolicyEvaluator.decide(policy, start, boost = boost)
        // Clamped up to the 10-minute floor rather than firing at 5: a boundary
        // seconds away must not become a reload storm.
        assertEquals(start + 10 * 60_000L, d.nextRefreshEpochMs)
    }

    // ── Budget governor ───────────────────────────────────────────────────

    @Test
    fun defaultScheduleIsAffordableSoNothingIsStretched() {
        // The regression this guards: a naive "spread the quota evenly" governor
        // charges rush hour as though it ran all day and silently corrects P1's
        // 15 minutes to ~33 every single morning, budget spent or not.
        val now = monday(8, 0)
        val d = RefreshPolicyEvaluator.decide(policy, now, ledger = ledger(0, monday(0, 0)))
        assertEquals(15, d.intervalMinutes)
        assertFalse(d.degraded)
    }

    @Test
    fun heavySpendStretchesTheInterval() {
        val now = monday(12, 0)
        val spent = policy.budget.dailyReloadCeiling - policy.budget.reserveForBoost - 2
        val d = RefreshPolicyEvaluator.decide(policy, now, ledger = ledger(spent, monday(0, 0)))
        assertTrue(d.degraded, "expected the governor to engage")
        assertTrue(d.intervalMinutes > 45, "got ${d.intervalMinutes}")
    }

    @Test
    fun exhaustedBudgetBacksOffButNeverBlacksOut() {
        // The regression this pins: back-off used to stretch to the whole
        // remaining 24 h window. On a real device an over-counted ledger
        // produced `next=627m` and the widget sat untouched through a Monday
        // morning peak. Overspending degrades gracefully (Apple stops honouring
        // reloads); a frozen board does not, so the ceiling always wins.
        val windowStart = monday(0, 0)
        val now = monday(12, 0)
        val d = RefreshPolicyEvaluator.decide(
            policy, now, ledger = ledger(policy.budget.dailyReloadCeiling * 2, windowStart),
        )
        assertTrue(d.degraded, "expected the governor to engage")
        assertTrue(
            d.intervalMinutes <= policy.budget.maxIntervalMinutes,
            "backed off to ${d.intervalMinutes}m, ceiling is ${policy.budget.maxIntervalMinutes}m",
        )
    }

    @Test
    fun theCeilingNeverPullsAnIntervalBelowItsTier() {
        // A tier deliberately slower than the ceiling (night is 180m vs a 120m
        // cap) must not be SPED UP by it — the cap exists to stop runaway
        // back-off, not to override a schedule that asked to be quiet.
        val nightly = RefreshPolicyEvaluator.decide(policy, at(2026, 8, 11, 3, 0))
        assertEquals(RefreshPolicyDefaults.TIER_NIGHT, nightly.tierId)
        assertEquals(180, nightly.intervalMinutes)
        assertFalse(nightly.degraded)
    }

    @Test
    fun aBoostMayDrawOnTheReserveThatRoutineSchedulingCannot() {
        val now = monday(12, 0)
        // Exactly at the routine ceiling: the reserve is all that is left.
        val spent = policy.budget.dailyReloadCeiling - policy.budget.reserveForBoost
        val l = ledger(spent, monday(0, 0))

        val routine = RefreshPolicyEvaluator.decide(policy, now, ledger = l)
        val boosted = RefreshPolicyEvaluator.decide(
            policy, now, boost = RefreshPolicyEvaluator.startBoost(policy, now), ledger = l,
        )
        assertTrue(routine.degraded, "routine scheduling must be held back at the ceiling")
        assertTrue(boosted.intervalMinutes < routine.intervalMinutes,
            "a boost must reach the reserve: boosted=${boosted.intervalMinutes} routine=${routine.intervalMinutes}")
    }

    @Test
    fun ledgerRollsAfterTwentyFourHours() {
        val opened = monday(0, 0)
        val stale = ledger(50, opened)
        val rolled = stale.rolled(opened + BudgetLedger.DAY_MS + 1)
        assertEquals(0, rolled.reloadCount)
    }

    // ── Next refresh ──────────────────────────────────────────────────────

    @Test
    fun nextRefreshLandsOnAWindowBoundaryWhenOneComesFirst() {
        // 09:20 in the rush tier: the interval alone would say 09:35, but the
        // tier changes at 09:30 and the timeline shape should change with it.
        val now = monday(9, 20)
        val d = RefreshPolicyEvaluator.decide(policy, now)
        assertEquals(monday(9, 30), d.nextRefreshEpochMs)
    }

    @Test
    fun nextRefreshIsNeverSoonerThanTheFloor() {
        val now = monday(9, 29)   // boundary is 60 seconds away
        val d = RefreshPolicyEvaluator.decide(policy, now)
        assertTrue(
            d.nextRefreshEpochMs >= now + policy.budget.minIntervalMinutes * 60_000L,
            "scheduled ${(d.nextRefreshEpochMs - now) / 60_000L} min out",
        )
    }

    // ── Segmented schedule (what the widget extension reads) ──────────────

    @Test
    fun scheduleCoversTheWholeSpanWithoutGapsOrOverlaps() {
        val start = monday(5, 0)
        val span = BudgetLedger.DAY_MS
        val segments = RefreshPolicyEvaluator.schedule(policy, start, span)

        assertTrue(segments.isNotEmpty())
        assertEquals(start, segments.first().startEpochMs)
        segments.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endEpochMs, b.startEpochMs, "gap or overlap between segments")
            assertTrue(a.endEpochMs > a.startEpochMs, "zero-length segment")
        }
        assertEquals(start + span, segments.last().endEpochMs)
    }

    @Test
    fun scheduleGivesTheRightTierAtEachHourOfATypicalWeekday() {
        // The failure this exists to catch: an extension holding one stale
        // decision applies rush-hour cadence at 03:00. Reading the tier out of
        // the SEGMENT covering that instant is what makes that impossible.
        val segments = RefreshPolicyEvaluator.schedule(policy, monday(0, 0), BudgetLedger.DAY_MS)

        fun tierAt(ms: Long): String = segments
            .first { ms >= it.startEpochMs && ms < it.endEpochMs }.tierId

        assertEquals(RefreshPolicyDefaults.TIER_NIGHT, tierAt(monday(3, 0)))
        assertEquals(RefreshPolicyDefaults.TIER_RUSH, tierAt(monday(8, 0)))
        assertEquals(RefreshPolicyDefaults.TIER_DAY, tierAt(monday(12, 0)))
        assertEquals(RefreshPolicyDefaults.TIER_RUSH, tierAt(monday(17, 30)))
        assertEquals(RefreshPolicyDefaults.TIER_DAY, tierAt(monday(21, 0)))
        assertEquals(RefreshPolicyDefaults.TIER_NIGHT, tierAt(monday(23, 30)))
    }

    @Test
    fun scheduleDropsOutOfBoostPartwayThrough() {
        val start = monday(12, 0)
        val boost = RefreshPolicyEvaluator.startBoost(policy, start)   // 90 min
        val segments = RefreshPolicyEvaluator.schedule(
            policy, start, spanMs = 6 * 60 * 60 * 1000L, boost = boost,
        )
        fun segAt(ms: Long) = segments.first { ms >= it.startEpochMs && ms < it.endEpochMs }

        assertTrue(segAt(start + 30 * 60_000L).boostActive)
        assertEquals(RefreshPolicyDefaults.TIER_RUSH, segAt(start + 30 * 60_000L).tierId)
        assertFalse(segAt(start + 120 * 60_000L).boostActive)
        assertEquals(RefreshPolicyDefaults.TIER_DAY, segAt(start + 120 * 60_000L).tierId)
    }

    /** Scheduled reloads a full day costs, summed from the segments themselves. */
    private fun reloadsInDay(startOfDay: Long): Double =
        RefreshPolicyEvaluator.schedule(policy, startOfDay, spanMs = BudgetLedger.DAY_MS)
            .sumOf { (it.endEpochMs - it.startEpochMs).toDouble() / (it.intervalMinutes * 60_000.0) }

    @Test
    fun aFullWeekdayFitsInsideTheRoutineAllowance() {
        // The schedule has to be affordable BY CONSTRUCTION — the governor is a
        // safety net, not the plan. If a weekday's own schedule exceeds the
        // routine allowance then every device degrades every day, which is
        // exactly the failure that took a Monday morning peak out.
        //
        // Measured from the live device schedule: a weekday costs ~41.8 against
        // an allowance of 43. That is real but SLIM — roughly one reload of
        // headroom — so this test exists to make any tightening of the tiers
        // fail loudly here rather than silently on people's phones.
        val allowance = policy.budget.dailyReloadCeiling - policy.budget.reserveForBoost
        val weekday = reloadsInDay(monday(0, 0))

        assertTrue(
            weekday <= allowance,
            "a weekday schedules $weekday reloads, over the $allowance routine allowance",
        )
        assertTrue(weekday > 30, "sanity: expected a busy weekday, got $weekday")
    }

    @Test
    fun aWeekendDayActuallyUsesItsAllowance() {
        // The weekend used to cost ~24 against an allowance of 43 — 43% of the
        // day's quota unspent every Saturday and Sunday while boards sat
        // staler than they had to. Unused budget is not "safe": it is a board
        // that could have been fresher for free.
        //
        // So this asserts a FLOOR as well as a ceiling. The floor is what would
        // have caught the original under-use, and what stops a future tweak
        // quietly giving it back.
        val allowance = policy.budget.dailyReloadCeiling - policy.budget.reserveForBoost
        val weekend = reloadsInDay(saturday(0, 0))

        assertTrue(
            weekend <= allowance,
            "a weekend day schedules $weekend reloads, over the $allowance allowance",
        )
        assertTrue(
            weekend >= allowance * 0.75,
            "a weekend day uses only $weekend of its $allowance allowance — quota left on the table",
        )
    }

    @Test
    fun scheduleIsBoundedSoTheAppGroupPayloadStaysSmall() {
        // The extension decodes this on every timeline build; an unbounded list
        // would put a growing JSON parse on the widget's hot path.
        val segments = RefreshPolicyEvaluator.schedule(
            policy, monday(0, 0), spanMs = 30 * BudgetLedger.DAY_MS, maxSegments = 48,
        )
        assertTrue(segments.size <= 48, "got ${segments.size}")
    }
}
