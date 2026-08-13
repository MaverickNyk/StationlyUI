package user

import com.stationly.core.model.FilterMode
import com.stationly.core.model.UserSelection
import com.stationly.core.model.user.Board
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.BoardPin
import com.stationly.core.model.user.BoardSelection
import com.stationly.core.model.user.BoardView
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The board is one per STATION and the fetch naptan hangs off the SELECTION.
 * Both facts are invisible on rail — every direction of every line shares the
 * station's naptan there — so a bug in either is only observable on buses, on a
 * real hub, at the point where the wrong side of the road is shown.
 *
 * These tests use Smithwood Close, which is the case that motivated the shape:
 * hub `490012211N`, route 39 inbound departing from pole `490008805N` and
 * outbound from `490012211N`.
 */
class BoardTest {

    private fun sel(
        station: String,
        parent: String,
        line: String,
        direction: String,
        mode: String = "bus",
        name: String = "Smithwood Close",
        filterMode: FilterMode = FilterMode.ALL,
        destinationIds: List<String> = emptyList(),
        viaStationIds: List<String> = emptyList(),
    ) = UserSelection(
        mode = mode,
        line = line,
        station = station,
        parentStationId = parent,
        stationName = name,
        direction = direction,
        destinations = emptyList(),
        destinationIds = destinationIds,
        filterMode = filterMode,
        viaStationIds = viaStationIds,
    )

    // ── Shape ───────────────────────────────────────────────────────────────

    @Test
    fun `a bus hub folds into ONE board whose selections keep their own poles`() {
        val boards = Board.fromSelections(
            listOf(
                sel("490008805N", "490012211N", "39", "inbound"),
                sel("490012211N", "490012211N", "39", "outbound"),
            ),
        )

        assertEquals(1, boards.size, "one hub is one board")
        val board = boards.single()
        assertEquals("490012211N", board.id, "the board is identified by the HUB")

        val byDirection = board.selections.associateBy { it.direction }
        // The whole reason the naptan is on the selection: the two sides of the
        // road are different stops. Collapsing these would serve inbound
        // departures on the outbound board.
        assertEquals("490008805N", byDirection.getValue("inbound").naptanId)
        assertEquals("490012211N", byDirection.getValue("outbound").naptanId)
    }

    @Test
    fun `a multi-line station is one board, and its lines are derived`() {
        val boards = Board.fromSelections(
            listOf(
                sel("940GZZLUKSX", "940GZZLUKSX", "circle", "inbound", "tube", "King's Cross"),
                sel("940GZZLUKSX", "940GZZLUKSX", "circle", "outbound", "tube", "King's Cross"),
                sel("940GZZLUKSX", "940GZZLUKSX", "northern", "inbound", "tube", "King's Cross"),
            ),
        )

        val board = boards.single()
        assertEquals(3, board.selections.size, "three queues, flat under the board")
        // Derived, never stored — a second copy of either would drift the first
        // time this hub gained a line of another mode.
        assertEquals(listOf("circle", "northern"), board.lines)
        assertEquals("tube", board.mode)
        // One stop, whatever the line count: this is what must be POLLED.
        assertEquals(listOf("940GZZLUKSX"), board.naptanIds)

        val grouped = board.byLine()
        assertEquals(listOf("circle", "northern"), grouped.map { it.first })
        assertEquals(2, grouped.first().second.size)
        assertEquals(1, grouped.last().second.size)
    }

    @Test
    fun `every selection survives the round trip`() {
        val original = listOf(
            sel("490008805N", "490012211N", "39", "inbound"),
            sel("490012211N", "490012211N", "39", "outbound"),
            sel("940GZZLUASL", "940GZZLUASL", "piccadilly", "inbound", "tube", "Arsenal"),
        )

        val restored = Board.fromSelections(original).flatMap { it.toSelections() }

        assertEquals(original.size, restored.size)
        // Compared as sets on the identity the app keys everything by — the
        // ORDER within a board is preserved but is not what must hold.
        assertEquals(original.map { it.boardKey }.toSet(), restored.map { it.boardKey }.toSet())
        assertEquals(original.map { it.parentStationId }.toSet(), restored.map { it.parentStationId }.toSet())
        assertEquals(original.map { it.stationName }.toSet(), restored.map { it.stationName }.toSet())
    }

    @Test
    fun `a pre-hub selection groups on its own naptan`() {
        // parentStationId blank means "same as station" — rows saved before hubs
        // existed. They must still produce a board, keyed on themselves.
        val board = Board.fromSelections(
            listOf(sel("940GZZLUASL", "", "piccadilly", "inbound", "tube", "Arsenal")),
        ).single()

        assertEquals("940GZZLUASL", board.id)
        assertEquals("940GZZLUASL", board.selections.single().naptanId)
    }

    // ── Filters ─────────────────────────────────────────────────────────────

    @Test
    fun `a filter survives on the queue it belongs to`() {
        val board = Board.fromSelections(
            listOf(
                sel(
                    "940GZZLUASL", "940GZZLUASL", "piccadilly", "westbound", "tube", "Arsenal",
                    filterMode = FilterMode.VIA,
                    destinationIds = listOf("940GZZLUHR5"),
                    viaStationIds = listOf("940GZZLUGPK"),
                ),
                // Same line, other way, deliberately UNFILTERED — a filter is a
                // statement about one queue of trains, so it must not leak
                // across directions.
                sel("940GZZLUASL", "940GZZLUASL", "piccadilly", "eastbound", "tube", "Arsenal"),
            ),
        ).single()

        val byDirection = board.selections.associateBy { it.direction }
        val west = byDirection.getValue("westbound").filter
        assertEquals(FilterMode.VIA, west.mode)
        assertEquals(listOf("940GZZLUHR5"), west.destinationIds)
        // The user's INTENT is kept beside the resolution, so a stale allow-list
        // can be re-resolved without asking them again.
        assertEquals(listOf("940GZZLUGPK"), west.viaIds)
        assertTrue(west.isActive)

        val east = byDirection.getValue("eastbound").filter
        assertEquals(FilterMode.ALL, east.mode)
        assertFalse(east.isActive)

        val restored = board.toSelections().associateBy { it.direction }
        assertEquals(FilterMode.VIA, restored.getValue("westbound").filterMode)
        assertEquals(listOf("940GZZLUGPK"), restored.getValue("westbound").viaStationIds)
        assertEquals(FilterMode.ALL, restored.getValue("eastbound").filterMode)
    }

    @Test
    fun `a filter mode with no resolved ids is not active`() {
        // The render path checks destinationIds; a mode set without a resolution
        // must show everything rather than an empty board.
        val board = Board.fromSelections(
            listOf(
                sel(
                    "940GZZLUASL", "940GZZLUASL", "piccadilly", "westbound", "tube", "Arsenal",
                    filterMode = FilterMode.DESTINATIONS,
                ),
            ),
        ).single()

        assertFalse(board.selections.single().filter.isActive)
    }

    // ── Configuration ───────────────────────────────────────────────────────

    @Test
    fun `configuration is carried per board, from the caller`() {
        val configs = mapOf(
            "490012211N" to BoardConfig(expanded = false, rowsPerPlatform = 5, position = 1),
            "940GZZLUASL" to BoardConfig(view = BoardView.BOARD_ONLY, position = 0),
        )
        val boards = Board.fromSelections(
            selections = listOf(
                sel("490008805N", "490012211N", "39", "inbound"),
                sel("940GZZLUASL", "940GZZLUASL", "piccadilly", "inbound", "tube", "Arsenal"),
            ),
            config = { configs.getValue(it) },
        ).associateBy { it.id }

        assertFalse(boards.getValue("490012211N").config.expanded)
        assertEquals(5, boards.getValue("490012211N").config.rowCap)
        assertEquals(BoardView.BOARD_ONLY, boards.getValue("940GZZLUASL").config.view)
        // The one thing a board must not lose on a re-push: the arrangement the
        // user set. Defaulting it here is how a sync used to wipe it.
        assertEquals(1, boards.getValue("490012211N").config.position)
    }

    @Test
    fun `rowsPerPlatform is clamped on read, never trusted from storage`() {
        // A value written by a build whose limits differed must not render
        // twelve rows; today's range wins quietly.
        assertEquals(BoardConfig.MAX_ROWS_PER_PLATFORM, BoardConfig(rowsPerPlatform = 12).rowCap)
        assertEquals(BoardConfig.MIN_ROWS_PER_PLATFORM, BoardConfig(rowsPerPlatform = 0).rowCap)
        assertEquals(4, BoardConfig(rowsPerPlatform = 4).rowCap)
    }

    @Test
    fun `an undragged board sorts after every dragged one`() {
        val dragged = BoardConfig(position = 3)
        val untouched = BoardConfig()
        assertEquals(BoardConfig.UNPOSITIONED, untouched.position)
        assertTrue(dragged.sortKey < untouched.sortKey, "a board added since the last reorder goes last")
    }

    @Test
    fun `the default board shows the hero above its board`() {
        val default = BoardConfig()
        assertTrue(default.expanded)
        assertEquals(BoardView.FULL, default.view)
        assertTrue(default.view.showsHero)
        assertNull(default.pin)
    }

    @Test
    fun `the hero-only view is gone and cannot come back by accident`() {
        // It was offered and dropped. The board is what the card is FOR, so no
        // view may hide it — which is now structural: there is nothing left to
        // hide it with.
        assertEquals(listOf(BoardView.FULL, BoardView.BOARD_ONLY), BoardView.entries)
    }

    @Test
    fun `a config naming the removed view decodes to the default`() {
        // Devices carry `view: "NEXT_ONLY"` from before the removal. `coerceInputValues`
        // falls back to the property default for an unknown enum member — so the card
        // shows MORE than was asked, never less, which is the right direction for a
        // screen whose entire purpose is departures.
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val stored = """{"view":"NEXT_ONLY","rowsPerPlatform":4}"""

        val decoded = json.decodeFromString(BoardConfig.serializer(), stored)

        assertEquals(BoardView.FULL, decoded.view)
        // Everything alongside it survives the coercion.
        assertEquals(4, decoded.rowsPerPlatform)
    }

    @Test
    fun `a pin is one block, and survives on the board`() {
        val board = Board.fromSelections(
            selections = listOf(sel("490008805N", "490012211N", "39", "inbound")),
            config = { BoardConfig(pin = BoardPin(BoardPin.Kind.STOP, "490008805N")) },
        ).single()

        assertEquals(BoardPin.Kind.STOP, board.config.pin?.kind)
        assertEquals("490008805N", board.config.pin?.id)
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    @Test
    fun `a board with no selections is not usable`() {
        // What a truncated or superseded payload decodes to: an id and a name,
        // and nothing fetchable. Treated as ABSENT so the callers fall back
        // instead of restoring nothing — or worse, concluding the cloud is empty
        // and deleting the device's boards.
        assertFalse(Board(id = "940GZZLUASL", name = "Arsenal").isUsable)
        assertTrue(
            Board.fromSelections(
                listOf(sel("940GZZLUASL", "940GZZLUASL", "piccadilly", "inbound", "tube", "Arsenal")),
            ).single().isUsable,
        )
    }

    @Test
    fun `naptanIds lists every stop the board fetches from, deduplicated`() {
        val board = Board.fromSelections(
            listOf(
                sel("490008805N", "490012211N", "39", "inbound"),
                sel("490012211N", "490012211N", "39", "outbound"),
                // A second route sharing one of the poles — the list is
                // deduplicated, because it answers "what must be polled".
                sel("490008805N", "490012211N", "639", "inbound"),
            ),
        ).single()

        assertEquals(setOf("490008805N", "490012211N"), board.naptanIds.toSet())
        assertEquals(2, board.naptanIds.size)
    }

    @Test
    fun `addedAt is taken per board, so a re-sync cannot reshuffle restore order`() {
        val ages = mapOf("490012211N" to 111L, "940GZZLUASL" to 222L)
        val boards = Board.fromSelections(
            selections = listOf(
                sel("490008805N", "490012211N", "39", "inbound"),
                sel("490012211N", "490012211N", "39", "outbound"),
                sel("940GZZLUASL", "940GZZLUASL", "piccadilly", "inbound", "tube", "Arsenal"),
            ),
            addedAt = { ages.getValue(it) },
        )

        assertEquals(111L, boards.first { it.id == "490012211N" }.addedAt)
        assertEquals(222L, boards.first { it.id == "940GZZLUASL" }.addedAt)
    }

    @Test
    fun `a selection key matches the key the app runs on`() {
        val selection = sel("490008805N", "490012211N", "39", "inbound")
        assertEquals(selection.boardKey, BoardSelection.from(selection).key)
    }
}
