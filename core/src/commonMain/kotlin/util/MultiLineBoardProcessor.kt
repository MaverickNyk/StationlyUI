package com.stationly.core.util

import com.stationly.core.model.PredictionDisplay

/**
 * One block of the board — a platform (rail) or a pole (bus) — as it exists
 * while the rows are being built: the group key, and every departure in it
 * paired with the feed it came from.
 *
 * Named only so the ordering rules below can be written as comparators over
 * something readable; `Map.Entry<String, List<Pair<PredictionDisplay, Feed>>>`
 * repeated four times obscures which of the three is being compared.
 */
private typealias Block = Map.Entry<String, List<Pair<PredictionDisplay, MultiLineBoardProcessor.Feed>>>

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
     * [BoardDisplayPrefs.rowCap] is a CEILING, applied per platform: you only
     * ever need the next few trains from the platform you are standing on, and a
     * platform with twelve queued departures would otherwise push every other
     * platform off the board. It is the one of the two the USER sets.
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
     *
     * The floor is deliberately NOT a setting. "How short may the panel get
     * before it stops looking like a board" is a layout fact the user has no way
     * to reason about, and a floor above the ceiling is a state a settings screen
     * would have to defend against for no gain.
     */
    const val MIN_BOARD_ROWS = 3

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
     * Whether a station groups by POLE rather than by platform.
     *
     * Every rule in this file that differs for buses hangs off this one
     * question, and it was being asked with a copy of the same string literal
     * at four call sites (the board, the panel, the settings screen, its
     * ViewModel). They agreed only by coincidence, and a fifth caller spelling
     * it `"Bus"` would have grouped a bus board by platform — which at an
     * unlettered stop means every pole collapsing into one block, the exact bug
     * [groupKeyFor] documents.
     */
    fun isBus(mode: String?): Boolean = mode?.trim().equals("bus", ignoreCase = true)

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
     * The order the blocks appear in, which is the ONLY level [BoardDisplayPrefs]
     * is allowed to touch besides the depth of each one.
     *
     * Three keys, and the sequence of them is the design:
     *
     *  1. **Unassigned platforms last, absolutely** — see [isUnassigned]. This
     *     sits ABOVE the pin on purpose: a pin is a preference, and "you cannot
     *     go and stand on a platform TfL has not allocated yet" is a fact. The
     *     case it guards is a pinned LINE whose only departures are unallocated,
     *     which would otherwise promote a block nobody can act on to the top of
     *     the board. (A pinned PLATFORM never collides with this — an unassigned
     *     block has no label for the picker to have offered.)
     *  2. **The pin** — one block, or every block carrying a pinned line.
     *  3. **The chosen sort.** Time reads the soonest ABSOLUTE arrival, never the
     *     `eta` label: the label is rounded AND deliberately bumped so
     *     same-platform trains don't collide, so it does not round-trip back to a
     *     sortable number. Platform reads the number out of the label so that 10
     *     follows 9.
     */
    private fun groupOrder(isBus: Boolean, prefs: BoardDisplayPrefs): Comparator<Block> {
        val label: (Block) -> String = { block -> groupLabelFor(block.value.first().first, isBus) }
        val base = compareBy<Block> { if (isUnassigned(label(it))) 1 else 0 }
            .thenBy { if (isPinned(it, isBus, prefs.pin)) 0 else 1 }
        return when (prefs.sort) {
            BoardSort.PLATFORM -> base
                .thenBy { platformNumber(label(it)) }
                // Lettered and unnumbered blocks ("Stop C") have no number to
                // order by and would otherwise sit in map order, which is
                // arbitrary and changes as departures arrive.
                .thenBy { label(it).lowercase() }
            BoardSort.TIME, BoardSort.DESTINATION -> base.thenBy { block ->
                block.value.mapNotNull { (p, _) -> p.targetEpochMs }.minOrNull() ?: Long.MAX_VALUE
            }
        }
    }

    /** Whether [pin] names this block — by its platform label, or by a line calling at it. */
    private fun isPinned(block: Block, isBus: Boolean, pin: BoardPin?): Boolean {
        if (pin == null || pin.id.isBlank()) return false
        return when (pin.kind) {
            BoardPin.Kind.PLATFORM -> groupLabelFor(block.value.first().first, isBus)
                .trim().equals(pin.id.trim(), ignoreCase = true)
            // Every block this line calls at, not just the first — at an
            // interchange a line is genuinely on two platforms, and promoting
            // one of them would be answering "show me my line" with half of it.
            BoardPin.Kind.LINE -> block.value.any { (_, feed) ->
                feed.line.trim().equals(pin.id.trim(), ignoreCase = true)
            }
        }
    }

    /**
     * The number inside a platform label, for ordering — 10 must follow 9.
     *
     * The first run of digits anywhere in the string, so it survives whatever the
     * backend wraps it in ("Platform 4", "Westbound Platform 4a"). Labels with no
     * number at all sort after every numbered one rather than at position zero:
     * "Stop C" belongs at the end of a numeric list, not the start of it.
     */
    private fun platformNumber(label: String): Int {
        var start = 0
        while (start < label.length && !label[start].isDigit()) start++
        if (start == label.length) return Int.MAX_VALUE
        var end = start
        while (end < label.length && label[end].isDigit()) end++
        return label.substring(start, end).toIntOrNull() ?: Int.MAX_VALUE
    }

    /**
     * The board's rows: a header per platform/stop, its departures beneath it,
     * arranged according to [prefs].
     *
     * Defaults reproduce the board exactly as it was before it had settings:
     * blocks led by their soonest train, rows in arrival order, three per
     * platform, nothing pinned.
     *
     * @param isBus buses group by stop and never show a direction suffix.
     * @param prefs this station's arrangement — see [BoardDisplayPrefs] for why
     *   nothing in it can change the GROUPING, only the order and the depth.
     */
    fun buildRows(
        feeds: List<Feed>,
        isBus: Boolean,
        prefs: BoardDisplayPrefs = BoardDisplayPrefs(),
    ): List<Row> {
        val groups = buildGroups(feeds, isBus, prefs)
        // NOT padded to the floor: a board with nothing on it at all is the
        // caller's to describe ("No departures right now"), and three blank rows
        // would pre-empt that with something that looks like a broken board.
        // The floor below exists to stop a board with ONE real departure
        // collapsing, which is a different situation.
        if (groups.isEmpty()) return emptyList()

        val rows = mutableListOf<Row>()
        var departureCount = 0

        groups.forEach { group ->
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
            if (group.header.isNotBlank()) rows.add(Row.PlatformHeader(group.header))

            group.departures.forEach { departure ->
                rows.add(
                    Row.Departure(
                        // Bracketed short form — "(Cir.) Edgware Road". The
                        // brackets keep the line visually subordinate to the
                        // destination, which is what you are actually scanning
                        // for; an unbracketed prefix competes with it.
                        linePrefix = if (group.mixesLines) "(${departure.lineShort})" else "",
                        destination = departure.prediction.destination,
                        eta = departure.prediction.eta,
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
     * One departure, with everything a renderer needs about WHERE it came from.
     *
     * [lineShort] is resolved here rather than at the call site so that every
     * surface showing this board — the home screen, the widget, anything next —
     * names a line identically. It is empty on bus, where the backend already
     * appends the route to the destination.
     */
    data class GroupedDeparture(
        val prediction: PredictionDisplay,
        val line: String,
        val lineShort: String,
    )

    /**
     * One block of the board: a platform (rail) or a pole (bus), its header, and
     * the departures under it — capped, ordered, in the position the board
     * gives it.
     *
     * [mixesLines] is the group's own answer to "should a row name its line",
     * computed from every departure the group HAS rather than from the ones that
     * survived the cap. Deciding it after the cap would let a platform gain and
     * lose its prefixes as trains tick off the bottom of it.
     */
    data class Group(
        /**
         * Stable identity for the block: the platform string on rail, the pole
         * naptan on bus. This is what a consumer that has to re-associate rows
         * with blocks later (the iOS widget's own REST refresh, which cannot
         * call this code) matches on.
         */
        val key: String,
        /** "Northern Platform 2", "Bus 39, 34 Stop N" — see [headerFor]. */
        val header: String,
        /** The backend's own platform label, verbatim: "Platform 8", "Stop C". */
        val label: String,
        val departures: List<GroupedDeparture>,
        val mixesLines: Boolean,
    )

    /**
     * The board as BLOCKS — grouping, ordering, capping and header text, with no
     * opinion about how any of it is drawn.
     *
     * ## Why this is the shared entry point
     * [buildRows] flattens these into a list of strings for Compose, which suits
     * a screen that scrolls and suits nothing else. The iOS widget pages between
     * blocks and re-derives its own ETA labels every minute, so it needs the
     * blocks themselves and the raw predictions inside them — and when it had to
     * re-derive the grouping instead, it got it wrong in exactly the way this
     * file warns about: it grouped buses by `platform`, so two poles at one hub
     * with no letters between them collapsed into a single block with both
     * directions interleaved.
     *
     * Every rule that makes a board a board — the pole key, unassigned last, the
     * pin, the cap-then-sort order — now has ONE implementation, and a second
     * consumer cannot quietly diverge from it.
     *
     * @param isBus buses group by stop and never show a direction suffix.
     * @param prefs this station's arrangement — see [BoardDisplayPrefs] for why
     *   nothing in it can change the GROUPING, only the order and the depth.
     */
    fun buildGroups(
        feeds: List<Feed>,
        isBus: Boolean,
        prefs: BoardDisplayPrefs = BoardDisplayPrefs(),
    ): List<Group> {
        // Flatten first, keeping each departure's feed so the row can name its
        // line and the header can collect the group's lines and directions.
        val all = feeds.flatMap { feed -> feed.predictions.map { it to feed } }
        if (all.isEmpty()) return emptyList()

        return all.groupBy { (prediction, feed) -> groupKeyFor(prediction, feed, isBus) }
            .entries
            .sortedWith(groupOrder(isBus, prefs))
            .map { (key, entries) ->
                val sorted = entries.sortedBy { (p, _) -> StationlyFormatters.arrivalSortKey(p) }
                val directions = sorted.mapTo(mutableSetOf()) { (_, feed) -> feed.direction }
                val lines = sorted.map { (_, feed) -> feed.line }.distinct()

                // The platform label is backend-owned ("Platform 8", "Stop C",
                // "Platform not assigned") — displayed verbatim, never relabelled
                // client-side. See the consistency contract in
                // BOARD_AND_DREAM_UI.md.
                val label = groupLabelFor(sorted.first().first, isBus)

                // ── The cap picks WHICH trains; the sort decides what ORDER
                // they are shown in, and that order is applied AFTERWARDS ──
                //
                // Taking from anything but the time-ordered list is the bug
                // waiting here: cap 3 applied to a destination-sorted platform
                // keeps the three alphabetically-first destinations, which at
                // Green Park means three Cockfosters trains an hour out and no
                // sign of the Uxbridge one leaving now. The user asked for their
                // trains to be grouped by where they go, not to be shown a
                // different set of trains.
                //
                // Ceiling per platform — NOT the board floor. See MIN_BOARD_ROWS.
                val shown = sorted.take(prefs.rowCap).let { picked ->
                    if (prefs.sort == BoardSort.DESTINATION) {
                        picked.sortedWith(
                            compareBy<Pair<PredictionDisplay, Feed>> { (p, _) ->
                                p.destination.trim().lowercase()
                            }.thenBy { (p, _) -> StationlyFormatters.arrivalSortKey(p) }
                        )
                    } else picked
                }

                Group(
                    key = key,
                    header = headerFor(label, lines, directions, isBus),
                    label = label,
                    departures = shown.map { (prediction, feed) ->
                        GroupedDeparture(
                            prediction = prediction,
                            line = feed.line,
                            // Buses never take a client-side prefix: the backend
                            // appends the route number to the destination itself
                            // ("53 Nags Head"), so adding one here would double
                            // it up.
                            lineShort = if (isBus) "" else LineShortNames.shortName(feed.line),
                        )
                    },
                    // Show the line on a row ONLY when the group actually mixes
                    // lines. On a single-line platform the header already named
                    // it, so a prefix would be the same word on every row — pure
                    // noise, and it would change how the long-standing
                    // single-line board looks.
                    mixesLines = lines.size > 1 && !isBus,
                )
            }
    }

    /**
     * The platform (or bus stop) labels a user could sensibly pin, for the "show
     * first" picker on the settings screen.
     *
     * Read off the board itself rather than from anything configured, because a
     * platform is not a thing the user configures — TfL decides it, and the only
     * honest list is the one their board has actually been showing.
     *
     * Two rules, both of which exist so the picker cannot offer a choice that
     * does nothing:
     *  - **Unassigned and unlabelled blocks are left out.** [buildRows] sinks
     *    unassigned platforms to the bottom regardless of any pin, so offering
     *    one would be offering a setting guaranteed to have no effect.
     *  - **Ordered by platform number, never by time.** This is a list someone
     *    reads and picks from; ordered by whose train is soonest it would
     *    rearrange itself under their finger every time the board refreshed.
     */
    fun pinnablePlatforms(feeds: List<Feed>, isBus: Boolean): List<String> {
        val all = feeds.flatMap { feed -> feed.predictions.map { it to feed } }
        if (all.isEmpty()) return emptyList()
        return all.groupBy { (prediction, feed) -> groupKeyFor(prediction, feed, isBus) }
            .entries
            .sortedWith(groupOrder(isBus, BoardDisplayPrefs(sort = BoardSort.PLATFORM)))
            .map { block -> groupLabelFor(block.value.first().first, isBus).trim() }
            .filter { it.isNotBlank() && !isUnassigned(it) }
            .distinct()
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
     *
     * ## What [prefs] does and does not reach here
     * Only [BoardPin]. A pinned block leads the legs and, more importantly,
     * survives the cap: at a four-platform station the two soonest legs may be
     * two platforms the user never uses, and a "show first" that quietly does
     * nothing on the collapsed card is a setting that appears broken to anyone
     * whose stations are collapsed by default.
     *
     * [BoardSort] deliberately does NOT apply. A leg answers "what can I catch",
     * so the two shown must be the two SOONEST — ordering by platform number
     * would pick the two lowest-numbered platforms instead, which is a different
     * question and a worse answer. There is no destination level to sort at
     * either: a leg is already one departure.
     */
    fun collapsedLegs(
        feeds: List<Feed>,
        isBus: Boolean,
        prefs: BoardDisplayPrefs = BoardDisplayPrefs(),
    ): List<Leg> {
        val all = feeds.flatMap { feed -> feed.predictions.map { it to feed } }
        if (all.isEmpty()) return emptyList()

        return all.groupBy { (prediction, feed) -> groupKeyFor(prediction, feed, isBus) }
            .entries
            .mapNotNull { block ->
                block.value.minByOrNull { (p, _) -> StationlyFormatters.arrivalSortKey(p) }
                    ?.let { soonest -> block to soonest }
            }
            .sortedWith(
                compareBy<Pair<Block, Pair<PredictionDisplay, Feed>>> { (block, _) ->
                    if (isPinned(block, isBus, prefs.pin)) 0 else 1
                }.thenBy { (_, soonest) -> StationlyFormatters.arrivalSortKey(soonest.first) }
            )
            .take(MAX_COLLAPSED_LEGS)
            .map { (_, soonest) -> soonest }
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
