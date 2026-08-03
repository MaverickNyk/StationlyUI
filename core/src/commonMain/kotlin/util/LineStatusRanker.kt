package com.stationly.core.util

/**
 * Picks and orders the line statuses a multi-line board shows in its ONE status
 * strip.
 *
 * ## Why
 * Every tracked line has its own status, and the board used to render a status
 * strip per line — four lines at King's Cross meant four strips, four marquees,
 * and the departures pushed off the panel by chrome that was mostly "Good
 * Service, Good Service, Good Service". A board now has a single strip at the
 * end of the departures, and this decides what goes in it.
 *
 * ## The rule
 * Worst first, then rotate. A closure or suspension on one line is the thing you
 * need to know before anything else, so it leads; the remaining disrupted lines
 * follow in severity order so nothing is silently hidden. Good Service is not
 * worth a rotation slot — it is only shown when EVERY line is good, in which case
 * one "Good Service" says it for the whole board.
 */
object LineStatusRanker {

    /**
     * Severity order, worst first. Mirrors TfL's own `statusSeverity` ordering
     * rather than inventing one, so our idea of "worse" matches the source's.
     *
     * Matched case-insensitively on the severity text because that is what we
     * actually hold: the board stores a formatted "Severity : Reason" string, not
     * the numeric code. Anything unrecognised sorts just BELOW the known
     * disruptions and above Good Service — an unknown severity is more likely to
     * be a new disruption type than a new flavour of "everything is fine", and
     * burying it would be the worse failure.
     */
    private val SEVERITY_ORDER: List<String> = listOf(
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

    private const val UNKNOWN_RANK = 1_000
    private const val GOOD_RANK = 2_000

    private val GOOD_SEVERITIES = setOf("good service", "no issues")

    fun isGoodService(severity: String?): Boolean =
        severity?.trim()?.lowercase() in GOOD_SEVERITIES

    /** Lower is worse. Unknown severities sort above Good Service — see above. */
    fun rankOf(severity: String?): Int {
        val s = severity?.trim().orEmpty()
        if (s.isEmpty()) return GOOD_RANK
        if (isGoodService(s)) return GOOD_RANK
        val index = SEVERITY_ORDER.indexOfFirst { it.equals(s, ignoreCase = true) }
        return if (index >= 0) index else UNKNOWN_RANK
    }

    /**
     * Traffic-light tone for a line's status, for the indicator dot on a line
     * pill and the hero's accent.
     *
     * Three buckets, derived from the same [SEVERITY_ORDER] the rotation uses, so
     * a dot can never disagree with the status text below it.
     *
     * [AMBER] is the interesting boundary: delays and reduced service still mean
     * a train is coming, so they are not red. Red is reserved for "you cannot
     * travel on this line right now" — closures and suspensions — because a
     * passenger who sees red should change their plan, not just expect to wait.
     */
    enum class Tone { GREEN, AMBER, RED }

    /** Severities at or worse than this rank are [Tone.RED]. */
    private val RED_SEVERITIES = setOf(
        "closed", "suspended", "part suspended", "planned closure",
        "part closure", "part closed", "service closed", "not running",
    )

    fun toneOf(severity: String?): Tone {
        val s = severity?.trim()?.lowercase().orEmpty()
        if (s.isEmpty() || isGoodService(s)) return Tone.GREEN
        if (s in RED_SEVERITIES) return Tone.RED
        return Tone.AMBER
    }

    /** One line's status, as the board holds it. */
    data class Entry(
        /** Display name of the line — "Northern". */
        val lineLabel: String,
        val severity: String,
        val reason: String,
    )

    /**
     * The statuses to rotate through, worst first.
     *
     * Returns disrupted lines only. When every line is healthy it returns a
     * single entry rather than one per line, because "Good Service" repeated
     * four times tells you nothing four times.
     *
     * Entries are de-duplicated on (severity, reason): several lines sharing one
     * incident — which is normal, the sub-surface lines share track — would
     * otherwise rotate the identical sentence several times over. The line labels
     * are joined instead, so "Circle, District  Minor Delays" reads as one fact.
     */
    fun rotation(entries: List<Entry>): List<Entry> {
        if (entries.isEmpty()) return emptyList()

        val disrupted = entries.filterNot { isGoodService(it.severity) }
        if (disrupted.isEmpty()) {
            // Everything is fine — say so once, for the board rather than a line.
            return listOf(Entry(lineLabel = "", severity = entries.first().severity, reason = ""))
        }

        return disrupted
            .groupBy { it.severity.trim().lowercase() to it.reason.trim().lowercase() }
            .map { (_, shared) ->
                shared.first().copy(
                    lineLabel = shared.map { it.lineLabel }.distinct().joinToString(", ")
                )
            }
            .sortedBy { rankOf(it.severity) }
    }

    /**
     * What the strip actually renders for one entry: "Northern  Part Closure"
     * plus the reason, or just the severity when the entry speaks for the whole
     * board (the all-good case above).
     */
    fun label(entry: Entry): String =
        if (entry.lineLabel.isBlank()) entry.severity
        else "${entry.lineLabel} ${entry.severity}"
}
