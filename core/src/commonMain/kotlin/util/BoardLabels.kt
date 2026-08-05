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
 */
object BoardLabels {

    /**
     * [title] distinguishes this board from its sibling on the same line;
     * [detail] carries whatever the title did not, or null.
     */
    data class Label(val title: String, val detail: String?)

    /** Shown when a board has no direction, no filter and no departures at all. */
    const val EVERY_DEPARTURE = "All departures"

    /**
     * Name one board, given where its trains are currently heading.
     *
     * A priority order, not a field. The title has to distinguish this board from
     * its sibling in words the user recognises as THEIR choice:
     *
     *  1. **A compass bearing** — "Northbound". What the platform signage says,
     *     and what they picked in the line picker.
     *  2. **The filter they set** — "Via Green Park", "Only Brixton". On a line
     *     whose directions are not compass, the filter is usually the reason
     *     there are two boards at all.
     *  3. **Where the trains actually go** — "Towards Putney Bridge", from
     *     [towards] (the caller reads it out of cached departures).
     *
     * "Inbound"/"Outbound" is the LAST resort rather than the first, which is
     * what it used to be. It means "towards the centre of the network" — an
     * operational fact about TfL, not about the user's journey — so it tells
     * someone choosing between two boards almost nothing. Every rung above it is
     * a phrase they would use themselves.
     *
     * This is the one surface where that word may appear at all: the board never
     * shows it (see [MultiLineBoardProcessor.compassOrNull]), but here the user
     * is identifying which of two boards to act on, and "the other one" is not a
     * label.
     */
    fun forBoard(board: UserSelection, towards: String? = null): Label {
        val compass = MultiLineBoardProcessor.compassOrNull(board.direction)
            ?.replaceFirstChar { it.uppercase() }
        val filter = filterLabel(board)
        val heading = towards?.trim()?.takeIf { it.isNotEmpty() }?.let { "Towards $it" }

        return when {
            compass != null -> Label(compass, filter ?: heading)
            filter != null -> Label(filter, heading)
            heading != null -> Label(heading, null)
            // Nothing to go on: no compass, no filter, and no cached departures
            // yet (a board added seconds ago, or one whose service has not run
            // today). The operator's own word is all that is left, and it does
            // at least separate the two rows.
            else -> Label(
                board.direction.trim().replaceFirstChar { it.uppercase() }
                    .ifBlank { EVERY_DEPARTURE },
                null,
            )
        }
    }

    /**
     * "Only Brixton", "Via Green Park" — or null when the board shows everything.
     *
     * VIA reports the stops the user ASKED to travel through rather than the
     * destinations that compiled to, because the resolution is an allow-list of
     * every stop beyond the via point and reading it back would name places the
     * user never mentioned.
     */
    fun filterLabel(board: UserSelection): String? = when {
        board.isUnfiltered -> null
        board.filterMode == FilterMode.VIA -> when (board.viaStationNames.size) {
            0 -> "Filtered"
            1 -> "Via ${board.viaStationNames[0]}"
            else -> "Via ${board.viaStationNames.size} stops"
        }
        board.destinations.size == 1 -> "Only ${board.destinations[0]}"
        // A resolved filter whose display names never arrived. Saying "filtered"
        // is honest; saying nothing would claim the board shows everything.
        board.destinations.isEmpty() -> "Filtered"
        else -> "${board.destinations.size} destinations"
    }
}
