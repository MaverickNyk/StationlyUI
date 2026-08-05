package util

import com.stationly.core.model.FilterMode
import com.stationly.core.model.UserSelection
import com.stationly.core.util.BoardLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How a board names itself on the settings screen.
 *
 * The rule is a precedence order, and the cases that matter are the ones where
 * two rungs are available at once — a filtered northbound board must lead with
 * the bearing and keep the filter, not choose one and lose the other.
 */
class BoardLabelsTest {

    private fun board(
        line: String = "victoria",
        direction: String = "Northbound",
        filterMode: FilterMode = FilterMode.ALL,
        destinations: List<String> = emptyList(),
        destinationIds: List<String> = emptyList(),
        viaStationNames: List<String> = emptyList(),
    ) = UserSelection(
        mode = "tube",
        line = line,
        station = "940GZZLUVIC",
        stationName = "Victoria",
        direction = direction,
        destinations = destinations,
        destinationIds = destinationIds,
        filterMode = filterMode,
        viaStationNames = viaStationNames,
    )

    @Test
    fun `a compass bearing wins the title`() {
        val label = BoardLabels.forBoard(board(direction = "Southbound"), towards = "Brixton")
        assertEquals("Southbound", label.title)
        // The heading is still worth saying — it just is not the headline.
        assertEquals("Towards Brixton", label.detail)
    }

    @Test
    fun `a filter rides in the detail line under a bearing`() {
        val label = BoardLabels.forBoard(
            board(
                direction = "Northbound",
                filterMode = FilterMode.VIA,
                destinationIds = listOf("940GZZLUGPK"),
                viaStationNames = listOf("Green Park"),
            )
        )
        assertEquals("Northbound", label.title)
        assertEquals("Via Green Park", label.detail)
    }

    @Test
    fun `a filter leads when the direction is not a compass one`() {
        // "Inbound" tells a passenger nothing, so the filter they chose is the
        // better name for the board.
        val label = BoardLabels.forBoard(
            board(
                line = "39",
                direction = "inbound",
                filterMode = FilterMode.DESTINATIONS,
                destinations = listOf("Putney Bridge"),
                destinationIds = listOf("490000185W"),
            ),
            towards = "Putney Bridge",
        )
        assertEquals("Only Putney Bridge", label.title)
        assertEquals("Towards Putney Bridge", label.detail)
    }

    @Test
    fun `where the trains go beats the operator's own word`() {
        val label = BoardLabels.forBoard(board(direction = "outbound"), towards = "Clapham Junction")
        assertEquals("Towards Clapham Junction", label.title)
        assertNull(label.detail)
    }

    @Test
    fun `inbound survives only as a last resort`() {
        // No compass, no filter, and no cached departures — a board added
        // seconds ago. The word is all that separates it from its sibling.
        val label = BoardLabels.forBoard(board(direction = "inbound"), towards = null)
        assertEquals("Inbound", label.title)
    }

    @Test
    fun `a board with nothing to say at all still says something`() {
        val label = BoardLabels.forBoard(board(direction = ""), towards = "   ")
        assertEquals(BoardLabels.EVERY_DEPARTURE, label.title)
    }

    @Test
    fun `an unfiltered board has no filter label`() {
        assertNull(BoardLabels.filterLabel(board()))
        // A filter mode with an empty resolution is not a filter — the board
        // shows everything, and saying "filtered" would be a lie.
        assertNull(
            BoardLabels.filterLabel(
                board(filterMode = FilterMode.VIA, viaStationNames = listOf("Green Park"))
            )
        )
    }

    @Test
    fun `several destinations degrade to a count`() {
        val label = BoardLabels.filterLabel(
            board(
                filterMode = FilterMode.DESTINATIONS,
                destinations = listOf("Brixton", "Walthamstow", "Seven Sisters"),
                destinationIds = listOf("a", "b", "c"),
            )
        )
        assertEquals("3 destinations", label)
    }

    @Test
    fun `several via stops degrade to a count`() {
        val label = BoardLabels.filterLabel(
            board(
                filterMode = FilterMode.VIA,
                destinationIds = listOf("a", "b"),
                viaStationNames = listOf("Green Park", "Oxford Circus"),
            )
        )
        assertEquals("Via 2 stops", label)
    }
}
