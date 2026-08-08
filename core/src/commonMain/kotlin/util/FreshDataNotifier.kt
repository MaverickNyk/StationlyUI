package com.stationly.core.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What just landed in SQLite.
 *
 * The signal used to be a bare `Unit`, which meant every collector had to
 * assume the worst and reload everything it owned. That was affordable when a
 * board was one station and the only trigger was an FCM push; it is not
 * affordable now. iOS runs a live WebSocket that emits per station every few
 * seconds, and a phone tracking seven stations in two directions was doing
 * fourteen SQL reads plus fourteen board re-derivations **per frame** — nearly
 * all of them re-reading rows that had not changed.
 *
 * Android has always been targeted: it pings SharedPreferences keys shaped
 * `predictions_<station>_<line>` and the collector keys off them. This brings
 * the shared notifier to the same precision.
 */
sealed interface FreshData {
    /** New predictions for one stop, by naptan. */
    data class Station(val stationId: String) : FreshData

    /** A new status for one line, by canonical line id. */
    data class Line(val lineId: String) : FreshData

    /**
     * Something changed and the emitter could not say what.
     *
     * Retained as the honest fallback rather than removed: a collector seeing
     * this reloads everything, which is exactly the old behaviour and is always
     * CORRECT — just expensive. Any new emitter that cannot name its scope
     * should use this rather than guess at one.
     */
    object All : FreshData
}

/**
 * App-wide "fresh data just landed" signal.
 *
 * The iOS FCM path ([com.stationly.core.usecase.ProcessPredictionsUseCase]) emits
 * once it has written new predictions to SQLite; a live `SummaryViewModel`
 * collects it and reloads the board **immediately**, instead of waiting up to
 * 30 s for its next poll. Because the FCM push is processed in-process
 * (Swift AppDelegate → `PushPayloadBridge` → use case) the emit reaches the
 * collector synchronously whenever the app is active — closing the latency gap
 * with Android's near-instant `FreshDataNotifier` push. The same path serves
 * the live stream, whose frames are handed to the identical use case.
 *
 * `replay = 0` + `extraBufferCapacity = 8` so [notifyFreshData] never suspends
 * and late subscribers don't replay a stale ping. This is the shared
 * (iOS/composeApp) analog of the Android app's own `util/FreshDataNotifier`.
 */
object FreshDataNotifier {
    private val _events = MutableSharedFlow<FreshData>(replay = 0, extraBufferCapacity = 8)
    val events: SharedFlow<FreshData> = _events.asSharedFlow()

    /**
     * Signal that fresh data is in SQLite and the affected boards should reload.
     *
     * Name the scope wherever it is known — see [FreshData]. The default is the
     * whole-app reload, which is correct for every caller and wasteful for most
     * of them.
     */
    fun notifyFreshData(what: FreshData = FreshData.All) {
        _events.tryEmit(what)
    }
}
