package com.stationly.mobile.util

import android.content.Context
import android.content.Intent
import com.stationly.mobile.dream.StationlyDreamService
import com.stationly.mobile.widget.DepartureWidgetProvider

/**
 * Single fan-out point for "fresh prediction data is in SQL now".
 *
 * Any code path that writes a new prediction payload to SQL (FCM push,
 * pull-to-refresh on home, refresh button on widget) should call
 * [notify] afterwards. This guarantees every surface picks up the
 * change with identical semantics — the chronometer resets, rows
 * re-derive, the colour goes back to amber — regardless of which
 * trigger caused the fetch.
 *
 * What it does:
 *   1. **SharedPreferences ping**  → [SummaryViewModel] listens for
 *      `predictions_<station>_<line>` key changes and re-reads SQL.
 *      Drives the home Board's prediction list + chronometer anchor.
 *   2. **`ACTION_DREAM_REFRESH` broadcast**  → [DreamHost] bumps its
 *      `refreshTick` `StateFlow`, which fires the `LaunchedEffect`
 *      that re-loads the snapshot from SQL. Drives the cluster +
 *      fullscreen dreams.
 *   3. **Widget redraw**  → [DepartureWidgetProvider.updateFromStorage]
 *      reads SQL and pushes a fresh RemoteViews to every widget
 *      instance.
 *
 * Without this helper each call site had to remember to do these three
 * things by hand, and (per the audit) only the FCM handler did all
 * three — pull-to-refresh skipped the dream, widget refresh skipped
 * BOTH home and dream. This file is the single answer for "where does
 * fresh data get fanned out?"
 */
object FreshDataNotifier {

    /**
     * Notify every surface that fresh data has landed for the given
     * `stationId` + `lineId`. Safe to call from any thread — the inner
     * operations are themselves thread-safe (SharedPreferences edit,
     * sendBroadcast, RemoteViews push).
     *
     * `stationId` + `lineId` identify which board's data changed; only
     * boards whose SharedPrefs key matches will trigger a re-read on
     * the home side. The dream broadcast and widget redraw are
     * board-agnostic — they re-read whatever is in SQL.
     */
    fun notify(context: Context, stationId: String, lineId: String) {
        pingHome(context, stationId, lineId)
        broadcastDream(context)
        redrawWidget(context)
    }

    /**
     * Variant for callers that just persisted a payload not tied to a
     * single (stationId, lineId) — e.g. line-status updates that
     * affect multiple boards, or background syncs that don't carry a
     * station context. Fires the dream broadcast and widget redraw
     * but skips the SharedPrefs ping (which is keyed to a specific
     * board).
     */
    fun notifyAll(context: Context) {
        broadcastDream(context)
        redrawWidget(context)
    }

    private fun pingHome(context: Context, stationId: String, lineId: String) {
        // The home `SummaryViewModel.prefsListener` is keyed by the
        // string "predictions_<station>_<line>". Writing a fresh
        // timestamp under that key triggers the listener, which calls
        // `loadPredictions(selection)` → re-reads SQL → updates the
        // prediction list + the chronometer anchor.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(
                "predictions_${stationId}_${lineId}",
                System.currentTimeMillis().toString(),
            )
            .apply()
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
}
