package com.stationly.core.config

import com.stationly.core.platform.Platform

/**
 * The one place a fetched SDUI config map is adopted.
 *
 * ## Why an entry point rather than N stores
 * Two typed views of the same payload exist — [BoardPolicyStore] for how the
 * board behaves, [LinePaletteStore] for what it is painted in — and both have to
 * be refreshed from the same map at the same moment. Left to individual call
 * sites that is three places (home screen, background refresh, and the auth
 * flow's own early fetch) each of which has to remember every store, and the
 * failure mode of forgetting one is silent: a board ticking by served rules and
 * painted in compiled colours.
 *
 * Adding a third store should be a change to this file and nowhere else.
 */
object SduiConfig {

    /** Adopt a freshly-fetched or freshly-cached config map. */
    fun refresh(strings: Map<String, String>) {
        if (strings.isEmpty()) return
        BoardPolicyStore.refresh(strings)
        LinePaletteStore.refresh(strings)
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Adopt the last cached config, for paths that run before any fetch.
     *
     * Ingest starts on the first stream frame, which can beat the home-config
     * request home; a background refresh has no UI above it at all. Without this
     * those paths would run on compiled values and then switch mid-session.
     */
    suspend fun loadFromCache() {
        val raw = runCatching {
            Platform.storageManager.loadString(ConfigKeys.HOME_CONFIG_CACHE_KEY)
        }.getOrNull() ?: return
        val decoded = runCatching {
            json.decodeFromString<Map<String, String>>(raw)
        }.getOrNull() ?: return
        refresh(decoded)
    }
}

/**
 * The palette in force, resolved once rather than per read.
 *
 * Same posture as [BoardPolicyStore]: [LinePalette.DEFAULT] until something
 * refreshes it, which is the honest answer on a cold install and the safe one
 * everywhere else. A colour change lands on the next fetch rather than instantly,
 * which matches how `ThemeRepository` has always applied a served palette.
 */
object LinePaletteStore {
    var current: LinePalette = LinePalette.DEFAULT
        private set

    fun refresh(strings: Map<String, String>) {
        if (strings.isEmpty()) return
        current = LinePalette.resolve(strings)
    }
}
