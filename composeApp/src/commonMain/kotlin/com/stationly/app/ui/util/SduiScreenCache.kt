package com.stationly.app.ui.util

import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.platform.Platform
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Disk cache for one SDUI screen layout, stored under [key]. The layout
 * counterpart of [HomeConfigCache], which does the same for the string map.
 *
 * Screens read it cache-first, network-second: the last layout the server sent
 * renders at once, and the fetch only repaints if something actually changed.
 * Without it a screen opens on its compiled fallback and then visibly swaps to
 * the served one, which is a jump whenever the two differ in length.
 */
class SduiScreenCache(private val key: String) {

    // Unknown keys ignored so a payload from a NEWER backend still renders the
    // parts this build understands, rather than throwing away the whole screen
    // over one field. `explicitNulls = false` keeps the stored blob small.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun load(): SduiAppScreen? = runCatching {
        Platform.storageManager.loadString(key)
            ?.let { json.decodeFromString<SduiAppScreen>(it) }
            ?.takeIf { it.components.isNotEmpty() }
    }.getOrNull()

    suspend fun save(screen: SduiAppScreen) {
        // An empty layout never overwrites a good one. A backend that answers
        // 200 with nothing in it is a deploy in progress, not a decision to
        // delete the screen.
        if (screen.components.isEmpty()) return
        runCatching { Platform.storageManager.saveString(key, json.encodeToString(screen)) }
    }
}
