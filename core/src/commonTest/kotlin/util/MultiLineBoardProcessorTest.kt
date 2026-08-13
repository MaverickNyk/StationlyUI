package util

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.BoardPin
import com.stationly.core.util.MultiLineBoardProcessor
import com.stationly.core.util.MultiLineBoardProcessor.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The board's grouping rules.
 *
 * Every case here is a bug that actually shipped to a device during the
 * multi-line rebuild, which is why they are worth pinning: the two row limits
 * being conflated, buses grouped on a display letter instead of the pole's
 * naptan, and unassigned platforms sorting by time as though you could go and
 * stand on one.
 */
class MultiLineBoardProcessorTest {

    private var clock = 0L

    /** A departure `minutes` from now, on [platform]. */
    private fun pred(
        destination: String,
        minutes: Int,
        platform: String = "Platform 1",
        stopLetter: String? = null,
    ) = PredictionDisplay(
        destination = destination,
        platform = platform,
        eta = if (minutes == 0) "Due" else "$minutes min",
        isDue = minutes == 0,
        stopLetter = stopLetter,
        targetEpochMs = minutes * 60_000L,
    )

    private fun feed(
        line: String,
        direction: String = "Northbound",
        stationId: String = "940GZZ",
        predictions: List<PredictionDisplay>,
    ) = MultiLineBoardProcessor.Feed(
        stationId = stationId,
        line = line,
        direction = direction,
        predictions = predictions,
    )

    private fun headers(rows: List<Row>) = rows.filterIsInstance<Row.PlatformHeader>().map { it.title }
    private fun departures(rows: List<Row>) = rows.filterIsInstance<Row.Departure>()
    /** Padding rows are blank — see MIN_BOARD_ROWS. */
    private fun realDepartures(rows: List<Row>) = departures(rows).filter { it.destination.isNotBlank() }

    // ── The two row limits, which are NOT the same limit ──

    @Test
    fun `caps each platform at three departures`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("northern", predictions = (1..8).map { pred("Morden", it) })
            ),
            isBus = false,
        )
        assertEquals(3, realDepartures(rows).size, "a platform must never show more than three")
    }

    @Test
    fun `caps per platform — not per board`() {
        // Two platforms, five departures each: each is capped at three, so the
        // board shows six — the cap is not a board-wide budget.
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("northern", predictions =
                    (1..5).map { pred("Morden", it, platform = "Platform 1") } +
                    (1..5).map { pred("High Barnet", it, platform = "Platform 2") }
                )
            ),
            isBus = false,
        )
        assertEquals(6, realDepartures(rows).size)
    }

    @Test
    fun `pads to three rows for the whole board when it is nearly empty`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("northern", predictions = listOf(pred("Morden", 2)))),
            isBus = false,
        )
        assertEquals(1, realDepartures(rows).size)
        assertEquals(3, departures(rows).size, "one real departure plus two blanks reaches the floor")
    }

    @Test
    fun `does not pad when several platforms already clear the floor together`() {
        // Three platforms with two departures each: no single platform reaches
        // three, but the BOARD has six, so nothing should be padded. Applying
        // the floor per platform (the original bug) would add three blanks.
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("northern", predictions =
                    (1..2).map { pred("A", it, platform = "Platform 1") } +
                    (1..2).map { pred("B", it, platform = "Platform 2") } +
                    (1..2).map { pred("C", it, platform = "Platform 3") }
                )
            ),
            isBus = false,
        )
        assertEquals(6, realDepartures(rows).size)
        assertEquals(6, departures(rows).size, "no blank padding rows")
    }

    // ── Grouping ──

    @Test
    fun `merges two lines sharing one platform into a single block`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("circle", predictions = listOf(pred("Edgware Road", 2, "Platform 2"))),
                feed("district", predictions = listOf(pred("Upminster", 4, "Platform 2"))),
            ),
            isBus = false,
        )
        assertEquals(1, headers(rows).size, "one physical platform is one block")
        assertEquals(listOf("Edgware Road", "Upminster"), realDepartures(rows).map { it.destination })
    }

    @Test
    fun `prefixes rows with the line only when the platform mixes lines`() {
        val mixed = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("circle", predictions = listOf(pred("Edgware Road", 2, "Platform 2"))),
                feed("district", predictions = listOf(pred("Upminster", 4, "Platform 2"))),
            ),
            isBus = false,
        )
        assertEquals(listOf("(Circ.)", "(Dist.)"), realDepartures(mixed).map { it.linePrefix })

        val single = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("circle", predictions = listOf(pred("Edgware Road", 2)))),
            isBus = false,
        )
        assertEquals("", realDepartures(single).single().linePrefix,
            "a single-line platform must look exactly as it always did")
    }

    @Test
    fun `orders platform blocks by their soonest departure`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("northern", predictions =
                    listOf(pred("Slow", 9, platform = "Platform 9")) +
                    listOf(pred("Fast", 1, platform = "Platform 1"))
                )
            ),
            isBus = false,
        )
        assertEquals(listOf("Northern Platform 1 Northbound", "Northern Platform 9 Northbound"), headers(rows))
    }

    @Test
    fun `sorts unassigned platforms last however soon their trains are`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("elizabeth", predictions =
                    listOf(pred("Soon", 0, platform = "Platform not assigned")) +
                    listOf(pred("Later", 8, platform = "Platform 3"))
                )
            ),
            isBus = false,
        )
        assertTrue(headers(rows).first().contains("Platform 3"),
            "an assigned platform you can walk to outranks an unassigned one")
        assertTrue(headers(rows).last().contains("not assigned"))
    }

    // ── Buses ──

    @Test
    fun `groups buses by pole naptan — not by stop letter`() {
        // Smithwood Close: both directions of route 39 are unlettered poles with
        // no platform, so a stopLetter/platform key collapsed them into one
        // block and interleaved departures from opposite sides of the road.
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("39", stationId = "490008805N", direction = "inbound",
                    predictions = listOf(pred("Putney Bridge", 14, platform = ""))),
                feed("39", stationId = "490012211N", direction = "outbound",
                    predictions = listOf(pred("Clapham Junction", 2, platform = ""))),
            ),
            isBus = true,
        )
        assertEquals(2, headers(rows).size, "two poles are two places")
    }

    @Test
    fun `labels a bus stop by its routes — and adds the stop letter when there is one`() {
        val lettered = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("39", predictions = listOf(pred("Putney", 3, platform = "Stop W")))),
            isBus = true,
        )
        assertEquals(listOf("Bus 39 Stop W"), headers(lettered))

        val unlettered = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("39", predictions = listOf(pred("Putney", 3, platform = "")))),
            isBus = true,
        )
        assertEquals(listOf("Bus 39"), headers(unlettered),
            "most suburban stops are unlettered — the routes are all we have")
    }

    @Test
    fun `never prefixes a bus row with the route — since the backend appends it`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("39", stationId = "A", predictions = listOf(pred("39 Putney", 3, platform = "Stop W"))),
                feed("85", stationId = "A", predictions = listOf(pred("85 Kingston", 5, platform = "Stop W"))),
            ),
            isBus = true,
        )
        assertTrue(realDepartures(rows).all { it.linePrefix.isEmpty() })
    }

    // ── Direction ──

    @Test
    fun `appends a compass direction but never inbound or outbound`() {
        val compass = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("northern", direction = "Southbound",
                predictions = listOf(pred("Morden", 2, "Platform 8")))),
            isBus = false,
        )
        assertEquals(listOf("Northern Platform 8 Southbound"), headers(compass))

        val operational = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("northern", direction = "inbound",
                predictions = listOf(pred("Morden", 2, "Platform 8")))),
            isBus = false,
        )
        assertEquals(listOf("Northern Platform 8"), headers(operational),
            "'inbound' is operational vocabulary and means nothing to a passenger")
    }

    @Test
    fun `omits the direction when a platform is worked both ways`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("circle", direction = "Eastbound", predictions = listOf(pred("A", 2, "Platform 2"))),
                feed("circle", direction = "Westbound", predictions = listOf(pred("B", 4, "Platform 2"))),
            ),
            isBus = false,
        )
        assertEquals(listOf("Circle Platform 2"), headers(rows),
            "an arbitrary one of the two would be actively wrong")
    }

    // ── Header shrink ladder ──

    @Test
    fun `header variants shorten the boilerplate word before the direction`() {
        val variants = MultiLineBoardProcessor.headerVariants("Dist & Circ. Platform 2 Northbound")
        assertEquals("Dist & Circ. Platform 2 Northbound", variants[0])
        assertEquals("Dist & Circ. Plat. 2 Northbound", variants[1])
        assertEquals("Dist & Circ. Plat. 2", variants.last())
    }

    @Test
    fun `header variants collapse to one entry when there is nothing to shorten`() {
        assertEquals(listOf("Stop W"), MultiLineBoardProcessor.headerVariants("Stop W"))
    }

    @Test
    fun `header variants shorten a long line name before dropping the direction`() {
        // The rung that makes a widget header fit. Shortening a line name costs
        // nothing a passenger cannot read back — "H&C" is what the roundel and
        // the station signage already say — so it must come BEFORE the one rung
        // that actually loses a fact.
        val variants = MultiLineBoardProcessor.headerVariants("Hammersmith City Platform 1 Eastbound")
        assertEquals("Hammersmith City Platform 1 Eastbound", variants[0])
        assertEquals("Hammersmith City Plat. 1 Eastbound", variants[1])
        assertEquals("H&C Plat. 1 Eastbound", variants[2])
        assertEquals("H&C Plat. 1", variants[3])
    }

    @Test
    fun `header variants drop a direction the backend put in parentheses`() {
        // Real device data: the backend's own platform label frequently arrives
        // as "Platform 2 (Westbound)", so the direction is INSIDE the label
        // rather than appended by headerFor. Stripping only the appended form
        // left the widest headers untouched at exactly the rung meant to rescue
        // them.
        val variants = MultiLineBoardProcessor.headerVariants("Piccadilly Platform 2 (Westbound)")
        assertEquals("Piccadilly Platform 2 (Westbound)", variants[0])
        assertEquals("Picc. Plat. 2 (Westbound)", variants[2])
        assertEquals("Picc. Plat. 2", variants.last())
    }

    @Test
    fun `every group carries its own shrink ladder`() {
        // The widget cannot call headerVariants — it is a separate process — so
        // the ladder has to travel with the block or the extension is back to
        // scaling the type down.
        val groups = MultiLineBoardProcessor.buildGroups(
            feeds = listOf(feed("northern", predictions = listOf(
                pred("Morden", 2, platform = "Platform 1 (Westbound)")
            ))),
            isBus = false,
        )
        val group = groups.single()
        assertEquals(group.header, group.headerVariants.first(), "widest rung is the header itself")
        assertEquals("Nor. Plat. 1", group.headerVariants.last())
    }

    // ── Empty ──

    @Test
    fun `returns no rows at all when every line is empty`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("northern", predictions = emptyList())),
            isBus = false,
        )
        assertTrue(rows.isEmpty(), "the caller substitutes the board-wide fallback copy")
    }
    // ── Arrangement: the user's own sort, depth and pin ──
    //
    // Every case here guards the same invariant from a different angle: a
    // setting may reorder blocks, reorder rows INSIDE a block, or promote a
    // block — and nothing may dissolve the grouping or change WHICH trains the
    // board picked.

    @Test
    fun `block order is the soonest train, and no setting can change it`() {
        // This board reads 10, 9, 2 because that is the order the trains arrive
        // in, not because anything was configured. Block order is fixed — see
        // BoardConfig for why the sort that used to be here was removed.
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("northern", predictions = listOf(
                    pred("A", 1, platform = "Platform 10"),
                    pred("B", 2, platform = "Platform 9"),
                    pred("C", 3, platform = "Platform 2"),
                ))
            ),
            isBus = false,
        )
        assertEquals(
            listOf("Northern Platform 10 Northbound", "Northern Platform 9 Northbound",
                "Northern Platform 2 Northbound"),
            headers(rows),
        )
    }

    @Test
    fun `rows inside a block are always in arrival order`() {
        // There is no level left to order by once you are on one platform.
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("victoria", predictions = listOf(
                    pred("Later", 5), pred("Soonest", 1), pred("Middle", 3),
                ))
            ),
            isBus = false,
        )
        assertEquals(listOf("Soonest", "Middle", "Later"), realDepartures(rows).map { it.destination })
    }

    @Test
    fun `the cap keeps the SOONEST trains — never the alphabetically first ones`() {
        // The rows a user is shown must always be the soonest rows. Capping off
        // any other ordering would leave this board showing two trains twenty
        // minutes out with no sign of the one leaving now.
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("piccadilly", predictions = listOf(
                    pred("Zebra", 1), pred("Alpha", 20), pred("Beta", 25),
                ))
            ),
            isBus = false,
            prefs = BoardConfig(rowsPerPlatform = 2),
        )
        assertEquals(listOf("Zebra", "Alpha"), realDepartures(rows).map { it.destination })
    }

    @Test
    fun `shows as many departures per platform as the user asked for`() {
        val eight = (1..8).map { pred("Morden", it) }
        listOf(2, 3, 4, 5).forEach { asked ->
            val rows = MultiLineBoardProcessor.buildRows(
                feeds = listOf(feed("northern", predictions = eight)),
                isBus = false,
                prefs = BoardConfig(rowsPerPlatform = asked),
            )
            assertEquals(asked, realDepartures(rows).size, "asked for $asked per platform")
        }
    }

    @Test
    fun `clamps a stored row count that is outside the range now offered`() {
        // A value can arrive from a build whose limits differed. A board
        // rendering twelve rows because an old preference said so is worse than
        // one that quietly honours today's range.
        val twelve = (1..12).map { pred("Morden", it) }
        val tooMany = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("northern", predictions = twelve)),
            isBus = false,
            prefs = BoardConfig(rowsPerPlatform = 12),
        )
        assertEquals(BoardConfig.MAX_ROWS_PER_PLATFORM, realDepartures(tooMany).size)

        val tooFew = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("northern", predictions = twelve)),
            isBus = false,
            prefs = BoardConfig(rowsPerPlatform = 0),
        )
        assertEquals(BoardConfig.MIN_ROWS_PER_PLATFORM, realDepartures(tooFew).size)
    }

    @Test
    fun `a pinned platform leads the board however far off its trains are`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("northern", predictions = listOf(
                    pred("Soon", 1, platform = "Platform 1"),
                    pred("Later", 12, platform = "Platform 9"),
                ))
            ),
            isBus = false,
            prefs = BoardConfig(pin = BoardPin(BoardPin.Kind.PLATFORM, "Platform 9")),
        )
        assertTrue(headers(rows).first().contains("Platform 9"))
        assertTrue(headers(rows).last().contains("Platform 1"), "the rest keep their own order")
    }

    @Test
    fun `a pinned line promotes EVERY platform it calls at`() {
        // A line at an interchange is genuinely on two platforms. Promoting one
        // of them answers "show me my line" with half of it.
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("northern", predictions = listOf(pred("Morden", 1, platform = "Platform 1"))),
                feed("victoria", predictions = listOf(
                    pred("Brixton", 6, platform = "Platform 3"),
                    pred("Brixton", 4, platform = "Platform 6"),
                )),
            ),
            isBus = false,
            prefs = BoardConfig(pin = BoardPin(BoardPin.Kind.LINE, "victoria")),
        )
        assertEquals(
            listOf("Victoria Platform 6 Northbound", "Victoria Platform 3 Northbound",
                "Northern Platform 1 Northbound"),
            headers(rows),
            "both Victoria blocks lead — soonest first between them",
        )
    }

    @Test
    fun `an unassigned platform stays last even when the pinned line is on it`() {
        // A pin is a preference. "You cannot go and stand on a platform TfL has
        // not allocated" is a fact, and facts outrank preferences.
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(
                feed("elizabeth", predictions = listOf(
                    pred("Soon", 0, platform = "Platform not assigned"),
                    pred("Later", 8, platform = "Platform 3"),
                )),
            ),
            isBus = false,
            prefs = BoardConfig(pin = BoardPin(BoardPin.Kind.LINE, "elizabeth")),
        )
        assertTrue(headers(rows).last().contains("not assigned"))
    }

    @Test
    fun `a pin that matches nothing tonight leaves the board exactly as it was`() {
        val feeds = listOf(
            feed("northern", predictions = listOf(
                pred("Soon", 1, platform = "Platform 1"),
                pred("Later", 12, platform = "Platform 9"),
            ))
        )
        val pinned = MultiLineBoardProcessor.buildRows(
            feeds = feeds,
            isBus = false,
            prefs = BoardConfig(pin = BoardPin(BoardPin.Kind.PLATFORM, "Platform 99")),
        )
        assertEquals(headers(MultiLineBoardProcessor.buildRows(feeds, isBus = false)), headers(pinned),
            "the platform will be back tomorrow — the setting waits rather than being pruned")
    }

    @Test
    fun `a pinned platform leads the legs on a collapsed card`() {
        // The pin used to decide SURVIVAL as well as order, because the card
        // stopped at two legs. With every block getting one there is nothing to
        // rescue a platform from, so this is purely "show first" now — but it
        // still has to work collapsed, or the setting reads as broken to anyone
        // whose stations are closed by default.
        //
        // Platform 7's train is the LAST to arrive and still leads.
        val legs = MultiLineBoardProcessor.collapsedLegs(
            feeds = listOf(
                feed("victoria", predictions = listOf(pred("Brixton", 1, platform = "Platform 1"))),
                feed("northern", predictions = listOf(pred("Morden", 2, platform = "Platform 2"))),
                feed("central", predictions = listOf(pred("Epping", 9, platform = "Platform 7"))),
            ),
            isBus = false,
            prefs = BoardConfig(pin = BoardPin(BoardPin.Kind.PLATFORM, "Platform 7")),
        )
        assertEquals(listOf("Epping", "Brixton", "Morden"), legs.map { it.towards })
    }

    @Test
    fun `collapsed legs ignore the depth cap — a leg is already one departure`() {
        // The cap is the one board setting with no effect until the station is
        // opened, which is why the slider on the settings screen says so.
        val legs = MultiLineBoardProcessor.collapsedLegs(
            feeds = listOf(
                feed("victoria", predictions = listOf(
                    pred("Brixton", 9, platform = "Platform 1"),
                    pred("Brixton", 11, platform = "Platform 1"),
                )),
                feed("northern", predictions = listOf(pred("Morden", 1, platform = "Platform 8"))),
                feed("central", predictions = listOf(pred("Epping", 2, platform = "Platform 9"))),
            ),
            isBus = false,
            prefs = BoardConfig(rowsPerPlatform = 5),
        )
        // One leg per PLATFORM, never per departure: Platform 1 has two trains
        // and contributes exactly one leg, for its soonest. Depth bounds a
        // block's rows and a leg is not a row.
        assertEquals(listOf("Morden", "Epping", "Brixton"), legs.map { it.towards })
        assertEquals(listOf("1 min", "2 min", "9 min"), legs.map { it.eta })
    }

    // ── What the "show first" picker is allowed to offer ──

    @Test
    fun `offers the platforms the board has actually shown — in number order`() {
        // Number order, never time order: this is a list someone reads and picks
        // from, and ordering it by whose train is soonest would rearrange it
        // under their finger every time the board refreshed.
        val platforms = MultiLineBoardProcessor.pinnablePlatforms(
            feeds = listOf(
                feed("northern", predictions = listOf(
                    pred("A", 1, platform = "Platform 10"),
                    pred("B", 2, platform = "Platform 2"),
                    pred("C", 3, platform = "Platform 2"),
                ))
            ),
            isBus = false,
        )
        assertEquals(listOf("Platform 2", "Platform 10"), platforms)
    }

    @Test
    fun `never offers an unassigned platform — pinning one could not do anything`() {
        val platforms = MultiLineBoardProcessor.pinnablePlatforms(
            feeds = listOf(
                feed("elizabeth", predictions = listOf(
                    pred("A", 1, platform = "Platform not assigned"),
                    pred("B", 2, platform = "Platform 3"),
                ))
            ),
            isBus = false,
        )
        assertEquals(listOf("Platform 3"), platforms)
    }

    @Test
    fun `offers nothing for an unlettered bus stop, which has no label to show`() {
        val platforms = MultiLineBoardProcessor.pinnablePlatforms(
            feeds = listOf(
                feed("39", stationId = "490008805N",
                    predictions = listOf(pred("Putney", 3, platform = ""))),
            ),
            isBus = true,
        )
        assertTrue(platforms.isEmpty(), "a blank chip is worse than no chip")
    }

    // ── The bus hub's own picker: poles, named by where they go ──

    @Test
    fun `offers each unlettered pole, named by where its buses go`() {
        // The case pinnablePlatforms cannot serve and never could. Both poles are
        // blank-labelled, so the naptan is the only thing telling them apart and
        // the destination is the only thing a person recognises.
        val stops = MultiLineBoardProcessor.pinnableStops(
            feeds = listOf(
                feed("39", stationId = "490008805N", predictions = listOf(
                    pred("Putney Bridge", 3, platform = ""),
                    pred("Putney Bridge", 9, platform = ""),
                )),
                feed("39", stationId = "490012211N", predictions = listOf(
                    pred("Clapham Junction", 5, platform = ""),
                )),
            ),
        )
        assertEquals(
            listOf(
                MultiLineBoardProcessor.StopOption("490012211N", "Clapham Junction"),
                MultiLineBoardProcessor.StopOption("490008805N", "Putney Bridge"),
            ),
            stops,
            "ordered by the label the user reads, so the chips do not move between refreshes",
        )
    }

    @Test
    fun `names a pole by its most common destination, not its soonest`() {
        // One short-terminating service must not rename the side of the road.
        val stops = MultiLineBoardProcessor.pinnableStops(
            feeds = listOf(
                feed("53", stationId = "490000123A", predictions = listOf(
                    pred("Horse Guards Parade", 1, platform = ""),
                    pred("Plumstead", 6, platform = ""),
                    pred("Plumstead", 14, platform = ""),
                )),
            ),
        )
        assertEquals(listOf("Plumstead"), stops.map { it.towards })
    }

    @Test
    fun `a pinned pole leads the board however far off its buses are`() {
        val groups = MultiLineBoardProcessor.buildGroups(
            feeds = listOf(
                feed("39", stationId = "490008805N",
                    predictions = listOf(pred("Putney Bridge", 12, platform = ""))),
                feed("39", stationId = "490012211N",
                    predictions = listOf(pred("Clapham Junction", 1, platform = ""))),
            ),
            isBus = true,
            prefs = BoardConfig(pin = BoardPin(BoardPin.Kind.STOP, "490008805N")),
        )
        assertEquals(listOf("490008805N", "490012211N"), groups.map { it.key })
    }

    @Test
    fun `a pinned UNLETTERED pole still leads a hub that has a lettered one`() {
        // TfL letters a stop only at multi-stop interchanges, so a hub can hold
        // one lettered pole and one bare one. Judging poles by `isUnassigned`
        // sank the bare one — its label is "" — beneath the lettered one, and
        // that key sorts ABOVE the pin, so this chip did nothing.
        //
        // Invisible at a hub whose poles are all unlettered, which is where
        // every other bus test here sits: there they land on the same side of
        // that key and it cancels out.
        val groups = MultiLineBoardProcessor.buildGroups(
            feeds = listOf(
                feed("39", stationId = "490008805N",
                    predictions = listOf(pred("Putney Bridge", 12, platform = ""))),
                feed("39", stationId = "490012211N",
                    predictions = listOf(pred("Clapham Junction", 1, platform = "Stop C"))),
            ),
            isBus = true,
            prefs = BoardConfig(pin = BoardPin(BoardPin.Kind.STOP, "490008805N")),
        )
        assertEquals(listOf("490008805N", "490012211N"), groups.map { it.key })
    }

    @Test
    fun `a rail platform TfL has not allocated still sorts last, pin or no pin`() {
        // The other half of the rule above: on RAIL the check must stay. You
        // cannot go and stand on a platform nobody has assigned.
        val groups = MultiLineBoardProcessor.buildGroups(
            feeds = listOf(
                feed("elizabeth", predictions = listOf(
                    pred("Soon", 1, platform = "Platform not assigned"),
                    pred("Later", 9, platform = "Platform 3"),
                )),
            ),
            isBus = false,
            prefs = BoardConfig(pin = BoardPin(BoardPin.Kind.LINE, "elizabeth")),
        )
        assertEquals(listOf("Platform 3", "Platform not assigned"), groups.map { it.key })
    }

    @Test
    fun `a pinned pole promotes ONE pole — a pinned route would promote both`() {
        // Why STOP had to exist. Both sides of the road run the 39, so a LINE pin
        // at this hub promotes every block and changes nothing at all.
        val feeds = listOf(
            feed("39", stationId = "490008805N",
                predictions = listOf(pred("Putney Bridge", 12, platform = ""))),
            feed("39", stationId = "490012211N",
                predictions = listOf(pred("Clapham Junction", 1, platform = ""))),
        )
        val byRoute = MultiLineBoardProcessor.buildGroups(
            feeds = feeds,
            isBus = true,
            prefs = BoardConfig(pin = BoardPin(BoardPin.Kind.LINE, "39")),
        )
        assertEquals(
            listOf("490012211N", "490008805N"),
            byRoute.map { it.key },
            "still plain time order — the pin promoted both, which promotes neither",
        )
    }

    @Test
    fun `bus mode is decided in one place`() {
        assertTrue(MultiLineBoardProcessor.isBus("bus"))
        assertTrue(MultiLineBoardProcessor.isBus("Bus"))
        assertTrue(MultiLineBoardProcessor.isBus(" bus "))
        assertTrue(!MultiLineBoardProcessor.isBus("tube"))
        assertTrue(!MultiLineBoardProcessor.isBus(null))
    }

    // ── Collapsed legs: the whole board as one line per direction ──

    @Test
    fun `collapsed legs take the soonest departure per platform`() {
        val legs = MultiLineBoardProcessor.collapsedLegs(
            feeds = listOf(
                feed("victoria", predictions = listOf(
                    pred("Brixton", 7, platform = "Platform 3"),
                    pred("Brixton", 2, platform = "Platform 3"),
                )),
            ),
            isBus = false,
        )
        assertEquals(1, legs.size)
        assertEquals("2 min", legs[0].eta)
        assertEquals("Brixton", legs[0].towards)
    }

    @Test
    fun `collapsed legs are ordered soonest first — not by the eta label`() {
        // "10 min" sorts BEFORE "2 min" as a string. Ordering on the label is
        // the bug this pins; the sort key is the absolute arrival time.
        val legs = MultiLineBoardProcessor.collapsedLegs(
            feeds = listOf(
                feed("victoria", predictions = listOf(pred("Brixton", 10, platform = "Platform 3"))),
                feed("northern", predictions = listOf(pred("Morden", 2, platform = "Platform 4"))),
            ),
            isBus = false,
        )
        assertEquals(listOf("2 min", "10 min"), legs.map { it.eta })
    }

    @Test
    fun `every platform gets a leg — a collapsed card hides nothing`() {
        // This used to stop at two. For anyone who keeps their stations
        // collapsed the card IS the home screen's answer, and a four-platform
        // station showing two legs looks complete while answering a narrower
        // question than the one asked.
        val feeds = listOf(
            feed("victoria", predictions = listOf(pred("Brixton", 1, platform = "Platform 1"))),
            feed("northern", predictions = listOf(pred("Morden", 2, platform = "Platform 2"))),
            feed("central", predictions = listOf(pred("Epping", 3, platform = "Platform 3"))),
        )
        val legs = MultiLineBoardProcessor.collapsedLegs(feeds = feeds, isBus = false)
        assertEquals(listOf("1 min", "2 min", "3 min"), legs.map { it.eta })
        // The height budget charges the open board per leg, so its count has to
        // agree with what the card will draw — see HomeBoardBudget.
        assertEquals(legs.size, MultiLineBoardProcessor.blockCount(feeds, isBus = false))
    }

    @Test
    fun `block count holds still when a platform runs dry`() {
        // The budget's stability contract: `collapsedLegs` reads TICKED rows and
        // loses a leg the moment a platform's last train goes, but `blockCount`
        // reads the cached ones and keeps the block. Budgeting on the leg list
        // would re-flow every open board on the page as trains depart.
        val cached = listOf(
            feed("victoria", predictions = listOf(pred("Brixton", 1, platform = "Platform 1"))),
            feed("northern", predictions = listOf(pred("Morden", 2, platform = "Platform 2"))),
        )
        // What the card draws once Platform 2 has emptied out.
        val live = listOf(cached[0], feed("northern", predictions = emptyList()))

        assertEquals(1, MultiLineBoardProcessor.collapsedLegs(live, isBus = false).size)
        assertEquals(2, MultiLineBoardProcessor.blockCount(cached, isBus = false))
    }

    @Test
    fun `two bus poles at one hub count as two blocks even with no platform between them`() {
        // The pole is the group key on bus — TfL letters stops only at
        // multi-stop interchanges, so counting by `platform` would collapse an
        // ordinary hub to one block and under-reserve its card's height.
        val poles = listOf(
            feed("39", stationId = "490008805N", predictions = listOf(pred("Putney Bridge", 1, platform = ""))),
            feed("39", stationId = "490012211N", predictions = listOf(pred("Clapham Junction", 2, platform = ""))),
        )
        assertEquals(2, MultiLineBoardProcessor.blockCount(poles, isBus = true))
        assertEquals(1, MultiLineBoardProcessor.blockCount(poles, isBus = false))
    }

    @Test
    fun `collapsed legs abbreviate the platform and the line — and drop the direction`() {
        // A leg shares ONE row with the station name, the destination and the
        // countdown, so every token has to earn its width. "Platform" is
        // boilerplate, the full line name is wider than the rest of the row put
        // together, and the compass is already implied by the destination
        // sitting immediately after it.
        val legs = MultiLineBoardProcessor.collapsedLegs(
            feeds = listOf(
                feed("hammersmith-city", direction = "Westbound",
                    predictions = listOf(pred("Ealing Broadway", 4, platform = "Platform 3"))),
            ),
            isBus = false,
        )
        assertEquals("H&C Plat. 3", legs[0].where)
    }

    @Test
    fun `collapsed bus legs drop the Bus prefix`() {
        // The card's roundel is already a bus roundel, and a route number is the
        // shortest true label there is.
        val legs = MultiLineBoardProcessor.collapsedLegs(
            feeds = listOf(
                feed("39", stationId = "490008805N",
                    predictions = listOf(pred("Putney", 3, platform = "", stopLetter = "W"))),
            ),
            isBus = true,
        )
        assertEquals("39 Stop W", legs[0].where)
    }

    @Test
    fun `collapsed legs split a bus stop by pole — not by line`() {
        // Both directions of route 39 at one hub are separate naptans, and they
        // are genuinely opposite sides of the road. One leg would answer for
        // half the users of this card.
        val legs = MultiLineBoardProcessor.collapsedLegs(
            feeds = listOf(
                feed("39", direction = "inbound", stationId = "490008805N",
                    predictions = listOf(pred("Putney", 3, platform = ""))),
                feed("39", direction = "outbound", stationId = "490012211N",
                    predictions = listOf(pred("Clapham", 5, platform = ""))),
            ),
            isBus = true,
        )
        assertEquals(2, legs.size)
        assertEquals(listOf("Putney", "Clapham"), legs.map { it.towards })
    }

    @Test
    fun `collapsed legs are empty when nothing is departing`() {
        val legs = MultiLineBoardProcessor.collapsedLegs(
            feeds = listOf(feed("victoria", predictions = emptyList())),
            isBus = false,
        )
        assertTrue(legs.isEmpty(), "the header shows the station alone")
    }

    // ─────────────────────────────────────────────────────────────────────
    // buildGroups — the shared entry point every board surface goes through.
    //
    // These exist because the iOS widget re-derived its own grouping for a
    // while and got exactly the case below wrong. Anything that renders a
    // board must come through here, and these are the rules it inherits.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `groups buses by pole — two unlettered poles at one hub stay apart`() {
        // The case that motivated this: TfL letters stops only at multi-stop
        // interchanges, so at an ordinary pair of poles both carry a blank
        // platform AND a null stopLetter. Anything keyed on the platform string
        // merges them — and they are on opposite sides of the road.
        val groups = MultiLineBoardProcessor.buildGroups(
            feeds = listOf(
                feed("39", stationId = "490008805N", direction = "inbound",
                    predictions = listOf(pred("39 Putney Bridge", 3, platform = ""))),
                feed("39", stationId = "490012211N", direction = "outbound",
                    predictions = listOf(pred("39 Clapham Junction", 5, platform = ""))),
            ),
            isBus = true,
        )
        assertEquals(2, groups.size, "one block per pole, not one per platform label")
        assertEquals(
            listOf("490008805N", "490012211N"),
            groups.map { it.key },
            "the pole naptan is the block's identity",
        )
    }

    @Test
    fun `groups rail by platform across every line calling there`() {
        val groups = MultiLineBoardProcessor.buildGroups(
            feeds = listOf(
                feed("circle", predictions = listOf(pred("Edgware Road", 2, platform = "Platform 1"))),
                feed("hammersmith-city", predictions = listOf(pred("Barking", 4, platform = "Platform 1"))),
                feed("circle", predictions = listOf(pred("Aldgate", 6, platform = "Platform 2"))),
            ),
            isBus = false,
        )
        assertEquals(2, groups.size, "two platforms, three feeds")
        val platform1 = groups.first { it.key == "Platform 1" }
        assertTrue(platform1.mixesLines, "two lines share it, so rows name their line")
        assertEquals(
            listOf("Circ.", "H&C"),
            platform1.departures.map { it.lineShort },
            "resolved here so every surface names a line identically",
        )
        assertFalse(
            groups.first { it.key == "Platform 2" }.mixesLines,
            "one line — the header already said which, so a prefix would repeat it",
        )
    }

    @Test
    fun `bus groups never carry a line prefix`() {
        // The backend already appends the route to the destination
        // ("39 Putney Bridge"), so a prefix would print it twice.
        val groups = MultiLineBoardProcessor.buildGroups(
            feeds = listOf(
                feed("39", stationId = "A", predictions = listOf(pred("39 Putney", 3, platform = "Stop W"))),
                feed("85", stationId = "A", predictions = listOf(pred("85 Kingston", 5, platform = "Stop W"))),
            ),
            isBus = true,
        )
        val stop = groups.single()
        assertFalse(stop.mixesLines, "two routes at one pole, still no prefix")
        assertTrue(stop.departures.all { it.lineShort.isEmpty() })
    }

    @Test
    fun `buildRows and buildGroups agree on order and depth`() {
        // buildRows is now a flattening of buildGroups, and this is what keeps
        // it honest: the two must never disagree about which departures the
        // board shows or what order the blocks are in.
        val feeds = listOf(
            feed("victoria", predictions = listOf(
                pred("Brixton", 1, platform = "Platform 2"),
                pred("Brixton", 9, platform = "Platform 2"),
                pred("Brixton", 12, platform = "Platform 2"),
                pred("Brixton", 15, platform = "Platform 2"),
            )),
            feed("victoria", predictions = listOf(pred("Walthamstow", 4, platform = "Platform 1"))),
        )
        val prefs = BoardConfig(rowsPerPlatform = 2)
        val groups = MultiLineBoardProcessor.buildGroups(feeds, isBus = false, prefs = prefs)
        val rows = MultiLineBoardProcessor.buildRows(feeds, isBus = false, prefs = prefs)

        assertEquals(
            groups.map { it.header },
            headers(rows),
            "same blocks, same order",
        )
        assertEquals(
            groups.flatMap { g -> g.departures.map { it.prediction.destination } },
            realDepartures(rows).map { it.destination },
            "same departures, same order, same cap",
        )
    }

    // ── The rowCap override: reserves for a consumer that re-derives labels ──

    @Test
    fun `rowCap override sets the depth, not the user's rowsPerPlatform`() {
        // The iOS widget asks for RESERVES: it re-derives every ETA label per
        // minute for an hour from one payload, so trains behind the visible
        // ones are what it shifts into view as the front of the queue departs.
        // Capping its payload at the display depth leaves it nothing to shift,
        // and the board empties itself until the next push.
        //
        // This shipped ignoring the parameter entirely — it took `prefs.rowCap`
        // in the body — so the widget silently got 3 rows however many it asked
        // for. It is the kind of bug an override argument invites, because the
        // call site reads correctly and only the body is wrong.
        val ten = (1..10).map { pred("Morden", it) }
        val groups = MultiLineBoardProcessor.buildGroups(
            feeds = listOf(feed("northern", predictions = ten)),
            isBus = false,
            prefs = BoardConfig(rowsPerPlatform = 3),
            rowCap = 8,
        )
        assertEquals(8, groups.single().departures.size)
    }

    @Test
    fun `rowCap override leaves the pin to the user`() {
        // The override is a DEPTH, and must not turn into "ignore prefs". A
        // widget still shows the station arranged the way its owner arranged it
        // on the home screen — only deeper.
        val feeds = listOf(
            feed("victoria", predictions = listOf(pred("Brixton", 1, platform = "Platform 9"))),
            feed("victoria", predictions = listOf(pred("Walthamstow", 5, platform = "Platform 2"))),
        )
        val pinned = MultiLineBoardProcessor.buildGroups(
            feeds = feeds,
            isBus = false,
            prefs = BoardConfig(pin = BoardPin(BoardPin.Kind.PLATFORM, "Platform 2")),
            rowCap = 8,
        )
        assertEquals(
            listOf("Platform 2", "Platform 9"),
            pinned.map { it.key },
            "the pinned block leads, even though Platform 9's train is sooner",
        )
    }

    @Test
    fun `two bus poles at one hub stay two blocks in the widget's payload`() {
        // The reported widget bug, at the depth the widget actually asks for.
        // Smithwood Close tracked both ways is two naptans and every prediction
        // carries platform="" — grouping by platform collapses them into one
        // block with both directions interleaved, which is what the widget did
        // while it re-derived its own grouping.
        val groups = MultiLineBoardProcessor.buildGroups(
            feeds = listOf(
                feed("39", stationId = "490008805N", direction = "inbound", predictions = listOf(
                    pred("39 Putney Bridge", 2, platform = ""),
                    pred("39 Putney Bridge", 9, platform = ""),
                )),
                feed("39", stationId = "490012211N", direction = "outbound", predictions = listOf(
                    pred("39 Clapham Junction", 4, platform = ""),
                )),
            ),
            isBus = true,
            rowCap = 8,
        )
        assertEquals(
            listOf("490008805N", "490012211N"),
            groups.map { it.key }.sorted(),
            "one block per pole — these are opposite sides of the road",
        )
        // Each pole keeps only its own trains: a passenger standing at one of
        // them cannot catch anything from the other.
        assertEquals(
            listOf("39 Putney Bridge", "39 Putney Bridge"),
            groups.first { it.key == "490008805N" }.departures.map { it.prediction.destination },
        )
    }
}
