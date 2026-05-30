package com.stationly.mobile.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.delay

/**
 * Compose state holding the current wall-clock millis, refreshed at the
 * next wall-clock minute boundary and every minute thereafter. All
 * surfaces that need "now" for an absolute-time countdown should share
 * this — so a row labelled "5 min" on the dot-matrix board and the
 * "NEXT DEPARTURE" hero strip flip from 5 → 4 at the SAME instant,
 * matching the user's phone clock.
 *
 * Why not just `delay(60_000L)` from composition start? Two reasons:
 *   - Drift: composing at 12:25:40 means every "minute" lands at xx:40,
 *     not the round minute the user expects.
 *   - Inconsistency: each composable would have its own offset, so the
 *     hero ticks at one moment and the rows tick at another.
 *
 * The minute-aligned cadence solves both. The state is shared via
 * `MutableLongState`; subscribe by reading `.value` in your composable.
 */
@Composable
fun rememberMinuteTick(): MutableLongState {
    val nowMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val msToNextMinute = 60_000L - (now % 60_000L)
            delay(msToNextMinute)
            nowMs.longValue = System.currentTimeMillis()
        }
    }
    return nowMs
}

/**
 * Per-minute self-tick for a list of [PredictionDisplay]. Wires
 * [rememberMinuteTick] to [tickPredictions] so any composable that
 * subscribes gets:
 *   - departed rows filtered out (`targetEpochMs` more than
 *     [DEPARTED_GRACE_MS] in the past)
 *   - surviving rows' [PredictionDisplay.eta] strings re-derived from
 *     [PredictionDisplay.targetEpochMs] against the current wall clock
 *
 * Result: a row labelled "5 min" at 12:25:40 ticks to "4 min" at
 * 12:26:00 (the wall-clock minute boundary, NOT 12:26:40) and disappears
 * shortly after the train passes its target — the next upcoming train
 * automatically slides up into the vacated slot.
 *
 * Rows where [PredictionDisplay.targetEpochMs] is null (FCM ISO
 * timestamp failed to parse) are passed through unchanged, so the
 * receive-time formatted `eta` string still renders.
 */
@Composable
fun rememberTickedPredictions(predictions: List<PredictionDisplay>): List<PredictionDisplay> {
    val nowMs by rememberMinuteTick()
    return remember(predictions, nowMs) { tickPredictions(predictions, nowMs) }
}

/**
 * How long after a train's [PredictionDisplay.targetEpochMs] before we
 * consider it departed and drop it from the visible board.
 *
 * Set to 30s — matches the dwell time of a tube train at a London
 * platform (doors open ~10s, boarding ~15-25s, doors close ~5s).
 * The board keeps showing "Due" through that window, mirroring what
 * the rider sees on the physical platform indicator at the station:
 * the "Next train: Due" line stays up while the train is actually
 * sitting there with its doors open.
 *
 * NOT matched to tfl.gov.uk's web display, which drops trains the
 * moment their target passes — that page shows arrivals, not station
 * dwell. We're matching the platform sign, which is what the rider
 * cross-checks against.
 *
 * Note: the practical drop time is also bounded by
 * `rememberMinuteTick`'s minute cadence — a train whose grace
 * window closes mid-minute lingers until the next tick. Up to ~60s
 * of additional visual lag is unavoidable without ticking
 * sub-minute; combined with the 30s grace, max-visible-after-target
 * is ~90s.
 *
 * Public + const so the widget (which can't call composables) can
 * apply the same threshold for cross-surface consistency.
 */
const val DEPARTED_GRACE_MS: Long = 30_000L

/**
 * Filter+tick step shared by the Compose `rememberTickedPredictions`
 * and the widget's RemoteViews render path. Lives outside the
 * @Composable so the widget code (broadcast-receiver / non-Compose)
 * can call it directly with the same semantics.
 *
 *   1. Drop predictions whose targetEpochMs is more than
 *      [DEPARTED_GRACE_MS] in the past — these trains have visibly
 *      gone, so the board shifts the next upcoming train into the
 *      vacated slot.
 *   2. Re-derive each surviving row's [PredictionDisplay.eta] from
 *      its [PredictionDisplay.targetEpochMs] against [nowMs], so
 *      "5 min" visibly ticks to "4 min" without waiting for FCM.
 *
 * Rows with `targetEpochMs == null` (FCM ISO timestamp didn't parse)
 * are passed through untouched.
 */
fun tickPredictions(
    predictions: List<PredictionDisplay>,
    nowMs: Long,
): List<PredictionDisplay> {
    if (predictions.isEmpty()) return predictions
    val departedBefore = nowMs - DEPARTED_GRACE_MS

    // Step 1: drop departed rows; keep targetEpochMs-null rows untouched so
    // they fall through the bump step too (they have no target to bump from).
    val survivors = predictions.filter { p ->
        val target = p.targetEpochMs ?: return@filter true
        target >= departedBefore
    }
    if (survivors.isEmpty()) return survivors

    // Step 2: per-platform monotonic bump. Two trains on the same platform
    // cannot share a label — if the rounding collides them, the later one
    // shifts up by 1. Bump propagates ("Due, Due, Due" → "Due, 1 min, 2 min").
    // Cross-platform is fine: Platform 1 "1 min" + Platform 2 "1 min" stays.
    //
    // Why here and not in the formatter: the rule is platform-aware and
    // sibling-aware; the canonical formatter operates on one row at a time.
    // tickPredictions already runs per render, has all sibling rows in
    // scope, and is shared across home/widget/dream, so this is the one
    // place we can guarantee identical bumping across surfaces.
    return survivors
        .groupBy { it.platform }
        .flatMap { (_, group) -> bumpPlatformGroup(group, nowMs) }
}

/**
 * Per-platform monotonic bump. Sorts the group by `targetEpochMs`
 * ascending so the earliest train keeps its raw label, then walks the
 * list enforcing `label[i] >= label[i-1] + 1`.
 *
 * Rows with null `targetEpochMs` (defensive ISO-parse fallback) are
 * pinned to the end of the group and passed through unchanged — we can't
 * bump what we can't compute.
 */
private fun bumpPlatformGroup(
    group: List<PredictionDisplay>,
    nowMs: Long,
): List<PredictionDisplay> {
    if (group.size <= 1) {
        // Single row still needs re-derive against current `now`.
        return group.map { p ->
            val target = p.targetEpochMs ?: return@map p
            p.copy(
                eta = StationlyFormatters.formatMinutesRemaining(target, nowMs, p.eta),
                isDue = (target - nowMs) / 1000 < 30,
            )
        }
    }

    val (withTarget, withoutTarget) = group.partition { it.targetEpochMs != null }
    val sorted = withTarget.sortedBy { it.targetEpochMs }
    var prevMin = -1   // "Due" == 0; -1 means nothing taken yet
    val bumped = sorted.map { p ->
        val secs = (p.targetEpochMs!! - nowMs) / 1000
        // Same TfL-style floor rounding as StationlyFormatters.formatMinutesRemaining —
        // duplicated inline only because we need the raw INT minutes to feed the
        // bump comparison; the canonical function returns a String. Keep them
        // in lockstep: secs<60 → 0 (Due), else floor(secs/60).
        val raw = when {
            secs < 60 -> 0   // Due
            else      -> (secs / 60).toInt()
        }
        val effective = maxOf(raw, prevMin + 1)
        prevMin = effective
        val label = if (effective == 0) "Due" else "$effective min"
        p.copy(eta = label, isDue = effective == 0)
    }
    return bumped + withoutTarget
}
