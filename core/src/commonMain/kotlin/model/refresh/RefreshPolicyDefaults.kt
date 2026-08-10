package com.stationly.core.model.refresh

/**
 * The schedule compiled into the app.
 *
 * This is not a placeholder — it is the answer used on first launch (before any
 * network call has completed), whenever the backend is unreachable, and
 * whenever a served policy fails to decode. Those are exactly the moments a
 * user is most likely to be forming an opinion about whether the widget works,
 * so the shipped default is a real schedule rather than a safe-but-useless one.
 *
 * The backend copy at `refreshPolicyService.ts` starts identical to this. When
 * they diverge the served one wins; keeping them in step at rest just means a
 * cold start and a warm one behave the same.
 */
object RefreshPolicyDefaults {

    const val TIER_RUSH = "P1"
    const val TIER_DAY = "P2"
    const val TIER_NIGHT = "P3"
    const val TIER_WEEKEND = "P4"

    /**
     * Weekday peaks. Fifteen minutes is the tightest cadence worth asking for:
     * TfL's own predictions move on roughly that scale, and the background-task
     * layer (which draws on a separate quota) is what actually delivers it —
     * the timeline alone could not.
     */
    private val RUSH = RefreshTier(
        id = TIER_RUSH,
        label = "Rush hour",
        intervalMinutes = 15,
        denseMinutes = 20,
        sparseStepMinutes = 5,
        horizonMinutes = 60,
        backgroundTaskMinutes = 15,
    )

    /**
     * The long middle of the day.
     *
     * 45 rather than the hour it might obviously be: the budget affords it
     * comfortably, and it means an off-peak board is never a full hour stale
     * when someone glances at it. The governor stretches this on its own if a
     * day turns out to be busy, so the optimistic setting costs nothing.
     */
    private val DAY = RefreshTier(
        id = TIER_DAY,
        label = "Off-peak",
        intervalMinutes = 45,
        denseMinutes = 15,
        sparseStepMinutes = 5,
        horizonMinutes = 60,
        backgroundTaskMinutes = 60,
    )

    /**
     * Overnight. `backgroundTaskMinutes = 0` switches the background wake OFF
     * entirely rather than merely slowing it — waking a phone at 03:00 to fetch
     * a board nobody will look at spends battery to change nothing. The timeline
     * still carries the board, and the first morning reload repairs it.
     */
    /**
     * Saturday and Sunday daytime.
     *
     * ## Why the weekend earns its own tier
     * It was folded into [DAY] at 45 minutes, which left a weekend costing ~24
     * scheduled reloads against an allowance of 43 — nearly half the day's quota
     * unspent, every weekend, while the board sat staler than it needed to.
     *
     * A weekend is not a weekday off-peak. There is no commuter peak to reserve
     * budget for, so the whole day can afford to be denser than a weekday
     * midday; but travel is also less time-critical than a 08:15 platform
     * decision, so it does not warrant [RUSH]'s fifteen. Thirty splits that
     * honestly and brings a weekend to ~35 reloads — real use of the allowance
     * with headroom left for a disruption push or a match-day boost.
     *
     * This tier is also the working proof that the tier list is open-ended: it
     * was added backend-first with no client release, which is the property the
     * whole policy document exists to provide.
     */
    private val WEEKEND = RefreshTier(
        id = TIER_WEEKEND,
        label = "Weekend",
        intervalMinutes = 30,
        denseMinutes = 15,
        sparseStepMinutes = 5,
        horizonMinutes = 60,
        backgroundTaskMinutes = 30,
    )

    private val NIGHT = RefreshTier(
        id = TIER_NIGHT,
        label = "Night",
        intervalMinutes = 180,
        denseMinutes = 10,
        sparseStepMinutes = 15,
        horizonMinutes = 45,
        backgroundTaskMinutes = 0,
    )

    private val WEEKDAYS = listOf("MON", "TUE", "WED", "THU", "FRI")
    private val WEEKEND_DAYS = listOf("SAT", "SUN")

    val POLICY = RefreshPolicy(
        version = 1,
        timezone = "Europe/London",
        tiers = listOf(RUSH, DAY, NIGHT, WEEKEND),
        windows = listOf(
            // Priority ascending so the peaks win any overlap with the bands
            // laid under them. Nothing here actually overlaps today; the
            // ordering is what keeps a hand-edited backend policy predictable.
            RefreshWindow(days = emptyList(), from = "23:00", to = "06:30",
                tierId = TIER_NIGHT, priority = 0),
            RefreshWindow(days = WEEKDAYS, from = "09:30", to = "16:00",
                tierId = TIER_DAY, priority = 1),
            RefreshWindow(days = WEEKDAYS, from = "19:30", to = "23:00",
                tierId = TIER_DAY, priority = 1),
            // Butts directly against the night band rather than starting at
            // 07:00. The half-hour gap that used to sit here fell through to
            // the default tier, which meant a weekend morning briefly ran at a
            // different cadence than the hour either side of it for no reason
            // anyone could have named.
            RefreshWindow(days = WEEKEND_DAYS, from = "06:30", to = "23:00",
                tierId = TIER_WEEKEND, priority = 1),
            RefreshWindow(days = WEEKDAYS, from = "06:30", to = "09:30",
                tierId = TIER_RUSH, priority = 2),
            RefreshWindow(days = WEEKDAYS, from = "16:00", to = "19:30",
                tierId = TIER_RUSH, priority = 2),
        ),
        // Weekend early mornings (06:30–07:00) match no window by design and
        // land here, which is the right answer for the handful of minutes
        // involved and keeps the window list short.
        defaultTierId = TIER_DAY,
        budget = RefreshBudget(
            dailyReloadCeiling = 55,
            reserveForBoost = 12,
            minIntervalMinutes = 10,
            // Stated explicitly even though it matches the model default. This
            // is the number that stops a mis-counted ledger becoming a
            // ten-hour blackout, so someone reading the shipped schedule should
            // be able to see it rather than having to know it is inherited.
            maxIntervalMinutes = 120,
        ),
        boost = BoostSpec(tierId = TIER_RUSH, maxDurationMinutes = 90),
        ttlMinutes = 720,
    )
}
