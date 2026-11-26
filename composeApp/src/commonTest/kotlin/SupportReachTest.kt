import com.stationly.app.ui.support.AskTrigger
import com.stationly.app.ui.support.SupportMoneyConfig
import com.stationly.app.ui.support.SupportProgress
import com.stationly.app.ui.support.SupportState
import com.stationly.app.ui.support.SupportTier
import com.stationly.app.ui.support.SupportUiState
import com.stationly.app.ui.support.askTriggerAt
import com.stationly.app.ui.support.askedLongEnoughAgo
import com.stationly.app.ui.whatsnew.WhatsNewContent
import com.stationly.core.model.sdui.SupportMoneyView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 2026-11 reach work: the ask on reaching home, the running-costs line,
 * and the What's new screen's content. Pure functions only, like
 * [SupportMoneyTest].
 */
class SupportReachTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    /** A payable card and a server that has answered "not a supporter". */
    private fun state(
        strings: Map<String, String> = emptyMap(),
        local: SupportState = SupportState(),
        server: SupportMoneyView? = SupportMoneyView(),
    ) = SupportUiState(
        config = SupportMoneyConfig(
            enabled = true,
            tiers = listOf(SupportTier(id = "t8", amountMinor = 800, url = "https://example.test/t8", googleProductId = "p.t8")),
        ),
        strings = mapOf(
            "home.promo.support_money.show" to "true",
            "home.promo.support_money.min_boards" to "1",
            "home.promo.support_money.min_days" to "1",
        ) + strings,
        local = local,
        server = server,
    )

    private val onOpen = mapOf(
        "home.promo.support_money.ask_on_open" to "true",
        "home.promo.support_money.ask_min_days" to "7",
        "home.promo.support_money.ask_every_days" to "14",
    )

    // ── The ask on reaching home ──────────────────────────────────────────

    @Test
    fun the_on_open_ask_is_off_unless_the_server_says_so() {
        // iOS is served `false`, and an older config has no key at all. Either
        // way a regular user reaching home is not asked.
        val used = SupportState(activeDays = 30)
        assertNull(state(local = used).askTriggerAt(now, boardMoment = null, homeMoment = 2))
        assertNull(
            state(strings = onOpen + ("home.promo.support_money.ask_on_open" to "false"), local = used)
                .askTriggerAt(now, null, 2),
        )
    }

    @Test
    fun a_regular_user_is_asked_on_reaching_home_once_they_have_used_it_for_a_week() {
        assertNull(state(onOpen, SupportState(activeDays = 6)).askTriggerAt(now, null, 1))
        assertEquals(AskTrigger.ON_OPEN, state(onOpen, SupportState(activeDays = 7)).askTriggerAt(now, null, 1))
    }

    @Test
    fun nobody_with_no_boards_is_asked_on_opening() {
        assertNull(state(onOpen, SupportState(activeDays = 30)).askTriggerAt(now, null, 0))
    }

    @Test
    fun the_on_open_ask_is_spaced_by_ask_every_days_whatever_the_answer_was() {
        val askedRecently = SupportState(activeDays = 30, lastAskedMs = now - 13 * day)
        assertNull(state(onOpen, askedRecently).askTriggerAt(now, null, 2))
        val askedLongAgo = SupportState(activeDays = 30, lastAskedMs = now - 14 * day)
        assertEquals(AskTrigger.ON_OPEN, state(onOpen, askedLongAgo).askTriggerAt(now, null, 2))
    }

    @Test
    fun maybe_later_and_a_contribution_still_silence_the_on_open_ask() {
        val quiet = SupportState(activeDays = 30, quietUntilMs = now + day)
        assertNull(state(onOpen, quiet).askTriggerAt(now, null, 2))
        val supporter = state(onOpen, SupportState(activeDays = 30), server = SupportMoneyView(isActiveSupporter = true))
        assertNull(supporter.askTriggerAt(now, null, 2))
    }

    @Test
    fun the_switches_that_silence_every_ask_silence_this_one() {
        val used = SupportState(activeDays = 30)
        assertNull(state(onOpen + ("home.promo.support_money.show" to "false"), used).askTriggerAt(now, 1, 2))
        val off = state(onOpen, used).copy(config = SupportMoneyConfig(enabled = false))
        assertNull(off.askTriggerAt(now, 1, 2))
        val busy = state(onOpen, used).copy(sheetVisible = true)
        assertNull(busy.askTriggerAt(now, 1, 2))
    }

    @Test
    fun a_board_just_added_still_asks_as_before_and_wins_over_the_on_open_moment() {
        // The original trigger is unchanged: min_boards / min_days, and NOT
        // spaced by ask_every_days.
        val askedYesterday = SupportState(activeDays = 1, lastAskedMs = now - day)
        assertEquals(AskTrigger.BOARD_ADDED, state(onOpen, askedYesterday).askTriggerAt(now, boardMoment = 1, homeMoment = 1))
        assertEquals(AskTrigger.BOARD_ADDED, state(local = SupportState(activeDays = 1)).askTriggerAt(now, 1, null))
    }

    @Test
    fun spacing_treats_never_asked_and_a_clock_that_went_backwards_as_long_enough_ago() {
        assertTrue(SupportState().askedLongEnoughAgo(now, 14))
        assertTrue(SupportState(lastAskedMs = now + day).askedLongEnoughAgo(now, 14))
    }

    // ── The running-costs line ────────────────────────────────────────────

    private fun progress(raised: Int?, extra: Map<String, String> = emptyMap()) = SupportProgress.from(
        buildMap {
            put("support_money.progress.show", "true")
            put("support_money.progress.cost_minor", "6000")
            raised?.let { put("support_money.progress.raised_minor", it.toString()) }
            put("support_money.progress.month", "December")
            putAll(extra)
        },
    )

    @Test
    fun the_running_costs_line_is_off_until_the_server_sends_a_real_cost() {
        assertNull(SupportProgress.from(emptyMap()))
        assertNull(SupportProgress.from(mapOf("support_money.progress.show" to "false", "support_money.progress.cost_minor" to "6000")))
        assertNull(SupportProgress.from(mapOf("support_money.progress.show" to "true", "support_money.progress.cost_minor" to "0")))
    }

    @Test
    fun the_running_costs_line_says_the_honest_thing_for_each_state_of_the_month() {
        val empty = progress(0)!!
        assertTrue(!empty.showBar && "£60" in empty.sentence && "obody" !in empty.sentence, empty.sentence)
        // Below the served threshold: cost only, no figure, never a made-up one.
        assertTrue(!progress(1250)!!.showBar)

        val some = progress(1250, mapOf("support_money.progress.show_raised_from_minor" to "1000"))!!
        assertTrue(some.showBar)
        assertEquals(1250f / 6000f, some.fraction)
        assertTrue("£12.50" in some.sentence && "given" in some.sentence, some.sentence)
        assertTrue("covered" !in some.sentence, "gross money is never called covered")

        // Exactly the cost is not "more than" it.
        assertTrue("more than" !in progress(6000)!!.sentence)

        val met = progress(9000)!!
        assertEquals(1f, met.fraction)
        assertTrue("more than £60" in met.sentence && "covered" !in met.sentence, met.sentence)
    }

    @Test
    fun served_running_costs_copy_wins_over_the_fallback() {
        val p = progress(100, mapOf("support_money.progress.text" to "{raised} of {cost} in {month}", "support_money.progress.show_raised_from_minor" to "1"))!!
        assertEquals("£1 of £60 in December", p.sentence)
    }

    // ── What's new ────────────────────────────────────────────────────────

    @Test
    fun whats_new_falls_back_to_true_words_when_nothing_is_served() {
        val c = WhatsNewContent.from(emptyMap())
        assertTrue(c.items.size >= 3)
        assertEquals("Got it", c.done)
    }

    @Test
    fun whats_new_reads_its_rows_from_the_server_and_survives_a_broken_list() {
        val served = WhatsNewContent.from(
            mapOf("whats_new.v2.items.json" to """[{"icon":"sync","title":"One","body":"b"},{"title":""}]"""),
        )
        assertEquals(listOf("One"), served.items.map { it.title })
        val broken = WhatsNewContent.from(mapOf("whats_new.v2.items.json" to "not json"))
        assertTrue(broken.items.size >= 3)
    }

    @Test
    fun the_compiled_in_copy_follows_the_voice_rules() {
        val c = WhatsNewContent.from(emptyMap())
        val all = listOf(c.title, c.subtitle, c.support, c.cta, c.done) +
            c.items.flatMap { listOf(it.title, it.body) } +
            listOfNotNull(progress(0)?.sentence, progress(100)?.sentence, progress(9000)?.sentence)
        for (s in all) {
            assertTrue(!s.contains('–') && !s.contains('—'), "no en/em dash: $s")
            assertTrue(!s.contains("donat", ignoreCase = true) && !s.contains("coffee", ignoreCase = true), "voice: $s")
        }
    }
}
