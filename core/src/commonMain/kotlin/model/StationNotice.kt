package com.stationly.core.model

import com.stationly.core.util.LineStatusRanker
import kotlinx.serialization.Serializable

/**
 * What the backend says about one stop: closed, part closed, exit only, or
 * closing later. Words and all; the app decides only where they go.
 *
 * Server half: `StationlySyncer/.../notices/StationNotice.java`.
 */
@Serializable
data class StationNotice(
    val id: String = "",
    /** `closed`, `part_closed`, `exit_only`, `closing`; `none` in a push clears. Anything else is ignored. */
    val kind: String = "",
    val title: String = "",
    val details: List<String> = emptyList(),
    val strip: String = "",
    val reason: String = "",
    val directions: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
    /** Whether a closed DIRECTION is certainly every train that way here. Absent is not. */
    val certain: Boolean = false,
    val from: Long = 0L,
    val to: Long = 0L,
    /** When to stop believing it. 0 (absent) is never believed. */
    val expiresAt: Long = 0L,
    /** When the backend said it. 0 (absent) never overrides one that says when. */
    val at: Long = 0L,
)

@Serializable
data class StationNotices(
    /**
     * Stops the answer covers, each with when it was learned: one here with no
     * notice is open, one not here is unknown.
     */
    val known: Map<String, Long> = emptyMap(),
    val notices: List<StationNotice> = emptyList(),
)

/** A [StationNotice] as it applies to one board, now. */
data class BoardNotice(
    val title: String,
    val details: List<String>,
    val strip: String,
    val reason: String,
    /** Whether it replaces the departures. Only when it is certain nothing can be caught here. */
    val hides: Boolean,
    /** TfL's own words without what the title says, a sentence each: for [rowsThatFit]. */
    val says: List<String> = emptyList(),
) {
    /**
     * The rows under the title: TfL's words if all of them fit, else the short details,
     * wrapped if that fits, as on the smallest widget it must.
     */
    fun rows(most: Int, fits: (String) -> Boolean): List<String> =
        rowsThatFit(says, most, fits) ?: rowsThatFit(details, most, fits) ?: details

    /**
     * The status strip where there is room for one thing: this, with a disrupted line in
     * the part that scrolls. [line] is null where no board is left open for it to be about. Severity to reason.
     */
    fun stripWith(line: Pair<String, String>?): Pair<String, String> =
        if (line == null || line.first.isBlank() || LineStatusRanker.isGoodService(line.first)) strip to reason
        else strip to listOf(reason, listOf(line.first, line.second).filter { it.isNotBlank() }.joinToString(": ")).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * This notice on [selection]'s board at [nowMs], or null when it is not about it.
 *
 * [platforms] are the platform labels of the board's own departures, and the only
 * place it says which way it faces. NOT [UserSelection.directionName]: that is a
 * bearing on the map ("Southbound" at Arsenal), where the platform is "Westbound".
 */
fun StationNotice.forBoard(selection: UserSelection, nowMs: Long, platforms: List<String> = emptyList()): BoardNotice? {
    if (nowMs >= expiresAt || nowMs >= to) return null
    fun notice(hides: Boolean, strip: String = this.strip) =
        BoardNotice(title, details, strip, reason, hides, if (kind == "closing") emptyList() else sentences(reason))
    return when (kind) {
        "closed" -> if (nowMs < from) null else notice(hides = true)
        // Sent before it happens, so the board changes on time without a push.
        "closing" -> if (nowMs < from) notice(hides = false) else notice(hides = true, strip = title)
        "exit_only" -> notice(hides = false)
        "part_closed" -> when {
            lines.isNotEmpty() -> if (lines.any { lineKey(it) == lineKey(selection.line) }) notice(hides = true) else null
            directions.isNotEmpty() -> {
                val closed = directions.map(String::lowercase)
                val facing = compassIn(selection.direction)?.let { listOf(it) } ?: platforms.map(::compassIn).distinct()
                when {
                    // Every departure must say so. Both ways, or no way we can tell: said, not hidden.
                    facing.isNotEmpty() && facing.all { it in closed } -> notice(hides = certain)
                    facing.isNotEmpty() && facing.none { it == null || it in closed } -> null
                    else -> notice(hides = false)
                }
            }
            else -> null
        }
        else -> null
    }?.takeIf { it.strip.isNotBlank() && (!it.hides || it.title.isNotBlank()) }
}

/**
 * [says] as at most [rows] board rows, or null when it does not fit and the short
 * details serve better. [fits] is the surface's own: it knows its width and type.
 * A sentence that needs two rows fills both alike, not all but a word in one.
 */
fun rowsThatFit(says: List<String>, rows: Int, fits: (String) -> Boolean): List<String>? {
    fun wrap(sentence: String, ok: (String) -> Boolean): List<String> =
        sentence.split(' ').fold(listOf()) { lines, word ->
            val longer = lines.lastOrNull()?.let { "$it $word" }
            if (longer != null && ok(longer)) lines.dropLast(1) + longer else lines + word
        }
    val out = says.flatMap { sentence ->
        val full = wrap(sentence, fits)
        val widest = full.maxOf { it.length }
        (1..widest).firstNotNullOf { most ->
            wrap(sentence) { it.length <= most }.takeIf { it.size <= full.size || most == widest }
        }.takeIf { it.size == full.size && it.all(fits) } ?: full
    }
    return out.takeIf { it.isNotEmpty() && it.size <= rows && it.all(fits) }
}

/** What the title has said already. */
private val TITLE_SAYS = Regex(
    "^(?:(?:this )?(?:bus )?stop (?:is )?closed|(?:this|the) station (?:is|will be) closed|closed to [\\w& ]+ line trains|the \\w+bound platform (?:at [\\w' ]+ )?(?:is|will be) closed|closed)\\b[ ,.-]*",
    RegexOption.IGNORE_CASE,
)
private val NOT_FOR_A_BOARD = Regex("\\S*tfl\\.gov\\.uk\\S*|\\bfor (?:more|further) information\\b.*|\\bcall(?: us)? on\\b.*", RegexOption.IGNORE_CASE)

private fun sentences(reason: String): List<String> =
    reason.replace(NOT_FOR_A_BOARD, "").trim().replace(TITLE_SAYS, "")
        // Not after "St. Pancras".
        .split(Regex("(?<=[a-z0-9][a-z0-9][.!]) +| +(?=Please\\b)"))
        .map { it.trim(' ', '.', ',') }.filter { it.isNotEmpty() }
        .map { it.replaceFirstChar(Char::uppercase) }

private val COMPASS = Regex("(north|south|east|west)bound", RegexOption.IGNORE_CASE)
private fun compassIn(text: String): String? = COMPASS.find(text)?.value?.lowercase()

/** "Hammersmith & City" and "hammersmith-city" are one line. */
private fun lineKey(line: String): String =
    line.lowercase().replace(Regex("\\band\\b"), "").filter { it.isLetterOrDigit() }
