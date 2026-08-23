package com.stationly.core.util

import com.stationly.core.model.FilterMode
import com.stationly.core.model.UserSelection

/**
 * How one tracked board names itself away from the departure board — on the
 * station settings screen, where the user picks which of several boards to edit
 * or delete.
 *
 * Lives in core, like every other presentation rule ([MultiLineBoardProcessor],
 * [LineStatusRanker], [LineShortNames]), because it is a decision with real
 * precedence in it and Compose is the wrong place to keep one untested.
 *
 * ## Everything here comes from the SAVED SELECTION, and nothing from departures
 * This used to take a `towards` string read out of cached predictions, and lead
 * with it whenever the direction was not a compass bearing. That described what
 * the line happened to be running when the cache was last written, which is a
 * different question from the one this screen asks. Two consequences, both seen:
 *
 *  - A board the user saved as plain "Inbound" — every destination — was
 *    labelled "Towards Ealing Broadway", because most trains in the cache
 *    happened to end there. It reads as a filter the user never set.
 *  - The same board said different things on different days, and said nothing
 *    at all on a board whose service had not run yet.
 *
 * The selection already holds exactly what was chosen: a direction, and either
 * nothing, a destination list, or a set of via stops and services. That is what
 * the user picked, it cannot drift, and it is available offline and instantly.
 * **Do not reintroduce a departures-derived label here.**
 *
 * ## The words are the picker's own words
 * "Finishing at" and "Going through" are the labels on the two filter modes in
 * `BoardFilterSheet`. A user who set a filter reads it back in the phrase they
 * set it with, rather than having to match a description to a decision.
 */
object BoardLabels {

    /**
     * [title] is the direction, which is what distinguishes this board from its
     * sibling on the same line; [detail] is what that direction shows, and is
     * never blank — a board nobody narrowed says so.
     */
    data class Label(val title: String, val detail: String)

    /** Title for a board with no direction recorded at all. */
    const val EVERY_DEPARTURE = "All departures"

    /** Detail for a board the user never narrowed. */
    const val EVERY_DESTINATION = "All destinations"

    /** Name one board from what the user saved for it. */
    fun forBoard(board: UserSelection): Label {
        // Resolved ONCE and handed down. The detail line's job is to add to the
        // title rather than repeat it, which it cannot judge without knowing
        // what the title actually turned out to be — see [detailLabel].
        val title = directionLabel(board)
        return Label(title, detailLabel(board, title))
    }

    /**
     * What this board shows, in one line that is never blank.
     *
     * A filter when there is one; otherwise the destinations the direction
     * actually serves, which is the honest answer to "what will I see here" and
     * the one the user asked for. [EVERY_DESTINATION] is the last resort, for a
     * board whose route data has not been stored or backfilled yet.
     */
    fun detailLabel(board: UserSelection): String =
        detailLabel(board, directionLabel(board))

    /**
     * ⚠️ The repetition test reads the TITLE, not [UserSelection.directionTowards].
     *
     * It used to compare the sole destination against `directionTowards` and
     * fall back to [EVERY_DESTINATION] whenever they matched. That is right for
     * the case it was written for — a bus titled "Towards Putney Bridge" going
     * only to Putney Bridge has nothing left to say — and wrong everywhere else,
     * because `towards` is served for RAIL too and loses to a compass bearing in
     * [directionLabel]. A Victoria board came out titled "Southbound" with
     * `towards = "Brixton"` and one destination "Brixton", and answered "what
     * does this board show" with "All destinations" — suppressing the one word
     * ("To Brixton") the row existed to print, because a string the user never
     * sees happened to match.
     *
     * Asking the title is the same rule stated against the thing it is actually
     * about, so it cannot come apart from [directionLabel] again when the
     * precedence there changes.
     */
    private fun detailLabel(board: UserSelection, title: String): String {
        filterLabel(board)?.let { return it }
        val destinations = board.directionDestinations.filter { it.isNotBlank() }
        if (destinations.isEmpty()) return EVERY_DESTINATION
        val titleAlreadySaysIt = destinations.size == 1 &&
            title.contains(destinations[0].trim(), ignoreCase = true)
        if (titleAlreadySaysIt) return EVERY_DESTINATION
        return "To ${joinOrCount(destinations, "destinations")}"
    }

    /**
     * The direction, in the words the platform uses where there are any.
     *
     * A compass bearing is what the signage says and what the user picked, so it
     * wins. "Inbound"/"Outbound" is the fallback rather than the preference: it
     * means "towards the centre of the network", an operational fact about TfL
     * rather than about the journey. It is kept because the user did choose it
     * and it does separate two rows — but it is the last thing tried.
     *
     * This is the one surface where those two words may appear at all. The board
     * never shows them; see [MultiLineBoardProcessor.compassOrNull].
     */
    fun directionLabel(board: UserSelection): String {
        // What the picker showed, when we stored it. The server decided this
        // string and the user chose from it, so nothing local may override it.
        board.directionName.trim().takeIf { it.isNotEmpty() && !it.equals("Towards", true) }
            ?.let { return it }
        // The direction id is already a compass word on some feeds.
        MultiLineBoardProcessor.compassOrNull(board.direction)?.let {
            return it.replaceFirstChar { c -> c.uppercase() }
        }
        // Rows saved before `directionName` existed, and anything the table
        // below cannot place.
        compassFallback(board.line, board.direction, board.mode)?.let { return it }
        // No bearing exists for this mode — every bus. TfL gives a route no
        // direction, so the only thing a passenger at the stop can act on is
        // where it is going. This is the picker's own headline.
        board.directionTowards.trim().takeIf { it.isNotEmpty() }
            ?.let { return "Towards $it" }
        return board.direction.trim()
            .replaceFirstChar { it.uppercase() }
            .ifBlank { EVERY_DEPARTURE }
    }

    /**
     * [directionLabel] phrased to sit INSIDE a sentence — "Remove Victoria
     * southbound?".
     *
     * A bearing is a single word and lowercases cleanly. Anything with a space
     * in it carries a place name and must be left exactly as it is: buses are
     * labelled "Towards Putney Bridge", and lowercasing that produced **"Remove
     * Bus 39 towards putney bridge?"** on the one dialog where the user is
     * deciding whether to delete something.
     *
     * The space test is the rule rather than a list of known bearings, so a
     * bearing added later (or a localisation) cannot fall through it.
     */
    fun directionPhrase(board: UserSelection): String {
        val label = directionLabel(board)
        return if (label.any { it.isWhitespace() }) label else label.lowercase()
    }

    /**
     * "Inbound"/"Outbound" turned into the word on the platform, for boards
     * saved before [UserSelection.directionName] was stored.
     *
     * ⚠️ **A MIRROR, not a source.** The authority is `getCompassDirection` in
     * the backend's `src/controllers/lineController.ts`, which is what fills
     * `directionName` and therefore what every board saved from now on carries.
     * This exists so the several hundred boards already on people's phones stop
     * saying "Inbound" without waiting on a re-fetch, and so the settings screen
     * keeps working offline. **If the two ever disagree, the backend is right.**
     *
     * Kept deliberately small: it maps only what TfL's raw direction cannot say
     * for itself. Bus is excluded — the backend returns the literal "Towards"
     * there, which is not a direction name at all, so a bus board falls through
     * to its destinations instead.
     */
    fun compassFallback(line: String, direction: String, mode: String): String? {
        if (mode.equals("bus", ignoreCase = true)) return null
        val inbound = when (direction.trim().lowercase()) {
            "inbound" -> true
            "outbound" -> false
            else -> return null
        }
        return when (line.trim().lowercase()) {
            // North/south lines. Piccadilly runs under N/S platform signage.
            "victoria", "northern", "bakerloo", "piccadilly",
            "lioness", "weaver",
            "windrush", "liberty" -> if (inbound) "Southbound" else "Northbound"
            // DLR is signed the other way round from the tube lines above.
            "dlr" -> if (inbound) "Northbound" else "Southbound"
            "mildmay" -> if (inbound) "Eastbound" else "Westbound"
            "suffragette" -> if (inbound) "Westbound" else "Eastbound"
            // The Circle is a loop, so it has no bearing at all.
            "circle" -> if (inbound) "Clockwise" else "Anticlockwise"
            // TfL labels the WESTERN termini inbound on these two, which is the
            // opposite of the east/west default below.
            "district", "metropolitan" -> if (inbound) "Westbound" else "Eastbound"
            else -> if (inbound) "Eastbound" else "Westbound"
        }
    }

    /**
     * What this board shows within its direction, or null when it shows all of
     * it. [forBoard] turns that null into [EVERY_DESTINATION]; the nullable form
     * exists for callers that need to know whether a filter is set at all.
     *
     * VIA reports what the user ASKED for rather than the destinations it
     * compiled to, because the resolution is an allow-list of every stop beyond
     * the via point and reading it back would name places they never mentioned.
     *
     * A VIA board can hold two kinds of pick and they read differently. A stop is
     * a place you pass, so it takes the picker's "Going through"; a whole service
     * already carries its own name, and "Going through Morden via Bank" is not a
     * sentence.
     *
     * Counts take over from names at three, and the two-item case is spelled out
     * with "and" rather than a comma: "Finishing at Brixton and Morden" is a
     * sentence, "Brixton, Morden" is a fragment of a list.
     */
    fun filterLabel(board: UserSelection): String? = when {
        board.isUnfiltered -> null
        board.filterMode == FilterMode.VIA -> {
            val services = board.patternNames
            val stops = board.viaStationNames
            when {
                // Filtered, with nothing left that can name it. Honest, and it
                // must not fall through to "All destinations" — that would claim
                // the board shows everything when it does not.
                services.isEmpty() && stops.isEmpty() -> "Narrowed to part of this line"
                stops.isEmpty() -> "Only ${joinOrCount(services, "services")}"
                services.isEmpty() -> "Going through ${joinOrCount(stops, "stops")}"
                // Both kinds picked. Neither phrasing fits both, and naming one
                // would hide the other, so it counts them apart.
                else -> "${count(stops, "stop")} and ${count(services, "service")}"
            }
        }
        // A resolved filter whose display names never arrived.
        board.destinations.isEmpty() -> "Finishing at chosen destinations"
        else -> "Finishing at ${joinOrCount(board.destinations, "destinations")}"
    }

    /** Up to two names spelled out; three or more counted. */
    private fun joinOrCount(names: List<String>, plural: String): String = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> "${names.size} $plural"
    }

    /** "1 stop" / "3 stops". */
    private fun count(names: List<String>, singular: String): String =
        "${names.size} $singular" + if (names.size == 1) "" else "s"
}
