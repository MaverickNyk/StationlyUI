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
}
