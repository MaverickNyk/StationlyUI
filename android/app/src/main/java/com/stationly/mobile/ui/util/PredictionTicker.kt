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
 * consider it departed and drop it from the visible board. 60s is a
 * Londoner-friendly grace window: the "Due" row stays on screen long
 * enough for the user to act (boarding takes ~30s, doors close ~30s
 * later), then disappears so the next upcoming train moves up.
 *
 * Public + const so the widget (which can't call composables) can
 * apply the same threshold for cross-surface consistency.
 */
const val DEPARTED_GRACE_MS: Long = 60_000L

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
    return predictions.mapNotNull { p ->
        val target = p.targetEpochMs ?: return@mapNotNull p
        if (target < departedBefore) return@mapNotNull null
        p.copy(
            eta = StationlyFormatters.formatMinutesRemaining(
                targetEpochMs = target,
                nowMs = nowMs,
                staleFallback = p.eta,
            ),
            isDue = (target - nowMs) / 1000 < 30,
        )
    }
}
