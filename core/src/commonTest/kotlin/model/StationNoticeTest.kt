package com.stationly.core.model

import com.stationly.core.config.FeatureFlags
import com.stationly.core.repository.StationNoticeStore
import com.stationly.core.util.asFallback
import com.stationly.core.util.resolveBoardFallbackCopy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When a station notice touches a board, and that it can never do so wrongly. */
class StationNoticeTest {

    private val now = 1_000_000L

    private fun board(line: String = "piccadilly", direction: String = "inbound", name: String = "") = UserSelection(
        mode = "tube", line = line, station = "940GZZLUASL", stationName = "Arsenal", direction = direction,
        destinations = emptyList(), destinationIds = emptyList(), directionName = name,
    )

    private fun notice(
        kind: String, from: Long = 0, to: Long = 9_000_000, expiresAt: Long = 8_000_000, title: String = "Station closed",
        strip: String = "Station closed", lines: List<String> = emptyList(), directions: List<String> = emptyList(), certain: Boolean = true,
    ) = StationNotice("940GZZLUASL", kind, title, listOf("Due to a fire alert"), strip, "why", directions, lines, certain, from, to, expiresAt)

    @AfterTest
    fun reset() = FeatureFlags.resetForTest()

    @Test
    fun `closed replaces the departures until it ends or expires`() {
        val n = notice("closed")
        assertTrue(n.forBoard(board(), now)!!.hides)
        assertNull(n.forBoard(board(), 8_000_000), "expired")
        assertNull(n.copy(to = now).forBoard(board(), now), "over")
        assertNull(n.copy(from = now + 1).forBoard(board(), now), "not yet")
        assertNull(n.copy(expiresAt = 0).forBoard(board(), now), "no expiry is never believed")
    }

    @Test
    fun `closing keeps the departures until the time and then closes without a push`() {
        val n = notice("closing", from = now + 60_000, strip = "Closes 23:35")
        val before = assertNotNull(n.forBoard(board(), now))
        assertFalse(before.hides)
        assertEquals("Closes 23:35", before.strip)
        val after = assertNotNull(n.forBoard(board(), now + 60_000))
        assertTrue(after.hides)
        assertEquals("Station closed", after.strip)
    }

    @Test
    fun `exit only never hides departures`() {
        assertFalse(notice("exit_only", title = "", strip = "Exit only").forBoard(board(), now)!!.hides)
    }

    @Test
    fun `one line closed touches only that line`() {
        val n = notice("part_closed", lines = listOf("Piccadilly"))
        assertTrue(n.forBoard(board("piccadilly"), now)!!.hides)
        assertNull(n.forBoard(board("district"), now))
        assertNotNull(notice("part_closed", lines = listOf("Hammersmith & City")).forBoard(board("hammersmith-city"), now))
        assertNotNull(notice("part_closed", lines = listOf("Hammersmith and City")).forBoard(board("hammersmith-city"), now))
    }

    @Test
    fun `one direction closed touches only a board that certainly faces that way`() {
        val n = notice("part_closed", directions = listOf("westbound"))
        assertTrue(n.forBoard(board(direction = "westbound"), now)!!.hides)
        assertTrue(n.forBoard(board(), now, listOf("Platform 2 (Westbound)", "Platform 2 (Westbound)"))!!.hides)
        // Seen at Arsenal: the map says southbound, the platform says westbound.
        assertTrue(n.forBoard(board(name = "Southbound"), now, listOf("Platform 2 (Westbound)"))!!.hides)
        assertNull(n.forBoard(board(name = "Westbound"), now, listOf("Platform 1 (Eastbound)")))
        assertNull(n.forBoard(board(direction = "eastbound"), now))
        assertNull(n.forBoard(board(), now, listOf("Platform 1 (Eastbound)")))
        for (unsure in listOf(listOf("Platform 2 (Westbound)", "Platform 1 (Eastbound)"), listOf("Platform 2 (Westbound)", "Platform 3"), emptyList())) {
            assertFalse(n.forBoard(board(), now, unsure)!!.hides, "$unsure: said, not hidden")
        }
    }

    @Test
    fun `a direction TfL does not tie to a line is said and not hidden`() {
        val n = notice("part_closed", directions = listOf("westbound"), certain = false)
        assertFalse(n.forBoard(board(), now, listOf("Platform 2 (Westbound)"))!!.hides)
        assertNull(n.forBoard(board(), now, listOf("Platform 1 (Eastbound)")))
    }

    @Test
    fun `what this app does not understand changes nothing`() {
        for (kind in listOf("", "none", "unclear", "flooded")) assertNull(notice(kind).forBoard(board(), now), kind)
        assertNull(notice("part_closed").forBoard(board(), now))
        assertNull(notice("closed", title = "").forBoard(board(), now), "nothing to put in place of the departures")
        assertNull(notice("closed", strip = "").forBoard(board(), now))
    }

    @Test
    fun `off it answers nothing whatever is stored`() {
        val stored = mapOf("940GZZLUASL" to notice("closed"))
        assertNull(StationNoticeStore.forBoard(stored, board(), now))
        for (off in listOf("false", "", "1", "TRUE")) {
            FeatureFlags.refresh(mapOf("flag.stationStatus.show" to off))
            assertNull(StationNoticeStore.forBoard(stored, board(), now), off)
        }
        FeatureFlags.refresh(mapOf("flag.stationStatus.show" to "true"))
        assertNotNull(StationNoticeStore.forBoard(stored, board(), now))
        FeatureFlags.refresh(mapOf("other" to "true"))
        assertNull(StationNoticeStore.forBoard(stored, board(), now), "a config without it is off")
        FeatureFlags.refresh(mapOf("flag.stationStatus.show" to "true"))
        assertNull(StationNoticeStore.forBoard(stored, board().copy(station = "other"), now))
    }

    @Test
    fun `a strip with room for one thing says both unless no board is left open`() {
        val exit = notice("exit_only", title = "", strip = "Exit only").forBoard(board(), now)!!
        assertEquals("Exit only" to "why", exit.stripWith("Good Service" to "Please move down"))
        assertEquals("Exit only" to "why", exit.stripWith(null))
        assertEquals("Exit only" to "why Severe Delays: Signal failure.", exit.stripWith("Severe Delays" to "Signal failure."))
        // One line closed, another open and delayed.
        val line = notice("part_closed", lines = listOf("Piccadilly"), strip = "Piccadilly closed here").forBoard(board(), now)!!
        assertEquals("Piccadilly closed here" to "why District Severe Delays: x", line.stripWith("District Severe Delays" to "x"))
    }

    @Test
    fun `its words replace the fallback's own`() {
        val copy = resolveBoardFallbackCopy(notice("closed").forBoard(board(), now)!!.asFallback(), mapOf("board.fallback.disrupted.detail" to "x"))
        assertEquals("Station closed", copy.title)
        assertEquals(listOf("Due to a fire alert"), copy.detailLines)
    }

    private fun says(reason: String, kind: String = "closed") =
        notice(kind, title = "Bus stop closed").copy(reason = reason, directions = emptyList(), lines = emptyList()).forBoard(board(), now)!!.says

    @Test
    fun `its own words without what the title says`() {
        assertEquals(listOf("Until 18:00 Monday 30 Nov 26", "Please use the next or previous stop to catch your bus"),
            says("Bus Stop Closed Until 18:00 Monday 30 Nov 26 Please use the next or previous stop to catch your bus"))
        assertEquals(listOf("Due to cycle lane works until 12 February 2027", "Please use next or previous stop"),
            says("This bus stop is closed due to cycle lane works until 12 February 2027. Please use next or previous stop"))
        assertEquals(listOf("Until 17:00 on Sunday 06 December due to gas works"), says("Bus stop closed until 17:00 on Sunday 06 December due to gas works. tfl.gov.uk."))
        assertEquals(listOf("While we respond to a power failure"), says("This station is closed while we respond to a power failure. Call us on 0343 222 1234."))
        assertEquals(emptyList(), says("Bus Stop Closed"))
        assertEquals(listOf("Due to a power failure", "Please use King's Cross St. Pancras"), says("This station is closed due to a power failure. Please use King's Cross St. Pancras."))
        // The last trains are no use once it has closed.
        assertEquals(emptyList(), says("Last trains will be at 2248 to Cheshunt.", kind = "closing"))
    }

    @Test
    fun `its own words only if all of them fit`() {
        val fits = { s: String -> s.length <= 30 }
        assertEquals(listOf("Until 18:00 Monday 30 Nov 26", "Please use the next", "or previous stop"),
            rowsThatFit(listOf("Until 18:00 Monday 30 Nov 26", "Please use the next or previous stop"), 3, fits = { it.length <= 28 }))
        assertEquals(null, rowsThatFit(listOf("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen"), 3, fits))
        assertEquals(null, rowsThatFit(listOf("Anextremelylongwordthatcannotfitanywhere"), 3, fits))
        assertEquals(null, rowsThatFit(emptyList(), 3, fits))
    }

    @Test
    fun `the short words wrap too where the long ones do not fit`() {
        val n = notice("closed", title = "Bus stop closed").copy(
            reason = "Bus Stop Closed Until 18:00 Monday 30 Nov 26 Please use the next or previous stop to catch your bus",
            details = listOf("Use the next or previous stop"),
        ).forBoard(board(), now)!!
        assertEquals(listOf("Until 18:00 Monday 30 Nov 26", "Please use the next or previous", "stop to catch your bus"), n.rows(3) { it.length <= 40 })
        assertEquals(listOf("Use the next or", "previous stop"), n.rows(3) { it.length <= 22 })
        assertEquals(n.details, n.rows(3) { it.length <= 5 })
    }
}
