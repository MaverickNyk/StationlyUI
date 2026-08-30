package com.stationly.core.config

import com.stationly.core.model.user.BoardConfig

/**
 * Everything about the departure board that is a JUDGEMENT rather than
 * arithmetic, resolved into one value the whole app reads.
 *
 * ## What belongs here, and what does not
 * Three tests, all of which have to hold:
 *
 *  1. **It is a judgement, not arithmetic.** "How long a departed train stays
 *     up" is a judgement. "Sixty seconds is a minute" is not — that one lives in
 *     `BoardTicker.minutesAt` and must stay there, because changing it does not
 *     tune the board, it makes the label lie: "Due" would start meaning "under
 *     ninety seconds" while "1 min" still meant a minute.
 *  2. **A wrong value degrades, it does not break.** A bad grace period shows a
 *     train slightly too long. A bad fetch timeout kills the board, which is why
 *     the timeouts are not here.
 *  3. **A safe default ships in the binary.** Every field below has one, and the
 *     app renders correctly having never reached the network. That is not a
 *     nicety: a cold install paints before any fetch returns, and an offline
 *     launch never gets one.
 *
 * ## Where the values come from
 * The SDUI home-config map (`GET /sdui/app/home-config`), through
 * [RemoteConfig], which clamps every one of them. Resolved once by
 * [BoardPolicyStore] rather than per read.
 *
 * ## The widget gets a copy, not a fetch
 * The extension is a separate process that never calls the network, so
 * `IosWidgetManager.publishFallbackCopy` republishes these fields into the App
 * Group alongside the fallback copy table, and `WidgetData.ticked(at:)` reads
 * them there. A field added here without a matching field on the Swift side is
 * a field the widget will keep the old value of, and the two surfaces will
 * disagree about the same board — which is the exact bug `BoardTicker` was
 * written to close.
 */
data class BoardPolicy(
    /**
     * How long after its arrival time a train stays on the board.
     *
     * 30s by default — the dwell of a tube train at a London platform, i.e. what
     * the physical indicator does: the "Due" line stays up while the train sits
     * with its doors open. Remote because dwell is not one number across modes;
     * a bus pulls away immediately and a terminating service sits far longer.
     */
    val departedGraceMs: Long = 30_000L,

    /**
     * How old the payload must be before a departed row is worth holding as
     * "Gone".
     *
     * Coupled to [freshMs] on purpose — see [BoardPolicyStore.resolve], which
     * refuses to let the two drift apart silently.
     */
    val retentionMinAgeMs: Long = 60_000L,

    /**
     * The label a retained departed train carries.
     *
     * Length-capped, and that is not cosmetic: the ETA column is the widest-label
     * column on the board and it decides where the destination truncates. Four
     * characters keeps long station names intact where eight would clip them,
     * which is the whole reason this reads "Gone" and not "Departed".
     */
    val departedLabel: String = "Gone",

    /**
     * How many rows per platform are written to SQL at ingest.
     *
     * The reserve the tick layer shifts up from as trains depart, NOT what the
     * board draws. Floored at [BoardConfig.MAX_ROWS_PER_PLATFORM] because a
     * reserve shallower than the deepest board a user can ask for would render
     * that board short no matter what they set.
     */
    val rowReserve: Int = 10,

    /** Footer chronometer: amber → grey. */
    val freshMs: Long = 60_000L,

    /** Footer chronometer: grey → red. */
    val staleMs: Long = 180_000L,

    /**
     * TfL severity strings, worst first, for the single status strip on a
     * multi-line board.
     *
     * Remote because it changes when TfL changes it, which should not need an
     * App Store release. Fails open by construction: an unrecognised severity
     * already sorts above "Good Service", so a truncated list degrades to "shown
     * but ranked low", never to "hidden".
     */
    val severityOrder: List<String> = DEFAULT_SEVERITY_ORDER,

    /**
     * The severities that make a line's indicator RED rather than amber.
     *
     * Red means "you cannot travel on this line right now" — a closure or a
     * suspension. Delays and reduced service stay amber: a train is still
     * coming, and someone who sees red should change their plan rather than
     * expect to wait. That distinction is the whole reason the dot exists.
     *
     * A SUBSET of [severityOrder], and it used to be a separate hand-kept list in
     * `LineStatusRanker` — a third enumeration of one vocabulary, alongside the
     * order here and the display names in `LineStatusSheet`. All three now come
     * off one table in the backend's `lineSeverityService.ts`, so TfL changing
     * its wording cannot leave them disagreeing.
     *
     * Compared case-insensitively: the board holds a formatted
     * "Severity : Reason" string, not a numeric code, and TfL's own casing is
     * not something to depend on.
     */
    val redSeverities: Set<String> = DEFAULT_RED_SEVERITIES,
) {
    companion object {
        /**
         * Declared BEFORE [DEFAULT], and that order is load-bearing rather than
         * stylistic: `DEFAULT` calls the constructor, whose default argument for
         * `severityOrder` is this list. Companion properties initialise top to
         * bottom, so with the two the other way round `DEFAULT` is built while
         * this is still null and the whole object fails to initialise — which on
         * JVM surfaces as `ExceptionInInitializerError` out of every call site
         * that so much as reads a defaulted policy parameter.
         */
        val DEFAULT_SEVERITY_ORDER: List<String> = listOf(
            "Closed",
            "Suspended",
            "Part Suspended",
            "Planned Closure",
            "Part Closure",
            "Part Closed",
            "Severe Delays",
            "Service Closed",
            "Not Running",
            "Reduced Service",
            "Bus Service",
            "Diverted",
            "Minor Delays",
            "Change of frequency",
            "Special Service",
            "Exit Only",
            "No Step Free Access",
            "Issues Reported",
            "Information",
        )

        /**
         * Declared before [DEFAULT] for the same reason as the order list above.
         * Lower-cased at rest so [redSeverities] can be membership-tested without
         * re-normalising on every read.
         */
        val DEFAULT_RED_SEVERITIES: Set<String> = setOf(
            "closed", "suspended", "part suspended", "planned closure",
            "part closure", "part closed", "service closed", "not running",
        )

        /** The compiled-in answer. Used until, and whenever, config is unavailable. */
        val DEFAULT = BoardPolicy()

        // ── Keys ──
        //
        // Named to sit alongside the `board.fallback.*` family already served by
        // the same endpoint, so one backend file holds every board knob.
        const val KEY_GRACE      = "board.tick.departedGraceMs"
        const val KEY_RETENTION  = "board.tick.retentionMinAgeMs"
        const val KEY_LABEL      = "board.tick.departedLabel"
        const val KEY_RESERVE    = "board.tick.rowReserve"
        const val KEY_FRESH      = "board.stale.freshMs"
        const val KEY_STALE      = "board.stale.staleMs"
        const val KEY_SEVERITY   = "board.status.severityOrder"
        const val KEY_RED_SEVERITY = "board.status.redSeverities"

        /** Longest label the ETA column can take without squeezing destinations. */
        const val MAX_LABEL_LEN = 6
    }
}
