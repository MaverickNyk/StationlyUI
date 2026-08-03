package com.stationly.core.util

import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.core.model.sdui.SduiRouteStop

/**
 * A direction's route from the user's station, as a proper tree.
 *
 * Replaces the earlier trunk-plus-flat-branches model, which computed ONE common
 * prefix across every terminus and then treated each remaining tail as an
 * independent branch. That is wrong wherever branches split at different points:
 * Piccadilly southbound from King's Cross has Uxbridge leaving at Acton Town but
 * Heathrow T4 and T5 staying together all the way to Hatton Cross, so the flat
 * model drew T4 and T5 as two full-length parallel lines that duplicated ~9
 * shared stops. Grouping recursively splits exactly where the route splits.
 *
 * Each node owns the stops travelled before its own divergence; [children] are
 * the routes leaving at its end. A leaf is a terminus.
 */
data class RouteTree(
    /** Stops on this segment, in order. Empty only at a root that splits immediately. */
    val stops: List<SduiRouteStop>,
    /** Sub-routes leaving after [stops]. Empty on a leaf. */
    val children: List<RouteTree>,
    /** Set on a leaf: the terminus this route ends at. */
    val terminusId: String? = null,
    val terminusName: String? = null,
) {
    val isLeaf: Boolean get() = children.isEmpty()

    /** Every stop in the tree, de-duplicated, in route order. */
    val allStops: List<SduiRouteStop>
        get() = (stops + children.flatMap { it.allStops }).distinctBy { it.id }

    /** Leaf count — the number of rows the tree needs when drawn. */
    val leafCount: Int get() = if (isLeaf) 1 else children.sumOf { it.leafCount }

    /**
     * Terminus names reachable from [stopId], or empty if the stop isn't here.
     *
     * Powers the "one pick per branch" rule: two stops sit on the SAME branch
     * exactly when one's reachable set contains the other's, because downstream
     * sets nest along a path and are disjoint across a split.
     */
    fun terminiFrom(stopId: String): Set<String> {
        if (stops.any { it.id == stopId }) return allTermini()
        children.forEach { child ->
            val found = child.terminiFrom(stopId)
            if (found.isNotEmpty()) return found
        }
        return emptySet()
    }

    /**
     * Column of a stop when the tree is drawn left-to-right, so a search hit can
     * be scrolled to. Lives here rather than in the renderer so scrolling and
     * drawing agree by construction. -1 if not present.
     */
    fun indexOfStop(id: String, startCol: Int = 0): Int {
        val i = stops.indexOfFirst { it.id == id }
        if (i >= 0) return startCol + i
        val childStart = startCol + stops.size
        children.forEach { child ->
            val found = child.indexOfStop(id, childStart)
            if (found >= 0) return found
        }
        return -1
    }

    private fun allTermini(): Set<String> =
        if (isLeaf) setOfNotNull(terminusId)
        else children.flatMapTo(mutableSetOf()) { it.allTermini() }

    companion object {
        /**
         * Build from a direction payload.
         *
         * An empty tree means the payload had no `upcomingStops` — a response
         * cached before the backend served ids. Callers must read that as
         * "cannot filter", never as "no stops".
         */
        fun from(direction: SduiDropdownOption): RouteTree {
            val runs = (direction.destinations ?: emptyList()).mapNotNull { dest ->
                val stops = dest.upcomingStops?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Run(dest.id, dest.label, stops)
            }
            if (runs.isEmpty()) {
                return RouteTree(direction.upcomingStops.orEmpty(), emptyList())
            }
            return build(runs)
        }

        private data class Run(val id: String, val name: String, val stops: List<SduiRouteStop>)

        private fun build(runs: List<Run>): RouteTree {
            if (runs.size == 1) {
                val r = runs[0]
                return RouteTree(r.stops, emptyList(), r.id, r.name)
            }

            // Longest prefix shared by EVERY run here, compared on naptan id —
            // display names are formatted and two stops can format identically,
            // which would merge a real divergence.
            val first = runs[0].stops
            var shared = 0
            while (shared < first.size &&
                runs.all { shared < it.stops.size && it.stops[shared].id == first[shared].id }
            ) shared++

            val head = first.take(shared)

            // Group the remainders by their next stop: runs that still travel
            // together recurse as one child and split further down.
            val groups = LinkedHashMap<String, MutableList<Run>>()
            for (r in runs) {
                val rest = r.stops.drop(shared)
                if (rest.isEmpty()) {
                    // This run terminates exactly where the others diverge (e.g.
                    // Kensington (Olympia)). It is a leaf of zero further stops,
                    // keyed on itself so it cannot collide with a real branch.
                    groups.getOrPut("__end_${r.id}") { mutableListOf() }.add(r.copy(stops = emptyList()))
                } else {
                    groups.getOrPut(rest[0].id) { mutableListOf() }.add(r.copy(stops = rest))
                }
            }

            return RouteTree(head, groups.values.map { build(it) })
        }
    }
}
