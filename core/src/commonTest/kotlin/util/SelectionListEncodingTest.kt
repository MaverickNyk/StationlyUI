package util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * How a list of strings survives one TEXT column.
 *
 * Mirrors `SqlStorage.encodeList` / `decodeList`, which are private. The rule is
 * worth a test on its own because the failure it prevents is silent: these
 * columns are index-aligned with each other, so one entry splitting in two
 * shifts every name onto the wrong id and the board comes back labelling stops
 * the user never picked.
 */
class SelectionListEncodingTest {

    private val json = Json
    private val serializer = ListSerializer(String.serializer())

    private fun encode(values: List<String>): String =
        if (values.isEmpty()) "" else json.encodeToString(serializer, values)

    private fun decode(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("[")) {
            runCatching { return json.decodeFromString(serializer, text) }
        }
        return text.split(",").filter { it.isNotEmpty() }
    }

    private fun roundTrip(values: List<String>) = decode(encode(values))

    @Test
    fun `a name containing a comma survives`() {
        // The bug this exists for. Comma-joined, "Kings Cross, York Way" came
        // back as two entries, shifting every later name onto the wrong id.
        val names = listOf("Kings Cross, York Way", "Bank")
        assertEquals(names, roundTrip(names))
        assertEquals(2, roundTrip(names).size)
    }

    @Test
    fun `alignment with a parallel id list is preserved`() {
        val ids = listOf("490000123A", "940GZZLUBNK")
        val names = listOf("Smithwood Close, Stop C", "Bank")
        val outIds = roundTrip(ids)
        val outNames = roundTrip(names)
        assertEquals(outIds.size, outNames.size, "the two lists must stay index-aligned")
        assertEquals("Smithwood Close, Stop C", outNames[outIds.indexOf("490000123A")])
    }

    @Test
    fun `empty round-trips to empty and stores nothing`() {
        assertEquals("", encode(emptyList()))
        assertEquals(emptyList(), roundTrip(emptyList()))
        assertEquals(emptyList(), decode(null))
        assertEquals(emptyList(), decode(""))
    }

    @Test
    fun `ordinary values are unchanged`() {
        val v = listOf("bank", "charingcross")
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `quotes and brackets in a name survive`() {
        val v = listOf("St Paul's \"Cathedral\"", "Shepherd's Bush [Market]")
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `a comma-joined column written by an earlier build still reads`() {
        // Not for released data — there is none — but every dev database holds
        // the old form, and a board list is not something to lose to a format
        // change.
        assertEquals(listOf("940GZZLUBNK", "940GZZLUCHX"), decode("940GZZLUBNK,940GZZLUCHX"))
        assertEquals(listOf("Bank"), decode("Bank"))
    }

    @Test
    fun `malformed json falls back rather than throwing`() {
        // A truncated write must degrade, never crash the board list on load.
        assertEquals(listOf("[\"broken"), decode("[\"broken"))
    }
}
