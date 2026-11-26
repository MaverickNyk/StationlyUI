import com.stationly.app.ui.dream.dreamBoardsFor
import com.stationly.core.model.UserSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The screensaver shows the WHOLE station, not the first board at it.
 *
 * ## Seen on a phone, 2026-09-12
 * The dream had never been rendered — a screensaver only runs with the screen
 * on, and the doze dream wins while it is off — so this survived every pass.
 * Previewed through system Settings, it drew "King's Cross St. Pancras
 * Underground Station" above a single block: Piccadilly, Platform 6,
 * Eastbound. Three trains, then a screen's worth of black.
 *
 * The user tracks four lines there in both directions. `loadDreamSnapshot`
 * resolved the station with `firstOrNull { it.station == id }` and rendered
 * that one board, so the screensaver named a station and showed a quarter of
 * it — with nothing on screen to say the other three quarters existed.
 *
 * Exactly the defect the WIDGET had (`WidgetMultiLineBoardTest`) and for the
 * same reason: a station is not a board, and resolving one to the other with
 * `first` silently drops the rest. The empty space below those three rows was
 * the other platforms, all along.
 */
class DreamBoardsForTest {

    private fun board(
        station: String,
        line: String,
        direction: String,
        name: String = "King's Cross St. Pancras",
        mode: String = "tube",
    ) = UserSelection(
        mode = mode,
        line = line,
        station = station,
        stationName = name,
        direction = direction,
        destinations = emptyList(),
        destinationIds = emptyList(),
    )

    private val kingsCross = listOf(
        board("940GZZLUKSX", "piccadilly", "outbound"),
        board("940GZZLUKSX", "piccadilly", "inbound"),
        board("940GZZLUKSX", "victoria", "outbound"),
        board("940GZZLUKSX", "circle", "inbound"),
    )

    /** **The one the old dream got wrong.** */
    @Test
    fun `every board at the chosen station reaches the dream`() {
        assertEquals(4, dreamBoardsFor(kingsCross, "940GZZLUKSX").size)
    }

    @Test
    fun `boards at other stations do not`() {
        val all = kingsCross + board("940GZZDLBNK", "dlr", "outbound", "Bank DLR Station", "dlr")
        val picked = dreamBoardsFor(all, "940GZZDLBNK")
        assertEquals(1, picked.size)
        assertEquals("dlr", picked.single().line)
    }

    /**
     * No preference set is the "Auto" row, which means the top board on the
     * home screen — and then the whole station THAT board is at, not that one
     * board. Auto must not be a quieter version of the same bug.
     */
    @Test
    fun `Auto takes the first board's whole station`() {
        val all = kingsCross + board("940GZZDLBNK", "dlr", "outbound", "Bank DLR Station", "dlr")
        assertEquals(4, dreamBoardsFor(all, null).size)
    }

    /**
     * A stored station the user has since deleted falls back the same way,
     * rather than rendering nothing — the screensaver runs unattended and an
     * empty panel overnight is worse than the wrong station.
     */
    @Test
    fun `a station that no longer exists falls back to the first one`() {
        val picked = dreamBoardsFor(kingsCross, "940GZZLUOLD")
        assertEquals(4, picked.size)
        assertTrue(picked.all { it.station == "940GZZLUKSX" })
    }

    @Test
    fun `no boards at all is no boards`() {
        assertEquals(emptyList(), dreamBoardsFor(emptyList(), "940GZZLUKSX"))
    }

    /**
     * A bus hub's poles have different naptans and the setting stores one, so
     * the dream shows THAT pole. Widening to the hub here would put the other
     * side of the road on a board the user chose one side of.
     */
    @Test
    fun `a bus pole is its own station, not its hub`() {
        val poles = listOf(
            board("490008805N", "39", "inbound", "Smithwood Close", "bus"),
            board("490012211N", "39", "outbound", "Smithwood Close", "bus"),
        )
        assertEquals(1, dreamBoardsFor(poles, "490012211N").size)
        assertEquals("outbound", dreamBoardsFor(poles, "490012211N").single().direction)
    }
}
