package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.stationly.app.sync.UserStateSync

/**
 * What is actually on the home screen, asked of the system that knows.
 *
 * `Board.widget` is the app's answer to "is this board on a widget?", and until
 * now Android never filled it in: `UserSettings.widgets` was an empty map on
 * every Android device, so the station screen's delete confirmation never
 * mentioned the widget it was about to blank, and the `widget.count` SDUI fact
 * reported zero for a phone covered in them.
 *
 * ## Android can answer this exactly, and iOS cannot
 * `AppWidgetManager.getAppWidgetIds()` returns every placed instance of our
 * provider, and [WidgetBindingStore] says which board each one is for. That is
 * the complete truth, from the system, synchronously. iOS gets nowhere near it:
 * `getCurrentConfigurations` returns `[]` inside a timeline, an app can never
 * read a widget's AppIntent configuration, and reinstalls leave phantom
 * registrations that only a device reboot clears — so its probe is a best-effort
 * stamp written by the widget itself and read back by the app.
 *
 * ## Device-local, re-derived, never synced
 * See [com.stationly.core.model.user.WidgetPlacement]. It is a fact about ONE
 * phone: the same board is on a widget here and not on the tablet, so
 * last-write-wins across devices would make it flap rather than converge. It is
 * cheap to re-derive and is, on every foreground.
 *
 * ## A failed look must not read as "no widgets"
 * `UserSettings.widgetsObserved` replaces the map wholesale, so reporting an
 * empty map is reporting that the user removed every widget. Anything that
 * throws here therefore reports NOTHING — the previous observation stands, which
 * is stale rather than wrong, and the next foreground tries again.
 */
object WidgetPlacementProbe {

    private const val TAG = "WidgetPlacement"

    fun observe(context: Context) {
        try {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, DepartureWidgetProvider::class.java),
            )

            // Dead bindings first, so a widget dragged off the home screen while
            // the app was not running stops being counted the moment it is.
            // `onDeleted` is the normal path; this is the backstop for a
            // launcher replaced, data cleared, or a broadcast dropped while the
            // app was force-stopped.
            WidgetBindingStore.prune(context)

            // `toList()` because `mapNotNull` is not defined on `IntArray`.
            val byBoard = ids.toList().mapNotNull { id ->
                val board = WidgetBindingStore.boundStation(context, id) ?: return@mapNotNull null
                board to family(manager.getAppWidgetOptions(id))
            }.groupBy({ it.first }, { it.second })

            UserStateSync.widgetsObserved(
                byBoard,
                // Every placed instance, INCLUDING any that is not bound to a
                // board yet — the count is "how many widgets are on the home
                // screen", which is what the SDUI `widget.count` fact means, and
                // an unconfigured one is still one of them. Deliberately not the
                // size of the map above, which has already lost that.
                total = ids.size,
            )
            Log.d(TAG, "observed ${ids.size} widget(s) across ${byBoard.size} board(s)")
        } catch (e: Exception) {
            // Reporting nothing, not reporting empty. See the KDoc.
            Log.w(TAG, "Widget placement probe failed; last observation stands", e)
        }
    }

    /**
     * The widget's size as the user sees it — `"4x2"`, home-screen cells.
     *
     * iOS names its sizes (`systemSmall`); Android has no such vocabulary
     * because the grid is the launcher's and varies by device, so the honest
     * answer is the cell span. The conversion is the one from the platform's own
     * sizing guidance: a cell is 70dp minus the 30dp of margin the host adds
     * between them.
     *
     * `OPTION_APPWIDGET_MIN_*` rather than MAX: the minimum is the size the host
     * has actually given this instance in its current orientation, which is what
     * is on screen. The bundle is empty on a host that never reported one, and
     * `"?"` is the honest answer there rather than a made-up 1x1.
     */
    private fun family(options: Bundle?): String {
        val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        if (width <= 0 || height <= 0) return "?"
        return "${cells(width)}x${cells(height)}"
    }

    private fun cells(dp: Int): Int = ((dp + 30) / 70).coerceAtLeast(1)
}
