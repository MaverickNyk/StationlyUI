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
        assertEquals("Hammersmith & City", LineShortNames.displayName("hammersmith-city"))
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

    // ── abbreviate: the shrink rung, applied to an assembled header ──

    @Test
    fun `abbreviate rewrites a full line name inside a finished header`() {
        // The input is what `displayName` now produces — headers are BUILT from
        // it, so a literal here that no longer matches would be testing a string
        // the app can never hand this function.
        assertEquals("H&C Plat. 1", LineShortNames.abbreviate("Hammersmith & City Plat. 1"))
        assertEquals("Nor. Platform 2 Westbound", LineShortNames.abbreviate("Northern Platform 2 Westbound"))
    }

    @Test
    fun `abbreviate leaves text with no line name in it alone`() {
        assertEquals("Stop W", LineShortNames.abbreviate("Stop W"))
        assertEquals("Bus 39, 34 Stop N", LineShortNames.abbreviate("Bus 39, 34 Stop N"))
        assertEquals("", LineShortNames.abbreviate(""))
    }

    @Test
    fun `abbreviate does not touch a name that is already as short as it gets`() {
        // DLR and Tram map to themselves. Replacing a string with itself buys no
        // width, and an entry that does nothing is an entry someone later has to
        // work out the purpose of.
        assertEquals("DLR Plat. 1", LineShortNames.abbreviate("DLR Plat. 1"))
        assertEquals("Tram Plat. 2", LineShortNames.abbreviate("Tram Plat. 2"))
    }

    @Test
    fun `abbreviate shortens every line in a multi-line header`() {
        assertEquals(
            "Dist. & Circ. Plat. 2",
            LineShortNames.abbreviate("District & Circle Plat. 2"),
        )
    }

    // ── The backend-first precedence ──
    //
    // These pin the property the whole LineNameStore change rests on: an empty
    // store must behave EXACTLY as this file did before it existed. Every case
    // above already asserts that implicitly — none of them populate the store —
    // so what is left to state explicitly is the fallthrough itself.

    @Test
    fun `an empty store leaves every answer to the local table`() {
        // The state on a fresh install, on a backend that does not serve the
        // field, and on every launch before the first line fetch. It is also the
        // state the rest of this file's tests run in, which is what makes them
        // a regression net for the store rather than tests of a bypassed path.
        assertEquals("H&C", LineShortNames.shortName("hammersmith-city"))
        assertEquals("Picc.", LineShortNames.shortName("piccadilly"))
    }

    @Test
    fun `an unknown line still falls through to its title-cased id`() {
        // Bus routes land here and always will — there are hundreds and neither
        // map will ever be complete. An unfamiliar name beats an empty prefix.
        assertEquals("39", LineShortNames.shortName("39"))
        assertEquals("Some-new-line", LineShortNames.shortName("some-new-line"))
        assertEquals("", LineShortNames.shortName(""))
        assertEquals("", LineShortNames.shortName(null))
    }

    // ── full names that are not the title-cased id ───────────────────────────

    /**
     * **The one the widget showed.** A single-line platform block takes its
     * header from `joinLines`, which for one line is `displayName` — and
     * `displayName` was nothing but "split on hyphens and capitalise each
     * word". That is right for "victoria" and wrong for every line whose name
     * is not a word.
     *
     * So the DLR's platform headers read "Dlr Platform 9", on the home board,
     * on the screensaver and on the home-screen widget. Nobody writes "Dlr". It
     * reads as a rendering fault because it is one.
     */
    @Test
    fun `an initialism keeps its capitals`() {
        assertEquals("DLR", LineShortNames.displayName("dlr"))
        assertEquals("DLR", LineShortNames.displayName("DLR"))
    }

    /**
     * The two ampersand lines. TfL's own names carry the "&", and dropping it
     * left "Hammersmith City", which is not the name of anything.
     */
    @Test
    fun `the ampersand lines get their ampersand`() {
        assertEquals("Hammersmith & City", LineShortNames.displayName("hammersmith-city"))
        assertEquals("Waterloo & City", LineShortNames.displayName("waterloo-city"))
    }

    /**
     * Everything else still title-cases, which is the rule that works for the
     * lines named after words and for the bus routes that will never be in any
     * map.
     */
    @Test
    fun `an ordinary line still title-cases, and so does a bus route`() {
        assertEquals("Victoria", LineShortNames.displayName("victoria"))
        assertEquals("Mildmay", LineShortNames.displayName("mildmay"))
        assertEquals("53", LineShortNames.displayName("53"))
        assertEquals("", LineShortNames.displayName(null))
    }

    /** And the header the widget builds now says it properly. */
    @Test
    fun `a single-line header names the DLR correctly`() {
        assertEquals("DLR", LineShortNames.joinLines(listOf("dlr")))
    }

    // ── the list form, for cards that enumerate a station's lines ────────────

    /**
     * `joinLines` builds a board HEADER — "Dist & Circ." — and the rule inside
     * it is the one every station card wants: one line gets its full name
     * because there is room, several switch to short forms because that is
     * exactly when width runs out.
     *
     * Three cards spelled that rule out for themselves and two of them got it
     * wrong, always taking the short form. So a station on one Overground line
     * read "Mild." — a truncation with a full stop after it, in a card with
     * room for "Mildmay" twice over. It looks like a rendering fault rather
     * than a name.
     */
    @Test
    fun `one line in a list gets its full name`() {
        assertEquals("Mildmay", LineShortNames.listLines(listOf("mildmay")))
        assertEquals("DLR", LineShortNames.listLines(listOf("dlr")))
    }

    /** Several switch to short forms, separated the way a card lists things. */
    @Test
    fun `several lines are short and dot-separated`() {
        assertEquals(
            "Picc. · Met. · Circ.",
            LineShortNames.listLines(listOf("piccadilly", "metropolitan", "circle")),
        )
    }

    /** De-duplicated: both directions of one line are one line. */
    @Test
    fun `a line named twice is listed once`() {
        assertEquals("Victoria", LineShortNames.listLines(listOf("victoria", "victoria")))
    }

    /**
     * Blank rather than a stray separator, so the caller can substitute its own
     * fallback ("No lines yet", or the mode) with a plain `ifBlank`.
     */
    @Test
    fun `no lines is blank, not a separator`() {
        assertEquals("", LineShortNames.listLines(emptyList()))
        assertEquals("", LineShortNames.listLines(listOf("", "  ")))
    }
}
