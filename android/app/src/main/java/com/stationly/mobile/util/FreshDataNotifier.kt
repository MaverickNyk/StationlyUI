package com.stationly.mobile.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.stationly.core.util.FreshData
import com.stationly.mobile.dream.StationlyDreamService
import com.stationly.mobile.widget.DepartureWidgetProvider
import com.stationly.core.util.FreshDataNotifier as SharedNotifier

/**
 * Single fan-out point for "fresh prediction data is in SQL now".
 *
 * Any code path that writes a new prediction payload to SQL (FCM push,
 * pull-to-refresh on home, refresh button on widget) calls one of the three
 * entry points below afterwards. This guarantees every surface picks up the
 * change with identical semantics — the chronometer resets, rows re-derive, the
 * colour goes back to amber — regardless of which trigger caused the fetch.
 *
 * The three surfaces:
 *
 *   1. **The app**, via the shared [SharedNotifier] flow, which
 *      `SummaryViewModel` collects. It reloads only the boards the event names.
 *   2. **The screensaver**, via the `ACTION_DREAM_REFRESH` broadcast → `DreamHost`
 *      bumps its `refreshTick` `StateFlow` and re-reads the snapshot from SQL.
 *   3. **The home-screen widget**, via `DepartureWidgetProvider.updateFromStorage`.
 *
 * ## What AV2-4.1 changed here, and why it was invisible
 * Surface 1 used to be a **SharedPreferences ping**: this wrote a timestamp
 * under `predictions_<station>_<line>` and v1's `SummaryViewModel` held an
 * `OnSharedPreferenceChangeListener` keyed to it. AV2-3.5 deleted that view
 * model along with the rest of v1's UI, and nothing in the app has registered a
 * preference listener since. The write kept happening, to a key with no reader.
 *
 * Meanwhile the shared `SummaryViewModel` — which IS the app's home screen now —
 * collects `com.stationly.core.util.FreshDataNotifier.events`, and nothing on
 * Android emitted to it. So **an FCM push wrote fresh departures to SQLite and
 * the open board did not move**: it caught up on its own 30-second poll, or when
 * the user backgrounded and returned. No error, no log line, and it looks
 * exactly like a slow network. iOS was unaffected throughout, because iOS
 * reaches the same SQLite through `ProcessPredictionsUseCase`, which emits.
 *
 * That is why the entry points are named per scope now instead of one `notify`.
 * The shared flow carries WHAT changed, and a station push and a line-status
 * push are different answers; collapsing them into one call would have meant
 * emitting [FreshData.All] for everything and reloading every board on the phone
 * on every push, which is the cost the shared notifier was made precise to
 * avoid.
 *
 * ## Ordering
 * The shared emit goes FIRST in each function. The other two surfaces reach into
 * Android — a broadcast and an AppWidgetManager call — and the one the user is
 * looking at should not queue behind them.
 */
object FreshDataNotifier {

    /**
     * New departures for one stop are in SQL.
     *
     * `stationId` is [com.stationly.core.model.UserSelection.station] — the
     * naptan the departures were FETCHED from, which on a bus stop is the pole
     * and not the hub. That is the id the shared collector matches boards by, so
     * passing the hub here would silently reload nothing.
     */
    fun notifyPredictions(context: Context, stationId: String) {
        announce(FreshData.Station(stationId))
        broadcastDream(context)
        // Only the widgets showing this stop. A `Station_{naptan}` push lands
        // every ~30s per tracked station, and redrawing all of them meant a
        // phone with four widgets doing four full RemoteViews rebuilds to
        // change one. See DepartureWidgetProvider.updateForStation, which also
        // explains why the push's naptan is not the widget's binding.
        DepartureWidgetProvider.updateForStation(context, stationId)
    }

    /**
     * A new status for one line is in SQL.
     *
     * Called ONCE per push rather than once per subscribed board: the event
     * names the line, and the collector already knows which of its boards ride
     * it. The old per-selection loop sent N identical pings.
     */
    fun notifyLineStatus(context: Context, lineId: String) {
        announce(FreshData.Line(lineId))
        broadcastDream(context)
        redrawWidget(context)
    }

    /**
     * Something changed and the caller cannot name the scope — a cross-device
     * reconcile that may have rewritten the whole board list, for instance.
     *
     * Honest rather than lazy: [FreshData.All] makes every collector reload
     * everything, which is always CORRECT and merely expensive. A caller that
     * CAN name its scope must use one of the two above.
     */
    fun notifyAll(context: Context) {
        announce(FreshData.All)
        broadcastDream(context)
        redrawWidget(context)
    }

    /**
     * Emit, and say so.
     *
     * The log line is not debug scaffolding left behind — it is the fix for the
     * half of this bug that made it survive a cutover and three device passes.
     * A fan-out that writes to a key nobody reads produces no error, no warning
     * and no trace, and the symptom (a board that updates a bit late) is
     * indistinguishable from a slow network. One line at the only place that
     * knows a push reached the app is the difference between "the board feels
     * sluggish" and a searchable answer.
     */
    private fun announce(what: FreshData) {
        Log.d(TAG, "fresh data → $what")
        SharedNotifier.notifyFreshData(what)
    }

    private fun broadcastDream(context: Context) {
        // `setPackage` so the broadcast only reaches our own
        // dynamically-registered receiver, not other apps.
        context.sendBroadcast(
            Intent(StationlyDreamService.ACTION_DREAM_REFRESH)
                .setPackage(context.packageName)
        )
    }

    private fun redrawWidget(context: Context) {
        // updateFromStorage is the widget's canonical "re-read SQL and
        // render" entry point — same path the watchdog and FCM use.
        DepartureWidgetProvider.updateFromStorage(context)
    }

    private const val TAG = "FreshData"
}
