package com.stationly.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's four empty states (AV2-10.7): which one applies, what it says,
 * and where a tap on it goes.
 */
class WidgetEmptyStateTest {

    @Test
    fun `the most fundamental reason wins`() {
        // Signed out beats everything: there is no account to have stations.
        assertEquals(WidgetEmptyState.SIGNED_OUT, WidgetEmptyState.resolve(signedIn = false, hasAnyBoard = true, wasBound = true))
        assertEquals(WidgetEmptyState.NO_STATIONS, WidgetEmptyState.resolve(signedIn = true, hasAnyBoard = false, wasBound = true))
        assertEquals(WidgetEmptyState.REMOVED, WidgetEmptyState.resolve(signedIn = true, hasAnyBoard = true, wasBound = true))
        assertEquals(WidgetEmptyState.NEEDS_STATION, WidgetEmptyState.resolve(signedIn = true, hasAnyBoard = true, wasBound = false))
    }

    /**
     * Each state's tap lands where its words point. Signing in and adding a
     * first station happen in the app; choosing a station for THIS widget
     * happens in its configuration.
     */
    @Test
    fun `the tap goes where the message says`() {
        fun target(s: WidgetEmptyState) = DepartureWidgetProvider.tapTargetFor(isBound = false, emptyState = s)
        assertEquals(WidgetTapTarget.APP, target(WidgetEmptyState.SIGNED_OUT))
        assertEquals(WidgetTapTarget.APP, target(WidgetEmptyState.NO_STATIONS))
        assertEquals(WidgetTapTarget.CONFIGURE, target(WidgetEmptyState.NEEDS_STATION))
        assertEquals(WidgetTapTarget.CONFIGURE, target(WidgetEmptyState.REMOVED))
        // A bound widget always opens the app, whatever is passed beside it.
        assertEquals(WidgetTapTarget.APP, DepartureWidgetProvider.tapTargetFor(isBound = true, emptyState = WidgetEmptyState.NEEDS_STATION))
    }

    @Test
    fun `served copy wins, and a blank served value falls back`() {
        val served = mapOf(
            "widget.state.needs_station.title" to "Pick a stop",
            "widget.state.needs_station.detail" to "",
        )
        assertEquals("Pick a stop", WidgetEmptyState.NEEDS_STATION.title(served))
        assertEquals(WidgetEmptyState.NEEDS_STATION.defaultDetail, WidgetEmptyState.NEEDS_STATION.detail(served))
        assertEquals(WidgetEmptyState.REMOVED.defaultTitle, WidgetEmptyState.REMOVED.title(emptyMap()))
    }

    /**
     * Android words, not iOS gestures, and within the widget's 30-character
     * budget. "Touch and hold, then tap Edit Widget" is an iPhone instruction.
     */
    @Test
    fun `the compiled copy is short and describes an Android tap`() {
        for (s in WidgetEmptyState.entries) {
            assertTrue("${s.name} detail too long: ${s.defaultDetail}", s.defaultDetail.length <= 30)
            assertTrue("${s.name} title too long: ${s.defaultTitle}", s.defaultTitle.length <= 30)
            assertTrue("${s.name} is an iOS gesture", !s.defaultDetail.contains("Touch and hold"))
            assertTrue("${s.name} has a dash", !s.defaultDetail.contains('—') && !s.defaultDetail.contains('–'))
        }
    }
}
