package com.stationly.core.config

/**
 * Typed, CLAMPED reads out of the SDUI home-config map.
 *
 * ## Why this exists rather than reading the map at the call site
 * The map is `Map<String,String>` and every consumer used to parse its own key
 * inline:
 *
 * ```
 * homeConfig["board.fallback.signalLostMin"]?.toLongOrNull()
 *     ?: BoardFallbackDefaults.SIGNAL_LOST_MIN
 * ```
 *
 * That reads fine for four keys and stops being defensible at fifteen, because
 * the pattern has no floor under it. A backend typo — `"600000"` where `"6"`
 * was meant — is accepted verbatim, and the board silently adopts a threshold
 * nobody chose. There is no signal, no crash, and no way to tell from the app
 * that it happened.
 *
 * Config is an OVERRIDE, not an instruction. Everything here is written so the
 * worst a bad payload can do is move a value to the edge of a range someone
 * thought about.
 *
 * ## The three failure modes, and what each does
 *  - **Key absent** → the default. The common case: the app has never fetched,
 *    is offline, or is talking to a backend that predates the key.
 *  - **Unparseable** → the default. `"soon"` is not a number and guessing at
 *    what it meant is worse than ignoring it.
 *  - **Out of range** → clamped TO THE BOUND, deliberately not to the default.
 *    A value of `5000` against a max of `120` is not noise, it is someone
 *    meaning "as much as possible"; honouring the intent at the limit is closer
 *    to right than discarding it. This is the one case where the config still
 *    moves the needle, and it is the one case where it should.
 */
object RemoteConfig {

    fun long(m: Map<String, String>, key: String, default: Long, min: Long, max: Long): Long {
        val parsed = m[key]?.trim()?.toLongOrNull() ?: return default
        return parsed.coerceIn(min, max)
    }

    fun int(m: Map<String, String>, key: String, default: Int, min: Int, max: Int): Int {
        val parsed = m[key]?.trim()?.toIntOrNull() ?: return default
        return parsed.coerceIn(min, max)
    }

    /**
     * A string value, length-capped.
     *
     * Blank is treated as absent rather than as an override: a key present with
     * an empty value is far more likely to be an unfinished edit than a request
     * for an empty label, and some of these strings size a column (see
     * [BoardPolicy.departedLabel]).
     */
    fun text(m: Map<String, String>, key: String, default: String, maxLen: Int): String {
        val raw = m[key]?.trim().orEmpty()
        if (raw.isEmpty()) return default
        return if (raw.length > maxLen) raw.take(maxLen) else raw
    }

    /**
     * A comma-separated list, trimmed and emptied-out.
     *
     * Falls back to the default on a list that comes back with nothing usable in
     * it, so `","` cannot silently erase an ordering the app depends on.
     */
    fun list(m: Map<String, String>, key: String, default: List<String>): List<String> {
        val raw = m[key]?.trim().orEmpty()
        if (raw.isEmpty()) return default
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.ifEmpty { default }
    }
}
