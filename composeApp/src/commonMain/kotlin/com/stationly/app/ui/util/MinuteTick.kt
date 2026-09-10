package com.stationly.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

/**
 * Compose state holding the current wall-clock millis, refreshed at the next
 * wall-clock minute boundary and every minute thereafter. Compose-Multiplatform
 * port of the Android `ui/util/PredictionTicker.kt#rememberMinuteTick`.
 *
 * All surfaces that need "now" for an absolute-time countdown share this so the
 * tick lands on the round minute (matching the user's phone clock) rather than
 * drifting from each composable's start offset.
 */
@Composable
fun rememberMinuteTick(): MutableLongState {
    val nowMs = remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = Clock.System.now().toEpochMilliseconds()
            val msToNextMinute = 60_000L - (now % 60_000L)
            delay(msToNextMinute)
            nowMs.longValue = Clock.System.now().toEpochMilliseconds()
        }
    }
    return nowMs
}
