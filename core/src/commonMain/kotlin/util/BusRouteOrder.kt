package com.stationly.core.util

/**
 * The order a passenger reads bus routes in: 14, 37, 39, 85, 93, 337, 424, 430,
 * then the night buses N74, N155.
 *
 * The line picker listed the backend's text order, so a stop read
 * 14, 337, 37, 39, 424, 430, 85, 93 (QA 2026-11-23, finding #7). That is the
 * order of the characters, not of the numbers on the front of the bus.
 *
 * ## The rule
 *  1. Plain day routes, by number.
 *  2. Lettered routes after them (C10, EL1, P5, W7, SL6...), by letter, then by
 *     number, so every "W" route sits together in numeric order.
 *  3. Night routes (N-prefixed) last, by number.
 *
 * Applied to BUSES only. Rail and Tube lines arrive in the order the backend
 * chose for them, which is a deliberate network order, not an alphabet.
 *
 * Shared code: the iOS line picker gets the same order.
 */
object BusRouteOrder {

    private val SHAPE = Regex("""^([A-Za-z]*)(\d+)([A-Za-z]*)$""")

    /** A sort key for one route id or label ("39", "N74", "Bus 39"). */
    fun key(route: String): Triple<Int, String, Int> {
        val bare = route.trim().removePrefix("Bus ").trim()
        val m = SHAPE.matchEntire(bare) ?: return Triple(3, bare.lowercase(), 0)
        val prefix = m.groupValues[1].uppercase()
        val number = m.groupValues[2].toIntOrNull() ?: 0
        val band = when {
            prefix.isEmpty() -> 0
            prefix == "N" -> 2
            else -> 1
        }
        return Triple(band, prefix + m.groupValues[3].uppercase(), number)
    }

    val comparator: Comparator<String> = Comparator { a, b ->
        val ka = key(a); val kb = key(b)
        compareValuesBy(ka, kb, { it.first }, { it.second }, { it.third })
    }

    /** [items] in route order, by the route text [routeOf] gives for each. */
    fun <T> sorted(items: List<T>, routeOf: (T) -> String): List<T> =
        items.sortedWith { a, b -> comparator.compare(routeOf(a), routeOf(b)) }
}
