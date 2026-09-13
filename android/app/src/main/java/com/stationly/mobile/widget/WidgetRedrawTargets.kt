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
     * The hubs whose board rides [pushedLineId].
     *
     * ## The flicker this exists to stop, measured
     * A line-status push used to redraw EVERY placed widget. One tap on refresh
     * fetches every tracked stop, the backend answers with a status push per
     * line, and three widgets times eight lines is 44 renders from one tap,
     * arriving four seconds later as a burst of flashes.
     *
     * A line changing status is only about the widgets whose board actually
     * rides it. Everything else on the home screen is being redrawn to tell it
     * something that is not about it.
     *
     * Resolved THROUGH the selections rather than compared directly, the same
     * way [hubsFedBy] resolves a naptan: a push names something in the data
     * model and a binding names a hub, and on a bus route those are not the
     * same string. Two poles of one stop are two selections under one hub, so a
     * route push has to reach that hub once rather than not at all.
     *
     * Case-insensitive because line ids arrive from the push payload and from
     * the local database, and only one of those is ours.
     */
    fun hubsOn(selections: List<UserSelection>, pushedLineId: String): Set<String> =
        selections
            .filter { it.line.equals(pushedLineId, ignoreCase = true) }
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
