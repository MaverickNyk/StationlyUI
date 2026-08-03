package util

import com.stationly.core.util.LineStatusRanker
import com.stationly.core.util.LineStatusRanker.Entry
import com.stationly.core.util.LineStatusRanker.Tone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the board's single status strip says, and in what order.
 *
 * The board used to render one strip per tracked line; with four lines at
 * King's Cross that was four strips, mostly "Good Service", pushing the actual
 * departures off the panel. These rules decide what earns the one strip.
 */
class LineStatusRankerTest {

    private fun entry(line: String, severity: String, reason: String = "") =
        Entry(lineLabel = line, severity = severity, reason = reason)

    @Test
    fun `worst severity leads`() {
        val rotation = LineStatusRanker.rotation(listOf(
            entry("Circle", "Minor Delays"),
            entry("Northern", "Part Closure"),
            entry("Victoria", "Severe Delays"),
        ))
        assertEquals(listOf("Northern", "Victoria", "Circle"), rotation.map { it.lineLabel })
    }

    @Test
    fun `good service never takes a rotation slot while anything is disrupted`() {
        val rotation = LineStatusRanker.rotation(listOf(
            entry("Circle", "Good Service"),
            entry("Northern", "Minor Delays"),
            entry("Victoria", "Good Service"),
        ))
        assertEquals(1, rotation.size)
        assertEquals("Northern", rotation.single().lineLabel)
    }

    @Test
    fun `all good collapses to one statement for the board — not one per line`() {
        val rotation = LineStatusRanker.rotation(listOf(
            entry("Circle", "Good Service"),
            entry("Northern", "Good Service"),
            entry("Victoria", "Good Service"),
        ))
        assertEquals(1, rotation.size, "'Good Service' three times says nothing three times")
        assertEquals("", rotation.single().lineLabel, "it speaks for the board, so it names no line")
        assertEquals("Good Service", LineStatusRanker.label(rotation.single()))
    }

    @Test
    fun `lines sharing one incident are joined rather than repeated`() {
        // The sub-surface lines share track, so one incident routinely lands on
        // several of them with identical wording.
        val rotation = LineStatusRanker.rotation(listOf(
            entry("Circle", "Minor Delays", "Signal failure at Baker Street"),
            entry("District", "Minor Delays", "Signal failure at Baker Street"),
            entry("Hammersmith City", "Minor Delays", "Signal failure at Baker Street"),
        ))
        assertEquals(1, rotation.size, "one incident is one rotation slot")
        assertEquals("Circle, District, Hammersmith City", rotation.single().lineLabel)
    }

    @Test
    fun `the same severity with different reasons stays as separate entries`() {
        val rotation = LineStatusRanker.rotation(listOf(
            entry("Circle", "Minor Delays", "Signal failure"),
            entry("Northern", "Minor Delays", "Earlier faulty train"),
        ))
        assertEquals(2, rotation.size, "two incidents are two facts")
    }

    @Test
    fun `an unrecognised severity outranks good service but not a real closure`() {
        val rotation = LineStatusRanker.rotation(listOf(
            entry("Circle", "Some New TfL Wording"),
            entry("Northern", "Suspended"),
        ))
        assertEquals(listOf("Northern", "Circle"), rotation.map { it.lineLabel })
        assertTrue(
            LineStatusRanker.rankOf("Some New TfL Wording") < LineStatusRanker.rankOf("Good Service"),
            "an unknown severity is more likely a new disruption than a new way of being fine",
        )
    }

    @Test
    fun `label names the line so a multi-line board says which one is shut`() {
        assertEquals("Northern Part Closure", LineStatusRanker.label(entry("Northern", "Part Closure")))
    }

    @Test
    fun `empty input produces no strip`() {
        assertTrue(LineStatusRanker.rotation(emptyList()).isEmpty())
    }

    // ── Traffic-light tone, for the pill dots ──

    @Test
    fun `red means you cannot travel — amber means you will wait`() {
        assertEquals(Tone.RED, LineStatusRanker.toneOf("Suspended"))
        assertEquals(Tone.RED, LineStatusRanker.toneOf("Part Closure"))
        assertEquals(Tone.AMBER, LineStatusRanker.toneOf("Severe Delays"))
        assertEquals(Tone.AMBER, LineStatusRanker.toneOf("Minor Delays"))
        assertEquals(Tone.GREEN, LineStatusRanker.toneOf("Good Service"))
    }

    @Test
    fun `an absent status is green rather than alarming`() {
        assertEquals(Tone.GREEN, LineStatusRanker.toneOf(null))
        assertEquals(Tone.GREEN, LineStatusRanker.toneOf(""))
    }

    @Test
    fun `severity matching ignores case`() {
        assertEquals(Tone.RED, LineStatusRanker.toneOf("SUSPENDED"))
        assertTrue(LineStatusRanker.isGoodService("good service"))
        assertTrue(LineStatusRanker.isGoodService("Good Service"))
    }
}
