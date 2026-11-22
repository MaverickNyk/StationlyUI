package com.stationly.core.service

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the app reads the backend's answer about a Play purchase.
 *
 * The rule under test: only a definite answer about THIS purchase is final.
 * Reading anything else as "rejected" would leave the purchase unconsumed for
 * good, and Google refunds it after three days, so a server blip would cost a
 * user their contribution being credited.
 */
class PlayVerifyOutcomeTest {

    private fun body(ok: Boolean = false, recorded: Boolean = false, reason: String? = null) =
        PlayVerifyResponse(ok = ok, recorded = recorded, reason = reason)

    @Test
    fun a_200_ok_credits_and_says_whether_it_was_new() {
        assertEquals(PlayVerifyOutcome.Credited(true), PlayVerifyOutcome.from(200, body(ok = true, recorded = true)))
        assertEquals(PlayVerifyOutcome.Credited(false), PlayVerifyOutcome.from(200, body(ok = true, recorded = false)))
    }

    @Test
    fun a_400_with_a_reason_is_final() {
        assertEquals(PlayVerifyOutcome.Rejected("account_mismatch"), PlayVerifyOutcome.from(400, body(reason = "account_mismatch")))
        assertEquals(PlayVerifyOutcome.Rejected("rejected"), PlayVerifyOutcome.from(400, body()))
    }

    @Test
    fun pending_is_not_a_rejection() {
        assertEquals(PlayVerifyOutcome.RetryLater, PlayVerifyOutcome.from(400, body(reason = "purchase_pending")))
    }

    @Test
    fun everything_that_is_not_an_answer_is_asked_again() {
        for (status in listOf(401, 403, 429, 500, 502, 503)) {
            assertEquals(PlayVerifyOutcome.RetryLater, PlayVerifyOutcome.from(status, body(reason = "x")), "HTTP $status")
        }
        assertEquals(PlayVerifyOutcome.RetryLater, PlayVerifyOutcome.from(400, null), "an unreadable 400")
        assertEquals(PlayVerifyOutcome.RetryLater, PlayVerifyOutcome.from(200, null), "an unreadable 200")
        assertEquals(PlayVerifyOutcome.RetryLater, PlayVerifyOutcome.from(200, body(ok = false)), "a 200 that is not ok")
    }
}
