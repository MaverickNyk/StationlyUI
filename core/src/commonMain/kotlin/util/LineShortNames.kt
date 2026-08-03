package com.stationly.core.util

/**
 * Short line labels for the departure board, where horizontal space is the
 * binding constraint.
 *
 * ## Why short names exist
 * A platform header now names the lines using it ("Dist & Circ. Platform 2
 * Northbound") and a row on a shared platform prefixes its line ("(Cir.)
 * Edgware Road"). Full names do not fit: "Hammersmith & City" alone is wider
 * than a phone board row, and two of them on one header is hopeless.
 *
 * ## Where these should live
 * **This map is a STOPGAP.** Line naming is backend-owned everywhere else in the
 * app (platform labels, status text, mode names all come down from the API), and
 * a hardcoded client map will drift the moment a line is renamed — as the whole
 * Overground fleet was in 2024. The intended end state is a `shortName` on the
 * line API, with this map as the fallback for payloads that predate it.
 *
 * Until then, treat this as the proposal under review, not as settled naming.
 */
object LineShortNames {

    /**
     * Proposed short forms, keyed by canonical line id.
     *
     * Conventions used, so future additions stay consistent:
     *  - **Real TfL abbreviations win.** "H&C" and "W&C" are what the roundels,
     *    the tube map key and station signage already use — inventing "Hamm."
     *    would be a worse label than the one passengers can see on the wall.
     *  - Otherwise: first syllable, trailing period ("Dist.", "Picc."). Long
     *    enough to disambiguate — "Cen." not "C." — because a board is read at a
     *    glance and a single letter is a puzzle.
     *  - Initialisms that are already the line's name keep it whole and take no
     *    period: DLR, Tram.
     *  - The 2024 Overground line names are short enough to survive light
     *    trimming; "Lion." and "Suff." are the only ones that really need it.
     */
    private val SHORT_NAMES: Map<String, String> = mapOf(
        // ── Tube ──
        "bakerloo" to "Bak.",
        "central" to "Cen.",
        "circle" to "Circ.",
        "district" to "Dist.",
        "hammersmith-city" to "H&C",
        "jubilee" to "Jub.",
        "metropolitan" to "Met.",
        "northern" to "Nor.",
        "piccadilly" to "Picc.",
        "victoria" to "Vic.",
        "waterloo-city" to "W&C",
        // ── Other rail ──
        "dlr" to "DLR",
        "elizabeth" to "Eliz.",
        "elizabeth-line" to "Eliz.",
        // ── Overground (2024 names) ──
        "lioness" to "Lion.",
        "mildmay" to "Mild.",
        "windrush" to "Wind.",
        "weaver" to "Weav.",
        "suffragette" to "Suff.",
        "liberty" to "Lib.",
        // ── Everything else ──
        "tram" to "Tram",
        "cable-car" to "Cable",
    )

    /**
     * Short label for a line id.
     *
     * Bus routes fall through unchanged: "53" is already as short as it gets,
     * and there are hundreds of them, so a map would never be complete. Any
     * unknown id is title-cased and returned rather than blanked — an unfamiliar
     * line name is far better than an empty prefix on a departure row.
     */
    fun shortName(line: String?): String {
        val key = line?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return ""
        SHORT_NAMES[key]?.let { return it }
        return key.replaceFirstChar { it.uppercase() }
    }

    /** Full display name, for the single-line case where there is room. */
    fun displayName(line: String?): String {
        val key = line?.trim().orEmpty()
        if (key.isEmpty()) return ""
        return key.split('-').joinToString(" ") { part ->
            part.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Joins the lines sharing one platform: "Dist & Circ.", "Nor, Jub & Vic.".
     *
     * A single line gets its FULL name — one line leaves room for it, and
     * "Northern Platform 2" reads better than "Nor. Platform 2". Two or more
     * switch to short forms, because that is exactly when width runs out.
     *
     * Caps at [MAX_NAMED_LINES]: past that the header is longer than the
     * platform fact it exists to deliver, so it degrades to a count. Four lines
     * sharing one platform is rare but real at the sub-surface interchanges
     * (Baker Street, King's Cross).
     */
    fun joinLines(lines: List<String>): String {
        val distinct = lines.filter { it.isNotBlank() }.distinct()
        return when {
            distinct.isEmpty() -> ""
            distinct.size == 1 -> displayName(distinct[0])
            distinct.size > MAX_NAMED_LINES -> "${distinct.size} lines"
            else -> {
                val shorts = distinct.map { shortName(it) }
                shorts.dropLast(1).joinToString(", ") + " & " + shorts.last()
            }
        }
    }

    private const val MAX_NAMED_LINES = 3
}
