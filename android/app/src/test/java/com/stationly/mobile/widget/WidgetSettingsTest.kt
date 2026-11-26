package com.stationly.mobile.widget

import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.BoardPin
import com.stationly.core.model.user.PlatformNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A widget's settings are the WIDGET's.
 *
 * ## The bug this replaces
 * The configure screen wrote `rowsPerPlatform`, `pin` and `platformNav` through
 * `UserSettings.update(groupingId)` — the same `BoardConfig` the station's own
 * settings screen edits and the home screen renders from. So changing how a
 * widget looked silently changed the card inside the app, and the two surfaces
 * could not be set differently even though they are different shapes with
 * different constraints.
 *
 * The owner's rule, 2026-09-12: "all the widget settings are only for the
 * widget, their selection doesn't impact the settings of the board inside the
 * app".
 *
 * ## Keyed per widget, not per station
 * Two widgets showing one station are two things somebody placed for two
 * reasons. The binding and the page index are keyed the same way for the same
 * reason.
 */
class WidgetSettingsTest {

    @Test
    fun `each widget has its own settings key`() {
        assertEquals("wpin_6", WidgetSettings.pinKeyFor(6))
        assertEquals("wnav_6", WidgetSettings.navKeyFor(6))
        assertEquals("wpin_11", WidgetSettings.pinKeyFor(11))
    }

    /**
     * Scrolling is the default, because it is what every widget did before the
     * setting existed. An absent value has to keep meaning "behave as it
     * already does".
     */
    @Test
    fun `an unset widget scrolls and pins nothing`() {
        assertEquals(PlatformNav.SCROLL, WidgetSettings.navFrom(null))
        assertNull(WidgetSettings.pinFrom(null))
        assertNull(WidgetSettings.pinFrom(""))
    }

    @Test
    fun `a stored navigation mode reads back`() {
        assertEquals(PlatformNav.STEP, WidgetSettings.navFrom("STEP"))
        assertEquals(PlatformNav.SCROLL, WidgetSettings.navFrom("SCROLL"))
    }

    /**
     * A value written by a future build with a third mode must not crash an
     * older one. It degrades to the default, the same way `BoardConfig` leans
     * on `coerceInputValues`.
     */
    @Test
    fun `an unknown navigation mode falls back to scrolling`() {
        assertEquals(PlatformNav.SCROLL, WidgetSettings.navFrom("CAROUSEL_OF_WONDERS"))
    }

    @Test
    fun `a pin survives a round trip`() {
        val pin = BoardPin(BoardPin.Kind.PLATFORM, "Platform 9")
        assertEquals(pin, WidgetSettings.pinFrom(WidgetSettings.pinToStored(pin)))
        val stop = BoardPin(BoardPin.Kind.STOP, "490012211N")
        assertEquals(stop, WidgetSettings.pinFrom(WidgetSettings.pinToStored(stop)))
    }

    /** Clearing a pin stores nothing rather than a sentinel nobody can read. */
    @Test
    fun `no pin stores blank`() {
        assertEquals("", WidgetSettings.pinToStored(null))
    }

    /**
     * A pin id can legitimately contain the separator — a platform LABEL is
     * whatever TfL prints, and "Platform 9 (Eastbound)" has a space and
     * brackets. Only the FIRST separator splits, so the id keeps whatever it
     * contains.
     */
    @Test
    fun `a pin id containing the separator survives`() {
        val pin = BoardPin(BoardPin.Kind.PLATFORM, "Platform 9 | Eastbound")
        assertEquals(pin, WidgetSettings.pinFrom(WidgetSettings.pinToStored(pin)))
    }

    /**
     * The widget's depth is NOT a setting and must not become one: a control
     * that changes the height of a home-screen widget moves everything around
     * it. Three per platform, padded. This asserts the constant stays put.
     */
    @Test
    fun `the widget's depth is fixed and is not the board's`() {
        assertEquals(3, DepartureWidgetProvider.ROWS_PER_PLATFORM)
        assertEquals(
            BoardConfig.DEFAULT_ROWS_PER_PLATFORM,
            DepartureWidgetProvider.ROWS_PER_PLATFORM,
        )
    }
}
