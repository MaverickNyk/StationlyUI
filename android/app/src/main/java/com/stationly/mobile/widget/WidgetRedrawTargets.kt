package com.stationly.mobile.widget

import com.stationly.core.model.UserSelection

/**
 * Which widgets a push is about — the two steps, without a `Context`.
 *
 * Separated from [DepartureWidgetProvider] so the rule can be tested: the
 * provider needs an `AppWidgetManager`, a `SharedPreferences` and a database
 * before it will answer anything, and the part worth pinning is neither of
 * those. It is one mapping and one filter, and both have a way of being wrong
 * that shows up only on a bus route.
 */
object WidgetRedrawTargets {

    /**
     * The hubs a `Station_{naptan}` push actually feeds.
     *
     * **The push's naptan is not a widget binding**, and this is where that
     * bites. The push names the stop departures were FETCHED from
     * ([UserSelection.station]); a binding holds the HUB the user picked
     * ([UserSelection.groupingId]). On rail they are the same string and every
     * shortcut works. On a bus route they are not: every pole has its own
     * naptan, so Smithwood Close resolves route 39 inbound to `490008805N` and
     * outbound to `490012211N` while the widget is bound to `490012211N` as a
     * stop. Matching the push's id against bindings directly would silently
     * never update a bus widget in one of its two directions — which looks like
     * a board that has simply gone quiet.
     *
     * So the naptan is resolved THROUGH the selections to the hubs it feeds.
     *
     * Case-insensitive because naptan ids arrive from two places — the push
     * payload and the local database — and only one of them is ours.
     */
    fun hubsFedBy(selections: List<UserSelection>, pushedStationId: String): Set<String> =
        selections
            .filter { it.station.equals(pushedStationId, ignoreCase = true) }
            .map { it.groupingId }
            .toSet()

    /**
     * The widget ids showing any of [hubs].
     *
     * @param bindings every placed widget id, mapped to the board it is bound to
     *   or null where it has none. Null is kept rather than filtered by the
     *   caller so that "placed but unbound" stays visible here: such a widget is
     *   never a redraw target, and it must not be, because the station it would
     *   be redrawn with is one nobody chose.
     */
    fun widgetsShowing(bindings: Map<Int, String?>, hubs: Set<String>): List<Int> =
        bindings.filter { (_, bound) -> bound != null && bound in hubs }.keys.sorted()
}
