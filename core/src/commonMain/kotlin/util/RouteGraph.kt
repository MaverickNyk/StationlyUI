package com.stationly.core.util

import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.core.model.sdui.SduiRoutePattern
import com.stationly.core.model.sdui.SduiRouteStop

/**
 * A direction's route as the network actually is: a graph that can split AND
 * merge.
 *
 * ## Why not a tree
 * [RouteTree] has `stops` and `children` and nothing else, so a line can divide
 * but never come back together. Real ones do: the Northern line splits at Camden
 * Town into a Charing Cross branch and a Bank branch and merges again at
 * Kennington. A tree can only encode that by duplicating the shared tail down
 * both branches, which draws two Kenningtons and two Mordens and tells the user
 * they are different places.
 *
 * ## One marker per station, and why that is right here
 * A station is ONE node, shared by every pattern calling there. Southbound from
 * Camden Town that makes Euston a place where the two branches meet and part
 * again, and Kennington a place where they meet and stay together.
 *
 * The printed tube map draws Euston as two markers joined by an interchange tie,
 * because it is drawing tunnels and platforms. This is drawing an answer to
 * "which trains take me through here", and for that Euston is one answer: every
 * southbound train calls there, whichever branch it then takes. Splitting it in
 * two would offer the user a choice between two Eustons that mean the same
 * thing. The platform difference the map is really showing is surfaced where it
 * is actionable instead — on the branches leaving the station you are standing
 * at, which is the one place TfL gives us a platform for.
 *
 * The only stop drawn twice is one the route genuinely calls at twice: Edgware
 * Road, which the Circle's spiral passes early and again at the end. See
 * [repeatedStops].
 *
 * ## Layout is part of the model, not the renderer
 * Columns and rows live here for the same reason [RouteTree.indexOfStop] did:
 * the canvas, the tappable nodes and the search-scroll all have to agree about
 * where a stop is, and the only way to guarantee that is to compute it once.
 */
data class RouteGraph(
    /** Drawable runs of stops, each on one row. Ordered by [Segment.startCol]. */
    val segments: List<Segment>,
    /** Every service pattern, in draw order. */
    val patterns: List<Pattern>,
    /** Rows the drawing occupies; the renderer sizes itself from this. */
    val rowCount: Int,
    /** Columns the drawing occupies. */
    val colCount: Int,
) {
    /** One service pattern — what the filter resolves against. */
    data class Pattern(
        val id: String,
        val terminusId: String,
        /** Bare terminus — "Morden". What a shared tail is labelled with. */
        val terminusName: String,
        /** Branch-qualified — "Morden via Bank". What a single branch is labelled with. */
        val label: String,
        val viaKey: String?,
    )

    /**
     * A run of consecutive stops travelled by exactly the same set of patterns.
     *
     * Segment boundaries are precisely where the route splits or merges, so a
     * segment is always drawable as one straight horizontal line and every
     * divergence is an edge between segments.
     */
    data class Segment(
        val id: Int,
        val stops: List<SduiRouteStop>,
        /** Grid column of [stops]`[0]`. Each stop occupies one column. */
        val startCol: Int,
        val row: Int,
        /** Patterns travelling this segment. */
        val patternIds: Set<String>,
        val nextIds: List<Int>,
        val prevIds: List<Int>,
    ) {
        val endCol: Int get() = startCol + stops.size - 1
        /** True where this segment is the end of at least one pattern. */
        val isTerminal: Boolean get() = nextIds.isEmpty()
    }

    /**
     * Index built ONCE per graph, not per lookup.
     *
     * Every accessor below is called from composition — the picker resolves a
     * search hit, the covered set, and the branch rule on recomposition — so
     * rebuilding a map or re-sorting the segment list inside each of them turned
     * a handful of lookups into repeated O(segments) work on the render path.
     *
     * `segments` is already ordered by [Segment.startCol]; the builder emits it
     * that way. Nothing here re-sorts it, and nothing should: the order IS the
     * left-to-right reading order the picker draws in.
     */
    private val byId: Map<Int, Segment> by lazy { segments.associateBy { it.id } }

    /** Every segment a stop appears in, in column order. Usually exactly one. */
    private val segmentsByStop: Map<String, List<Segment>> by lazy {
        val out = LinkedHashMap<String, MutableList<Segment>>()
        segments.forEach { seg ->
            seg.stops.forEach { out.getOrPut(it.id) { mutableListOf() }.add(seg) }
        }
        out
    }

    /** Every stop drawn, de-duplicated, in column order. */
    val allStops: List<SduiRouteStop> by lazy {
        segments.flatMap { it.stops }.distinctBy { it.id }
    }

    /**
     * Where a stop sits, so a search hit can be scrolled to. Null if absent.
     *
     * Returns the FIRST occurrence in column order. A stop the route visits
     * twice — Edgware Road on the Circle's spiral — has two, and scrolling to
     * the earlier one is what the user means by "find it".
     */
    fun positionOf(stopId: String): Pair<Int, Int>? {
        val seg = segmentsByStop[stopId]?.firstOrNull() ?: return null
        val i = seg.stops.indexOfFirst { it.id == stopId }
        return if (i < 0) null else (seg.startCol + i) to seg.row
    }

    fun columnOfStop(stopId: String): Int = positionOf(stopId)?.first ?: -1

    /**
     * Every stop reachable from [stopId], itself included.
     *
     * This is what picking a stop actually MEANS: "trains through here" admits
     * every service that gets this far, so the whole of the route beyond it is
     * covered by that one choice. The map draws this so a selection reads as the
     * stretch of line it claims rather than as a single ticked dot — which is
     * what made taking a whole branch look like it had ticked an arbitrary
     * station in the middle.
     *
     * Walks from EVERY occurrence, so a stop the route calls at twice is covered
     * from its first call onward rather than from an arbitrary one.
     */
    fun downstreamOf(stopId: String): Set<String> {
        val starts = segmentsByStop[stopId] ?: return emptySet()
        val out = LinkedHashSet<String>()
        val seen = HashSet<Int>()
        val queue = ArrayDeque<Int>()

        starts.forEach { seg ->
            val i = seg.stops.indexOfFirst { it.id == stopId }
            if (i >= 0) {
                for (n in i until seg.stops.size) out.add(seg.stops[n].id)
                queue.addAll(seg.nextIds)
            }
        }
        while (queue.isNotEmpty()) {
            val seg = byId[queue.removeFirst()] ?: continue
            if (!seen.add(seg.id)) continue
            seg.stops.forEach { out.add(it.id) }
            queue.addAll(seg.nextIds)
        }
        return out
    }

    /**
     * Patterns reachable from [stopId] — the "one pick per branch" rule.
     *
     * Keyed on PATTERN, never terminus: "via Bank" and "via Charing Cross" share
     * one terminus, and a terminus-keyed set makes two genuinely different picks
     * look identical so one is silently dropped.
     *
     * Unions every occurrence. Taking only the first was wrong for the Circle,
     * where the spiral calls at Edgware Road twice and the two calls can sit on
     * segments with different pattern sets.
     */
    fun patternsFrom(stopId: String): Set<String> {
        val segs = segmentsByStop[stopId] ?: return emptySet()
        if (segs.size == 1) return segs[0].patternIds
        return segs.flatMapTo(LinkedHashSet()) { it.patternIds }
    }

    /**
     * Branch tokens of every pattern reaching [stopId].
     *
     * EMPTY means the patterns carry no discriminator — TfL publishes none for
     * the four Metropolitan runs to Aldgate — and callers must read that as
     * "cannot narrow", never as "nothing matches".
     */
    fun viaKeysFrom(stopId: String): Set<String> {
        val ids = patternsFrom(stopId)
        if (ids.isEmpty()) return emptySet()
        return patterns.mapNotNullTo(LinkedHashSet()) { if (it.id in ids) it.viaKey else null }
    }

    /**
     * Stops the route calls at more than once on ONE journey.
     *
     * Not two branches through one station — that is a single node here. This is
     * the Circle's spiral, which passes Edgware Road on the way round and
     * terminates there. Both calls are real, so both are drawn.
     */
    val repeatedStops: Set<String> by lazy {
        segmentsByStop.entries
            .filter { (id, segs) -> segs.sumOf { seg -> seg.stops.count { it.id == id } } > 1 }
            .mapTo(LinkedHashSet()) { it.key }
    }

    companion object {

        /**
         * Build from a direction payload.
         *
         * Prefers [SduiDropdownOption.patterns]; falls back to the
         * terminus-keyed `destinations` for an older backend or a payload
         * cached before patterns existed. The fallback cannot show a branch that
         * shares a terminus with another — that branch was deleted server-side
         * before it arrived — but a partial map beats a blank one.
         */
        fun from(direction: SduiDropdownOption): RouteGraph {
            val runs: List<Run> = direction.patterns
                ?.mapNotNull { p ->
                    p.stops.takeIf { it.isNotEmpty() }?.let {
                        Run(p.id, p.terminusId, p.terminusName, p.label, p.viaKey, it)
                    }
                }
                ?.takeIf { it.isNotEmpty() }
                ?: (direction.destinations ?: emptyList()).mapNotNull { d ->
                    d.upcomingStops?.takeIf { it.isNotEmpty() }
                        ?.let { Run(d.id, d.id, d.label, d.label, null, it) }
                }

            if (runs.isEmpty()) {
                val trunk = direction.upcomingStops.orEmpty()
                return RouteGraph(
                    segments = if (trunk.isEmpty()) emptyList()
                               else listOf(Segment(0, trunk, 0, 0, emptySet(), emptyList(), emptyList())),
                    patterns = emptyList(),
                    rowCount = 1,
                    colCount = trunk.size,
                )
            }
            return build(runs)
        }

        internal data class Run(
            val id: String,
            val terminusId: String,
            val terminusName: String,
            val label: String,
            val viaKey: String?,
            val stops: List<SduiRouteStop>,
        )

        /** One stop of one pattern, before nodes are shared. */
        private data class Cell(val runId: String, val index: Int, val stop: SduiRouteStop)

        private fun build(runs: List<Run>): RouteGraph {
            // Draw order: patterns sharing a prefix must be adjacent rows, or a
            // split fans out across the whole diagram and crosses everything
            // between. Sorting on the stop-id sequence groups them by
            // construction - identical prefixes sort together.
            val ordered = runs.sortedBy { r -> r.stops.joinToString(">") { it.id } }

            // Each pattern as a list of NODE KEYS. A node is (stop, how many
            // times this pattern has already called there), so the Circle's two
            // calls at Edgware Road stay two nodes instead of folding into one
            // and turning the spiral into a loop this model cannot hold.
            val nodeStop = HashMap<String, SduiRouteStop>()
            val nodePatterns = LinkedHashMap<String, LinkedHashSet<String>>()
            val runNodes = LinkedHashMap<String, List<String>>()
            ordered.forEach { run ->
                val seen = HashMap<String, Int>()
                runNodes[run.id] = run.stops.map { stop ->
                    val ord = seen.getOrElse(stop.id) { 0 }
                    seen[stop.id] = ord + 1
                    val key = nodeKey(stop.id, ord)
                    nodeStop[key] = stop
                    nodePatterns.getOrPut(key) { LinkedHashSet() }.add(run.id)
                    key
                }
            }

            val succ = HashMap<String, LinkedHashSet<String>>()
            val pred = HashMap<String, LinkedHashSet<String>>()
            runNodes.values.forEach { keys ->
                keys.zipWithNext { a, b ->
                    succ.getOrPut(a) { LinkedHashSet() }.add(b)
                    pred.getOrPut(b) { LinkedHashSet() }.add(a)
                }
            }

            // COLUMN = longest path from the origin, in topological order.
            //
            // It must be computed over the EDGES, not from each stop's index in
            // its own pattern. Index alone breaks the moment two branches have
            // different lengths: Euston is index 0 on the Bank branch and index 1
            // on the Charing Cross branch, so a max-of-index would put Euston and
            // Bank in the same column and draw one on top of the other.
            //
            // Layering is also what makes a merge line up. Kennington is pushed
            // right until it clears every branch feeding it, so all of them arrive
            // at one column and it becomes ONE marker - a shorter branch simply
            // stretches to meet it, which is what the printed map does too.
            val column = HashMap<String, Int>()
            val indegree = HashMap<String, Int>()
            // Seed EVERY node, not just the ones an edge lands on. A pattern's
            // first stop has no predecessor, so leaving it unseeded left the
            // origin column undefined and the segment pass threw on it.
            nodeStop.keys.forEach { column[it] = 0; indegree[it] = pred[it]?.size ?: 0 }
            val queue = ArrayDeque(indegree.filterValues { it == 0 }.keys)
            var visited = 0
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                visited++
                val cu = column.getOrElse(u) { 0 }
                succ[u]?.forEach { v ->
                    column[v] = maxOf(column.getOrElse(v) { 0 }, cu + 1)
                    val d = indegree.getValue(v) - 1
                    indegree[v] = d
                    if (d == 0) queue.addLast(v)
                }
            }
            if (visited < nodeStop.size) {
                // A cycle, which the per-pattern ordinal should make impossible.
                // Fall back to position-in-pattern rather than dropping the
                // unvisited nodes: a stretched diagram beats a missing branch.
                runNodes.values.forEach { keys ->
                    keys.forEachIndexed { i, k -> column[k] = maxOf(column.getOrElse(k) { 0 }, i) }
                }
            }

            // SEGMENTS: cut wherever the set of patterns changes, because that is
            // exactly where the route splits or merges. Everything between two
            // cuts is one straight line on one row.
            val segmentOf = HashMap<String, Int>()
            val segStops = ArrayList<MutableList<String>>()
            val segPatterns = ArrayList<Set<String>>()
            ordered.forEach { run ->
                val keys = runNodes.getValue(run.id)
                var current = -1
                keys.forEachIndexed { i, key ->
                    val here = nodePatterns.getValue(key)
                    val placed = segmentOf[key]
                    val contiguous = placed == null &&
                        current >= 0 &&
                        segPatterns[current] == here &&
                        column.getValue(key) == column.getValue(keys[i - 1]) + 1
                    when {
                        placed != null -> current = placed
                        contiguous -> { segStops[current].add(key); segmentOf[key] = current }
                        else -> {
                            current = segStops.size
                            segStops.add(mutableListOf(key))
                            segPatterns.add(here)
                            segmentOf[key] = current
                        }
                    }
                }
            }

            val next = Array(segStops.size) { LinkedHashSet<Int>() }
            val prevSeg = Array(segStops.size) { LinkedHashSet<Int>() }
            ordered.forEach { run ->
                var last = -1
                runNodes.getValue(run.id).forEach { key ->
                    val seg = segmentOf.getValue(key)
                    if (last >= 0 && last != seg) { next[last].add(seg); prevSeg[seg].add(last) }
                    last = seg
                }
            }

            val startCols = IntArray(segStops.size) { column.getValue(segStops[it].first()) }
            val order = segStops.indices.sortedBy { startCols[it] }

            // ROWS: a segment takes the row nearest its feeders that no
            // overlapping segment already holds. Greedy is enough - these graphs
            // are a handful of branches - and hunting outward from the preferred
            // row keeps a merge centred between the lines feeding it rather than
            // pushed to an edge.
            val row = IntArray(segStops.size) { Int.MIN_VALUE }
            val occupied = HashMap<Int, MutableList<IntRange>>()
            val trackOf = ordered.withIndex().associate { (i, r) -> r.id to i }
            order.forEach { seg ->
                val span = startCols[seg]..(startCols[seg] + segStops[seg].size - 1)
                val feeders = prevSeg[seg].filter { row[it] != Int.MIN_VALUE }
                val preferred = if (feeders.isNotEmpty()) {
                    feeders.sumOf { row[it] } / feeders.size
                } else {
                    val tracks = segPatterns[seg].mapNotNull { trackOf[it] }
                    if (tracks.isEmpty()) 0 else tracks.sum() / tracks.size
                }
                var r = preferred
                var step = 0
                while (!isFree(occupied, r, span)) {
                    step++
                    r = if (step % 2 == 1) preferred + (step + 1) / 2 else preferred - step / 2
                }
                row[seg] = r
                occupied.getOrPut(r) { mutableListOf() }.add(span)
            }

            // Normalise so the topmost row is 0 - the outward search goes
            // negative whenever a merge sits above the lines feeding it.
            val minRow = row.min()
            val segments = order.map { seg ->
                Segment(
                    id = seg,
                    stops = segStops[seg].map { nodeStop.getValue(it) },
                    startCol = startCols[seg],
                    row = row[seg] - minRow,
                    patternIds = segPatterns[seg],
                    nextIds = next[seg].toList(),
                    prevIds = prevSeg[seg].toList(),
                )
            }

            return RouteGraph(
                segments = segments,
                patterns = ordered.map {
                    Pattern(it.id, it.terminusId, it.terminusName, it.label, it.viaKey)
                },
                rowCount = (segments.maxOfOrNull { it.row } ?: 0) + 1,
                colCount = (segments.maxOfOrNull { it.endCol } ?: -1) + 1,
            )
        }

        private fun nodeKey(stopId: String, ordinal: Int) = "$stopId#$ordinal"

        private fun isFree(
            occupied: Map<Int, MutableList<IntRange>>,
            row: Int,
            span: IntRange,
        ): Boolean = occupied[row]?.none { it.first <= span.last && span.first <= it.last } ?: true
    }
}
