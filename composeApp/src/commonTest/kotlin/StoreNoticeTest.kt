import com.stationly.app.ui.support.StoreNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The store's answers that land under the Pay button. Served copy wins; the
 * fallbacks follow the support voice rules because they are what a user reads
 * until the backend serves the keys.
 */
class StoreNoticeTest {

    @Test
    fun the_served_wording_replaces_the_fallback_and_a_blank_one_does_not() {
        val n = StoreNotice.UNAVAILABLE
        assertEquals("Served", n.text(mapOf(n.key to "Served")))
        assertEquals(n.fallback, n.text(mapOf(n.key to "  ")))
        assertEquals(n.fallback, n.text(emptyMap()))
    }

    @Test
    fun every_fallback_follows_the_support_voice_rules() {
        for (n in StoreNotice.entries) {
            assertTrue(n.fallback.isNotBlank(), "${n.name} needs copy")
            assertFalse(n.fallback.contains('—') || n.fallback.contains('–'), "${n.name}: no dashes")
            assertFalse(n.fallback.contains("donat", ignoreCase = true), "${n.name}: not a donation")
            assertTrue(n.key.startsWith("support_money.store."), "${n.name}: key namespace")
        }
    }

    @Test
    fun only_a_pending_or_in_progress_payment_is_shown_as_not_an_error() {
        assertEquals(
            setOf(StoreNotice.PENDING, StoreNotice.FINISHING_PREVIOUS),
            StoreNotice.entries.filterNot { it.isError }.toSet(),
        )
    }
}
