package com.stationly.core.util

import com.stationly.core.config.BoardPolicy
import com.stationly.core.config.BoardPolicyStore

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.BoardPin

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
     * [BoardConfig.rowCap] is a CEILING, applied per platform: you only
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
     * Departures kept per block BEHIND the ones on screen.
     *
     * Neither a ceiling nor a floor on what is displayed — it is the buffer that
     * makes a board tick without the network. [BoardTicker] re-derives every
     * label each minute and sheds the trains that have left; a block capped at
     * what fits would have nothing to shift up behind them, so the board would
     * empty itself out and stay empty until the next push. It is also what the
     * "Gone" retention holds when a block runs dry.
     *
     * ## Ten, which is everything TfL has
     * Measured against the live feed: TfL returns at most **10** arrivals per
     * platform and predicts ~25 minutes ahead — Edgware sends exactly 10 on each
     * of its two Northern platforms, King's Cross 9 on its busiest. At 8 the cap
     * genuinely bit: two trains dropped at Edgware, and the widget ran out of
     * queue about five minutes early. At 10 nothing is trimmed in practice, so
     * every departure the backend knows about is available to shift into view
     * between refreshes.
     *
     * ## Why a number rather than no cap
     * These rows cross a process boundary. `WidgetBoard.predictions` is
     * `@Transient` precisely because duplicating them took a five-platform board
     * from ~6KB to ~12KB in the App Group, parsed on every timeline build and
     * every render, in a process iOS cold-launches for a single tap. An
     * unbounded reserve puts the size of that payload in TfL's hands rather than
     * ours, on a feed we do not control and cannot test against.
     *
     * ## What it does NOT buy
     * Not a longer widget. The runway is bounded by how far TfL predicts (~25
     * min), not by how many rows we keep — `DepartureBoardProvider.horizonMinutes`
     * already sizes the timeline to the last departure, and past that the
     * retention layer holds "Gone". Raising this further would reserve rows that
     * do not exist.
     *
     * The ONE number for reserve depth: `SyncPredictionsUseCase` writes SQL at
     * this cap and the board re-applies it per block, so there is no point in
     * the two differing. Mirrored by hand as `WidgetRefreshService.perPlatformCap`
     * in Swift for the widget's own REST refresh — change both together.
     *
     * ## One name, deliberately
     * This was briefly a pair — a `ROW_RESERVE` constant beside a `rowReserve`
     * getter — and that is a trap rather than a convenience: both compile at
     * every call site, they read almost identically, and picking the constant
     * silently ignores whatever the backend served. The compiled default now
     * lives in [BoardPolicy.rowReserve] with the rest of the board policy, and
     * this is the only way to ask.
     */
    val rowReserve: Int get() = BoardPolicyStore.current.rowReserve

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

    /**
     * What a row prints before its destination — "39", "(Cir.)", or nothing.
     *
     * Two shapes, and the difference is which fact leads on that board:
     *
     *  - **Rail: the bracketed short form**, "(Cir.) Edgware Road". The brackets
     *    keep the line subordinate to the destination, which is what you scan a
     *    tube platform for; an unbracketed prefix competes with it.
     *  - **Bus: the bare route number**, "39 Putney Bridge", exactly as a TfL
     *    stop sign prints it. At a pole the number IS what you are looking for,
     *    and brackets around it would demote the one fact that says whether this
     *    departure is yours.
     *
     * Public and mode-shaped rather than inlined at the one call site, because
     * it is not the only surface that draws this: the dream board builds the
     * same prefix from its single selection, and the iOS widget restates it in
     * Swift. Three copies of "bare on bus, bracketed on rail" is how the three
     * surfaces come to disagree about the same row.
     *
     * WHETHER a row gets one is a separate question, and the block owns it —
     * see [Group.showsLinePrefix].
     */
    fun linePrefixText(lineShort: String, isBus: Boolean): String {
        val short = lineShort.trim()
        return when {
            short.isEmpty() -> ""
            isBus           -> short
            else            -> "($short)"
        }
    }

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
            /**
             * A retained already-departed train — see [BoardPolicy.departedLabel].
             *
             * Carried as a flag rather than left for the renderer to infer from
             * the label, so "has it left" stays a single decision made in
             * [BoardTicker]. A renderer comparing `eta == "Gone"` would be a
             * second copy of the rule, and the first constant to move would
             * leave it dimming nothing.
             */
            val departed: Boolean = false,
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
     * Every departure across every feed, each still paired with the feed it came
     * from — the shape all four grouping passes below start from.
     *
     * Written out by hand at each of those call sites until now, which is four
     * chances for one of them to lose the pairing and start grouping departures
     * that no longer know which pole they were fetched from. That is the exact
     * bug [groupKeyFor] documents, so the flattening belongs next to it.
     */
    private fun List<Feed>.withFeeds(): List<Pair<PredictionDisplay, Feed>> =
        flatMap { feed -> feed.predictions.map { it to feed } }

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
     * board legibility to solve a problem only busy interchanges have — and it
     * would make the widget and the home board disagree about the same header,
     * when the whole point is that they do not.
     *
     * ## The order is the design: lossless rungs before lossy ones
     *  1. **Full.** What a board with room shows, and most boards have room.
     *  2. **"Platform" → "Plat."** Pure boilerplate; the NUMBER is the fact.
     *  3. **Full line names → short forms.** "Hammersmith City" → "H&C" costs
     *     nothing a passenger cannot read back — it is what the roundel, the map
     *     key and the station signage already say. This rung is why a widget
     *     header fits at all: a pager header spends its width on two arrows and
     *     a page marker before the text gets any.
     *  4. **Drop the compass direction.** The ONLY rung that loses information,
     *     so it is last. Better a readable "Dist & Circ. Plat. 2" than an
     *     ellipsised header missing the platform number.
     */
    fun headerVariants(title: String): List<String> {
        val full = title.trim()
        if (full.isEmpty()) return listOf("")
        val abbreviated = full.replace("Platform", "Plat.", ignoreCase = false)
        val shortLines = LineShortNames.abbreviate(abbreviated)
        // BOTH forms of the direction, because they arrive by different routes.
        // [headerFor] appends a bare "Westbound"; the backend's own platform
        // label frequently arrives already carrying "(Westbound)", and on the
        // lines this ladder exists for it is usually the parenthesised one —
        // "Piccadilly Platform 2 (Westbound)" is real device data. Stripping
        // only the appended form left the widest headers untouched at exactly
        // the rung meant to rescue them.
        val noDirection = COMPASS_DIRECTIONS.fold(shortLines) { acc, d ->
            val Cased = d.replaceFirstChar { it.uppercase() }
            acc.replace(" ($Cased)", "").replace(" $Cased", "")
        }.trim()
        return listOf(full, abbreviated, shortLines, noDirection).distinct()
    }

    /**
     * The order the blocks appear in, which is the ONLY level [BoardConfig]
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
     *  3. **The soonest train.** The soonest ABSOLUTE arrival, never the `eta`
     *     label: the label is rounded AND deliberately bumped so same-platform
     *     trains don't collide, so it does not round-trip back to a sortable
     *     number. And the soonest train that has not ALREADY LEFT — see
     *     [soonestArrival].
     *
     * There is no user-chosen sort in here any more, and [BoardConfig]
     * carries the argument for why not.
     */
    private fun groupOrder(
        isBus: Boolean,
        prefs: BoardConfig,
        nowMs: Long?,
        policy: BoardPolicy,
    ): Comparator<Block> =
        unassignedLast(isBus)
            .thenBy { if (isPinned(it, isBus, prefs.pin)) 0 else 1 }
            .thenBy { block -> soonestArrival(block, nowMs, policy) }

    /**
     * When this block's next train arrives, for ordering — ignoring the ones
     * that have already gone.
     *
     * The filter is why [nowMs] is threaded this far in. Departed rows now reach
     * the grouping (they are what [BoardTicker]'s retention holds, and they used
     * to be filtered out one step earlier), and their targets are in the PAST —
     * so a platform whose trains have all left would sort ahead of every
     * platform you could still catch something from. Ordered on the soonest
     * LIVE train, a block with nothing left sinks to the bottom, which is where
     * a block of "Gone" rows belongs.
     *
     * [nowMs] null means "do not judge" — the pre-[BoardTicker] behaviour, kept
     * for callers that have already shed their departed rows.
     */
    private fun soonestArrival(block: Block, nowMs: Long?, policy: BoardPolicy): Long {
        val targets = block.value.mapNotNull { (p, _) -> p.targetEpochMs }
        if (nowMs == null) return targets.minOrNull() ?: Long.MAX_VALUE
        val cutoff = nowMs - policy.departedGraceMs
        return targets.filter { it >= cutoff }.minOrNull() ?: Long.MAX_VALUE
    }

    /** A block's own platform label — every ordering rule below keys off it. */
    private fun labelOf(block: Block, isBus: Boolean): String =
        groupLabelFor(block.value.first().first, isBus)

    /**
     * The one key BOTH orderings share, and the only one that is not a
     * preference: a block you cannot walk to sorts last.
     *
     * Extracted so the two cannot drift. They answer different questions and are
     * right to differ below this line — but a build where the board sank
     * unassigned platforms and the picker did not would offer the user a pin
     * guaranteed to do nothing.
     *
     * ## It does not apply to buses, and applying it was a bug
     * "Unassigned" means the operator has not told you where to stand yet. A bus
     * POLE is always somewhere you can stand: it has a naptan and a position on
     * a street, and TfL simply does not letter it unless the stop is a
     * multi-stop interchange. Judging poles by [isUnassigned] therefore marked
     * every ordinary one — the blank label reads as unassigned — and sank it
     * beneath any lettered pole at the same hub, *above* the pin, so pinning
     * "→ Clapham Junction" at a hub with one lettered stop did nothing at all.
     *
     * It went unnoticed because a hub whose poles are ALL unlettered has every
     * block on the same side of this key, where it cancels out.
     */
    private fun unassignedLast(isBus: Boolean): Comparator<Block> =
        compareBy { if (!isBus && isUnassigned(labelOf(it, isBus))) 1 else 0 }

    /**
     * The order the "show first" PICKER lists blocks in — by platform number, so
     * that 10 follows 9.
     *
     * Separate from [groupOrder] because it answers a different question. The
     * board is ordered by whichever train is soonest, which is right for
     * something you read at a glance and wrong for a list you pick from: ordered
     * that way the chips would rearrange themselves under the user's finger
     * every time the board refreshed.
     */
    private fun pickerOrder(isBus: Boolean): Comparator<Block> =
        unassignedLast(isBus)
            .thenBy { platformNumber(labelOf(it, isBus)) }
            // Lettered and unnumbered blocks ("Stop C") have no number to order
            // by and would otherwise sit in map order, which is arbitrary and
            // changes as departures arrive.
            .thenBy { labelOf(it, isBus).lowercase() }

    /**
     * Whether [pin] names this block — by its platform label, by its pole, or by
     * a line calling at it.
     */
    private fun isPinned(block: Block, isBus: Boolean, pin: BoardPin?): Boolean {
        if (pin == null || pin.id.isBlank()) return false
        return when (pin.kind) {
            BoardPin.Kind.PLATFORM ->
                labelOf(block, isBus).trim().equals(pin.id.trim(), ignoreCase = true)
            // The group key IS the pole naptan on bus — see [groupKeyFor]. Never
            // matched on the label, which is blank at every unlettered stop and
            // would pin all of them at once.
            BoardPin.Kind.STOP -> block.key.trim().equals(pin.id.trim(), ignoreCase = true)
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
     * @param prefs this station's arrangement — see [BoardConfig] for why
     *   nothing in it can change the GROUPING, only the order and the depth.
     */
    fun buildRows(
        feeds: List<Feed>,
        isBus: Boolean,
        prefs: BoardConfig = BoardConfig(),
    ): List<Row> = rowsFrom(buildGroups(feeds, isBus, prefs))

    /**
     * The same rows, from blocks that have already been built — and, on the home
     * screen, already been through [BoardTicker].
     *
     * Split out from [buildRows] because a surface that ticks needs the blocks
     * in between: the shed, the retention and the bump are all per-block, and a
     * flat list of strings has thrown that structure away. [buildRows] is now
     * this composed with [buildGroups], so a caller that does not tick is
     * unchanged.
     *
     * @param rowCap departures drawn per block. This is where display depth is
     *   decided on a ticked board — the blocks still carry their reserves, and
     *   the Swift widget's `prefix(slots)` is the same step on the other side of
     *   the wire. Anything below 1 means "draw them all", which is what a caller
     *   that already capped at grouping time wants.
     */
    fun rowsFrom(
        groups: List<Group>,
        rowCap: Int = 0,
        policy: BoardPolicy = BoardPolicyStore.current,
    ): List<Row> {
        // NOT padded to the floor: a board with nothing on it at all is the
        // caller's to describe ("No departures right now"), and three blank rows
        // would pre-empt that with something that looks like a broken board.
        // The floor below exists to stop a board with ONE real departure
        // collapsing, which is a different situation.
        if (groups.isEmpty()) return emptyList()

        val rows = mutableListOf<Row>()
        var departureCount = 0

        groups.forEach { group ->
            // A block ticked down to nothing is a header with no departures
            // under it. [BoardTicker.tick] already drops these; this guards the
            // path where blocks are assembled some other way.
            if (group.departures.isEmpty()) return@forEach
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

            val shown =
                if (rowCap > 0) group.departures.take(rowCap) else group.departures
            shown.forEach { departure ->
                rows.add(
                    Row.Departure(
                        linePrefix = if (group.showsLinePrefix) {
                            linePrefixText(departure.lineShort, group.isBus)
                        } else "",
                        destination = departure.prediction.destination,
                        eta = departure.prediction.eta,
                        departed = BoardTicker.isGone(departure.prediction, policy),
                    )
                )
                departureCount++
            }
        }

        // Every block was empty, so there is nothing here to pad OUT — same case
        // as no blocks at all, and the caller's to describe.
        if (rows.isEmpty()) return emptyList()

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
        /**
         * The naptan this departure was FETCHED from — [Feed.stationId].
         *
         * Carried through the merge so a caller can put a row back where it came
         * from. The board's hero needs exactly that: it answers "when is the
         * next train on THIS line", which is a per-selection question asked of a
         * list that no longer has selections in it. Re-deriving the answer from
         * the raw predictions instead is how the hero and the row beneath it
         * came to disagree — the row's label has been through [BoardTicker]'s
         * bump and the raw one has not.
         */
        val stationId: String = "",
        /** "Eastbound" / "Inbound" — see [Feed.direction]. */
        val direction: String = "",
    ) {
        /**
         * [UserSelection.boardKey] for the selection this row came from. Kept in
         * the same shape deliberately: it is what every per-board map on the home
         * screen is keyed on, and a second spelling would silently fail to match.
         */
        val boardKey: String get() = "${stationId}_${line}_$direction"

        /** Copy carrying a re-derived ETA — see [BoardTicker]. */
        fun relabelled(eta: String, isDue: Boolean): GroupedDeparture =
            copy(prediction = prediction.copy(eta = eta, isDue = isDue))
    }

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
        /**
         * [header] and its progressively shorter forms, widest first — see
         * [headerVariants]. The renderer measures and takes the first that fits.
         *
         * Carried on the group so a renderer that cannot call this file (the iOS
         * widget, across a process boundary) shrinks a header by the same rules
         * the home board does, instead of scaling the type down or ellipsising
         * the platform number off the end.
         */
        val headerVariants: List<String>,
        /** The backend's own platform label, verbatim: "Platform 8", "Stop C". */
        val label: String,
        val departures: List<GroupedDeparture>,
        /**
         * Whether this block holds departures from more than one line — a plain
         * fact about its contents, and nothing more. It answers the DISPLAY
         * question for rail (see [showsLinePrefix]) but is not the same
         * question, and conflating the two is what would make a single-route bus
         * pole claim to mix lines.
         */
        val mixesLines: Boolean,
        /**
         * Whether this block is a bus pole. Decides both whether a row takes a
         * prefix at all ([showsLinePrefix]) and how it is SHAPED
         * ([linePrefixText]).
         *
         * Defaulted so every existing construction site is unchanged.
         */
        val isBus: Boolean = false,
    ) {
        /**
         * Whether rows here name their own line.
         *
         * **Rail: only when the block mixes lines.** On a single-line platform
         * the header already named it, so a prefix would be the same word on
         * every row — pure noise, and it would change how the long-standing
         * single-line board looks.
         *
         * **Bus: always.** A route number is not a repeated word; it is the
         * first thing you scan a stop's board for, and every TfL bus sign prints
         * it against each departure even where a single route calls. It is also
         * the only thing separating two rows at a pole served by the 39 and the
         * 85 — the backend's `displayName` is the destination alone (see the
         * note on [GroupedDeparture.lineShort]), so without it those rows are
         * two bare place names.
         */
        val showsLinePrefix: Boolean get() = isBus || mixesLines
    }

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
     * @param prefs this station's arrangement — see [BoardConfig] for why
     *   nothing in it can change the GROUPING, only the order and the depth.
     */
    fun buildGroups(
        feeds: List<Feed>,
        isBus: Boolean,
        prefs: BoardConfig = BoardConfig(),
        /**
         * Departures kept per block, overriding [BoardConfig.rowCap].
         *
         * For the screen the cap IS the display depth and the preference is the
         * right answer. The iOS widget is the exception: it re-derives its ETA
         * labels every minute from a timeline built once, so it needs RESERVES
         * behind the visible rows — trains that have not been shown yet, and
         * departed ones it can hold when a platform empties out. Capping its
         * payload at what fits would leave it with nothing to shift into view.
         */
        rowCap: Int = prefs.rowCap,
        /**
         * Wall clock, when the caller intends to tick these blocks afterwards.
         *
         * Only [soonestArrival] reads it, and only to keep a block whose trains
         * have all left from leading the board. Null preserves the older
         * behaviour, which is correct for any caller that sheds departed rows
         * before it gets here.
         */
        nowMs: Long? = null,
        /**
         * The board rules in force. Only the ordering reads it, and only for the
         * grace period that decides whether a block's soonest train has already
         * left — see [soonestArrival].
         */
        policy: BoardPolicy = BoardPolicyStore.current,
    ): List<Group> {
        // Flatten first, keeping each departure's feed so the row can name its
        // line and the header can collect the group's lines and directions.
        val all = feeds.withFeeds()
        if (all.isEmpty()) return emptyList()

        return all.groupBy { (prediction, feed) -> groupKeyFor(prediction, feed, isBus) }
            .entries
            .sortedWith(groupOrder(isBus, prefs, nowMs, policy))
            .map { (key, entries) ->
                val sorted = entries.sortedBy { (p, _) -> StationlyFormatters.arrivalSortKey(p) }
                val directions = sorted.mapTo(mutableSetOf()) { (_, feed) -> feed.direction }
                val lines = sorted.map { (_, feed) -> feed.line }.distinct()

                // The platform label is backend-owned ("Platform 8", "Stop C",
                // "Platform not assigned") — displayed verbatim, never relabelled
                // client-side. See the consistency contract in
                // BOARD_AND_DREAM_UI.md.
                val label = groupLabelFor(sorted.first().first, isBus)

                // ── The cap picks WHICH trains, and it picks them off the
                // TIME-ordered list ──
                //
                // Taking from anything else is the bug waiting here: cap 3
                // applied to a list ordered by destination keeps the three
                // alphabetically-first ones, which at Green Park means three
                // Cockfosters trains an hour out and no sign of the Uxbridge one
                // leaving now. The rows the user is shown must always be the
                // soonest rows.
                //
                // Ceiling per platform — NOT the board floor. See MIN_BOARD_ROWS.
                // [rowCap], never `prefs.rowCap`: the widget passes its RESERVE
                // depth here and still wants the user's pin from prefs.
                val shown = sorted.take(rowCap)

                val header = headerFor(label, lines, directions, isBus)
                Group(
                    key = key,
                    header = header,
                    headerVariants = headerVariants(header),
                    label = label,
                    departures = shown.map { (prediction, feed) ->
                        GroupedDeparture(
                            prediction = prediction,
                            line = feed.line,
                            // One naming map for every mode, bus included: a
                            // route number comes back unchanged ("39"), which is
                            // already what the blind on the front of the bus
                            // says, and it is what `WidgetFeed.lineShort` has
                            // always carried across to the widget's own refresh.
                            //
                            // Blank on bus until now, on the belief that the
                            // backend appended the route to the destination
                            // itself ("53 Nags Head"). It does not: the
                            // prediction source builds `displayName` out of
                            // `towards`/`destinationName` alone (see
                            // `TubeDlrBusTramMixPredictionSource.formatDisplayName`),
                            // so a pole serving several routes rendered as a
                            // column of destinations with nothing saying which
                            // bus was which.
                            lineShort = LineShortNames.shortName(feed.line),
                            // The last point at which a row knows which board it
                            // came from — see [GroupedDeparture.stationId].
                            stationId = feed.stationId,
                            direction = feed.direction,
                        )
                    },
                    mixesLines = lines.size > 1,
                    isBus = isBus,
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
     *  - **Ordered by platform number, never by time** — see [pickerOrder].
     *
     * Buses go through [pinnableStops] instead: a pole's label is blank at every
     * unlettered stop, so this returns nothing for most bus hubs by design.
     */
    fun pinnablePlatforms(feeds: List<Feed>, isBus: Boolean): List<String> {
        val all = feeds.withFeeds()
        if (all.isEmpty()) return emptyList()
        return all.groupBy { (prediction, feed) -> groupKeyFor(prediction, feed, isBus) }
            .entries
            .sortedWith(pickerOrder(isBus))
            .map { block -> groupLabelFor(block.value.first().first, isBus).trim() }
            .filter { it.isNotBlank() && !isUnassigned(it) }
            .distinct()
    }

    /**
     * One bus pole the user could pin, named by where its buses go.
     *
     * [key] is the naptan — the board's group key for buses, and what
     * [BoardPin.Kind.STOP] matches on. [towards] is the only part a person can
     * recognise.
     */
    data class StopOption(val key: String, val towards: String)

    /**
     * The poles at a bus hub, for the "show first" picker.
     *
     * This exists because [pinnablePlatforms] cannot serve a bus hub and never
     * could: TfL letters stops only at multi-stop interchanges, so at an ordinary
     * one every pole's label is blank and there is nothing to offer. The pin that
     * IS worth having there — "the side going towards Putney Bridge first" — was
     * unreachable, while the only chips on offer were routes, and a routes chip
     * at a hub where both sides run the same route promotes every pole and
     * changes nothing.
     *
     * A pole is named by its most common destination rather than its soonest.
     * One short-terminating service should not rename the side of the road the
     * user stands on, and the point of the label is to be the same tomorrow.
     *
     * Poles with nothing to say are left out: a chip reading "towards —" is a
     * choice the user cannot evaluate. Ordered by that label so the list is
     * stable between refreshes, for the same reason [pickerOrder] exists.
     *
     * **One chip per destination**, even where two poles share one. The label is
     * the entire way a user tells these apart, so two chips both reading
     * "→ Putney Bridge" are a choice that cannot be made — and whichever they
     * picked, the board would look like it had ignored half of it. Keeping the
     * first is deterministic because the sort runs before the de-duplication.
     */
    fun pinnableStops(feeds: List<Feed>): List<StopOption> {
        val all = feeds.withFeeds()
        if (all.isEmpty()) return emptyList()
        return all.groupBy { (prediction, feed) -> groupKeyFor(prediction, feed, isBus = true) }
            .mapNotNull { (key, entries) ->
                entries.map { (p, _) -> p.destination.trim() }
                    .filter { it.isNotEmpty() }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?.let { StopOption(key = key, towards = it) }
            }
            .sortedBy { it.towards.lowercase() }
            .distinctBy { it.towards.lowercase() }
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
     * from each PLACE you could stand, soonest first.
     *
     * ## One leg per block, and no cap
     * This used to stop at two, on the argument that "three legs is a board
     * again, at which point the user should just expand it". That reasoning
     * mistook the collapsed card for a teaser. It is not — for a user who keeps
     * their stations collapsed it is the ONLY thing the home screen says about
     * that station, and dropping the third and fourth platform means the card
     * silently answers a question the user did not ask: not "when can I leave"
     * but "when can I leave from one of the two platforms we happened to keep".
     * A four-platform interchange with two legs looks complete and is wrong.
     *
     * The layout cost the cap was really paying for is handled where it belongs:
     * `HomeBoardBudget.boardMaxHeight` now charges the open board for the legs a
     * collapsed station will actually draw, instead of assuming a constant.
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
     * Only [BoardPin], which now decides ORDER alone rather than survival: with
     * every block getting a leg there is nothing left for a pin to rescue one
     * from. It still leads, so the platform the user named is the first line
     * their eye lands on.
     *
     * [BoardConfig.rowsPerPlatform] deliberately does NOT apply — a leg IS
     * one departure, so there is no depth here to bound. That is worth knowing
     * when reading the settings screen: of the two board settings, only the pin
     * has any effect until the station is opened, which is why the depth slider
     * says so.
     */
    fun collapsedLegs(
        feeds: List<Feed>,
        isBus: Boolean,
        prefs: BoardConfig = BoardConfig(),
    ): List<Leg> {
        val all = feeds.withFeeds()
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
        // [LineShortNames.shortName] for every mode, bus included: it returns a
        // route number unchanged apart from casing a night route's letter
        // ("n39" → "N39"), which the raw feed id does not. One naming map, so a
        // leg and the row it collapses cannot spell the same route two ways.
        val line = LineShortNames.shortName(feed.line)
        return listOf(line, label).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * How many blocks this station has — platforms on rail, poles on bus.
     *
     * The number of legs a collapsed card will draw, and the reason it exists
     * separately from [collapsedLegs] is STABILITY. The home screen charges the
     * open board for the height a collapsed one takes
     * (`HomeBoardBudget.boardMaxHeight`), and that figure must not move every
     * time a train departs, or every open board on the page re-flows underneath
     * whatever the user is reading.
     *
     * So it counts blocks in the CACHED rows rather than legs in the ticked
     * ones. The two differ in exactly one case — a platform whose departures
     * have all gone drops its leg but keeps its block — which makes this a
     * stable upper bound: it over-reserves by one row for a few minutes rather
     * than jittering, and it can never under-reserve and push the board off
     * screen. It settles on its own when `SqlStorage.getPredictions` drops the
     * whole payload at [SqlStorage.PAYLOAD_TTL_MS].
     *
     * Counts into a set rather than through [withFeeds]: this runs for every
     * collapsed station on every prediction update, and the pairs the shared
     * flattening allocates would all be thrown away to answer "how many distinct
     * keys" — the one grouping question that needs no departures kept.
     */
    fun blockCount(feeds: List<Feed>, isBus: Boolean): Int {
        val keys = mutableSetOf<String>()
        feeds.forEach { feed ->
            feed.predictions.forEach { keys.add(groupKeyFor(it, feed, isBus)) }
        }
        return keys.size
    }
}
