package com.stationly.core.util

import com.stationly.core.config.BoardPolicy
import com.stationly.core.config.BoardPolicyStore

/**
 * Single source of truth for the "X ago" chronometer colour on every
 * Stationly surface (home Board, dream Board, home-screen widget).
 *
 * The chronometer carries the freshness signal — amber while data is
 * current, dimming to grey once it's a minute stale, going red when
 * it's been three minutes since the last successful sync. All three
 * surfaces read the same thresholds and the same palette here so the
 * user can glance at any of them and trust the colour to mean the
 * same thing.
 *
 * Anchored to `lastUpdatedMs` (the SQL row's persistence timestamp,
 * i.e. when the FCM / REST payload was saved) — NOT to render time.
 * This is critical for the widget: when its watchdog re-renders an
 * already-stale board, the colour must reflect the true data age, not
 * reset to amber.
 *
 *   ageMs < FRESH_MS  → amber (data is current)
 *   FRESH_MS ≤ ageMs < STALE_MS  → grey (some staleness, probably fine)
 *   ageMs ≥ STALE_MS  → red (likely a problem; sync may be broken)
 */
object StaleColor {
    /**
     * Amber → grey transition threshold (data age in ms).
     *
     * The compiled default. [BoardPolicy.freshMs] is what is actually in force
     * and the backend can retune it — and it is deliberately the same number as
     * [BoardPolicy.retentionMinAgeMs], because the footer going grey and the
     * rows going "Gone" are one statement about one payload. `BoardPolicyStore`
     * keeps the pair from drifting apart when only one of them is served.
     */
    const val FRESH_MS: Long = 60_000L
    /** Grey → red transition threshold (data age in ms). The compiled default. */
    const val STALE_MS: Long = 180_000L

    /** Standard TfL amber — "data is fresh". */
    const val AMBER: Int = 0xFFFFB300.toInt()
    /** Soft grey — "showing its age, but probably still useful". */
    const val GREY: Int  = 0xFF888888.toInt()
    /** Alert red — "this hasn't refreshed in a while, treat with care". */
    const val RED: Int   = 0xFFFF3B30.toInt()

    /**
     * Pick the right colour for a chronometer given how old the data is.
     * The age is clamped to ≥ 0 so a clock-skew "future" lastUpdatedMs
     * doesn't render as red.
     */
    fun colorForAge(ageMs: Long, policy: BoardPolicy = BoardPolicyStore.current): Int {
        val clamped = if (ageMs < 0L) 0L else ageMs
        return when {
            clamped < policy.freshMs -> AMBER
            clamped < policy.staleMs -> GREY
            else                     -> RED
        }
    }

    /**
     * The next wall-clock millis at which the colour will transition,
     * given the data's `lastUpdatedMs` and the current `nowMs`. Returns
     * null once we're past the red threshold — at that point no more
     * transitions are pending.
     *
     * The widget uses this to schedule its colour-fade `AlarmManager`
     * alarms; Compose surfaces don't need it because they read the
     * minute-tick state directly and recompute every minute.
     */
    fun nextTransitionMs(
        lastUpdatedMs: Long,
        nowMs: Long,
        policy: BoardPolicy = BoardPolicyStore.current,
    ): Long? {
        val toGrey = lastUpdatedMs + policy.freshMs
        val toRed  = lastUpdatedMs + policy.staleMs
        return when {
            nowMs < toGrey -> toGrey
            nowMs < toRed  -> toRed
            else           -> null
        }
    }
}
