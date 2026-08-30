package com.stationly.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.util.BoardTicker

/**
 * The prediction tick contract, for the surfaces that render a FLAT list of
 * departures rather than a grouped board — the dream board and its hero.
 *
 * Every surface that renders "X min" must show the same value at any wall-clock
 * moment, and the only way to guarantee that is to share the math. The math is
 * [BoardTicker], in core, where it is testable and where the Swift widget's own
 * copy points; this file is now only the Compose wrapper around it plus the
 * platform grouping a flat list needs.
 *
 * The station card does NOT come through here. It builds real blocks first (see
 * `StationBoard`) and ticks those, because the bump has to run over the queue a
 * passenger actually experiences — which on a bus hub is a POLE, and poles are
 * indistinguishable once you are looking at `platform` alone.
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
 * Filter+tick step shared by [rememberTickedPredictions] and any non-Compose
 * caller:
 *
 *   1. Drop predictions whose targetEpochMs is more than the policy's departed
 *      grace period in the past.
 *   2. Re-derive each surviving row's eta from targetEpochMs against [nowMs].
 *   3. Per-platform monotonic bump — two trains on the same platform cannot
 *      share a label; if the rounding collides them the later one shifts up
 *      by 1 ("Due, Due, Due" → "Due, 1 min, 2 min"). Cross-platform
 *      collisions are fine.
 *
 * Rows with `targetEpochMs == null` (FCM ISO timestamp didn't parse) are
 * passed through untouched.
 *
 * No retention here: holding a departed row is a decision about how many SLOTS a
 * block has to fill, and a flat list has no blocks and no slots. The surfaces
 * that do have them pass through [BoardTicker.tick] instead.
 */
fun tickPredictions(
    predictions: List<PredictionDisplay>,
    nowMs: Long,
): List<PredictionDisplay> {
    if (predictions.isEmpty()) return predictions
    return predictions
        .groupBy { it.platform }
        .flatMap { (_, group) -> BoardTicker.tickRows(group, nowMs) }
}
