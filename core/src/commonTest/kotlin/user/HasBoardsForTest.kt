package user

import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.UserProfileResponse
import com.stationly.core.model.user.Board
import com.stationly.core.model.user.BoardSelection
import com.stationly.core.model.user.hasBoardsFor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a phone that came from v1 may stop protecting the boards it brought. */
class HasBoardsForTest {

    private fun row(station: String, line: String = "victoria", direction: String = "southbound") = UserSelection(
        mode = "tube", line = line, station = station, stationName = station, direction = direction,
        destinations = emptyList(), destinationIds = emptyList(),
    )

    private fun profile(updatedAt: Long, vararg stations: String) = UserProfileResponse(
        uid = "u1", email = "a@b.c", displayName = "A", stations = emptyList(), boardsUpdatedAt = updatedAt,
        boards = stations.map {
            Board(id = it, name = it, selections = listOf(BoardSelection(it, "victoria", "tube", "southbound")))
        },
    )

    @Test
    fun `an iPhones list without the v1 board does not have it`() {
        assertFalse(profile(5, "IPHONE").hasBoardsFor(listOf(row("V1"))))
    }

    @Test
    fun `the union does`() {
        assertTrue(profile(5, "IPHONE", "V1").hasBoardsFor(listOf(row("V1"))))
    }

    @Test
    fun `a list derived from stations was never saved`() {
        assertFalse(profile(0, "V1").hasBoardsFor(listOf(row("V1"))))
    }

    @Test
    fun `the direction counts and the case does not`() {
        assertFalse(profile(5, "V1").hasBoardsFor(listOf(row("V1", direction = "northbound"))))
        assertTrue(profile(5, "V1").hasBoardsFor(listOf(row("V1", line = "Victoria", direction = "Southbound"))))
    }
}
