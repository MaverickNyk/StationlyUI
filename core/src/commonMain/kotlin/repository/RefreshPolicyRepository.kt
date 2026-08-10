package com.stationly.core.repository

import com.stationly.core.model.refresh.BoostState
import com.stationly.core.model.refresh.BudgetLedger
import com.stationly.core.model.refresh.RefreshPolicy
import com.stationly.core.model.refresh.RefreshPolicyDefaults
import com.stationly.core.platform.Platform
import com.stationly.core.refresh.RefreshDecision
import com.stationly.core.refresh.RefreshPolicyEvaluator
import com.stationly.core.refresh.RefreshSegment
import com.stationly.core.service.NetworkModule
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * The device's copy of the refresh schedule, and the state that modifies it.
 *
 * Three things are persisted, and they have deliberately different lifetimes:
 *
 *  - the **policy** is the backend's document, cached so a cold launch and an
 *    offline launch both behave;
 *  - the **boost** is a temporary promotion with an absolute deadline, so it
 *    survives a restart and still expires on time;
 *  - the **ledger** is what has actually been spent against the quota, which is
 *    the only input the client contributes and the reason the governor can tell
 *    a quiet day from a busy one.
 *
 * Shaped after `ThemeRepository` in the Compose module: cached read,
 * fire-and-forget write, every failure swallowed in favour of the last good
 * value. A refresh schedule that throws is strictly worse than one that is a
 * few hours out of date.
 *
 * Lives in `core` rather than in the Compose module because it is not UI and
 * both platforms are meant to consume it — Android can pace its own periodic
 * work off the same document without a second implementation of "rush hour".
 */
object RefreshPolicyRepository {

    private const val KEY_POLICY_JSON = "refresh_policy_json"
    private const val KEY_POLICY_FETCHED_AT = "refresh_policy_fetched_at"
    private const val KEY_BOOST_JSON = "refresh_boost_json"
    private const val KEY_LEDGER_JSON = "refresh_ledger_json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    // ── Policy ────────────────────────────────────────────────────────────

    /**
     * The policy in force: the cached document, or the compiled-in default.
     *
     * A cached copy that fails to decode is treated as absent rather than
     * propagated. That covers the case that actually happens — a backend change
     * that removes a field an older client requires — and it degrades to a
     * working schedule instead of to none.
     */
    suspend fun policy(): RefreshPolicy {
        val raw = Platform.storageManager.loadString(KEY_POLICY_JSON)
            ?: return RefreshPolicyDefaults.POLICY
        return runCatching { json.decodeFromString<RefreshPolicy>(raw) }
            .getOrElse { RefreshPolicyDefaults.POLICY }
            // A document with no tiers cannot schedule anything; treat it as a
            // failed fetch rather than letting it blank the cadence.
            .takeIf { it.tiers.isNotEmpty() }
            ?: RefreshPolicyDefaults.POLICY
    }

    /** Whether the cached policy is older than its own declared TTL. */
    suspend fun isStale(nowMs: Long = now()): Boolean {
        val fetchedAt = Platform.storageManager.loadString(KEY_POLICY_FETCHED_AT)?.toLongOrNull()
            ?: return true
        return nowMs - fetchedAt >= policy().ttlMinutes.toLong() * 60_000L
    }

    /**
     * Fetch and cache the policy. Returns true if the stored document changed,
     * which is the caller's cue to rewrite the schedule the widget reads.
     *
     * Safe to call on every launch: it is one small GET, and the client caches
     * the result rather than the schedule it implies.
     */
    suspend fun refreshFromBackend(): Boolean = runCatching {
        val fresh = NetworkModule.sduiApi.getRefreshPolicy()
        if (fresh.tiers.isEmpty()) return false      // refuse a document that cannot schedule
        val encoded = json.encodeToString(RefreshPolicy.serializer(), fresh)
        val previous = Platform.storageManager.loadString(KEY_POLICY_JSON)
        Platform.storageManager.saveString(KEY_POLICY_JSON, encoded)
        Platform.storageManager.saveString(KEY_POLICY_FETCHED_AT, now().toString())
        encoded != previous
    }.getOrElse { false }

    // ── Boost ─────────────────────────────────────────────────────────────

    /** The stored boost, or null once it has lapsed. Expiry is checked on read
     *  so a missed `boost.stop` can never leave one in force. */
    suspend fun boost(nowMs: Long = now()): BoostState? {
        val raw = Platform.storageManager.loadString(KEY_BOOST_JSON) ?: return null
        val state = runCatching { json.decodeFromString<BoostState>(raw) }.getOrNull() ?: return null
        return state.takeIf { it.isActive(nowMs) }
    }

    /**
     * Begin a boost, capped by the policy's own ceiling regardless of what was
     * asked for — see [RefreshPolicyEvaluator.startBoost].
     */
    suspend fun startBoost(
        tierId: String = "",
        requestedMinutes: Int = -1,
        reason: String = "",
        nowMs: Long = now(),
    ): BoostState {
        val p = policy()
        val state = RefreshPolicyEvaluator.startBoost(
            policy = p,
            nowEpochMs = nowMs,
            tierId = tierId.ifEmpty { p.boost.tierId },
            requestedMinutes = if (requestedMinutes > 0) requestedMinutes else p.boost.maxDurationMinutes,
            reason = reason,
        )
        Platform.storageManager.saveString(
            KEY_BOOST_JSON, json.encodeToString(BoostState.serializer(), state),
        )
        return state
    }

    /** End a boost early. The absolute deadline means this is an optimisation,
     *  never a correctness requirement. */
    suspend fun stopBoost() {
        Platform.storageManager.saveString(KEY_BOOST_JSON, "")
    }

    // ── Budget ledger ─────────────────────────────────────────────────────

    /**
     * What has been spent against the quota in the current rolling window.
     *
     * Prefers the platform's own tally where one exists — on iOS the widget
     * extension is the only process that sees a timeline build, so its count is
     * the real one and the app's would badly under-report. Falls back to the
     * stored ledger elsewhere.
     *
     * Rolling the window is a WRITE, so it is pushed back to whichever store
     * the reading came from; otherwise a window that aged out would be
     * recomputed as fresh on every call while the underlying counter kept
     * climbing, and the governor would think a spent day was an empty one.
     */
    suspend fun ledger(nowMs: Long = now()): BudgetLedger {
        RefreshBudgetStore.read()?.let { platform ->
            val rolled = platform.rolled(nowMs)
            if (rolled.windowStartEpochMs != platform.windowStartEpochMs) {
                RefreshBudgetStore.reset(nowMs)
            }
            return rolled
        }
        val raw = Platform.storageManager.loadString(KEY_LEDGER_JSON)
        val stored = raw?.let { runCatching { json.decodeFromString<BudgetLedger>(it) }.getOrNull() }
        val rolled = (stored ?: BudgetLedger()).rolled(nowMs)
        if (stored != null && rolled.windowStartEpochMs != stored.windowStartEpochMs) {
            Platform.storageManager.saveString(
                KEY_LEDGER_JSON, json.encodeToString(BudgetLedger.serializer(), rolled),
            )
        }
        return rolled
    }

    /**
     * Record refreshes the APP itself triggered (a push-driven rebuild, a
     * background task) on platforms with no separate tally.
     *
     * A no-op where [RefreshBudgetStore] supplies the count, because there the
     * extension is already counting every reload including the ones these
     * cause — adding to it here would double-charge the same work and stretch
     * the schedule for a day that was not actually busy.
     */
    suspend fun recordSpend(count: Int, nowMs: Long = now()) {
        if (count <= 0 || RefreshBudgetStore.read() != null) return
        val current = ledger(nowMs)
        val updated = current.copy(reloadCount = current.reloadCount + count)
        Platform.storageManager.saveString(
            KEY_LEDGER_JSON, json.encodeToString(BudgetLedger.serializer(), updated),
        )
    }

    // ── Decisions ─────────────────────────────────────────────────────────

    suspend fun decideNow(nowMs: Long = now()): RefreshDecision =
        RefreshPolicyEvaluator.decide(policy(), nowMs, boost(nowMs), ledger(nowMs))

    /** The schedule the widget extension reads. See [RefreshSegment]. */
    suspend fun schedule(nowMs: Long = now()): List<RefreshSegment> =
        RefreshPolicyEvaluator.schedule(
            policy = policy(),
            fromEpochMs = nowMs,
            boost = boost(nowMs),
            ledger = ledger(nowMs),
        )

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}
