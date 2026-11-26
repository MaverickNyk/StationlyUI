package com.stationly.mobile.widget

import com.stationly.core.model.UserSelection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A push for one station must not repaint a widget showing another.
 *
 * ## What this costs when it is wrong, in both directions
 * Redraw too MUCH and a phone with four widgets does four full RemoteViews
 * rebuilds — SQL read, tick, platform grouping, row inflation — every ~30
 * seconds per tracked stop, to change one of them. That was the behaviour until
 * AV2-5.3: `updateFromStorage` drew every placed widget on every push.
 *
 * Redraw too LITTLE and a board silently stops updating. That is the failure
 * hiding in the bus case below, and it is invisible on rail — which is what
 * every development device is testing on.
 */
class WidgetRedrawTargetsTest {

    // Smithwood Close: the hub, and the two poles route 39 departs from. The
    // same worked example as `BoardTest` and `V1ContractTest`.
    private val hub = "490012211N"
    private val poleIn = "490008805N"
    private val poleOut = "490012211N"
    private val kingsCross = "940GZZLUKSX"

    private fun sel(station: String, parent: String, line: String, direction: String) = UserSelection(
        mode = if (line.toIntOrNull() != null) "bus" else "tube",
        line = line,
        station = station,
        parentStationId = parent,
        stationName = "",
        direction = direction,
        destinations = emptyList(),
        destinationIds = emptyList(),
    )

    /**
     * **The one that is invisible on rail.** The push names the POLE; the widget
     * is bound to the STOP. Matching the push id against bindings directly finds
     * nothing, and route 39's inbound widget never updates again.
     */
    @Test
    fun `a push for a bus pole reaches the widget bound to its stop`() {
        val selections = listOf(
            sel(poleIn, hub, "39", "inbound"),
            sel(poleOut, hub, "39", "outbound"),
        )

        assertEquals(setOf(hub), WidgetRedrawTargets.hubsFedBy(selections, poleIn))
    }

    @Test
    fun `on rail the naptan is the hub and nothing special happens`() {
        val selections = listOf(sel(kingsCross, kingsCross, "victoria", "inbound"))

        assertEquals(setOf(kingsCross), WidgetRedrawTargets.hubsFedBy(selections, kingsCross))
    }

    @Test
    fun `a push for a station nobody tracks feeds nothing`() {
        val selections = listOf(sel(kingsCross, kingsCross, "victoria", "inbound"))

        assertEquals(emptySet<String>(), WidgetRedrawTargets.hubsFedBy(selections, "940GZZLUOXC"))
    }

    /** The ids come from two places and only one of them is ours. */
    @Test
    fun `naptan matching ignores case`() {
        val selections = listOf(sel(kingsCross, kingsCross, "victoria", "inbound"))

        assertEquals(setOf(kingsCross), WidgetRedrawTargets.hubsFedBy(selections, kingsCross.lowercase()))
    }

    /** The acceptance criterion, stated. */
    @Test
    fun `a widget bound to another station is not a target`() {
        val targets = WidgetRedrawTargets.widgetsShowing(
            bindings = mapOf(11 to hub, 12 to kingsCross),
            hubs = setOf(hub),
        )

        assertEquals(listOf(11), targets)
    }

    /**
     * A widget placed but never bound — the user backed out of the picker, or a
     * restore brought the instance back without its binding. It is never a
     * redraw target, because the only station it could be drawn with is one
     * nobody chose. See `WidgetBindingStore`'s "never guess".
     */
    @Test
    fun `an unbound widget is never redrawn`() {
        val targets = WidgetRedrawTargets.widgetsShowing(
            bindings = mapOf(11 to null, 12 to hub),
            hubs = setOf(hub),
        )

        assertEquals(listOf(12), targets)
    }

    // ── a line-status push is not every widget's business ───────────────────

    /**
     * **The flicker, measured.** One refresh tap produced 44 widget renders.
     *
     * `notifyLineStatus` redrew EVERY placed widget, and a refresh fetches
     * every tracked stop, so the backend answered with a line-status push per
     * line. Eight lines times three widgets is the burst the owner described as
     * "multiple flashes": widget 16, 19, 20, over and over, four seconds after a
     * tap they had already forgotten about.
     *
     * A line-status change is only about the widgets whose board actually rides
     * that line. The same rule `hubsFedBy` already applies to a station push,
     * and for the same reason: a push names something in the DATA model and a
     * binding names a hub, so the two have to be resolved through the
     * selections rather than compared.
     */
    @Test
    fun `a line push only reaches the hubs that ride that line`() {
        val selections = listOf(
            sel("940GZZLUKSX", "940GZZLUKSX", "piccadilly", "outbound"),
            sel("940GZZLUKSX", "940GZZLUKSX", "circle", "inbound"),
            sel("940GZZDLBNK", "940GZZDLBNK", "dlr", "outbound"),
        )
        assertEquals(setOf("940GZZLUKSX"), WidgetRedrawTargets.hubsOn(selections, "piccadilly"))
        assertEquals(setOf("940GZZDLBNK"), WidgetRedrawTargets.hubsOn(selections, "dlr"))
    }

    /** A line nobody tracks reaches nothing, rather than everything. */
    @Test
    fun `a line no board rides redraws nothing`() {
        val selections = listOf(sel("940GZZDLBNK", "940GZZDLBNK", "dlr", "outbound"))
        assertEquals(emptySet<String>(), WidgetRedrawTargets.hubsOn(selections, "victoria"))
    }

    /**
     * Line ids arrive from the push payload and from the local database, and
     * only one of those is ours. Same reason `hubsFedBy` is case-insensitive.
     */
    @Test
    fun `line matching ignores case`() {
        val selections = listOf(sel("940GZZDLBNK", "940GZZDLBNK", "dlr", "outbound"))
        assertEquals(setOf("940GZZDLBNK"), WidgetRedrawTargets.hubsOn(selections, "DLR"))
    }

    /**
     * A bus hub's poles are separate selections under one hub, so a route push
     * must reach the hub once rather than not at all.
     */
    @Test
    fun `a bus route reaches its hub through either pole`() {
        val selections = listOf(
            sel("490008805N", "490HUB", "39", "inbound"),
            sel("490012211N", "490HUB", "39", "outbound"),
        )
        assertEquals(setOf("490HUB"), WidgetRedrawTargets.hubsOn(selections, "39"))
    }
}
