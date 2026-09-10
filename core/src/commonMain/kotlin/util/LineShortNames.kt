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
     * ## The precedence, and why it is this way round
     *  1. **[LineNameStore]** — what the BACKEND said, if it has ever said
     *     anything about this line. Serving the names is what turns a rename
     *     into a deploy instead of a release on two app stores, so a served
     *     answer always wins over a compiled-in one.
     *  2. **[SHORT_NAMES]** — the local map, now a FALLBACK rather than the
     *     source. It still answers for every launch before the first line fetch,
     *     for a reinstall, and for a backend that has not shipped the field.
     *  3. **Title-case the id.** Bus routes land here and always will: "53" is
     *     already as short as a label gets and there are hundreds of them, so
     *     neither map will ever be complete. An unfamiliar line name is far
     *     better than an empty prefix on a departure row.
     *
     * Nothing here can fail: each step degrades into the next, and the last one
     * always produces a string. That is what makes adding the backend as step 1
     * incapable of regressing a board that was rendering fine without it.
     */
    fun shortName(line: String?): String {
        val key = line?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return ""
        LineNameStore.shortNameOrNull(key)?.let { return it }
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
     * Rewrite any FULL line name in a finished string to its short form —
     * "Hammersmith City Platform 1" → "H&C Platform 1".
     *
     * ## Why this works on the assembled string
     * It is a rung of [MultiLineBoardProcessor.headerVariants], and that ladder
     * is handed a header that has already been built. Taking the line list
     * instead would mean every caller that wants to shrink a header also has to
     * carry the lines that went into it — which the collapsed card, the compact
     * board and the widget's own renderer do not have and should not need.
     *
     * The mapping is exact and closed: the only full names that can appear here
     * are the ones [displayName] produces from [SHORT_NAMES]' own keys, so this
     * cannot match a station or destination by accident ("Northern" the line
     * versus "Northern" in a place name is not a case that arises — headers
     * carry line names, platform labels and compass directions and nothing
     * else).
     *
     * Longest first, so a name that contains another is not half-replaced.
     */
    fun abbreviate(text: String): String {
        if (text.isBlank()) return text
        return KNOWN_LINE_IDS.fold(text) { acc, id ->
            val full = displayName(id)
            // Resolved through [shortName] per call, NOT precomputed: the store
            // is populated asynchronously, so a map memoised at first use would
            // pin this to the local table for the life of the process and the
            // backend's names would only ever apply after a restart.
            val short = shortName(id)
            if (short.length < full.length) acc.replace(full, short) else acc
        }
    }

    /**
     * Every line id with a known short form, longest DISPLAY name first.
     *
     * The order is the point: a name that contains another must be replaced
     * first, or the shorter one half-rewrites it. The list itself is static —
     * which lines exist does not change at runtime, only what they are CALLED —
     * so this is the part that is safe to memoise.
     *
     * Derived from [SHORT_NAMES] rather than written out again: a second literal
     * list would be one more place to forget when a line is renamed, which is
     * the failure this whole file is documented as a stopgap against.
     */
    private val KNOWN_LINE_IDS: List<String> by lazy {
        SHORT_NAMES.keys.sortedByDescending { displayName(it).length }
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
