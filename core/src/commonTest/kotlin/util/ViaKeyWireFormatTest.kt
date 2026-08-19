package util

import com.stationly.core.model.PredictionItem
import com.stationly.core.repository.SqlStorage
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire contract for `viaKey`.
 *
 * The backend OMITS the field when a service has no branch, rather than sending
 * null, because only two lines in the network ever produce a value and an
 * explicit null would ride on every departure of every other line on every
 * stream frame.
 *
 * That makes "absent" the normal case, so absent has to be safe.
 */
class ViaKeyWireFormatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a departure with no branch omits the field and decodes to null`() {
        // Exactly what the Victoria line sends.
        val wire = """
            {"destId":"940GZZLUBXN","platform":"Platform 2","eta":"x","displayName":"Brixton"}
        """.trimIndent()
        val p = json.decodeFromString<PredictionItem>(wire)
        assertNull(p.viaKey)
        assertEquals("Brixton", p.displayName)
    }

    @Test
    fun `a branched departure carries its token`() {
        val wire = """
            {"destId":"940GZZLUMDN","platform":"Platform 4","eta":"x",
             "displayName":"Morden via Bank","viaKey":"bank"}
        """.trimIndent()
        assertEquals("bank", json.decodeFromString<PredictionItem>(wire).viaKey)
    }

    @Test
    fun `an explicit null still decodes when an older backend sends one`() {
        // The field used to be emitted as null. A backend mid-rollout may still
        // do it, and it must mean the same thing as absent.
        val wire = """
            {"destId":"940GZZLUBXN","platform":"p","eta":"x","displayName":"Brixton","viaKey":null}
        """.trimIndent()
        assertNull(json.decodeFromString<PredictionItem>(wire).viaKey)
    }

    @Test
    fun `an absent branch never hides a train`() {
        // The whole reason absent has to be safe: on the Victoria line every
        // departure arrives this way, and reading it as "not on my branch" would
        // empty boards across most of the network.
        val allowedDestIds = setOf("940GZZLUBXN")
        val allowedViaKeys = setOf("bank")
        assertTrue(
            SqlStorage.matchesFilter("940GZZLUBXN", allowedDestIds, null, allowedViaKeys),
            "a departure with no branch token must be shown",
        )
    }

    @Test
    fun `an unrecognised branch never hides a train either`() {
        // A token TfL invents that we do not know about yet. Same rule: show it.
        // Hiding on an unknown value would break the moment TfL renames a via.
        assertTrue(
            SqlStorage.matchesFilter("940GZZLUMDN", setOf("940GZZLUMDN"), null, setOf("bank")),
        )
    }
}
