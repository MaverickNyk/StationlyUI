package config

import com.stationly.core.config.BoardQuota
import com.stationly.core.model.UserSelection
import com.stationly.core.model.user.Board
import com.stationly.core.model.user.BoardSelection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The station limit on the paths that bring boards in wholesale (2026-11-26):
 * the first launch after a v1 upgrade, and a sign-in. On the Pixel, the v1 board
 * plus four from the account made five against a limit of four.
 */
class BoardQuotaFitTest {

    private val four = 4

    private fun board(id: String, stop: String = id) =
        Board(id = id, name = id, selections = listOf(BoardSelection(stop, "victoria", "tube", "southbound")))

    private fun row(stop: String, hub: String = "") = UserSelection(
        mode = "tube", line = "victoria", station = stop, parentStationId = hub, stationName = stop,
        direction = "southbound", destinations = emptyList(), destinationIds = emptyList(),
    )

    @Test
    fun `a v1 board and four from the account keep the v1 board and the first three`() {
        val fit = BoardQuota.fit(listOf(row("V1")), listOf(board("A"), board("B"), board("C"), board("D")), four)
        assertEquals(listOf("A", "B", "C"), fit.kept.map { it.id })
        assertEquals(listOf("D"), fit.left.map { it.id })
    }

    @Test
    fun `a board the phone already has is not a new slot, matched by stop when the v1 row has no hub`() {
        // The account knows the bus stop by its hub, the v1 row only by its pole.
        val hub = Board(id = "HUB", name = "Stop", selections = listOf(BoardSelection("POLE", "73", "bus", "inbound")))
        val fit = BoardQuota.fit(listOf(row("POLE")), listOf(hub, board("A"), board("B"), board("C")), four)
        assertEquals(listOf("HUB", "A", "B", "C"), fit.kept.map { it.id })
        assertEquals(emptyList(), fit.left)
    }

    @Test
    fun `a sign-in takes the first four in the account's order`() {
        val fit = BoardQuota.fit(emptyList(), (1..6).map { board("S$it") }, four)
        assertEquals(listOf("S1", "S2", "S3", "S4"), fit.kept.map { it.id })
        assertEquals(listOf("S5", "S6"), fit.left.map { it.id })
    }

    @Test
    fun `under the limit nothing is left out`() {
        val fit = BoardQuota.fit(listOf(row("V1")), listOf(board("A"), board("B")), four)
        assertEquals(listOf("A", "B"), fit.kept.map { it.id })
        assertEquals(emptyList(), fit.left)
    }

    @Test
    fun `a phone already at the limit takes nothing new but keeps what it shares`() {
        val local = listOf(row("A"), row("B"), row("C"), row("D"))
        val fit = BoardQuota.fit(local, listOf(board("B"), board("E")), four)
        assertEquals(listOf("B"), fit.kept.map { it.id })
        assertEquals(listOf("E"), fit.left.map { it.id })
    }

    @Test
    fun `a v1 bus stop tracked both ways is one station, not two`() {
        // v1 rows carry no hub: the two directions are two poles.
        val hub = Board(id = "HUB", name = "Stop", selections = listOf(
            BoardSelection("N", "39", "bus", "inbound"), BoardSelection("S", "39", "bus", "outbound")))
        val fit = BoardQuota.fit(listOf(row("N"), row("S")), listOf(hub, board("A"), board("B"), board("C")), four)
        assertEquals(listOf("HUB", "A", "B", "C"), fit.kept.map { it.id })
        assertEquals(emptyList(), fit.left)
    }
}
