package util

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.util.StationlyFormatters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Board identity, and the two different questions you can ask about a departure's
 * time. Both are places where a plausible-looking shortcut is wrong.
 */
class BoardIdentityAndEtaTest {

    private fun selection(
        line: String = "circle",
        station: String = "940GZZLUKSX",
        direction: String = "Eastbound",
        parent: String = "",
    ) = UserSelection(
        mode = "tube",
        line = line,
        station = station,
        parentStationId = parent,
        stationName = "King's Cross",
        direction = direction,
        destinations = emptyList(),
        destinationIds = emptyList(),
    )

    // ── Identity ──

    @Test
    fun `board key separates two lines at one station`() {
        // Keyed on station alone, the second board written would silently erase
        // the first's departures — correct only while the product was one line.
        val circle = selection(line = "circle")
        val district = selection(line = "district")
        assertTrue(circle.boardKey != district.boardKey)
    }

    @Test
    fun `board key separates two directions on one line`() {
        assertTrue(selection(direction = "Eastbound").boardKey != selection(direction = "Westbound").boardKey)
    }

    @Test
    fun `grouping id is the hub — so several bus poles render as one card`() {
        // Every bus pole has its own naptan; the hub is what the user picked.
        val inbound = selection(line = "39", station = "490008805N", parent = "HUBSMT")
        val outbound = selection(line = "39", station = "490012211N", parent = "HUBSMT")
        assertEquals(inbound.groupingId, outbound.groupingId, "one stop is one card")
        assertTrue(inbound.boardKey != outbound.boardKey, "but two poles are two boards")
    }

    @Test
    fun `grouping id falls back to the station when there is no hub`() {
        assertEquals("940GZZLUKSX", selection().groupingId)
    }

    // ── The two time questions ──

    private fun pred(eta: String, isDue: Boolean = false, target: Long? = null) =
        PredictionDisplay(
            destination = "Morden", platform = "Platform 1",
            eta = eta, isDue = isDue, targetEpochMs = target,
        )

    @Test
    fun `sort key uses the absolute time — never the rounded label`() {
        // The label is rounded AND deliberately bumped so two same-platform
        // trains never read the same, so it does not round-trip to a number.
        val bumped = pred(eta = "3 min", target = 90_000L)
        assertEquals(90_000L, StationlyFormatters.arrivalSortKey(bumped))
    }

    @Test
    fun `sort key falls back to the label only when there is no timestamp`() {
        assertEquals(0L, StationlyFormatters.arrivalSortKey(pred("Due", isDue = true)))
        assertEquals(5 * 60_000L, StationlyFormatters.arrivalSortKey(pred("5 min")))
    }

    @Test
    fun `an unparseable eta sorts to the end — not the front`() {
        val key = StationlyFormatters.arrivalSortKey(pred("¯\\_(ツ)_/¯"))
        assertTrue(key >= 999 * 60_000L, "junk must not masquerade as the next train")
    }

    @Test
    fun `displayed minutes reads the label — so the hero cannot contradict the row`() {
        // The opposite of arrivalSortKey, on purpose: recomputing from the
        // timestamp here would undo the per-platform bump that the row below is
        // already showing.
        val bumped = pred(eta = "3 min", target = 90_000L)
        assertEquals(3, StationlyFormatters.displayedMinutes(bumped))
    }

    @Test
    fun `displayed minutes treats Due as zero however it is flagged`() {
        assertEquals(0, StationlyFormatters.displayedMinutes(pred("Due", isDue = true)))
        assertEquals(0, StationlyFormatters.displayedMinutes(pred("Due")))
        assertEquals(0, StationlyFormatters.displayedMinutes(pred("due")))
    }

    @Test
    fun `sorting orders by real arrival even when labels disagree`() {
        val sorted = StationlyFormatters.sortPredictions(listOf(
            pred(eta = "3 min", target = 180_000L),
            pred(eta = "3 min", target = 90_000L),
        ))
        assertEquals(90_000L, sorted.first().targetEpochMs)
    }
}
