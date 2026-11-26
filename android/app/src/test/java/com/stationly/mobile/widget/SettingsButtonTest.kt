package com.stationly.mobile.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gear is drawn only where the launcher has no "Settings" of its own. */
class SettingsButtonTest {
    @Test
    fun `Android 11 and below keep the gear`() {
        assertTrue(DepartureWidgetProvider.showsSettingsButton(26))
        assertTrue(DepartureWidgetProvider.showsSettingsButton(30))
    }

    @Test
    fun `Android 12 and above rely on the long-press Settings`() {
        assertFalse(DepartureWidgetProvider.showsSettingsButton(31))
        assertFalse(DepartureWidgetProvider.showsSettingsButton(37))
    }
}
