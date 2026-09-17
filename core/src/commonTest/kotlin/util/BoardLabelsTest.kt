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
 * Every case here is built from a SAVED SELECTION and nothing else. The old
 * suite passed a `towards` string read out of cached departures and asserted a
 * precedence order between that and the user's own choice; both are gone. The
 * question this screen asks is "what did I set up here", and the answer cannot
 * depend on what the line happened to be running when the cache was written.
 */
class BoardLabelsTest {

    private fun board(
        line: String = "victoria",
        direction: String = "Northbound",
        mode: String = "tube",
        directionName: String = "",
        directionDestinations: List<String> = emptyList(),
        directionTowards: String = "",
        filterMode: FilterMode = FilterMode.ALL,
        destinations: List<String> = emptyList(),
        destinationIds: List<String> = emptyList(),
        viaStationNames: List<String> = emptyList(),
        patternNames: List<String> = emptyList(),
    ) = UserSelection(
        mode = mode,
        line = line,
        station = "940GZZLUVIC",
        stationName = "Victoria",
        direction = direction,
        destinations = destinations,
        destinationIds = destinationIds,
        filterMode = filterMode,
        viaStationNames = viaStationNames,
        patternNames = patternNames,
        directionName = directionName,
        directionDestinations = directionDestinations,
        directionTowards = directionTowards,
    )

    // ── The title is the direction, always ────────────────────────────────

    @Test
    fun `a compass bearing titles the board`() {
        assertEquals("Southbound", BoardLabels.forBoard(board(direction = "Southbound")).title)
    }

    @Test
    fun `a filter never displaces the direction from the title`() {
        // The filter used to take the title whenever the direction was not a
        // compass one, which left two boards on the same line showing no
        // direction at all — on the screen called "Lines and directions".
        // A BUS, where "Inbound" survives on purpose: TfL publishes no bearing
        // for a route, the backend returns the literal "Towards" as its
        // direction name, and the destination line below carries the meaning.
        val label = BoardLabels.forBoard(
            board(
                line = "39",
                mode = "bus",
                direction = "inbound",
                filterMode = FilterMode.DESTINATIONS,
                destinations = listOf("Putney Bridge"),
                destinationIds = listOf("490000185W"),
            )
        )
        assertEquals("Inbound", label.title)
        assertEquals("Finishing at Putney Bridge", label.detail)
    }

    @Test
    fun `the stored picker name wins over everything`() {
        // The server decided this string and the user chose from it. Nothing
        // local may second-guess it, including the fallback table.
        assertEquals(
            "Clockwise",
            BoardLabels.forBoard(
                board(line = "circle", direction = "inbound", directionName = "Clockwise")
            ).title,
        )
    }

    @Test
    fun `inbound and outbound never reach the screen on rail`() {
        // The whole point: "Inbound" is an operational fact about TfL's network,
        // not about anyone's journey. A board saved before `directionName`
        // existed still has to name a platform the user can stand on.
        assertEquals(
            "Southbound",
            BoardLabels.forBoard(board(line = "victoria", direction = "inbound")).title,
        )
        assertEquals(
            "Northbound",
            BoardLabels.forBoard(board(line = "victoria", direction = "outbound")).title,
        )
    }

    @Test
    fun `the fallback table follows the backend's own exceptions`() {
        // District and Metropolitan are labelled the opposite way round from the
        // east-west default; DLR the opposite of the north-south lines; the
        // Circle has no bearing at all. Mirrors `getCompassDirection`.
        assertEquals("Westbound", BoardLabels.compassFallback("district", "inbound", "tube"))
        assertEquals("Eastbound", BoardLabels.compassFallback("metropolitan", "outbound", "tube"))
        assertEquals("Northbound", BoardLabels.compassFallback("dlr", "inbound", "dlr"))
        assertEquals("Clockwise", BoardLabels.compassFallback("circle", "inbound", "tube"))
        assertEquals("Eastbound", BoardLabels.compassFallback("jubilee", "inbound", "tube"))
    }

    @Test
    fun `a bus is named by where it goes, not by inbound`() {
        // TfL gives a route no direction, so the only thing a passenger at the
        // stop can act on is the destination. This is the picker's own headline.
        val label = BoardLabels.forBoard(
            board(
                line = "39",
                mode = "bus",
                direction = "inbound",
                directionName = "Towards",
                directionTowards = "Putney Bridge",
                directionDestinations = listOf("Putney Bridge"),
            )
        )
        assertEquals("Towards Putney Bridge", label.title)
        // And the detail does NOT repeat the title back.
        assertEquals(BoardLabels.EVERY_DESTINATION, label.detail)
    }

    @Test
    fun `a bus serving several destinations still lists them`() {
        val label = BoardLabels.forBoard(
            board(
                line = "39",
                mode = "bus",
                direction = "outbound",
                directionTowards = "Clapham Junction",
                directionDestinations = listOf("Clapham Junction", "Wandsworth"),
            )
        )
        assertEquals("Towards Clapham Junction", label.title)
        assertEquals("To Clapham Junction and Wandsworth", label.detail)
    }

    @Test
    fun `a compass bearing still beats a towards`() {
        // Rail has both. The bearing is what the platform signage says.
        val label = BoardLabels.forBoard(
            board(
                line = "victoria",
                direction = "inbound",
                directionTowards = "Brixton",
            )
        )
        assertEquals("Southbound", label.title)
    }

    @Test
    fun `a bearing lowercases mid-sentence, a place name does not`() {
        // "Remove Victoria southbound?" reads; "Remove Bus 39 towards putney
        // bridge?" does not, and that is what a blanket lowercase produced on
        // the one dialog that deletes something.
        assertEquals(
            "southbound",
            BoardLabels.directionPhrase(board(line = "victoria", direction = "Southbound")),
        )
        assertEquals(
            "Towards Putney Bridge",
            BoardLabels.directionPhrase(
                board(
                    line = "39",
                    mode = "bus",
                    direction = "inbound",
                    directionTowards = "Putney Bridge",
                )
            ),
        )
    }

    @Test
    fun `a bus has no bearing to fall back to`() {
        // The backend returns the literal "Towards" for bus, which is not a
        // direction name — so the fallback declines rather than inventing one.
        assertNull(BoardLabels.compassFallback("39", "inbound", "bus"))
        // A stored "Towards" is rejected, and with no destination stored either
        // there is nothing left but the operator's own word.
        assertEquals(
            "Inbound",
            BoardLabels.forBoard(
                board(line = "39", mode = "bus", direction = "inbound", directionName = "Towards")
            ).title,
        )
    }

    @Test
    fun `a board with no direction recorded still says something`() {
        assertEquals(
            BoardLabels.EVERY_DEPARTURE,
            BoardLabels.forBoard(board(direction = "")).title,
        )
    }

    // ── The detail is what the direction shows, and is never blank ────────

    @Test
    fun `an unnarrowed direction names the destinations it serves`() {
        // The user picked a direction and no filter. "All destinations" is true
        // but tells them nothing; the destinations themselves are the answer.
        val label = BoardLabels.forBoard(
            board(
                line = "district",
                direction = "inbound",
                directionDestinations = listOf("Richmond", "Ealing Broadway"),
            )
        )
        assertEquals("Westbound", label.title)
        assertEquals("To Richmond and Ealing Broadway", label.detail)
    }

    @Test
    fun `a bearing that does not name the destination still lists it`() {
        // The repetition test asks the TITLE, not `directionTowards`. `towards`
        // is served for rail as well as bus and loses to a compass bearing, so
        // comparing against it suppressed "To Brixton" under a title reading
        // "Southbound" — a word the user never sees deleting the one they came
        // to read.
        val label = BoardLabels.forBoard(
            board(
                line = "victoria",
                direction = "inbound",
                directionTowards = "Brixton",
                directionDestinations = listOf("Brixton"),
            )
        )
        assertEquals("Southbound", label.title)
        assertEquals("To Brixton", label.detail)
    }

    @Test
    fun `every destination is the last resort, not the first answer`() {
        // Only for a board whose route data has never been stored or backfilled.
        assertEquals(
            BoardLabels.EVERY_DESTINATION,
            BoardLabels.forBoard(board(direction = "inbound")).detail,
        )
    }

    @Test
    fun `a filter still beats the direction's destination list`() {
        // The destinations describe the DIRECTION; the filter describes what the
        // user narrowed it to, which is strictly more specific.
        val label = BoardLabels.forBoard(
            board(
                direction = "Northbound",
                filterMode = FilterMode.DESTINATIONS,
                destinations = listOf("Brixton"),
                destinationIds = listOf("a"),
                directionDestinations = listOf("Brixton", "Morden"),
            )
        )
        assertEquals("Finishing at Brixton", label.detail)
    }

    @Test
    fun `a via filter rides in the detail line under the bearing`() {
        val label = BoardLabels.forBoard(
            board(
                direction = "Northbound",
                filterMode = FilterMode.VIA,
                destinationIds = listOf("940GZZLUGPK"),
                viaStationNames = listOf("Green Park"),
            )
        )
        assertEquals("Northbound", label.title)
        assertEquals("Going through Green Park", label.detail)
    }

    // ── filterLabel: null means "shows everything" ────────────────────────

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
    fun `two destinations are spelled out and three are counted`() {
        assertEquals(
            "Finishing at Brixton and Walthamstow",
            BoardLabels.filterLabel(
                board(
                    filterMode = FilterMode.DESTINATIONS,
                    destinations = listOf("Brixton", "Walthamstow"),
                    destinationIds = listOf("a", "b"),
                )
            ),
        )
        assertEquals(
            "Finishing at 3 destinations",
            BoardLabels.filterLabel(
                board(
                    filterMode = FilterMode.DESTINATIONS,
                    destinations = listOf("Brixton", "Walthamstow", "Seven Sisters"),
                    destinationIds = listOf("a", "b", "c"),
                )
            ),
        )
    }

    @Test
    fun `two via stops are spelled out and three are counted`() {
        assertEquals(
            "Going through Green Park and Oxford Circus",
            BoardLabels.filterLabel(
                board(
                    filterMode = FilterMode.VIA,
                    destinationIds = listOf("a", "b"),
                    viaStationNames = listOf("Green Park", "Oxford Circus"),
                )
            ),
        )
        assertEquals(
            "Going through 3 stops",
            BoardLabels.filterLabel(
                board(
                    filterMode = FilterMode.VIA,
                    destinationIds = listOf("a"),
                    viaStationNames = listOf("Green Park", "Oxford Circus", "Victoria"),
                )
            ),
        )
    }

    @Test
    fun `a board filtered to one whole service is named after it`() {
        // Taking a branch stores no via STOP, only a pattern. The card read a
        // bare "Filtered" — honest and useless — because it looked at
        // viaStationNames alone.
        assertEquals(
            "Only Morden via Bank",
            BoardLabels.filterLabel(
                board(
                    filterMode = FilterMode.VIA,
                    destinationIds = listOf("940GZZLUMDN"),
                    patternNames = listOf("Morden via Bank"),
                )
            ),
        )
    }

    @Test
    fun `a service is not prefixed with the stop wording`() {
        // "Going through Morden via Bank" is not a sentence. A stop is a place
        // you pass and takes that phrasing; a service already carries its name.
        val label = BoardLabels.filterLabel(
            board(
                filterMode = FilterMode.VIA,
                destinationIds = listOf("940GZZLUMDN"),
                patternNames = listOf("Morden via Charing Cross"),
            )
        )
        assertEquals(false, label?.startsWith("Going through"))
    }

    @Test
    fun `several services are counted`() {
        assertEquals(
            "Only 3 services",
            BoardLabels.filterLabel(
                board(
                    filterMode = FilterMode.VIA,
                    destinationIds = listOf("a"),
                    patternNames = listOf("Morden via Bank", "Morden via CX", "Kennington"),
                )
            ),
        )
    }

    @Test
    fun `a stop and a service together are counted apart`() {
        // Neither phrasing fits both, and naming one would hide the other.
        assertEquals(
            "1 stop and 1 service",
            BoardLabels.filterLabel(
                board(
                    filterMode = FilterMode.VIA,
                    destinationIds = listOf("a"),
                    viaStationNames = listOf("Bank"),
                    patternNames = listOf("Battersea"),
                )
            ),
        )
    }

    @Test
    fun `a filter with no names left says so rather than claiming everything`() {
        assertEquals(
            "Narrowed to part of this line",
            BoardLabels.filterLabel(
                board(filterMode = FilterMode.VIA, destinationIds = listOf("a")),
            ),
        )
    }
}
