package com.stationly.core.refresh

import com.stationly.core.model.refresh.BoostState
import com.stationly.core.model.refresh.BudgetLedger
import com.stationly.core.model.refresh.RefreshPolicy
import com.stationly.core.model.refresh.RefreshTier
import com.stationly.core.util.inTimeWindow
import com.stationly.core.util.parseHHmmOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil

/**
 * What a client should do right now, derived from the policy.
 *
 * Everything a refresh surface needs is on this one object, so a caller never
 * re-reads the policy and never re-derives a rule. The iOS widget extension in
 * particular cannot run Kotlin at all — it reads a serialized copy of this out
 * of the App Group — which is why the decision is a flat bag of resolved
 * numbers rather than a handle onto the policy.
 */
data class RefreshDecision(
    val tierId: String,
    val tierLabel: String,
    /** Post-governor. This is the number to schedule against, not the tier's. */
    val intervalMinutes: Int,
    val denseMinutes: Int,
    val sparseStepMinutes: Int,
    val horizonMinutes: Int,
    /** 0 means "do not schedule a background wake in this tier". */
    val backgroundTaskMinutes: Int,
    val nextRefreshEpochMs: Long,
    val boostActive: Boolean,
    /** True when the governor stretched the interval to protect the budget.
     *  Surfaced only in the on-device trace — a user should never be told the
     *  widget is economising, but an engineer reading a trace must be. */
    val degraded: Boolean,
    /** Reloads spent in the current rolling window, for the trace. */
    val spentToday: Int,
)

/**
 * One stretch of wall-clock during which the decision does not change.
 *
 * ## Why a schedule exists at all, rather than just a current decision
 * The iOS widget extension is a separate process that cannot run Kotlin, so it
 * cannot evaluate the policy — it can only read what the app last wrote. A
 * single "current decision" is therefore a trap: a phone whose app has not been
 * opened since yesterday evening would still be holding the 18:00 decision, and
 * would happily apply rush-hour cadence at 03:00. The staleness is invisible
 * and the cost is the user's battery.
 *
 * So the app writes a SEGMENTED schedule covering the next couple of days, and
 * the extension's only job is to find the segment containing its own `now`.
 * Interpretation stays in Kotlin, on one side, tested; the extension does a
 * lookup.
 */
data class RefreshSegment(
    val startEpochMs: Long,
    /** Exclusive. */
    val endEpochMs: Long,
    val tierId: String,
    val intervalMinutes: Int,
    val denseMinutes: Int,
    val sparseStepMinutes: Int,
    val horizonMinutes: Int,
    val backgroundTaskMinutes: Int,
    val boostActive: Boolean,
)

/**
 * Turns a [RefreshPolicy] into a [RefreshDecision]. The only interpreter of the
 * policy document anywhere in the codebase.
 *
 * Pure and total: no clock, no I/O, no platform types, no throwing. `nowEpochMs`
 * and the persisted state are arguments, which is what makes the whole schedule
 * — window matching across midnight, boost expiry, budget degradation —
 * testable without a device, and what lets iOS and Android reach identical
 * answers from the same document.
 */
object RefreshPolicyEvaluator {

    /**
     * Granularity of the forward simulation in [plannedReloads]. Fifteen
     * minutes is fine enough to place a window boundary accurately and coarse
     * enough that a full day is 96 trivial iterations.
     */
    private const val PLAN_STEP_MS = 15L * 60L * 1000L

    fun decide(
        policy: RefreshPolicy,
        nowEpochMs: Long,
        boost: BoostState? = null,
        ledger: BudgetLedger = BudgetLedger(),
    ): RefreshDecision = decide(Compiled(policy), nowEpochMs, boost, ledger)

    /**
     * The next [spanMs] of decisions, cut at every point one would change.
     *
     * Written to the App Group for the widget extension to look up — see
     * [RefreshSegment] for why the extension gets a schedule rather than a
     * single decision.
     *
     * The budget governor is evaluated per segment against the ledger as it
     * stands NOW, because future spend is unknowable. Later segments therefore
     * understate what will have been spent by the time they apply, which errs
     * toward the optimistic; it self-corrects the moment the app runs again and
     * rewrites the schedule.
     */
    fun schedule(
        policy: RefreshPolicy,
        fromEpochMs: Long,
        spanMs: Long = 2 * BudgetLedger.DAY_MS,
        boost: BoostState? = null,
        ledger: BudgetLedger = BudgetLedger(),
        maxSegments: Int = 48,
    ): List<RefreshSegment> {
        if (spanMs <= 0L) return emptyList()
        // Compiled ONCE for the whole schedule rather than per segment. Each
        // segment runs a 96-step forward simulation, and each of those steps
        // used to re-parse every window's "HH:mm" strings — roughly 110k string
        // splits per publish, growing with the number of windows. See [Compiled].
        val compiled = Compiled(policy)
        val end = fromEpochMs + spanMs
        val out = mutableListOf<RefreshSegment>()

        var t = fromEpochMs
        while (t < end && out.size < maxSegments) {
            val d = decide(compiled, t, boost, ledger)
            // Every reason the decision could change: a window edge, the boost
            // lapsing, or the end of the span we were asked for.
            val boundary = compiled.nextBoundaryMs(t) ?: end
            val boostEnd = boost?.expiresAtEpochMs?.takeIf { it > t } ?: Long.MAX_VALUE
            val segmentEnd = minOf(boundary, boostEnd, end)

            out += RefreshSegment(
                startEpochMs = t,
                endEpochMs = segmentEnd,
                tierId = d.tierId,
                intervalMinutes = d.intervalMinutes,
                denseMinutes = d.denseMinutes,
                sparseStepMinutes = d.sparseStepMinutes,
                horizonMinutes = d.horizonMinutes,
                backgroundTaskMinutes = d.backgroundTaskMinutes,
                boostActive = d.boostActive,
            )
            // Strictly increasing: `nextBoundaryMs` is exclusive of `t`, the
            // boost end is filtered to `> t`, and `end > t` holds the loop.
            t = segmentEnd
        }
        return out
    }

    /**
     * Stamp a boost's absolute deadline at the moment it starts.
     *
     * `requestedMinutes` is what the push asked for; the policy's
     * [com.stationly.core.model.refresh.BoostSpec.maxDurationMinutes] is the
     * ceiling and always wins. A server that asks for eight hours gets ninety
     * minutes, because the device — not the sender — is the thing that has to
     * live with the battery cost.
     */
    fun startBoost(
        policy: RefreshPolicy,
        nowEpochMs: Long,
        tierId: String = policy.boost.tierId,
        requestedMinutes: Int = policy.boost.maxDurationMinutes,
        reason: String = "",
    ): BoostState {
        val capped = requestedMinutes.coerceIn(1, policy.boost.maxDurationMinutes.coerceAtLeast(1))
        return BoostState(
            tierId = tierId.ifEmpty { policy.boost.tierId },
            startedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = nowEpochMs + capped * 60_000L,
            reason = reason,
        )
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun decide(
        c: Compiled,
        nowEpochMs: Long,
        boost: BoostState?,
        ledger: BudgetLedger,
    ): RefreshDecision {
        val policy = c.policy
        val rolled = ledger.rolled(nowEpochMs)

        val boostActive = boost?.isActive(nowEpochMs) == true
        val tier = if (boostActive) {
            // The boost names its own tier so a push can promote to something
            // other than the policy's usual choice; the policy's boost tier is
            // the fallback, and the scheduled tier the last resort.
            c.tierById(boost!!.tierId)
                ?: c.tierById(policy.boost.tierId)
                ?: c.tierAt(nowEpochMs)
        } else {
            c.tierAt(nowEpochMs)
        }

        val floorMinutes = c.floorMinutes
        val target = tier.intervalMinutes.coerceAtLeast(floorMinutes)

        // ── Budget governor ──
        //
        // A boost may draw on the reserve; the routine schedule may not. That
        // is the entire purpose of holding some back — a disruption at 21:00
        // is worth more than the four ordinary reloads it displaces.
        val ceiling = if (boostActive) policy.budget.dailyReloadCeiling
        else policy.budget.dailyReloadCeiling - policy.budget.reserveForBoost
        val remaining = ceiling - rolled.reloadCount
        val msLeft = rolled.remainingWindowMs(nowEpochMs)

        // What the REST OF THE SCHEDULE actually costs, simulated forward
        // through the policy's own windows.
        //
        // The obvious governor — spread the remaining quota evenly over the
        // remaining hours — is wrong in a way that quietly guts the top tier: it
        // charges rush hour as though rush hour ran all day, so a P1 target of
        // 15 minutes gets "corrected" to 33 the moment it is asked for, every
        // day, budget spent or not. Simulating tells the truth instead, so
        // nothing is stretched until something ELSE (pushes, a boost, a long
        // spell in a dense tier) has genuinely eaten the day's quota.
        val planned = c.plannedReloads(nowEpochMs, msLeft)
        val stretch = when {
            remaining <= 0 -> Double.MAX_VALUE   // exhausted: back off hard
            planned <= remaining -> 1.0          // affordable as scheduled
            else -> planned / remaining
        }

        val governed = if (stretch == 1.0) target else {
            val stretched = if (stretch == Double.MAX_VALUE) Int.MAX_VALUE
            else ceil(target * stretch).toInt()
            stretched.coerceAtLeast(target)
        }
        // Floor, then CEILING. The ceiling is what stops an exhausted — or
        // simply mis-counted — ledger from turning into a blackout: this used
        // to back off to the whole remaining window, which on a real device
        // produced a 627-minute interval and a board that sat untouched through
        // a Monday morning peak. See [RefreshBudget.maxIntervalMinutes].
        //
        // Never below the tier's own ask either: a slow tier (night at 180m)
        // must not be sped UP by a cap that exists only to bound runaway
        // back-off.
        val intervalCeiling = maxOf(policy.budget.maxIntervalMinutes, target)
            .coerceAtLeast(floorMinutes)
        val interval = governed.coerceIn(floorMinutes, intervalCeiling)

        // ── When to come back ──
        //
        // The interval is the usual answer, but two things can legitimately
        // pull it in: a window boundary (so a tier change lands promptly rather
        // than up to one stale interval late) and a boost's expiry (so the
        // cadence drops back the moment it should). The floor then guarantees a
        // boundary seconds away cannot trigger a rapid-fire reload — being a
        // few minutes late into a tier is free; a reload storm at every
        // boundary is not.
        val byInterval = nowEpochMs + interval * 60_000L
        val boundary = c.nextBoundaryMs(nowEpochMs)
        val boostEnd = boost?.expiresAtEpochMs?.takeIf { boostActive && it > nowEpochMs }
        val soonest = listOfNotNull(byInterval, boundary, boostEnd).min()
        val next = maxOf(soonest, nowEpochMs + floorMinutes * 60_000L)

        return RefreshDecision(
            tierId = tier.id,
            tierLabel = tier.label,
            intervalMinutes = interval,
            denseMinutes = tier.denseMinutes.coerceAtLeast(1),
            sparseStepMinutes = tier.sparseStepMinutes.coerceAtLeast(1),
            horizonMinutes = tier.horizonMinutes.coerceAtLeast(tier.denseMinutes),
            backgroundTaskMinutes = tier.backgroundTaskMinutes.coerceAtLeast(0),
            nextRefreshEpochMs = next,
            boostActive = boostActive,
            degraded = interval > target,
            spentToday = rolled.reloadCount,
        )
    }

    /**
     * A policy with everything parsed, resolved and de-duplicated once.
     *
     * ## Why this exists
     * The hot path is [plannedReloads], which walks the next 24 hours in
     * 15-minute steps asking "which tier applies here" — 96 lookups per
     * decision, and [schedule] makes up to 48 decisions. Done against the raw
     * document, every one of those lookups re-split every window's `"HH:mm"`
     * strings and re-scanned the tier list: on the order of 110,000 string
     * parses per publish, scaling with the number of windows. Compiling once
     * turns each lookup into integer comparisons over a prepared list.
     *
     * Every fallible step happens HERE, exactly once, and produces a structure
     * that cannot fail later: an unparseable time or a window naming a tier
     * that does not exist is dropped at compile time, which is also what makes
     * an unknown tier from a newer backend inert rather than fatal.
     */
    private class Compiled(val policy: RefreshPolicy) {

        /** A window that parsed and resolved. Malformed ones never get here. */
        class Window(
            val from: LocalTime,
            val to: LocalTime,
            /** `from > to`, i.e. the window runs through midnight. */
            val wraps: Boolean,
            /** Upper-case three-letter days; empty means every day. */
            val days: Set<String>,
            val tier: RefreshTier,
            val priority: Int,
        )

        /** Mistyped zone falls back rather than throwing — a bad SDUI string
         *  must not take the widget down with it. */
        val zone: TimeZone =
            runCatching { TimeZone.of(policy.timezone) }.getOrElse { TimeZone.UTC }

        private val tiersById: Map<String, RefreshTier> =
            policy.tiers.associateBy { it.id }

        val windows: List<Window> = policy.windows.mapNotNull { w ->
            val from = parseHHmmOrNull(w.from) ?: return@mapNotNull null
            val to = parseHHmmOrNull(w.to) ?: return@mapNotNull null
            val tier = tiersById[w.tierId] ?: return@mapNotNull null
            Window(
                from = from,
                to = to,
                wraps = from > to,
                days = w.days.mapNotNullTo(mutableSetOf()) {
                    it.trim().uppercase().takeIf { d -> d.isNotEmpty() }
                },
                tier = tier,
                priority = w.priority,
            )
        }

        /** Every distinct edge, precomputed — [nextBoundaryMs] is called once
         *  per decision and used to re-derive this list every time. */
        private val boundaryTimes: List<LocalTime> =
            windows.flatMap { listOf(it.from, it.to) }.distinct()

        private val fallbackTier: RefreshTier =
            tiersById[policy.defaultTierId]
                ?: policy.tiers.firstOrNull()
                ?: RefreshTier(id = "fallback")

        val floorMinutes: Int = policy.budget.minIntervalMinutes.coerceAtLeast(1)

        fun tierById(id: String): RefreshTier? = if (id.isEmpty()) null else tiersById[id]

        /**
         * The tier the WINDOWS select at [atMs], ignoring any boost.
         *
         * Highest priority wins; ties go to the earlier list entry, so the
         * outcome is deterministic and the list order is meaningful.
         */
        fun tierAt(atMs: Long): RefreshTier {
            val local = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(zone)
            var best: Window? = null
            for (w in windows) {
                if (!matches(w, local)) continue
                // Strictly greater, so the FIRST window at a given priority wins.
                if (best == null || w.priority > best.priority) best = w
            }
            return best?.tier ?: fallbackTier
        }

        private fun matches(w: Window, local: LocalDateTime): Boolean {
            if (!inTimeWindow(local.time, w.from, w.to)) return false
            if (w.days.isEmpty()) return true

            // A window that wraps midnight and which we are inside of BEFORE
            // `to` actually began YESTERDAY, so it is yesterday's weekday that
            // has to match. Without this, a "FRI 23:00–06:30" window silently
            // stops applying at midnight — the half people are awake for.
            val date =
                if (w.wraps && local.time < w.to) local.date.minus(1, DateTimeUnit.DAY)
                else local.date
            return date.dayOfWeek.name.take(3) in w.days
        }

        /**
         * Reloads the rest of the rolling window will cost if the schedule runs
         * as written. Fractional on purpose — a 90-minute stretch of a
         * 45-minute tier is two reloads, and rounding each step would
         * accumulate badly over a day.
         */
        fun plannedReloads(fromMs: Long, spanMs: Long): Double {
            if (spanMs <= 0L) return 0.0
            var total = 0.0
            var t = fromMs
            val end = fromMs + spanMs
            while (t < end) {
                val step = minOf(PLAN_STEP_MS, end - t)
                val interval = tierAt(t).intervalMinutes.coerceAtLeast(floorMinutes).toLong()
                total += step.toDouble() / (interval * 60_000L).toDouble()
                t += PLAN_STEP_MS
            }
            return total
        }

        /**
         * The next instant any window starts or ends, or null if the policy has
         * no usable boundaries.
         *
         * Projects each distinct edge onto today and tomorrow rather than
         * stepping forward in time — exact, and cheap regardless of how far
         * away the answer is. Two days is always enough: some boundary recurs
         * daily, so the nearest future one cannot be more than 24 hours out.
         */
        fun nextBoundaryMs(nowMs: Long): Long? {
            if (boundaryTimes.isEmpty()) return null
            val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
            var best: Long? = null
            for (offset in 0..1) {
                val date = today.plus(offset, DateTimeUnit.DAY)
                for (time in boundaryTimes) {
                    val ms = LocalDateTime(date, time).toInstant(zone).toEpochMilliseconds()
                    if (ms > nowMs && (best == null || ms < best)) best = ms
                }
            }
            return best
        }
    }
}
