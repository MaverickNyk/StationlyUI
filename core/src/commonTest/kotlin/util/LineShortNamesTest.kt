package util

import com.stationly.core.util.LineShortNames
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Short line labels, for the places where width is the binding constraint —
 * platform headers, row prefixes and the pill row.
 */
class LineShortNamesTest {

    @Test
    fun `keeps TfL's own abbreviations rather than inventing new ones`() {
        // These are what the tube map key, the roundels and the platform signage
        // already use, so an invented "Hamm." would be a worse label than the one
        // a passenger can read off the wall.
        assertEquals("H&C", LineShortNames.shortName("hammersmith-city"))
        assertEquals("W&C", LineShortNames.shortName("waterloo-city"))
    }

    @Test
    fun `initialisms that are already the name keep it whole`() {
        assertEquals("DLR", LineShortNames.shortName("dlr"))
        assertEquals("Tram", LineShortNames.shortName("tram"))
    }

    @Test
    fun `bus routes pass through untouched`() {
        // "53" is already as short as it gets, and there are hundreds of routes,
        // so a lookup table could never be complete.
        assertEquals("53", LineShortNames.shortName("53"))
        assertEquals("N29", LineShortNames.shortName("N29"))
    }

    @Test
    fun `an unknown line is title-cased rather than blanked`() {
        assertEquals("Someline", LineShortNames.shortName("someline"))
        assertEquals("", LineShortNames.shortName(null))
    }

    @Test
    fun `display name expands hyphenated ids into words`() {
        assertEquals("Hammersmith City", LineShortNames.displayName("hammersmith-city"))
        assertEquals("Northern", LineShortNames.displayName("northern"))
    }

    @Test
    fun `one line gets its full name — because there is room for it`() {
        assertEquals("Northern", LineShortNames.joinLines(listOf("northern")))
    }

    @Test
    fun `several lines switch to short forms — which is when width runs out`() {
        assertEquals("Dist. & Circ.", LineShortNames.joinLines(listOf("district", "circle")))
        assertEquals("Met., H&C & Circ.", LineShortNames.joinLines(listOf("metropolitan", "hammersmith-city", "circle")))
    }

    @Test
    fun `past a few lines the header degrades to a count`() {
        // Otherwise the header is longer than the platform fact it exists to
        // deliver — real at the sub-surface interchanges.
        assertEquals(
            "4 lines",
            LineShortNames.joinLines(listOf("metropolitan", "hammersmith-city", "circle", "district")),
        )
    }

    @Test
    fun `duplicate and blank line ids are ignored`() {
        assertEquals("Northern", LineShortNames.joinLines(listOf("northern", "northern", "")))
        assertEquals("", LineShortNames.joinLines(emptyList()))
    }
}
