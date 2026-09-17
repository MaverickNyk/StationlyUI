package com.stationly.core.util

import com.stationly.core.model.FilterMode
import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.core.model.sdui.SduiRouteStop

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
        /**
         * Branch tokens of the patterns that actually reach the chosen stop.
         *
         * The second half of the runtime check, and the half that fixes
         * rejoining lines. [destinationIds] answers "could a train ending there
         * have passed my stop"; once two branches meet again the answer is yes
         * for BOTH branches and only this can separate them.
         *
         * EMPTY means the patterns carry no discriminator — TfL publishes none
         * for the four Metropolitan runs to Aldgate — and callers must then
         * ignore it rather than reject everything.
         */
        val viaKeys: List<String> = emptyList(),
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
        val viaKeys = LinkedHashSet<String>()

        // Every distinct service pattern when the backend supplies them,
        // falling back to the terminus-keyed chips otherwise. The fallback is
        // the OLD behaviour exactly, including its blind spot: it cannot see a
        // branch that shares a terminus with another, because that branch was
        // deleted before it arrived.
        val runs: List<Pair<List<SduiRouteStop>, String?>> =
            direction.patterns
                ?.takeIf { it.isNotEmpty() }
                ?.map { it.stops to it.viaKey }
                ?: (direction.destinations ?: emptyList())
                    .mapNotNull { d -> d.upcomingStops?.let { it to null } }

        // Set when some chosen stop is served by EVERY pattern. Such a stop
        // cannot narrow by branch — asking for "trains through Euston" at Camden
        // Town is asking for all of them — and applying a token check anyway
        // could only ever hide a train whose label we failed to recognise.
        var anyStopServedByAll = false

        for (viaStopId in viaStopIds) {
            var reached = 0
            for ((stops, viaKey) in runs) {
                val idx = stops.indexOfFirst { it.id == viaStopId }
                if (idx < 0) continue // this branch never reaches the via stop
                reached++
                for (i in idx until stops.size) allowed[stops[i].id] = stops[i].name
                // Only patterns that REACH the stop contribute a token, which is
                // what makes the check exclusive: at Camden Town "via Bank"
                // collects `bank` and nothing else, so a Charing Cross train is
                // turned away even though it ends at the same Morden.
                if (viaKey != null) viaKeys += viaKey
            }
            if (reached > 0 && reached == runs.size) anyStopServedByAll = true

            // A stop may exist only on the shared trunk when no branch data is
            // present; fall back to the direction-level list so those boards
            // still resolve.
            if (reached == 0) {
                val trunk = direction.upcomingStops ?: emptyList()
                val idx = trunk.indexOfFirst { it.id == viaStopId }
                if (idx >= 0) {
                    for (i in idx until trunk.size) allowed[trunk[i].id] = trunk[i].name
                    // The trunk is every branch by definition, so this is the
                    // same "narrows nothing" case.
                    anyStopServedByAll = true
                }
            }
        }

        return if (allowed.isEmpty()) EMPTY
        else Resolution(
            allowed.keys.toList(),
            allowed.values.toList(),
            if (anyStopServedByAll) emptyList() else viaKeys.toList(),
        )
    }

    /**
     * "Only trains running these services."
     *
     * What a terminus chip means, and a different question from [resolveVia].
     * "All Morden via Bank trains" is a statement about WHERE A TRAIN GOES;
     * "trains through Bank" is a statement about a PLACE. Forcing the first
     * through the second is what made tapping a branch tick a station in the
     * middle of it — the only way to say "this branch" was to name the first
     * stop nothing else reached.
     *
     * A departure matches when its destination is somewhere on the chosen
     * pattern AND its branch token is one of theirs. The stop list is the whole
     * pattern rather than just its terminus, so a service that turns short part
     * way down the branch still counts — it is the same service, and hiding it
     * would be the same mistake a terminus-only allow-list has always made.
     */
    fun resolvePatterns(
        direction: SduiDropdownOption,
        chosenPatternIds: Set<String>,
    ): Resolution {
        if (chosenPatternIds.isEmpty()) return EMPTY
        val all = direction.patterns ?: return EMPTY
        val chosen = all.filter { it.id in chosenPatternIds }
        if (chosen.isEmpty()) return EMPTY

        // From where the branch BECOMES this branch, not from the origin.
        //
        // A pattern's stop list starts at the station you are standing at, so it
        // includes the trunk every service shares. Taking all of it means "All
        // Battersea trains" admits anything ending anywhere on the way there —
        // a Kennington turn-back that never goes near Battersea matched, because
        // Kennington is on the Battersea run.
        //
        // The divergence point is the longest prefix this pattern shares with
        // any sibling. Past it, only this service goes, so a train ending past it
        // committed to this branch. Short workings DOWN the branch still match,
        // which is the whole reason this is a corridor and not a terminus list.
        val allowed = LinkedHashMap<String, String>()
        chosen.forEach { p ->
            var diverge = 0
            all.forEach { other ->
                if (other.id != p.id) {
                    var i = 0
                    while (i < p.stops.size && i < other.stops.size &&
                        p.stops[i].id == other.stops[i].id
                    ) i++
                    if (i > diverge) diverge = i
                }
            }
            for (i in diverge until p.stops.size) allowed[p.stops[i].id] = p.stops[i].name
        }

        // Same rule as resolveVia: a token set naming every service narrows
        // nothing, and keeping it could only hide a train whose label we failed
        // to parse.
        val keys = chosen.mapNotNull { it.viaKey }.toSet()
        val narrows = chosen.size < all.size && keys.isNotEmpty()

        return if (allowed.isEmpty()) EMPTY
        else Resolution(
            allowed.keys.toList(),
            allowed.values.toList(),
            if (narrows) keys.toList() else emptyList(),
        )
    }

    /** Dispatch on [FilterMode]. [ALL][FilterMode.ALL] resolves to no filtering. */
    fun resolve(
        mode: FilterMode,
        direction: SduiDropdownOption,
        chosenDestinationIds: Set<String> = emptySet(),
        viaStopIds: Set<String> = emptySet(),
        chosenPatternIds: Set<String> = emptySet(),
    ): Resolution = when (mode) {
        FilterMode.ALL -> EMPTY
        FilterMode.DESTINATIONS -> resolveDestinations(direction, chosenDestinationIds)
        // Stops and whole branches are both "narrow this board", and a user can
        // mix them: two stops on one branch plus all of another. Union the two
        // resolutions rather than making them exclusive modes, which would force
        // a choice the map does not present.
        FilterMode.VIA -> merge(
            resolveVia(direction, viaStopIds),
            resolvePatterns(direction, chosenPatternIds),
        )
    }

    /**
     * Union of two resolutions.
     *
     * Both halves widen what is shown, so the ids union. The BRANCH tokens union
     * too, with one exception that matters: if either half narrows by nothing,
     * the combined filter narrows by nothing, or a stop every service reaches
     * would start excluding services the other half admitted.
     */
    private fun merge(a: Resolution, b: Resolution): Resolution {
        if (a.isEmpty) return b
        if (b.isEmpty) return a
        val ids = LinkedHashMap<String, String>()
        a.destinationIds.forEachIndexed { i, id -> ids[id] = a.destinationNames.getOrElse(i) { id } }
        b.destinationIds.forEachIndexed { i, id -> ids[id] = b.destinationNames.getOrElse(i) { id } }
        val keys = if (a.viaKeys.isEmpty() || b.viaKeys.isEmpty()) emptyList()
                   else (a.viaKeys + b.viaKeys).distinct()
        return Resolution(ids.keys.toList(), ids.values.toList(), keys)
    }

}
