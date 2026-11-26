package com.stationly.app.ui.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parts of Android's Play Billing route that do not need Play.
 */
class PlayBillingTest {

    // ── The account binding (must match the backend) ──────────────────────

    @Test
    fun the_account_id_is_sha256_hex_of_the_uid_and_matches_the_backend() {
        // The SAME vector as the backend's test of `playAccountId('uid_a')`.
        // If either side changes how it hashes, every Android purchase is
        // refused as "account_mismatch", so the two are pinned to one value.
        assertEquals(
            "fe8436efb888a5f9bda977733b623cb4baaf8723f5e04122d915aadaa988201a",
            PlayAccount.idFor("uid_a"),
        )
    }

    @Test
    fun the_account_id_fits_plays_64_character_limit_and_never_carries_the_uid() {
        val uid = "AbCdEfGhIjKlMnOpQrStUvWxYz12" // a Firebase-shaped uid
        val id = PlayAccount.idFor(uid)
        assertEquals(64, id.length)
        assertFalse(id.contains(uid, ignoreCase = true))
    }

    @Test
    fun the_session_id_mirrors_the_servers_ledger_row() {
        assertEquals("google_GPA.1234-5678", PlayAccount.sessionId("GPA.1234-5678", "token"))
        // No order id is malformed, but the device still needs a stable key.
        assertEquals("google_" + "0123456789abcdef", PlayAccount.sessionId(null, "xxxx0123456789abcdef"))
        assertEquals("google_" + "0123456789abcdef", PlayAccount.sessionId(" ", "xxxx0123456789abcdef"))
    }

    // ── What Android counts as payable ────────────────────────────────────

    private fun card(tiers: String) = SupportMoneyConfigDefaults.parse(
        mapOf(SupportMoneyConfigDefaults.CARD_KEY to """{"enabled":true,"tiers":[$tiers],"cta":{"url_oneoff":""}}""")
    )

    @Test
    fun a_card_with_play_products_is_offerable_on_android() {
        val cfg = card("""{"id":"t4","amount_minor":400,"google_product_id":"uk.co.stationly.support.t4"}""")
        assertTrue(cfg.isPayable)
        assertTrue(cfg.isOfferable)
        assertEquals("uk.co.stationly.support.t4", nativeProductId(cfg.tiers.single()))
    }

    @Test
    fun an_app_store_product_alone_is_not_payable_on_android() {
        // It used to be: `isPayable` read the Apple id on every platform, so
        // Android looked live only because the backend sent it App Store ids.
        val cfg = card("""{"id":"t4","amount_minor":400,"apple_product_id":"uk.co.stationly.support.t4"}""")
        assertFalse(cfg.isPayable)
    }

    // ── The log row that would have caught the 2026-11-24 bug ─────────────

    @Test
    fun the_verify_outcome_is_logged_as_a_short_label_that_tells_a_401_apart() {
        assertEquals("credited", verifyLabel(com.stationly.core.service.PlayVerifyOutcome.Credited(newlyRecorded = true)))
        assertEquals("already_credited", verifyLabel(com.stationly.core.service.PlayVerifyOutcome.Credited(newlyRecorded = false)))
        assertEquals("rejected:account_mismatch", verifyLabel(com.stationly.core.service.PlayVerifyOutcome.Rejected("account_mismatch")))
        assertEquals("retry:401", verifyLabel(com.stationly.core.service.PlayVerifyOutcome.RetryLater(401)))
        assertEquals("retry:network", verifyLabel(com.stationly.core.service.PlayVerifyOutcome.RetryLater()))
    }
}
