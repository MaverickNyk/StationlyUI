package com.stationly.core.util

import com.stationly.core.model.PredictionDisplay

/**
 * Builds ONE departure board out of the several lines a user tracks at a station.
 *
 * ## What a board is
 * One board is one STATION, and that has been true since multi-line landed — it
 * is only the grouping underneath that changed. For buses a "station" is a hub,
 * which physically spans several naptans (stops); they still share one hub id and
 * one station name, so they still render as one board, just with a block per stop.
 *
 * ## Why this exists
 * Before multi-line, a board was one line in one direction, so
 * [GlobalBoardProcessor.prepareLegacyRows] could group by platform within a single
 * feed and be correct by construction. With several lines at one station that
 * falls apart in three ways, all of which this file fixes:
 *
 *  1. **Platforms fragmented.** Two lines sharing Platform 2 produced two separate
 *     "Platform 2" blocks, so the board showed the same physical platform twice
 *     and neither block was in true arrival order. Grouping is now by PLATFORM
 *     across every line (see [groupKeyFor]), which is what a passenger standing on
 *     that platform actually experiences.
 *  2. **Redundant headers.** Each feed emitted a "Circle · Inbound" strip AND a
 *     platform header. The platform header now carries both facts — "Platform 2
 *     (Eastbound)" — so the line/direction strip is gone.
 *  3. **The 3-row minimum was applied per feed**, so a station with four tracked
 *     lines padded out to twelve rows, most of them blank. It is now a floor for
 *     the WHOLE board ([MIN_BOARD_ROWS]).
 */
object MultiLineBoardProcessor {

    /**
     * TWO SEPARATE LIMITS, and conflating them is the easy mistake here.
     *
     * [MAX_ROWS_PER_PLATFORM] is a CEILING, applied per platform: you only ever
     * need the next few trains from the platform you are standing on, and a
     * platform with twelve queued departures would otherwise push every other
     * platform off the board.
     *
     * [MIN_BOARD_ROWS] is a FLOOR, applied once to the WHOLE board: it keeps the
     * panel from collapsing to a sliver as the last trains of the night drop off.
     * Applying the floor per platform (the original behaviour) meant a four-line
     * station padded to twelve rows, most of them blank, and the real departures
     * were lost among the padding.
     *
     * So a board showing one platform with two trains gets ONE blank row — two
     * real plus one pad reaches the floor of three. A board showing three
     * platforms with two trains each gets NO blanks at all: six rows already
     * clears the floor, even though no single platform reaches three.
     */
    const val MIN_BOARD_ROWS = 3

    /** Ceiling per platform — see [MIN_BOARD_ROWS] for how the two interact. */
    const val MAX_ROWS_PER_PLATFORM = 3

    /**
     * One tracked line/direction at this station, plus its departures.
     *
     * Carries `line`/`direction` alongside the predictions because
     * [PredictionDisplay] itself has neither: before multi-line the line was
     * implicit in which board you were looking at. Grouping ACROSS lines needs
     * each departure to be able to name its own, so the caller pairs them up here
     * rather than us guessing downstream.
     */
    data class Feed(
        /**
         * The naptan these departures were FETCHED from — [UserSelection.station],
         * not the hub.
         *
         * This is the bus grouping key. Every bus pole is its own naptan, and one
         * hub's poles are genuinely different places on different sides of the
         * road: Smithwood Close resolves route 39 inbound to 490008805N and
         * outbound to 490012211N. Merging them puts departures you cannot catch
         * from where you are standing into one block.
         */
        val stationId: String,
        /** Canonical line id — "circle", "53". Used for identity, never shown. */
        val line: String,
        /** "Eastbound" / "Inbound". May be blank. */
        val direction: String,
        val predictions: List<PredictionDisplay>,
    )

    sealed class Row {
        /** "Platform 2 (Eastbound)" for rail, "Stop W" for buses. */
        data class PlatformHeader(val title: String) : Row()
        /**
         * [linePrefix] is blank unless the group mixes lines — see
         * [buildRows]. Blank [destination] + [eta] is a padding row.
         */
        data class Departure(
            val linePrefix: String,
            val destination: String,
            val eta: String,
        ) : Row()
    }

    /**
     * Group identity for one departure.
     *
     * Rail groups by platform: a platform is a physical place, and two lines
     * calling at it are one queue to the person standing there.
     *
     * Buses have no platforms, so one POLE (naptan) is the equivalent unit, and
     * the key is the feed's [Feed.stationId].
     *
     * This was briefly keyed on `stopLetter`, which is wrong: TfL only letters
     * stops at multi-stop interchanges, so at an ordinary stop every pole has a
     * null letter and a blank platform and they all collapse into ONE group.
     * That merged both directions of route 39 at Smithwood Close into a single
     * block — departures towards Putney Bridge and towards Clapham Junction
     * interleaved, from two poles on opposite sides of the road.
     *
     * No backend change is needed for this: [UserSelection.station] is already
     * the resolved per-direction naptan (`parentStationId` is the hub the card
     * groups by), so the pole identity was available all along.
     */
    private fun groupKeyFor(
        prediction: PredictionDisplay,
        feed: Feed,
        isBus: Boolean,
    ): String = if (isBus) feed.stationId else prediction.platform

    /**
     * Compass directions only. "Inbound"/"Outbound" are deliberately dropped.
     *
     * They are operational vocabulary, not passenger vocabulary: inbound means
     * "towards the centre of the network", which tells a passenger standing on a
     * platform nothing they can act on. A compass bearing does — it is what the
     * platform signage itself says, and it is how you know you are on the right
     * side of the tracks.
     */
    private val COMPASS_DIRECTIONS = setOf(
        "northbound", "southbound", "eastbound", "westbound",
    )

    /**
     * The compass form of a direction, or null for "Inbound"/"Outbound".
     *
     * Public because the hero needs the same judgement: a split hero labels its
     * two halves by direction, and it must apply exactly this rule or the board
     * and the hero would disagree about what a direction is worth showing.
     */
    fun compassOrNull(direction: String?): String? =
        direction?.trim()?.takeIf { it.lowercase() in COMPASS_DIRECTIONS }

    /**
     * A platform with no assigned platform, which always sorts to the END of the
     * board regardless of how soon its trains are.
     *
     * These are departures the operator has not yet allocated, so you cannot go
     * and stand anywhere on the strength of them. Sorting them by time would put
     * an unactionable block above platforms you could actually walk to.
     */
    private fun isUnassigned(platformLabel: String): Boolean {
        val p = platformLabel.trim().lowercase()
        return p.isEmpty() || p.contains("not assigned") || p.contains("unassigned")
    }

    /**
     * The label for one group's block — "Platform 2", "Stop W".
     *
     * The platform string is backend-owned and shown verbatim wherever it exists
     * (see the consistency contract in BOARD_AND_DREAM_UI.md). The bus branch is
     * the one place we synthesise, and only as a FALLBACK:
     * `SyncPredictionsUseCase` documents that a bus prediction carries
     * `platform = "Stop C"` when the stop is assigned but `platform = ""` when it
     * is not — while still carrying a `stopLetter`.
     *
     * That blank is what made bus boards render a header strip with no text in
     * it: the group formed correctly on `stopLetter` and then had nothing to
     * display. So when the backend gives us a letter but no label, we build
     * "Stop W" from the letter rather than showing an empty row.
     */
    private fun groupLabelFor(prediction: PredictionDisplay, isBus: Boolean): String {
        val platform = prediction.platform.trim()
        if (!isBus) return platform
        if (platform.isNotEmpty()) return platform
        val letter = prediction.stopLetter?.trim().orEmpty()
        return if (letter.isEmpty()) "" else "Stop $letter"
    }

    /**
     * Header text for a group: "Northern Platform 2", "Dist & Circ. Platform 2
     * Northbound", "Stop W".
     *
     * Deliberately does NOT run through [StationlyFormatters.platformHeaderText],
     * which renders "Circle: Platform 2". A group can now hold several lines, so
     * one line name with a colon would be a lie; the lines are joined by
     * [LineShortNames.joinLines] instead.
     *
     * Buses get the stop label alone — no line, because the backend appends the
     * route to each row, and no direction, because a stop letter already implies
     * it and "Stop W (Outbound)" is noise.
     */
    private fun headerFor(
        platformLabel: String,
        lines: List<String>,
        directions: Set<String>,
        isBus: Boolean,
    ): String {
        val label = platformLabel.trim()
        // Buses: "Bus 39", "Bus 39, 34, 33", "Bus 39, 34 Stop N".
        //
        // The routes always lead, and the stop label follows only when there is
        // one. Most suburban stops are unlettered — TfL letters stops only at
        // multi-stop interchanges — so a header built from the label alone was
        // blank at exactly the stops people use most.
        //
        // No direction suffix: a pole only runs one way, so it is implied, and
        // "Stop N Outbound" is noise.
        if (isBus) {
            val routes = busRouteList(lines)
            return listOf(routes, label).filter { it.isNotBlank() }.joinToString(" ")
        }
        // Only when every feed in the group agrees — a platform worked in both
        // directions gets no suffix rather than an arbitrary one.
        val direction = directions.singleOrNull()?.let { compassOrNull(it) }
        return listOf(LineShortNames.joinLines(lines), label, direction.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /**
     * Routes calling at one stop, as "Bus 39" / "Bus 39, 34, 33".
     *
     * Comma-separated rather than the "&" form rail headers use: a bus stop can
     * serve a dozen routes, where "39, 34 & 33" reads as a sentence and a plain
     * list reads as what it is — a set of numbers to scan for yours.
     *
     * Capped at [MAX_BUS_ROUTES], with an overflow count. A busy stop can serve
     * fifteen routes and the header would otherwise be several lines long,
     * burying the departures it sits above.
     */
    private fun busRouteList(lines: List<String>): String {
        val routes = lines.filter { it.isNotBlank() }.distinct()
        if (routes.isEmpty()) return ""
        val shown = routes.take(MAX_BUS_ROUTES).joinToString(", ")
        val overflow = routes.size - MAX_BUS_ROUTES
        return if (overflow > 0) "$BUS_PREFIX $shown +$overflow" else "$BUS_PREFIX $shown"
    }

    /**
     * TODO(i18n): this is the one hardcoded user-facing word in this file. Every
     * other label here is backend-owned. It belongs in homeConfig alongside
     * `board.status_label` and friends.
     */
    private const val BUS_PREFIX = "Bus"

    private const val MAX_BUS_ROUTES = 4

    /**
     * Progressively shorter forms of a platform header, widest first.
     *
     * The caller measures and takes the first that fits, so this is a fallback
     * LADDER, not a formatting choice: a board almost always has room for
     * "Platform 7" and should show it. Shrinking unconditionally would cost every
     * board legibility to solve a problem only busy interchanges have.
     *
     * The rungs shorten the least meaningful token first. "Platform" is pure
     * boilerplate — the number is the fact — so it goes before the direction,
     * which is real information.
     */
    fun headerVariants(title: String): List<String> {
        val full = title.trim()
        if (full.isEmpty()) return listOf("")
        val abbreviated = full.replace("Platform", "Plat.", ignoreCase = false)
        // Last resort: drop the compass suffix too. Better a readable
        // "Dist & Circ. Plat. 2" than an ellipsised header that loses the
        // platform number — the one thing the row exists to tell you.
        val noDirection = COMPASS_DIRECTIONS.fold(abbreviated) { acc, d ->
            acc.replace(" ${d.replaceFirstChar { it.uppercase() }}", "")
        }
        return listOf(full, abbreviated, noDirection).distinct()
    }

    /**
     * The board's rows: a header per platform/stop, its departures in arrival
     * order beneath it, groups ordered by whichever has the soonest train.
     *
     * @param isBus buses group by stop and never show a direction suffix.
     */
    fun buildRows(feeds: List<Feed>, isBus: Boolean): List<Row> {
        // Flatten first, keeping each departure's feed so the row can name its
        // line and the header can collect the group's lines and directions.
        val all = feeds.flatMap { feed -> feed.predictions.map { it to feed } }
        if (all.isEmpty()) return emptyList()

        val groups = all.groupBy { (prediction, feed) -> groupKeyFor(prediction, feed, isBus) }
            .entries
            .sortedWith(
                // Unassigned platforms sink to the bottom whatever their times —
                // see isUnassigned. Everything else is ordered by its soonest
                // departure, on the absolute arrival time and never the `eta`
                // label: the label is rounded AND deliberately bumped so
                // same-platform trains don't collide, so it does not round-trip
                // back to a sortable number.
                compareBy<Map.Entry<String, List<Pair<PredictionDisplay, Feed>>>> { (_, entries) ->
                    if (isUnassigned(groupLabelFor(entries.first().first, isBus))) 1 else 0
                }.thenBy { (_, entries) ->
                    entries.mapNotNull { (p, _) -> p.targetEpochMs }.minOrNull() ?: Long.MAX_VALUE
                }
            )

        val rows = mutableListOf<Row>()
        var departureCount = 0

        groups.forEach { (_, entries) ->
            val sorted = entries.sortedBy { (p, _) -> StationlyFormatters.arrivalSortKey(p) }
            val directions = sorted.mapTo(mutableSetOf()) { (_, feed) -> feed.direction }
            val lines = sorted.map { (_, feed) -> feed.line }.distinct()

            // The platform label is backend-owned ("Platform 8", "Stop C",
            // "Platform not assigned") — displayed verbatim, never relabelled
            // client-side. See the consistency contract in BOARD_AND_DREAM_UI.md.
            val platformLabel = groupLabelFor(sorted.first().first, isBus)
            val header = headerFor(platformLabel, lines, directions, isBus)
            // Omit the strip entirely rather than emitting a blank one.
            //
            // A single unlettered bus stop (Smithwood Close and most suburban
            // stops — TfL only letters stops at multi-stop interchanges) has no
            // platform AND no stopLetter, so there is genuinely nothing to put
            // here. This used to print "Bus 53" because the header ran through
            // platformHeaderText, which falls back to the line prefix; dropping
            // the route number from the header left it with nothing to say.
            //
            // An empty strip reads as a rendering bug. The station strip above
            // already names the stop, so with one group the header adds nothing
            // anyway — the rows simply start.
            if (header.isNotBlank()) rows.add(Row.PlatformHeader(header))

            // Show the line on a row ONLY when the group actually mixes lines.
            // On a single-line platform the header already named it, so a prefix
            // would be the same word on every row — pure noise, and it would
            // change how the long-standing single-line board looks.
            val mixesLines = lines.size > 1

            // Buses never take a client-side prefix: the backend appends the
            // route number to the destination itself ("53 Nags Head"), so adding
            // one here would double it up.
            // Ceiling per platform — NOT the board floor. See MIN_BOARD_ROWS.
            sorted.take(MAX_ROWS_PER_PLATFORM).forEach { (prediction, feed) ->
                rows.add(
                    Row.Departure(
                        // Bracketed short form — "(Cir.) Edgware Road". The
                        // brackets keep the line visually subordinate to the
                        // destination, which is what you are actually scanning
                        // for; an unbracketed prefix competes with it.
                        linePrefix = if (mixesLines && !isBus) {
                            "(${LineShortNames.shortName(feed.line)})"
                        } else "",
                        destination = prediction.destination,
                        eta = prediction.eta,
                    )
                )
                departureCount++
            }
        }

        // Board-wide floor, applied ONCE at the end across every platform —
        // never per group. See [MIN_BOARD_ROWS].
        repeat((MIN_BOARD_ROWS - departureCount).coerceAtLeast(0)) {
            rows.add(Row.Departure("", " ", " "))
        }
        return rows
    }

    /**
     * One line of a COLLAPSED station card — the whole board reduced to a
     * sentence per direction.
     *
     * [line] is carried raw so the caller can tint the leg with the line's
     * colour, which is Compose's job, not this file's.
     */
    data class Leg(
        val line: String,
        /** "Picc. Plat. 4" / "39 Stop W" — blank when nothing is known. */
        val where: String,
        /** Where the train is going, verbatim from the prediction. */
        val towards: String,
        val eta: String,
    )

    /**
     * What a collapsed station shows instead of its board: the soonest departure
     * from each PLACE you could stand, soonest first, capped at
     * [MAX_COLLAPSED_LEGS].
     *
     * Grouped on the same key the BOARD groups on — rail by platform, bus by
     * pole ([groupKeyFor]) — for two reasons. A leg can then never describe a
     * platform the expanded card would not show; and at a station that is
     * tracked both ways those groups ARE the two directions, so "can I still go
     * the way I am going" is answered without a direction special case. One leg
     * would answer it for half the users of the card.
     *
     * Ordered on [StationlyFormatters.arrivalSortKey] and never on the `eta`
     * label — the label is rounded and deliberately bumped, so ordering by it
     * would put the wrong train first exactly when two are close.
     */
    fun collapsedLegs(feeds: List<Feed>, isBus: Boolean): List<Leg> {
        val all = feeds.flatMap { feed -> feed.predictions.map { it to feed } }
        if (all.isEmpty()) return emptyList()

        return all.groupBy { (prediction, feed) -> groupKeyFor(prediction, feed, isBus) }
            .values
            .mapNotNull { group ->
                group.minByOrNull { (p, _) -> StationlyFormatters.arrivalSortKey(p) }
            }
            .sortedBy { (p, _) -> StationlyFormatters.arrivalSortKey(p) }
            .take(MAX_COLLAPSED_LEGS)
            .map { (prediction, feed) ->
                Leg(
                    line = feed.line,
                    where = legWhere(prediction, feed, isBus),
                    towards = prediction.destination.trim(),
                    eta = prediction.eta.trim(),
                )
            }
    }

    /**
     * The "where" half of a collapsed leg — "Picc. Plat. 4", "39 Stop W".
     *
     * Deliberately NOT [headerFor], which is board vocabulary and is wrong here
     * in three ways. A board header has a row to itself; a leg shares one line
     * with the station name, the destination and the countdown, so every token
     * has to earn its width:
     *
     *  - **Short line name always.** [LineShortNames.joinLines] gives a single
     *    line its FULL name because a header has room for it. A leg does not:
     *    "Hammersmith & City" is wider than everything else on the row put
     *    together.
     *  - **"Platform" abbreviates to "Plat."** The number is the fact; the word
     *    is boilerplate.
     *  - **No compass direction.** The destination sits immediately after this
     *    and already says which way the train goes — "Plat. 4 Westbound ·
     *    Ealing Broadway" states it twice. This is also why the board's
     *    "never show Inbound/Outbound" rule needs no restating here: nothing
     *    directional survives.
     *
     * Buses drop the "Bus" prefix too. The card's own roundel is a bus roundel,
     * and the route number is already the shortest true label there is.
     */
    private fun legWhere(prediction: PredictionDisplay, feed: Feed, isBus: Boolean): String {
        val label = groupLabelFor(prediction, isBus).trim().replace("Platform", "Plat.")
        val line = if (isBus) feed.line.trim() else LineShortNames.shortName(feed.line)
        return listOf(line, label).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * How many legs a collapsed card shows.
     *
     * Two, because two is what "both directions" means and what a header-height
     * row can hold legibly. A three-platform interchange collapsed to three legs
     * is a board again — at which point the user should just expand it.
     */
    const val MAX_COLLAPSED_LEGS = 2
}
