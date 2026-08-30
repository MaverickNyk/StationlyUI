package com.stationly.core.config

import com.stationly.core.model.user.BoardConfig
import com.stationly.core.platform.Platform

/**
 * The one resolved [BoardPolicy] the app reads, and the only place the SDUI map
 * is turned into one.
 *
 * ## Why a store and not a parameter everywhere
 * The policy is needed in two kinds of place and they cannot share a mechanism:
 *
 *  - **Render**, where a Compose surface already holds the config map and could
 *    pass it down.
 *  - **Ingest**, where [com.stationly.core.usecase.SyncPredictionsUseCase] runs
 *    on a stream frame in `core`, several times a minute, with no UI above it
 *    and no map in scope. It needs [BoardPolicy.rowReserve] to decide how deep
 *    to write SQL.
 *
 * Threading a map from the ViewModel into the stream path to serve one integer
 * would invert the dependency for no gain. So the resolved value is held here
 * and both sides read it.
 *
 * ## What is deliberately NOT here
 * The tick itself. [com.stationly.core.util.BoardTicker] stays pure and takes a
 * policy as a defaulted parameter — its whole test suite depends on being able
 * to hand it one. This store only decides what that default is at runtime.
 *
 * ## Staleness is fine
 * [current] is whatever the last [refresh] resolved, and a config change
 * therefore lands on the next fetch rather than instantly. That matches
 * `ThemeRepository`, which applies its palette on the next launch, and it is the
 * right posture: a board that re-derived its own rules mid-glance would be worse
 * than one a minute behind.
 */
object BoardPolicyStore {

    /**
     * The policy in force. [BoardPolicy.DEFAULT] until something resolves one,
     * which is the honest answer on a cold install and the safe one everywhere
     * else — never zeroes, never an empty list.
     */
    var current: BoardPolicy = BoardPolicy.DEFAULT
        private set

    /** Adopt a freshly-fetched config map. Called after the home-config sync. */
    fun refresh(strings: Map<String, String>) {
        if (strings.isEmpty()) return
        current = resolve(strings)
    }

    /**
     * Adopt the last cached config, for the paths that run before any fetch.
     *
     * Ingest starts on the first stream frame, which can beat the home-config
     * request home. Without this the first minutes of a session would write SQL
     * at the compiled reserve and then quietly switch depth mid-session.
     */
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

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
     * The map, clamped into a policy.
     *
     * ## The freshness pair is validated, not just clamped
     * [BoardPolicy.retentionMinAgeMs] and [BoardPolicy.freshMs] are both 60s in
     * the defaults ON PURPOSE: the footer turning grey and the rows turning
     * "Gone" are the same statement about the same payload, and a board where
     * one happens without the other is a board disagreeing with itself. Once
     * they are two independently settable keys they will drift, so a payload
     * that sets one and not the other gets the other moved with it.
     *
     * Setting BOTH explicitly is honoured as written — that is someone who has
     * decided the two mean different things, and the config is allowed to say
     * so.
     */
    internal fun resolve(strings: Map<String, String>): BoardPolicy {
        val fresh = RemoteConfig.long(
            strings, BoardPolicy.KEY_FRESH,
            default = BoardPolicy.DEFAULT.freshMs, min = 15_000L, max = 600_000L,
        )

        // Retention follows freshness unless it is set in its own right — see
        // the note above. `fresh` is already clamped, and the retention clamp is
        // the wider of the two ranges, so the coupling can never produce a value
        // outside it.
        val retentionDefault =
            if (strings.containsKey(BoardPolicy.KEY_FRESH)) fresh
            else BoardPolicy.DEFAULT.retentionMinAgeMs

        return BoardPolicy(
            departedGraceMs = RemoteConfig.long(
                strings, BoardPolicy.KEY_GRACE,
                default = BoardPolicy.DEFAULT.departedGraceMs, min = 0L, max = 120_000L,
            ),
            retentionMinAgeMs = RemoteConfig.long(
                strings, BoardPolicy.KEY_RETENTION,
                default = retentionDefault, min = 15_000L, max = 600_000L,
            ),
            departedLabel = RemoteConfig.text(
                strings, BoardPolicy.KEY_LABEL,
                default = BoardPolicy.DEFAULT.departedLabel, maxLen = BoardPolicy.MAX_LABEL_LEN,
            ),
            rowReserve = RemoteConfig.int(
                strings, BoardPolicy.KEY_RESERVE,
                default = BoardPolicy.DEFAULT.rowReserve,
                // Never shallower than the deepest board a user can ask for, or
                // that board renders short however they set it.
                min = BoardConfig.MAX_ROWS_PER_PLATFORM,
                max = 20,
            ),
            freshMs = fresh,
            // Ordered after fresh so the ladder can never inverted-clamp: red
            // must not come before grey.
            staleMs = RemoteConfig.long(
                strings, BoardPolicy.KEY_STALE,
                default = maxOf(BoardPolicy.DEFAULT.staleMs, fresh),
                min = fresh, max = 3_600_000L,
            ),
            severityOrder = RemoteConfig.list(
                strings, BoardPolicy.KEY_SEVERITY,
                default = BoardPolicy.DEFAULT_SEVERITY_ORDER,
            ),
            // Normalised on the way in, once, so every `toneOf` call is a plain
            // set membership test rather than a lowercase per read.
            redSeverities = RemoteConfig.list(
                strings, BoardPolicy.KEY_RED_SEVERITY,
                default = BoardPolicy.DEFAULT_RED_SEVERITIES.toList(),
            ).map { it.lowercase() }.toSet(),
        )
    }
}
