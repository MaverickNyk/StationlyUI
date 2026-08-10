package com.stationly.core.model.refresh

import kotlinx.serialization.Serializable

/**
 * How often a client should refresh a glanceable surface, authored by the
 * backend and evaluated on the device.
 *
 * ## Why this is a document and not a constant
 * The iOS widget lives inside a hard quota — WidgetKit meters timeline reloads
 * at roughly 40–70 a day and *throttles* a widget that overspends, which on a
 * home screen is indistinguishable from a widget that is broken. The right
 * cadence is therefore not a number anyone can hardcode: it depends on the hour
 * (a 07:40 board is worth ten times a 02:40 one), on the day, and on whether
 * something is happening in the city tonight. All three change faster than an
 * App Store release.
 *
 * So the schedule ships as data. The client's only job is to evaluate it — see
 * [com.stationly.core.refresh.RefreshPolicyEvaluator], which is a pure function
 * over this document and is the single place any of it is interpreted.
 *
 * ## Shared, not iOS-specific
 * Nothing here mentions WidgetKit. Android already gets its freshness from FCM
 * and can adopt the same document to pace its own periodic work; the model and
 * the evaluator sit in `commonMain` so the two platforms can never drift on
 * what "rush hour" means.
 *
 * Every field carries a default so a payload from an older or newer backend
 * still decodes (the Ktor client is configured `ignoreUnknownKeys`). A policy
 * that fails to decode falls back to [RefreshPolicyDefaults.POLICY].
 */
@Serializable
data class RefreshPolicy(
    val id: String = "widget_refresh_policy",
    /**
     * Bumped by the backend on every edit. The client stores the version it
     * holds so a `policy.update` push can say "you are stale" without carrying
     * the document itself.
     */
    val version: Int = 1,
    /** IANA zone the [windows] are written in. Not the device's zone — a
     *  Londoner's rush hour is 07:00 London time whether or not they are. */
    val timezone: String = "Europe/London",
    val tiers: List<RefreshTier> = emptyList(),
    val windows: List<RefreshWindow> = emptyList(),
    /** Used when no window matches, and when a window names a tier that does
     *  not exist — a backend typo degrades to a sane cadence, never to none. */
    val defaultTierId: String = "",
    val budget: RefreshBudget = RefreshBudget(),
    val boost: BoostSpec = BoostSpec(),
    /** How long a cached copy may be trusted before the client refetches. */
    val ttlMinutes: Int = 720,
)

/**
 * One cadence band.
 *
 * ## Identified by an opaque String, deliberately never an enum
 * The whole point of serving this from the backend is that the set of bands can
 * grow — a fourth "engineering works" tier, a fifth for weekends — without
 * shipping a client. An enum would make every unknown id a decode failure and
 * put the tier list back under release control, which is the thing this design
 * exists to escape. Unknown ids resolve to [RefreshPolicy.defaultTierId]
 * instead, so a new tier is inert on old clients rather than fatal.
 */
@Serializable
data class RefreshTier(
    val id: String = "",
    /** Human-readable, for admin tooling and the on-device trace only. */
    val label: String = "",
    /** Target gap between scheduled reloads. The evaluator may *stretch* this
     *  to protect the daily budget, and clamps it to
     *  [RefreshBudget.minIntervalMinutes] so a bad value cannot burn the quota. */
    val intervalMinutes: Int = 60,
    /** Minutes of per-minute timeline entries. A countdown is read over the
     *  next few minutes; past that the number is context, not a decision. */
    val denseMinutes: Int = 15,
    /** Spacing of the cheap tail that keeps a timeline alive to its horizon
     *  without paying to render an entry a minute. */
    val sparseStepMinutes: Int = 5,
    /** Upper bound on how far ahead to build. The board's own last departure
     *  still shortens this — see `DepartureBoardProvider.horizonMinutes`. */
    val horizonMinutes: Int = 60,
    /** Cadence for the platform's own background wake (iOS `BGAppRefreshTask`),
     *  which draws on a budget separate from the widget's. 0 disables it —
     *  correct overnight, when waking to fetch a board nobody will look at
     *  spends battery for nothing. */
    val backgroundTaskMinutes: Int = 0,
)

/**
 * A span of the week that selects a tier.
 *
 * Windows may overlap; [priority] breaks the tie (higher wins), so a
 * "New Year's Eve" window can be laid over the ordinary night tier without
 * rewriting it. Among equal priorities the earlier entry wins, making the list
 * order meaningful and the outcome deterministic.
 */
@Serializable
data class RefreshWindow(
    /** Three-letter days, `"MON"`..`"SUN"`, case-insensitive. Empty = daily. */
    val days: List<String> = emptyList(),
    /** Inclusive "HH:mm" in [RefreshPolicy.timezone]. */
    val from: String = "",
    /** Exclusive "HH:mm". May be earlier than [from] to wrap midnight, which is
     *  how the overnight band is expressed — see [com.stationly.core.util.inTimeWindow]. */
    val to: String = "",
    val tierId: String = "",
    val priority: Int = 0,
)

/**
 * The client's self-imposed ceiling, which exists because Apple's is silent.
 *
 * WidgetKit does not tell an app it is near the limit; it simply stops honouring
 * reloads, and the widget goes quiet with no error anywhere. So the client meters
 * itself against a figure comfortably under the real one and stretches its own
 * interval as the day is spent — see the governor in `RefreshPolicyEvaluator`.
 */
@Serializable
data class RefreshBudget(
    /** Reloads per rolling 24 h. Under Apple's ~40–70 on purpose: the observed
     *  ceiling varies per device and per user habit, and being throttled costs
     *  far more than being slightly conservative. */
    val dailyReloadCeiling: Int = 55,
    /** Held back from ordinary scheduling so a boost or a disruption push
     *  arriving late in the day still has quota to spend. A boost is allowed to
     *  draw on it; the routine schedule is not. */
    val reserveForBoost: Int = 12,
    /** Hard floor on any computed interval, applied last and to every path.
     *  Guards against a backend typo (`intervalMinutes: 1`) draining a device's
     *  quota before anyone notices. */
    val minIntervalMinutes: Int = 10,
    /**
     * Hard CEILING on any computed interval, however spent the budget looks.
     *
     * The governor used to back off to the whole remaining 24-hour window when
     * the ledger read exhausted. Measured on device: a polluted ledger produced
     * `next=627m`, and the widget went untouched through an entire Monday
     * morning peak — a self-inflicted blackout far worse than the thing being
     * avoided.
     *
     * That trade is wrong in both directions. Overspending has a GRACEFUL
     * failure — Apple simply stops honouring reloads, and the timeline's own
     * tick layer keeps the board counting down meanwhile. Under-refreshing has
     * an ABRUPT one: a board frozen for hours with no way for the user to know
     * it is not live. So when the two are in tension, err toward asking.
     *
     * Two hours: longer than every tier's normal interval (so it never binds in
     * ordinary operation), short enough that the worst case is a board a
     * commute stale rather than a day.
     */
    val maxIntervalMinutes: Int = 120,
)

/** Which tier a boost switches to, and the longest it may last. */
@Serializable
data class BoostSpec(
    val tierId: String = "",
    /**
     * The self-expiry that makes boost safe to use.
     *
     * A boost is started and stopped by push, and a push is not guaranteed to
     * arrive — so a device that misses `boost.stop` must not stay in rush-hour
     * cadence forever. The client stamps an absolute deadline at start and
     * evaluates against it, so expiry needs no second push, no timer, and no
     * running process. See [BoostState].
     */
    val maxDurationMinutes: Int = 90,
)

/**
 * An in-force boost, persisted on the device.
 *
 * [expiresAtEpochMs] is absolute and is written when the boost STARTS. That is
 * what makes the 90-minute ceiling hold even if the device is offline, asleep,
 * or never receives the stop push: the boost is over when the clock says so,
 * and every evaluation re-checks it.
 */
@Serializable
data class BoostState(
    val tierId: String = "",
    val startedAtEpochMs: Long = 0L,
    val expiresAtEpochMs: Long = 0L,
    /** Free-text origin ("match:wembley", "admin") for the on-device trace. */
    val reason: String = "",
) {
    fun isActive(nowEpochMs: Long): Boolean =
        tierId.isNotEmpty() && nowEpochMs < expiresAtEpochMs
}

/**
 * Rolling 24-hour tally of scheduled reloads actually spent.
 *
 * The window is anchored at [windowStartEpochMs] and rolls forward as a block
 * rather than sliding continuously: a true sliding window needs every
 * timestamp retained, and the extra precision buys nothing when the figure it
 * feeds is already deliberately conservative.
 */
@Serializable
data class BudgetLedger(
    val windowStartEpochMs: Long = 0L,
    val reloadCount: Int = 0,
) {
    /** The ledger as of [nowEpochMs], starting a fresh window when the last
     *  one has aged out (or was never opened). */
    fun rolled(nowEpochMs: Long): BudgetLedger =
        if (windowStartEpochMs <= 0L || nowEpochMs - windowStartEpochMs >= DAY_MS)
            BudgetLedger(windowStartEpochMs = nowEpochMs, reloadCount = 0)
        else this

    /** Records one reload, rolling the window first if it is due. */
    fun recording(nowEpochMs: Long): BudgetLedger =
        rolled(nowEpochMs).let { it.copy(reloadCount = it.reloadCount + 1) }

    /** Millis until this window rolls; never negative. */
    fun remainingWindowMs(nowEpochMs: Long): Long =
        (windowStartEpochMs + DAY_MS - nowEpochMs).coerceAtLeast(0L)

    companion object { const val DAY_MS: Long = 24L * 60L * 60L * 1000L }
}
