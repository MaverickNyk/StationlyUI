import com.stationly.app.ui.support.SupportEntry
import com.stationly.app.ui.support.SupportMoneyConfigDefaults
import com.stationly.app.ui.support.SupportState
import com.stationly.app.ui.support.SupportUiState
import com.stationly.app.ui.support.checkoutUrlFor
import com.stationly.app.ui.support.contributionCount
import com.stationly.app.ui.support.formatMoney
import com.stationly.app.ui.support.iconGlyph
import com.stationly.app.ui.support.fillTokens
import com.stationly.app.ui.support.SupportStore
import com.stationly.app.ui.support.contributedOptimistically
import com.stationly.core.model.sdui.SupportMoneyEntry
import com.stationly.core.model.sdui.SupportMoneyView
import com.stationly.app.ui.support.mayAskAgain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The support feature's decisions, pinned.
 *
 * Everything here is a pure function of a record and a clock — deliberately, so
 * the rules that decide whether someone is asked for money can be tested without
 * a device, a network, or a composition. The parts that are NOT here (the
 * banner's timers, the browser presentation) are the parts that cannot be
 * asserted off-device, and they are kept as thin as possible for that reason.
 */
class SupportMoneyTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun contributed(atMs: Long, amount: Int = 800, session: String = "") =
        SupportState(contributions = listOf(SupportEntry(atMs, amount, session)))

    // ── The device covers the optimistic gap, and only that ───────────────

    @Test
    fun a_just_returned_checkout_is_believed_without_the_server() {
        // The browser comes back before Stripe's webhook necessarily has. For
        // those few seconds the user has paid and the server does not know, and
        // making them watch a spinner to confirm a thing they just did would be
        // the worst possible place to be accurate at the expense of decent.
        val s = contributed(atMs = now)
        assertTrue(s.contributedOptimistically(now))
        assertTrue(s.contributedOptimistically(now + 60_000))
    }

    @Test
    fun the_devices_own_belief_expires_in_minutes_not_a_month() {
        // The bound that matters. If the payment never actually landed — a
        // return URL pasted by hand, a checkout that failed after redirecting —
        // the mark goes in ten minutes. The old model, which stored a thirty-day
        // `untilMs` on the device, gave that same case a month.
        val s = contributed(atMs = now)
        assertTrue(s.contributedOptimistically(now + SupportStore.OPTIMISTIC_WINDOW_MS - 1))
        assertFalse(s.contributedOptimistically(now + SupportStore.OPTIMISTIC_WINDOW_MS))
        assertFalse(s.contributedOptimistically(now + day))
    }

    @Test
    fun a_device_that_has_never_taken_a_payment_claims_nothing() {
        assertFalse(SupportState().contributedOptimistically(now))
        // And a clock that has gone backwards does not open the window either.
        assertFalse(contributed(atMs = now).contributedOptimistically(now - 1000))
    }

    @Test
    fun the_newest_contribution_is_the_one_that_counts() {
        val s = SupportState(
            contributions = listOf(
                SupportEntry(now - 200 * day, 1200, "cs_old"),
                SupportEntry(now, 400, "cs_new"),
            ),
        )
        assertTrue(s.contributedOptimistically(now), "an old row must not hold the window open")
        assertFalse(s.contributedOptimistically(now + day))
    }

    // ── Who the server says is a supporter ────────────────────────────────

    /**
     * The REAL rule, not a copy of it.
     *
     * This used to spell the condition out again here, which is the one thing a
     * regression test must never do: it pins a rule the app might not be
     * running, so the two can drift apart and the test keeps passing while the
     * product is wrong. `SupportUiState.isSupporterAt` exists precisely so the
     * decision has one implementation and this can call it.
     */
    private fun showsBadge(server: SupportMoneyView?, local: SupportState, nowMs: Long) =
        SupportUiState(server = server, local = local).isSupporterAt(nowMs)

    @Test
    fun an_account_with_no_contributions_shows_no_badge() {
        // THE BUG. The backend OMITS `supportMoney` entirely for an account that
        // has never contributed, so a successful fetch yields null — which the
        // old code could not tell apart from a failed request, and so discarded.
        // A badge that had once been true could never be turned off by the
        // server: it survived deleting every row in Firestore, survived a
        // sign-out, and greeted a brand new account on the same device.
        //
        // A response with no record must project to a definite "no", not to
        // "we do not know".
        val serverSaysNothing = SupportMoneyView()
        assertFalse(serverSaysNothing.isActiveSupporter)
        assertEquals(0, serverSaysNothing.count)
        assertFalse(showsBadge(serverSaysNothing, SupportState(), now))
    }

    @Test
    fun a_signed_out_or_unfetched_state_shows_no_badge_either() {
        // `server = null` means "not asked yet", which is the honest state
        // between launch and the first response. It must not render a badge on
        // its own — only the device's own optimistic window can do that, and
        // only for ten minutes after a payment IT took.
        assertFalse(showsBadge(null, SupportState(), now))
    }

    @Test
    fun the_server_saying_yes_is_enough_on_its_own() {
        // The cross-device case: a contribution made on another phone, with
        // nothing in this device's record at all.
        val server = SupportMoneyView(
            isActiveSupporter = true,
            count = 2,
            entries = listOf(SupportMoneyEntry("cs_1", now, 800, "GBP")),
        )
        assertTrue(showsBadge(server, SupportState(), now))
    }

    @Test
    fun the_server_saying_no_does_not_cancel_a_payment_taken_seconds_ago() {
        // The webhook has not landed yet, so the server honestly says no while
        // the user is looking at their own thank-you screen. The device covers
        // exactly that gap.
        val justPaid = contributed(atMs = now)
        assertTrue(showsBadge(SupportMoneyView(), justPaid, now))
        // And stops covering it once the gap is implausible.
        assertFalse(showsBadge(SupportMoneyView(), justPaid, now + SupportStore.OPTIMISTIC_WINDOW_MS))
    }

    // ── When we may ask ───────────────────────────────────────────────────

    @Test
    fun a_fresh_install_may_be_asked() {
        assertTrue(SupportState().mayAskAgain(now))
    }

    @Test
    fun declining_buys_three_days_of_silence() {
        val s = SupportState(quietUntilMs = now + 3 * day)
        assertFalse(s.mayAskAgain(now))
        assertFalse(s.mayAskAgain(now + 3 * day - 1))
        assertTrue(s.mayAskAgain(now + 3 * day))
    }

    @Test
    fun contributing_silences_the_ask_for_about_as_long_as_the_badge_lasts() {
        // The rule that matters most: asking someone for money three weeks after
        // they gave you some is how a thank-you turns into a subscription prompt.
        // A NAG policy, not a badge decision — the badge is the server's answer,
        // and the banner is additionally suppressed outright while it says yes.
        val s = contributed(atMs = now)
            .copy(quietUntilMs = now + SupportStore.QUIET_AFTER_CONTRIBUTION_DAYS * day)
        assertFalse(s.mayAskAgain(now + 29 * day))
        assertTrue(s.mayAskAgain(now + 30 * day))
    }

    // ── Attribution ───────────────────────────────────────────────────────

    @Test
    fun checkout_url_substitutes_the_uid() {
        assertEquals(
            "https://buy.stripe.com/test_x?client_reference_id=abc123",
            checkoutUrlFor("https://buy.stripe.com/test_x?client_reference_id={uid}", "abc123"),
        )
    }

    @Test
    fun checkout_is_refused_rather_than_opened_unattributed() {
        // Taking money we cannot credit is worse than not taking it: the user
        // pays, gets nothing, and nobody finds out until they ask why.
        assertNull(checkoutUrlFor("https://buy.stripe.com/test_x?client_reference_id={uid}", null))
        assertNull(checkoutUrlFor("https://buy.stripe.com/test_x?client_reference_id={uid}", ""))
        assertNull(checkoutUrlFor("", "abc123"))
    }

    @Test
    fun a_uid_needing_escaping_is_encoded_not_pasted() {
        val url = checkoutUrlFor("https://x/y?client_reference_id={uid}", "a b/c&d")
        assertEquals("https://x/y?client_reference_id=a%20b%2Fc%26d", url)
    }

    // ── Copy ──────────────────────────────────────────────────────────────

    @Test
    fun money_drops_the_decimals_on_whole_amounts_and_keeps_them_otherwise() {
        assertEquals("£8", formatMoney(800))
        assertEquals("£12", formatMoney(1200))
        assertEquals("£7.50", formatMoney(750))
        assertEquals("£0.05", formatMoney(5))
    }

    @Test
    fun an_unknown_token_is_left_visible_rather_than_blanked() {
        // A visible `{whatever}` is a bug report; a silently empty sentence is
        // a wrong sentence nobody notices.
        assertEquals("Pay £8 now {mystery}", "Pay {amount} now {mystery}".fillTokens("amount" to "£8"))
    }

    // ── The payload ───────────────────────────────────────────────────────

    @Test
    fun a_missing_or_broken_card_falls_back_instead_of_throwing() {
        assertEquals(SupportMoneyConfigDefaults.FALLBACK, SupportMoneyConfigDefaults.parse(emptyMap()))
        assertEquals(
            SupportMoneyConfigDefaults.FALLBACK,
            SupportMoneyConfigDefaults.parse(mapOf(SupportMoneyConfigDefaults.CARD_KEY to "{not json")),
        )
    }

    @Test
    fun the_fallback_is_never_payable() {
        // It exists so the parse path is total, not so the feature can run
        // without a backend — it has no checkout URLs behind its button.
        assertFalse(SupportMoneyConfigDefaults.FALLBACK.isPayable)
    }

    @Test
    fun a_real_payload_parses_into_a_payable_card() {
        val json = """
            {"type":"support_money_card","id":"support_main","enabled":true,"icon":"coffee",
             "heading":"Buy me a coffee","body":"b","impact_line":"i",
             "tiers":[{"id":"t8","amount_minor":800,"label":"A round","hint":"","url":"https://buy.stripe.com/test_B?client_reference_id={uid}"}],
             "currency":"GBP","default_tier_id":"t8",
             "custom_amount":{"enabled":true,"min_minor":100,"max_minor":50000,"hint":""},
             "cta":{"method":"native_pay","label":"Pay {amount}","note":"n","url_oneoff":"","url_monthly":""},
             "thanks":{"title_lines":["Thank you"],"board_lines":[],"note":"","confetti":true},
             "badge":{"label":"Supporter","show_on_home":false},
             "unknown_field_from_a_newer_backend":42}
        """.trimIndent()
        val cfg = SupportMoneyConfigDefaults.parse(mapOf(SupportMoneyConfigDefaults.CARD_KEY to json))
        assertTrue(cfg.isPayable, "an unknown field must not cost us the whole card")
        assertEquals(800, cfg.defaultTier?.amountMinor)
        assertEquals("Supporter", cfg.badge.label)
    }

    @Test
    fun an_enabled_card_with_no_links_is_not_payable() {
        // The state the backend ships in before the operator wires Stripe. A
        // button with no URL behind it is worse than no button.
        val json = """{"enabled":true,"tiers":[{"id":"t8","amount_minor":800,"url":""}],
                       "cta":{"url_oneoff":""}}"""
        val cfg = SupportMoneyConfigDefaults.parse(mapOf(SupportMoneyConfigDefaults.CARD_KEY to json))
        assertFalse(cfg.isPayable)
    }

    // ── The voice ─────────────────────────────────────────────────────────

    @Test
    fun the_compiled_in_fallback_obeys_the_same_voice_rules_as_the_server() {
        // The fallback is what renders on a cold, offline first launch, which is
        // the one time nobody is looking at it in review. It drifted before: the
        // server copy was rewritten and the fallback kept saying "buy me a
        // coffee", so the only build that could not be corrected by a redeploy
        // was the one still using the old words.
        //
        // Mirrors `support config: the voice rules hold` in the backend suite.
        val copy = with(SupportMoneyConfigDefaults.FALLBACK) {
            listOf(heading, body, impactLine, cta.label, cta.note) + tiers.flatMap { listOf(it.label, it.hint) }
        }
        for (line in copy) {
            assertFalse(line.contains("coffee", ignoreCase = true), "no coffee metaphor: $line")
            assertFalse(line.contains("donat", ignoreCase = true), "not a donation: $line")
            assertFalse(line.contains('\u2013') || line.contains('\u2014'), "no en/em dash: $line")
        }
    }

    @Test
    fun an_unknown_icon_token_falls_back_to_the_brand_mark_not_the_cup() {
        // The token is server-driven so the metaphor can change without a
        // release. A build that predates a new token must land on the app's own
        // mark, not on whichever metaphor happened to be the default when it
        // shipped.
        assertEquals("💛", iconGlyph("heart"))
        assertEquals("💛", iconGlyph("something_a_newer_backend_invented"))
        assertEquals("☕", iconGlyph("coffee"), "the old token still renders for anyone still serving it")
    }

    @Test
    fun the_reward_screen_thanks_you_for_the_amount_you_actually_gave() {
        // A tier's own `thanks` when the figure matches a rung, and the card's
        // general note otherwise, which is what a custom amount always gets.
        // Naming the specific thing the specific amount paid for is the whole
        // difference between being thanked and being acknowledged.
        val json = """
            {"enabled":true,"tiers":[
              {"id":"t4","amount_minor":400,"label":"A day of departures","hint":"h","thanks":"{amount} keeps a day of departures live."},
              {"id":"t8","amount_minor":800,"label":"A stop with no board","hint":"h","thanks":"{amount} helps a stop with no screen."}],
             "thanks":{"note":"Thank you, genuinely."}}
        """.trimIndent()
        val cfg = SupportMoneyConfigDefaults.parse(mapOf(SupportMoneyConfigDefaults.CARD_KEY to json))
        fun noteFor(amount: Int) = cfg.tiers
            .firstOrNull { it.amountMinor == amount && it.thanks.isNotBlank() }?.thanks
            ?: cfg.thanks.note

        assertEquals("{amount} keeps a day of departures live.", noteFor(400))
        assertEquals("{amount} helps a stop with no screen.", noteFor(800))
        // A custom amount matches no rung and lands on the general note.
        assertEquals("Thank you, genuinely.", noteFor(750))
        // And the amount is filled in for the reader.
        assertEquals("£4 keeps a day of departures live.", noteFor(400).fillTokens("amount" to formatMoney(400)))
    }

    @Test
    fun each_tier_carries_the_note_the_sheet_renders_under_the_ladder() {
        // £4 / £8 / £12 with nothing else on screen leaves the reader holding
        // "why is that one more than this one". The sheet answers it from
        // `hint`, so a payload whose hints are blank silently loses the answer.
        val json = """
            {"enabled":true,"tiers":[
              {"id":"t4","amount_minor":400,"label":"Fixes a bug","hint":"Covers chasing a bug down.","url":"https://x?client_reference_id={uid}"},
              {"id":"t8","amount_minor":800,"label":"Funds a feature","hint":"Builds something small.","url":"https://y?client_reference_id={uid}"}],
             "default_tier_id":"t8"}
        """.trimIndent()
        val cfg = SupportMoneyConfigDefaults.parse(mapOf(SupportMoneyConfigDefaults.CARD_KEY to json))
        assertEquals("Builds something small.", cfg.defaultTier?.hint)
        assertTrue(cfg.tiers.all { it.hint.isNotBlank() })
    }

    @Test
    fun contribution_count_powers_copy_and_nothing_else() {
        val s = SupportState(
            contributions = listOf(
                SupportEntry(now, 400, "cs_a"),
                SupportEntry(now + day, 800, "cs_b"),
            ),
        )
        assertEquals(2, s.contributionCount)
    }

    @Test
    fun the_badge_window_is_not_something_the_client_can_be_told() {
        // The payload has no `duration_days` and the client has no field to put
        // one in. If a backend ever serves it again, this fails rather than the
        // client quietly growing a second opinion about who is a supporter.
        val json = """{"enabled":true,"badge":{"label":"Supporter","duration_days":30,"show_on_home":true}}"""
        val cfg = SupportMoneyConfigDefaults.parse(mapOf(SupportMoneyConfigDefaults.CARD_KEY to json))
        assertEquals("Supporter", cfg.badge.label)
        assertTrue(cfg.badge.showOnHome)
        // A backend still serving `duration_days` must not break the parse, and
        // must not hand the client a window either: the field lands nowhere,
        // because `SupportBadge` has no property for it.
        assertEquals(SupportMoneyConfigDefaults.FALLBACK.badge.copy(showOnHome = true), cfg.badge)
    }
}
