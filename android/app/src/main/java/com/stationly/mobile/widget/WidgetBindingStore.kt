package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * Which station each placed widget is for.
 *
 * ## Why Android does not need — and cannot use — a widget stack
 * iOS solves "several stations" with a WidgetKit stack: one widget frame, many
 * configurations, the user swipes. Android has no such thing (a couple of OEM
 * launchers fake it; the Pixel launcher does not), and reaching for one is
 * looking for the wrong shape. Android's home screen is a free grid and has
 * supported **many instances of one provider** since widgets existed — each
 * with its own `appWidgetId`. Two stations is two widgets, side by side, and
 * that is the whole answer. This file is the map from that id to a station.
 *
 * What Android additionally has, and iOS cannot:
 *
 *  - `AppWidgetManager.getAppWidgetIds()` — the app can enumerate its own placed
 *    widgets. iOS's `getCurrentConfigurations` returns `[]` inside a timeline and
 *    an iOS app can never read a widget's AppIntent configuration at all.
 *  - **Read AND write.** Because this store is ours, a screen inside the app can
 *    re-point any widget at another board without the user touching the home
 *    screen (AV2-5.2). On iOS that is long-press-and-edit or nothing.
 *  - `requestPinAppWidget` — the app can ask the launcher to place a widget
 *    already bound to a chosen station, so "add a widget for this station" can
 *    be a button on the station itself rather than a hunt through the widget
 *    gallery.
 *
 * ## What the value is, and why it is not (station, line, direction)
 * The value is a [com.stationly.core.model.UserSelection.groupingId] — the HUB
 * the user picked, which is what the home screen keys a card on and what a user
 * means by "a station". Keying on the full board triple would bind a widget to
 * one direction of one line, so a user who later edits that board's lines would
 * find their widget silently unbound.
 *
 * ## Never guess
 * A widget whose binding is missing, or whose station is no longer one of the
 * user's boards, renders an honest empty state. It does not fall back to "the
 * first station" — that is [com.stationly.mobile.widget.DepartureWidgetProvider]'s
 * old behaviour and it is the single worst thing a departure board can do: show
 * somebody a train that is not theirs, at a stop they are not standing at, with
 * no indication anything is wrong.
 */
object WidgetBindingStore {

    /** The same file the manual-refresh debounce uses. */
    internal const val PREFS = "widget_prefs"

    /** `binding_<appWidgetId>` → grouping id. */
    internal const val KEY_PREFIX = "binding_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Point [appWidgetId] at the board grouped under [groupingId]. */
    fun bind(context: Context, appWidgetId: Int, groupingId: String) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        prefs(context).edit().putString(KEY_PREFIX + appWidgetId, groupingId).apply()
    }

    /** The grouping id this widget is for, or null if it was never bound. */
    fun boundStation(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(KEY_PREFIX + appWidgetId, null)?.takeIf { it.isNotBlank() }

    /** Every binding this device holds, keyed by `appWidgetId`. */
    fun all(context: Context): Map<Int, String> =
        prefs(context).all.mapNotNull { (key, value) ->
            val id = key.removePrefix(KEY_PREFIX).takeIf { key.startsWith(KEY_PREFIX) }?.toIntOrNull()
            val station = value as? String
            if (id != null && !station.isNullOrBlank()) id to station else null
        }.toMap()

    /**
     * Forget these widgets. Called from `onDeleted`, which Android delivers when
     * the user drags a widget off the home screen.
     */
    fun unbind(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        prefs(context).edit().apply {
            appWidgetIds.forEach { remove(KEY_PREFIX + it) }
        }.apply()
    }

    /**
     * Drop bindings for widgets that no longer exist, whatever the reason.
     *
     * `onDeleted` is the normal path and is usually enough. This is the backstop
     * for the cases where it is not: a launcher replaced or its data cleared, a
     * restore from backup onto a home screen that never had these widgets, or a
     * broadcast dropped while the app was force-stopped. Without it the store
     * accumulates ids forever and the in-app manager (AV2-5.2) lists widgets
     * that are not on any home screen.
     *
     * iOS has the same problem in a worse form — reinstalls left phantom widget
     * registrations that only a device REBOOT cleared, and the only honest count
     * came from what was observed rather than what the system claimed.
     * `getAppWidgetIds()` is more trustworthy than that, so on Android the
     * system's list is the authority and this simply agrees with it.
     */
    fun prune(context: Context) {
        val live = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, DepartureWidgetProvider::class.java))
            .toSet()
        val dead = all(context).keys.filterNot { it in live }
        if (dead.isEmpty()) return
        unbind(context, dead.toIntArray())
    }
}
