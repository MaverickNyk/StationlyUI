package com.stationly.app.ui.util

import com.stationly.core.platform.Platform
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Disk cache for the SDUI home-config string map — the iOS analogue of
 * Android's `util/HomeConfigStore` (which every cold surface reads so SDUI
 * copy renders instantly on launch instead of flashing hardcoded fallbacks
 * until the network returns).
 *
 * Backed by the shared `Platform.storageManager` (NSUserDefaults on iOS) as
 * one JSON blob. Last-successful-sync wins; an empty fetch never clobbers a
 * good cache.
 */
object HomeConfigCache {
    private const val KEY = "home_config_strings_cache"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Map<String, String> = runCatching {
        Platform.storageManager.loadString(KEY)
            ?.let { json.decodeFromString<Map<String, String>>(it) }
    }.getOrNull() ?: emptyMap()

    suspend fun save(strings: Map<String, String>) {
        if (strings.isEmpty()) return
        runCatching { Platform.storageManager.saveString(KEY, json.encodeToString(strings)) }
    }
}
