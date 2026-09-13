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

    /**
     * Whether anything has adopted a served config in this process yet.
     *
     * Guards [ensureLoaded] from overwriting a live fetch with a stale cache,
     * which is the one way a warm-up can make things worse than not running.
     */
    var adopted: Boolean = false
        private set

    /** Reset for test isolation. */
    fun resetForTest() {
        adopted = false
    }

    /** Adopt a freshly-fetched or freshly-cached config map. */
    fun refresh(strings: Map<String, String>) {
        if (strings.isEmpty()) return
        BoardPolicyStore.refresh(strings)
        LinePaletteStore.refresh(strings)
        adopted = true
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

    /**
     * Warm the served config for a surface that runs with no screen above it.
     *
     * ## The gap this closes, which was Android-only and invisible
     * Every rule the board runs on is served: how long a departed train stays
     * on screen and what it says while it does ([BoardPolicy.departedGraceMs],
     * [BoardPolicy.departedLabel]), how deep SQL is written
     * ([BoardPolicy.rowReserve]), when a timestamp starts going amber
     * ([BoardPolicy.freshMs] / [BoardPolicy.staleMs]), which line status counts
     * as the worst one ([BoardPolicy.severityOrder]), and the line colours.
     * Until something calls [refresh] they are the values that were compiled,
     * and `BoardPolicy.DEFAULT` is a safe answer rather than a correct one.
     *
     * On iOS every path that runs without UI already warms itself —
     * `BackgroundBoardRefresher` does exactly this. On Android the paths that
     * run without UI are the ones that run MOST: the home-screen widget is
     * redrawn by an FCM push several times a minute in a process where no
     * Activity need ever have started, and the same is true of the prediction
     * sync that writes what it draws and of the screensaver. Those surfaces
     * were therefore running on compiled rules indefinitely, while the app
     * beside them ran on served ones — the same station, two sets of rules, and
     * nothing on screen to say which you were looking at.
     *
     * Idempotent and cheap: a preferences read and a small JSON decode, skipped
     * entirely once anything has adopted a config. Safe to call on every push.
     */
    suspend fun ensureLoaded() {
        if (adopted) return
        loadFromCache()
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
