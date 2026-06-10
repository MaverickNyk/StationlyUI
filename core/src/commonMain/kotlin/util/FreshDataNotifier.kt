package com.stationly.core.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide "fresh data just landed" signal.
 *
 * The iOS FCM path ([com.stationly.core.usecase.ProcessFcmPayloadUseCase]) emits
 * once it has written new predictions to SQLite; a live `SummaryViewModel`
 * collects it and reloads the board **immediately**, instead of waiting up to
 * 30 s for its next poll. Because the FCM push is processed in-process
 * (Swift AppDelegate → `FcmPayloadBridge` → use case) the emit reaches the
 * collector synchronously whenever the app is active — closing the latency gap
 * with Android's near-instant `FreshDataNotifier` push.
 *
 * `replay = 0` + `extraBufferCapacity = 8` so [notifyFreshData] never suspends
 * and late subscribers don't replay a stale ping. This is the shared
 * (iOS/composeApp) analog of the Android app's own `util/FreshDataNotifier`.
 */
object FreshDataNotifier {
    private val _events = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 8)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** Signal that fresh predictions are in SQLite and the board should reload. */
    fun notifyFreshData() {
        _events.tryEmit(Unit)
    }
}
