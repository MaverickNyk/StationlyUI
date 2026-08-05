package util

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.util.MultiLineBoardProcessor
import com.stationly.core.util.MultiLineBoardProcessor.Row
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // ── Empty ──

    @Test
    fun `returns no rows at all when every line is empty`() {
        val rows = MultiLineBoardProcessor.buildRows(
            feeds = listOf(feed("northern", predictions = emptyList())),
            isBus = false,
        )
        assertTrue(rows.isEmpty(), "the caller substitutes the board-wide fallback copy")
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
    fun `collapsed legs are capped at two`() {
        val legs = MultiLineBoardProcessor.collapsedLegs(
            feeds = listOf(
                feed("victoria", predictions = listOf(pred("Brixton", 1, platform = "Platform 1"))),
                feed("northern", predictions = listOf(pred("Morden", 2, platform = "Platform 2"))),
                feed("central", predictions = listOf(pred("Epping", 3, platform = "Platform 3"))),
            ),
            isBus = false,
        )
        assertEquals(MultiLineBoardProcessor.MAX_COLLAPSED_LEGS, legs.size)
        assertEquals(listOf("1 min", "2 min"), legs.map { it.eta })
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
}
