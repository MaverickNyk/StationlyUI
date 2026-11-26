package user

import com.stationly.core.model.sdui.SubscribedStation
import com.stationly.core.model.sdui.UserProfileResponse
import com.stationly.core.model.user.Board
import com.stationly.core.model.user.BoardSelection
import com.stationly.core.model.user.effectiveBoards
import com.stationly.core.model.user.toSubscribedStations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which of the account's two lists is the account — AV2-4.3 task (d).
 *
 * The order matters more on Android than it ever did on iOS, because an Android
 * account can hold BOTH: a `stations` array written by a v1 phone that is still
 * installed, and a `boards` array written by this one. Picking the wrong one at
 * login restores boards the user deleted, or drops the filters off the ones they
 * kept — and either way it looks like the app losing their work rather than a
 * fold reading the wrong field.
 */
class EffectiveBoardsTest {

    private val hub = "490012211N"
    private val poleIn = "490008805N"

    private fun profile(
        boards: List<Board> = emptyList(),
        stations: List<SubscribedStation> = emptyList(),
    ) = UserProfileResponse(
        uid = "u1",
        email = "a@b.c",
        displayName = "A",
        stations = stations,
        boards = boards,
    )

    private fun board(id: String = hub, name: String = "Smithwood Close") = Board(
        id = id,
        name = name,
        selections = listOf(BoardSelection(naptanId = poleIn, line = "39", mode = "bus", direction = "inbound")),
    )

    private fun station(id: String = poleIn, parent: String? = hub) = SubscribedStation(
        id = id,
        name = "Smithwood Close",
        line = "39",
        mode = "bus",
        direction = "inbound",
        parentStationId = parent,
    )

    @Test
    fun `boards win when the account has them`() {
        // The legacy row names a DIFFERENT line. If the fold read it, the user
        // would come back to a board they do not have.
        val result = profile(
            boards = listOf(board()),
            stations = listOf(station().copy(line = "639")),
        ).effectiveBoards()

        assertEquals(1, result.size)
        assertEquals("39", result.single().selections.single().line)
    }

    @Test
    fun `stations answer when there are no boards`() {
        val result = profile(stations = listOf(station())).effectiveBoards()

        assertEquals(1, result.size)
        assertEquals(hub, result.single().id, "the hub must survive the fold, or one bus stop becomes a card per pole")
    }

    /**
     * A board with no selections says nothing — a truncated payload, or a
     * response from a backend that predates the shape. Treating it as an answer
     * suppresses the legacy fallback, and the user lands on a home screen of
     * cards that never populate.
     */
    @Test
    fun `an unusable board does not suppress the legacy list`() {
        val result = profile(
            boards = listOf(Board(id = hub, name = "Smithwood Close", selections = emptyList())),
            stations = listOf(station()),
        ).effectiveBoards()

        assertEquals(1, result.size)
        assertEquals("39", result.single().selections.single().line)
    }

    @Test
    fun `an account with nothing folds to nothing`() {
        assertTrue(profile().effectiveBoards().isEmpty())
    }

    /**
     * **The dual-write has to round-trip.** `/user/sync/stations` is a full
     * replace, so the projection this client writes is the entire world a v1
     * phone sees. A fold that drops anything deletes it from that phone.
     */
    @Test
    fun `a board projected to the legacy list folds back to the same board`() {
        val original = board()

        val roundTripped = profile(stations = listOf(original).toSubscribedStations()).effectiveBoards()

        assertEquals(1, roundTripped.size)
        assertEquals(original.id, roundTripped.single().id)
        assertEquals(
            original.selections.map { it.naptanId to it.line },
            roundTripped.single().selections.map { it.naptanId to it.line },
        )
    }
}
