import com.stationly.app.ui.dream.dreamStationOptions
import com.stationly.core.model.UserSelection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The screensaver's station picker offers one row per CHOICE, not one per board.
 *
 * ## What it did, and why it looked like it worked
 * The picker listed `getAllSelections()` raw — one row per (station, line,
 * direction). The setting it writes is a single station naptan, and
 * `loadDreamSnapshot` resolves it with `firstOrNull { it.station == id }`.
 *
 * So at King's Cross, where four lines are tracked in both directions, the
 * picker drew EIGHT rows all named "King's Cross St. Pancras Underground
 * Station", differing only in a subtitle reading "Piccadilly · Outbound" or
 * "Circle · Inbound". Tapping any of them stored the same naptan, which lit up
 * all eight at once — and the screensaver then showed whichever board happened
 * to be first, not the one that was tapped.
 *
 * It was a picker offering a choice it could not honour, and every row after
 * the first was a promise the dream had no way to keep.
 */
class DreamStationOptionsTest {

    private fun board(
        station: String,
        name: String,
        line: String,
        direction: String,
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
        board("940GZZLUKSX", "King's Cross St. Pancras", "piccadilly", "outbound"),
        board("940GZZLUKSX", "King's Cross St. Pancras", "piccadilly", "inbound"),
        board("940GZZLUKSX", "King's Cross St. Pancras", "victoria", "outbound"),
        board("940GZZLUKSX", "King's Cross St. Pancras", "circle", "inbound"),
    )

    @Test
    fun `eight boards at one station are one row`() {
        val options = dreamStationOptions(kingsCross)
        assertEquals(1, options.size)
        assertEquals("940GZZLUKSX", options.single().stationId)
        assertEquals("King's Cross St. Pancras", options.single().name)
    }

    /**
     * The row describes the STATION, so it names every line there — not the one
     * line whichever board happened to sort first, which is what the subtitle
     * used to show and was wrong for seven rows out of eight.
     */
    @Test
    fun `the row names every line at that station, de-duplicated and in order`() {
        assertEquals(
            listOf("piccadilly", "victoria", "circle"),
            dreamStationOptions(kingsCross).single().lines,
        )
    }

    @Test
    fun `two real stations stay two rows`() {
        val options = dreamStationOptions(
            kingsCross + board("940GZZDLBNK", "Bank DLR Station", "dlr", "outbound", mode = "dlr"),
        )
        assertEquals(listOf("940GZZLUKSX", "940GZZDLBNK"), options.map { it.stationId })
        assertEquals("dlr", options.last().mode)
    }

    /**
     * A bus hub's poles have DIFFERENT naptans, and the naptan is exactly what
     * the setting stores — so they are genuinely different choices and must not
     * be collapsed. Grouping by the hub here would offer one row that stored a
     * value `loadDreamSnapshot` could then resolve to either pole.
     */
    @Test
    fun `two poles of one bus stop are two choices, because the setting is a naptan`() {
        val options = dreamStationOptions(
            listOf(
                board("490008805N", "Smithwood Close", "39", "inbound", mode = "bus"),
                board("490012211N", "Smithwood Close", "39", "outbound", mode = "bus"),
            ),
        )
        assertEquals(2, options.size)
    }

    @Test
    fun `no boards is no rows`() {
        assertEquals(emptyList(), dreamStationOptions(emptyList()))
    }
}
