package com.stationly.core.util

import com.stationly.core.model.FilterMode
import com.stationly.core.model.sdui.SduiDropdownOption

/**
 * Turns a user's filter INTENT ("only Heathrow trains", "anything through Green
 * Park") into the flat allow-list of destination naptan ids the runtime check
 * uses.
 *
 * All the difficulty lives here, and it runs ONCE — when the board is saved, or
 * when a stale resolution is refreshed. Every filter kind compiles down to the
 * same list, so the ingest path stays a single set lookup per departure and the
 * render path does nothing at all.
 */
object BoardFilterResolver {

    /** A resolved filter: what to store on the selection. */
    data class Resolution(
        val destinationIds: List<String>,
        val destinationNames: List<String>,
    ) {
        val isEmpty: Boolean get() = destinationIds.isEmpty()
    }

    val EMPTY = Resolution(emptyList(), emptyList())

    /**
     * "Only trains terminating at these destinations."
     *
     * Deliberately EXACT — the chosen termini and nothing else. A user who asks
     * for Heathrow trains does not want one that turns short at Northfields,
     * even though it travels the same way. That is the opposite of [resolveVia]
     * on purpose: the two modes answer different questions.
     */
    fun resolveDestinations(
        direction: SduiDropdownOption,
        chosenDestinationIds: Set<String>,
    ): Resolution {
        val chosen = (direction.destinations ?: emptyList())
            .filter { it.id in chosenDestinationIds }
        return Resolution(
            destinationIds = chosen.map { it.id },
            destinationNames = chosen.map { it.label },
        )
    }

    /**
     * "Only trains that call at [viaStopId]."
     *
     * Returns the via stop plus EVERY stop beyond it, across every branch that
     * reaches it — not the branch termini.
     *
     * Storing the downstream closure rather than the termini is the whole point.
     * TfL turns trains short constantly, especially off-peak and during
     * disruption, and a terminus-only allow-list would hide them: a Piccadilly
     * service showing "Northfields" genuinely does call at Green Park, but
     * Northfields is not one of {Heathrow, Uxbridge}. Because any destination
     * beyond the via stop can only be reached THROUGH it, membership of this set
     * is exactly the question "does this train pass my station".
     *
     * Branch-correct by construction: only destinations whose own run contains
     * the via stop contribute, so a District train to Wimbledon never matches
     * "via Turnham Green" — that branch diverges before it.
     *
     * Returns [EMPTY] when the payload has no [SduiDropdownOption.upcomingStops]
     * (older backend or a cached pre-upgrade payload). Empty means "no filter",
     * so the board shows everything rather than silently hiding trains.
     */
    fun resolveVia(
        direction: SduiDropdownOption,
        viaStopIds: Set<String>,
    ): Resolution {
        if (viaStopIds.isEmpty()) return EMPTY

        // Insertion-ordered and de-duplicated: branches share a trunk, so the
        // same stop is reached via several destinations, and several picked
        // stops on one branch overlap heavily.
        val allowed = LinkedHashMap<String, String>()

        for (viaStopId in viaStopIds) {
            var matchedBranch = false
            for (dest in direction.destinations ?: emptyList()) {
                val stops = dest.upcomingStops ?: continue
                val idx = stops.indexOfFirst { it.id == viaStopId }
                if (idx < 0) continue // this branch never reaches the via stop
                matchedBranch = true
                for (i in idx until stops.size) allowed[stops[i].id] = stops[i].name
            }

            // A stop may exist only on the shared trunk when no branch data is
            // present; fall back to the direction-level list so those boards
            // still resolve.
            if (!matchedBranch) {
                val trunk = direction.upcomingStops ?: emptyList()
                val idx = trunk.indexOfFirst { it.id == viaStopId }
                if (idx >= 0) for (i in idx until trunk.size) allowed[trunk[i].id] = trunk[i].name
            }
        }

        return if (allowed.isEmpty()) EMPTY
        else Resolution(allowed.keys.toList(), allowed.values.toList())
    }

    /** Dispatch on [FilterMode]. [ALL][FilterMode.ALL] resolves to no filtering. */
    fun resolve(
        mode: FilterMode,
        direction: SduiDropdownOption,
        chosenDestinationIds: Set<String> = emptySet(),
        viaStopIds: Set<String> = emptySet(),
    ): Resolution = when (mode) {
        FilterMode.ALL -> EMPTY
        FilterMode.DESTINATIONS -> resolveDestinations(direction, chosenDestinationIds)
        FilterMode.VIA -> resolveVia(direction, viaStopIds)
    }

}
