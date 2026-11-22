package com.stationly.app.ui.widgets

import com.stationly.app.ui.util.SduiScreenCache
import com.stationly.core.config.ConfigKeys

/**
 * Disk cache for the widget-guide layout.
 *
 * The guide is read cache-first, network-second, exactly like the auth copy and
 * for a stronger version of the same reason: somebody opening a help screen has
 * already hit a problem, and making them wait for a round trip to be told how
 * to fix it adds one. See `WidgetGuideDefaults` for the floor beneath this.
 */
val WidgetGuideCache = SduiScreenCache(ConfigKeys.WIDGET_GUIDE_CACHE_KEY)
