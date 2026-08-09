package util

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.util.BoardDisplayPrefs
import com.stationly.core.util.BoardTicker
import com.stationly.core.util.MultiLineBoardProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a board says as the clock runs on without new data.
 *
 * The whole feature is that these labels are DERIVED, not delivered: the backend
 * sends an absolute arrival time and every surface turns it into "2 min" on its
 * own clock. So the interesting cases are all about time passing with the
 * payload standing still — which is exactly what a test can hold and a device
 * cannot.
 */
class BoardTickerTest {

    private val t0 = 1_700_000_000_000L   // arbitrary fixed "now"
    private val minute = 60_000L

    /** A departure [minutes] out from [t0], on [platform]. */
    private fun departure(
        name: String,
        minutes: Long,
        platform: String = "Platform 1",
    ) = PredictionDisplay(
        destination = name,
        platform = platform,
        // Deliberately WRONG at ingest: it is what the label said when the
        // payload landed, and every assertion below is about the board refusing
        // to blit it. A row that still reads its stored eta would pass a test
        // that only ever checked t0.
        eta = "$minutes min",
        isDue = false,
        targetEpochMs = t0 + minutes * minute,
    )

    private fun feed(vararg predictions: PredictionDisplay) = listOf(
        MultiLineBoardProcessor.Feed(
            stationId = "940GZZLUKSX",
            line = "circle",
            direction = "Eastbound",
            predictions = predictions.toList(),
        )
    )

    /** The board's blocks at [nowMs] — reserves included, as the hero sees them. */
    private fun blocksAt(
        feeds: List<MultiLineBoardProcessor.Feed>,
        nowMs: Long,
        rows: Int = 3,
        payloadWrittenAt: Long = t0,
        isBus: Boolean = false,
    ): List<MultiLineBoardProcessor.Group> {
        val prefs = BoardDisplayPrefs(rowsPerPlatform = rows)
        return BoardTicker.tick(
            groups = MultiLineBoardProcessor.buildGroups(
                feeds, isBus, prefs,
                rowCap = MultiLineBoardProcessor.ROW_RESERVE,
                nowMs = nowMs,
            ),
            nowMs = nowMs,
            payloadAgeMs = nowMs - payloadWrittenAt,
            displayRows = prefs.rowCap,
        )
    }

    /**
     * What the board actually DRAWS at [nowMs], as "destination → label" pairs.
     *
     * Goes through [MultiLineBoardProcessor.rowsFrom] rather than reading the
     * blocks directly, because the display cap lives there — asserting on the
     * blocks would test a board deeper than the one on screen. Padding rows are
     * dropped; [`a short board is padded to the floor`] covers those.
     */
    private fun boardAt(
        feeds: List<MultiLineBoardProcessor.Feed>,
        nowMs: Long,
        rows: Int = 3,
        payloadWrittenAt: Long = t0,
        isBus: Boolean = false,
    ): List<Pair<String, String>> =
        MultiLineBoardProcessor
            .rowsFrom(blocksAt(feeds, nowMs, rows, payloadWrittenAt, isBus), rowCap = rows)
            .filterIsInstance<MultiLineBoardProcessor.Row.Departure>()
            .filter { it.destination.isNotBlank() }
            .map { it.destination to it.eta }

    // ── The walkthrough: five departures, a three-row board, no new data ──
    //
    // One payload, six minutes of wall clock, nothing refetched. This is the
    // behaviour the whole pipeline exists to produce, and every line of it used
    // to be wrong on the home screen in one of two ways: the reserves were
    // trimmed to three at the ViewModel so Dep 4 and Dep 5 never arrived, and
    // there was no retention so the board padded itself with blanks instead of
    // saying "Gone".

    private val fiveDepartures = feed(
        departure("Dep 1", 1),
        departure("Dep 2", 2),
        departure("Dep 3", 3),
        departure("Dep 4", 4),
        departure("Dep 5", 5),
    )

    /**
     * A moment just after the [n]th departure has cleared the grace period.
     *
     * Every step below is read here, and the offset is why the top row says
     * "Due" rather than "1 min": a train leaves the board 30s AFTER its target,
     * by which point the next one is 30s out — and under a minute is "Due".
     * A row therefore reads "Due" for the last 90 seconds of its life, which is
     * the TfL rule and what the platform indicators show.
     */
    private fun justAfterDeparture(n: Int) = t0 + n * minute + 31_000L

    @Test
    fun `minute 0 - the three soonest, and the reserves wait behind them`() {
        assertEquals(
            listOf("Dep 1" to "1 min", "Dep 2" to "2 min", "Dep 3" to "3 min"),
            boardAt(fiveDepartures, t0),
        )
    }

    @Test
    fun `minute 1 - the queue shifts up and Dep 4 arrives from the reserves`() {
        // Dep 1 is past its target by the grace period, so it is gone — and the
        // row it vacated is filled by a train that was never on screen. Nothing
        // was refetched to make this happen.
        assertEquals(
            listOf("Dep 2" to "Due", "Dep 3" to "1 min", "Dep 4" to "2 min"),
            boardAt(fiveDepartures, justAfterDeparture(1)),
        )
    }

    @Test
    fun `minute 2 - Dep 5 arrives, the last of the reserves`() {
        assertEquals(
            listOf("Dep 3" to "Due", "Dep 4" to "1 min", "Dep 5" to "2 min"),
            boardAt(fiveDepartures, justAfterDeparture(2)),
        )
    }

    @Test
    fun `minute 3 - reserves exhausted, so the block holds its last departure`() {
        // Two live trains and three slots. The shortfall is filled by the most
        // recent departure, labelled and dimmed rather than counted down —
        // "Gone" is a state, not a duration.
        assertEquals(
            listOf("Dep 3" to "Gone", "Dep 4" to "Due", "Dep 5" to "1 min"),
            boardAt(fiveDepartures, justAfterDeparture(3)),
        )
    }

    @Test
    fun `minute 4 - two held, one live`() {
        assertEquals(
            listOf("Dep 3" to "Gone", "Dep 4" to "Gone", "Dep 5" to "Due"),
            boardAt(fiveDepartures, justAfterDeparture(4)),
        )
    }

    @Test
    fun `minute 5 - everything has gone, and the board says so rather than emptying`() {
        assertEquals(
            listOf("Dep 3" to "Gone", "Dep 4" to "Gone", "Dep 5" to "Gone"),
            boardAt(fiveDepartures, justAfterDeparture(5)),
        )
    }

    // ── Retention is gated on the payload's age ──

    @Test
    fun `a fresh payload never resurrects a train that has already left`() {
        // TfL legitimately reports departures that have just gone, and SQL holds
        // the previous fetch's rows, so a payload can arrive containing them.
        // Backfilling unconditionally would label the newest data the app has as
        // "Gone" seconds after a successful refresh — the reported bug this gate
        // exists for. Two live trains on a three-row board is the truth.
        val justWritten = justAfterDeparture(3)
        assertEquals(
            listOf("Dep 4" to "Due", "Dep 5" to "1 min"),
            boardAt(fiveDepartures, justWritten, payloadWrittenAt = justWritten),
        )
    }

    @Test
    fun `retention starts exactly at the freshness threshold`() {
        val now = justAfterDeparture(3)
        val onTheEdge = now - BoardTicker.RETENTION_MIN_AGE_MS
        assertTrue(
            boardAt(fiveDepartures, now, payloadWrittenAt = onTheEdge).any { it.second == "Gone" },
            "a payload exactly RETENTION_MIN_AGE_MS old is old enough to hold its departures",
        )
        assertTrue(
            boardAt(fiveDepartures, now, payloadWrittenAt = onTheEdge + 1).none { it.second == "Gone" },
            "a millisecond fresher and the board is short rather than held",
        )
    }

    // ── "Due", and the rule that two trains in one block never read the same ──

    @Test
    fun `under a minute reads Due, and the grace keeps it there past its target`() {
        val single = feed(departure("Cockfosters", 1))
        assertEquals(listOf("Cockfosters" to "Due"), boardAt(single, t0 + 30_000L))
        // 20s AFTER the train was due. It is standing at the platform with its
        // doors open, which is what the physical indicator shows too.
        assertEquals(listOf("Cockfosters" to "Due"), boardAt(single, t0 + minute + 20_000L))
    }

    @Test
    fun `isDue means the row reads Due — however many trains share the block`() {
        // These were two different rules in one function: a LONE row was flagged
        // on `secs < 30` while a bumped one was flagged on `minutes == 0`. So a
        // single train 45s out was labelled "Due" and flagged isDue = false —
        // and the widget tints from the flag, so it rendered in ordinary amber
        // where the identical train on a busier platform rendered red.
        fun flagsAt(vararg secondsOut: Long): List<Pair<String, Boolean>> {
            val rows = secondsOut.mapIndexed { i, s ->
                departure("Dep $i", 0).copy(targetEpochMs = t0 + s * 1000)
            }
            return blocksAt(feed(*rows.toTypedArray()), t0)
                .single().departures.map { it.prediction.eta to it.prediction.isDue }
        }

        assertEquals(listOf("Due" to true), flagsAt(45), "one train, 45s out")
        // The same 45s train with a neighbour — it must be flagged identically.
        assertEquals("Due" to true, flagsAt(45, 200).first(), "same train, busier block")
        assertEquals(listOf("1 min" to false), flagsAt(90), "not due, and says so")
    }

    @Test
    fun `two trains at one platform cannot share a label`() {
        // Both round to "Due"; the later one is bumped so the rows stay
        // distinguishable. This is why nothing may read a label back as a
        // number — see StationlyFormatters.arrivalSortKey.
        val board = boardAt(
            feed(
                departure("Ealing Broadway", 0).copy(targetEpochMs = t0 + 10_000L),
                departure("Richmond", 0).copy(targetEpochMs = t0 + 40_000L),
            ),
            t0,
        )
        assertEquals(listOf("Ealing Broadway" to "Due", "Richmond" to "1 min"), board)
    }

    @Test
    fun `two poles at one bus hub are separate queues and are bumped separately`() {
        // The bug this closes: the home board bumped by `platform`, and TfL
        // letters stops only at multi-stop interchanges — so at an ordinary hub
        // every pole reports a blank platform and two of them were bumped as one
        // queue. The widget bumps per block and read "Due, Due"; the home board
        // read "Due, 1 min" for the same two buses.
        val poles = listOf(
            MultiLineBoardProcessor.Feed(
                stationId = "490008805N", line = "39", direction = "Inbound",
                predictions = listOf(
                    departure("Putney Bridge", 0, platform = "").copy(targetEpochMs = t0 + 10_000L)
                ),
            ),
            MultiLineBoardProcessor.Feed(
                stationId = "490012211N", line = "39", direction = "Outbound",
                predictions = listOf(
                    departure("Clapham Junction", 0, platform = "").copy(targetEpochMs = t0 + 20_000L)
                ),
            ),
        )
        val board = boardAt(poles, t0, isBus = true)
        assertEquals(2, board.size)
        assertTrue(board.all { it.second == "Due" }, "different poles, so no bump between them: $board")
    }

    // ── Ordering ──

    @Test
    fun `a platform whose trains have all left never leads the board`() {
        val feeds = listOf(
            MultiLineBoardProcessor.Feed(
                stationId = "940GZZLUKSX", line = "circle", direction = "Eastbound",
                predictions = listOf(departure("Aldgate", 1, platform = "Platform 1")),
            ),
            MultiLineBoardProcessor.Feed(
                stationId = "940GZZLUKSX", line = "district", direction = "Eastbound",
                predictions = listOf(departure("Upminster", 8, platform = "Platform 2")),
            ),
        )
        // Five minutes on: Platform 1's only train left long ago, Platform 2's
        // is still to come. Ordered on raw arrival time the departed one would
        // sort first — it has the smaller timestamp — and the board would lead
        // with a block nobody can act on.
        val now = t0 + 5 * minute
        val groups = BoardTicker.tick(
            groups = MultiLineBoardProcessor.buildGroups(
                feeds, isBus = false, prefs = BoardDisplayPrefs(),
                rowCap = MultiLineBoardProcessor.ROW_RESERVE, nowMs = now,
            ),
            nowMs = now, payloadAgeMs = now - t0, displayRows = 3,
        )
        assertEquals("Platform 2", groups.first().label)
    }

    // ── The display cap is the user's, and it is applied AFTER the shed ──

    @Test
    fun `the row setting bounds what is shown without bounding what is held`() {
        // Two rows on screen, but the block still knows about Dep 3 onwards —
        // capping before the shed is what left the old board with nothing to
        // shift up.
        assertEquals(
            listOf("Dep 1" to "1 min", "Dep 2" to "2 min"),
            boardAt(fiveDepartures, t0, rows = 2),
        )
        assertEquals(
            listOf("Dep 3" to "Due", "Dep 4" to "1 min"),
            boardAt(fiveDepartures, justAfterDeparture(2), rows = 2),
        )
    }

    @Test
    fun `the hero can still see a train the board has no room to draw`() {
        // The depth setting arranges the BOARD and is deliberately not allowed
        // to re-point the hero — one setting doing two jobs. So the ticked
        // blocks keep their reserves and only the renderer trims, which is why
        // the cap lives in rowsFrom rather than in tick.
        val shallow = blocksAt(fiveDepartures, t0, rows = 2)
        val labels = shallow.single().departures.map { it.prediction.destination to it.prediction.eta }

        assertEquals(listOf("Dep 1" to "1 min", "Dep 2" to "2 min"), labels.take(2))
        assertTrue(labels.size > 2, "reserves survive the tick: $labels")
        // And the labels the board draws are byte-identical to the block's
        // first rows — the bump runs before any trimming, so where the cap
        // lands cannot change what a row says.
        assertEquals(labels.take(2), boardAt(fiveDepartures, t0, rows = 2))
    }

    @Test
    fun `a short board is padded to the floor so the panel does not collapse`() {
        val rows = MultiLineBoardProcessor.rowsFrom(
            blocksAt(feed(departure("Aldgate", 2)), t0), rowCap = 3,
        ).filterIsInstance<MultiLineBoardProcessor.Row.Departure>()
        assertEquals(MultiLineBoardProcessor.MIN_BOARD_ROWS, rows.size)
        assertEquals(1, rows.count { it.destination.isNotBlank() })
    }

    @Test
    fun `a row with no parseable arrival time is never dropped and never relabelled`() {
        val undated = PredictionDisplay(
            destination = "Unknown", platform = "Platform 1", eta = "3 min",
            isDue = false, targetEpochMs = null,
        )
        val board = boardAt(feed(departure("Aldgate", 1), undated), t0 + 10 * minute)
        assertTrue(board.contains("Unknown" to "3 min"), "kept verbatim: $board")
    }

    // ── The rows the renderer draws ──

    @Test
    fun `retained rows are flagged for the renderer rather than matched on text`() {
        val rows = MultiLineBoardProcessor
            .rowsFrom(blocksAt(fiveDepartures, justAfterDeparture(5)), rowCap = 3)
            .filterIsInstance<MultiLineBoardProcessor.Row.Departure>()

        assertEquals(3, rows.size)
        assertTrue(rows.all { it.departed }, "every held row says so: $rows")
    }

    @Test
    fun `a board with no rows at all is left for the caller to describe`() {
        // Not padded to MIN_BOARD_ROWS: three blank rows look like a broken
        // board, and the fallback copy ("Service ended for tonight") is the
        // honest answer.
        assertTrue(
            MultiLineBoardProcessor.rowsFrom(emptyList()).isEmpty()
        )
    }
}
