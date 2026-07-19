package com.stationly.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.util.StationlyFormatters

/**
 * The prediction tick contract — Compose-Multiplatform port of Android
 * `ui/util/PredictionTicker.kt`, sharing [rememberMinuteTick] from
 * MinuteTick.kt. Every surface that renders "X min" (home board rows, home
 * hero, dream board rows, dream hero, widget) must show the same value at any
 * wall-clock moment; the only way to guarantee that is to share the math.
 */

/**
 * Per-minute self-tick for a list of [PredictionDisplay]. Subscribing
 * composables get departed rows filtered out and surviving rows' `eta`
 * re-derived from `targetEpochMs` at every wall-clock minute boundary.
 */
@Composable
fun rememberTickedPredictions(predictions: List<PredictionDisplay>): List<PredictionDisplay> {
    val nowMs by rememberMinuteTick()
    return remember(predictions, nowMs) { tickPredictions(predictions, nowMs) }
}

/**
 * How long after a train's `targetEpochMs` before we consider it departed
 * and drop it from the visible board.
 *
 * 30s — matches the dwell time of a tube train at a London platform, i.e.
 * the physical platform indicator's behaviour (the "Due" line stays up
 * while the train sits with doors open). MUST stay in lockstep with the
 * Android constant AND the Swift widget's `WidgetData.ticked(at:)` grace.
 */
const val DEPARTED_GRACE_MS: Long = 30_000L

/**
 * Filter+tick step shared by [rememberTickedPredictions] and any non-Compose
 * caller:
 *
 *   1. Drop predictions whose targetEpochMs is more than [DEPARTED_GRACE_MS]
 *      in the past.
 *   2. Re-derive each surviving row's eta from targetEpochMs against [nowMs].
 *   3. Per-platform monotonic bump — two trains on the same platform cannot
 *      share a label; if the rounding collides them the later one shifts up
 *      by 1 ("Due, Due, Due" → "Due, 1 min, 2 min"). Cross-platform
 *      collisions are fine.
 *
 * Rows with `targetEpochMs == null` (FCM ISO timestamp didn't parse) are
 * passed through untouched.
 */
fun tickPredictions(
    predictions: List<PredictionDisplay>,
    nowMs: Long,
): List<PredictionDisplay> {
    if (predictions.isEmpty()) return predictions
    val departedBefore = nowMs - DEPARTED_GRACE_MS

    val survivors = predictions.filter { p ->
        val target = p.targetEpochMs ?: return@filter true
        target >= departedBefore
    }
    if (survivors.isEmpty()) return survivors

    return survivors
        .groupBy { it.platform }
        .flatMap { (_, group) -> bumpPlatformGroup(group, nowMs) }
}

/**
 * Per-platform monotonic bump. Sorts the group by `targetEpochMs` ascending
 * so the earliest train keeps its raw label, then walks the list enforcing
 * `label[i] >= label[i-1] + 1`. Null-target rows are pinned to the end of
 * the group and passed through unchanged.
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
        // Same TfL-style floor rounding as formatMinutesRemaining — inline
        // only because the bump comparison needs the raw INT minutes.
        // Keep in lockstep: secs<60 → 0 (Due), else floor(secs/60).
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
