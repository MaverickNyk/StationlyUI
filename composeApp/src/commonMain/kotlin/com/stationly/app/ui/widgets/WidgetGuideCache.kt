package com.stationly.app.ui.widgets

import com.stationly.core.config.ConfigKeys
import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.platform.Platform
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Disk cache for the widget-guide layout. Same shape as
 * [com.stationly.app.ui.util.HomeConfigCache], for a different payload.
 *
 * The guide is read cache-first, network-second, exactly like the auth copy and
 * for a stronger version of the same reason: somebody opening a help screen has
 * already hit a problem, and making them wait for a round trip to be told how
 * to fix it adds one. See `WidgetGuideDefaults` for the floor beneath this.
 */
object WidgetGuideCache {
    private val KEY = ConfigKeys.WIDGET_GUIDE_CACHE_KEY

    // Unknown keys ignored so a payload from a NEWER backend still renders the
    // parts this build understands, rather than throwing away the whole screen
    // over one field. `explicitNulls = false` keeps the stored blob small.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun load(): SduiAppScreen? = runCatching {
        Platform.storageManager.loadString(KEY)
            ?.let { json.decodeFromString<SduiAppScreen>(it) }
            ?.takeIf { it.components.isNotEmpty() }
    }.getOrNull()

    suspend fun save(screen: SduiAppScreen) {
        // An empty layout never overwrites a good one. A backend that answers
        // 200 with nothing in it is a deploy in progress, not a decision to
        // delete the guide.
        if (screen.components.isEmpty()) return
        runCatching { Platform.storageManager.saveString(KEY, json.encodeToString(screen)) }
    }
}
