package com.stationly.app.ui.support

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The server-driven payload behind every "support Stationly" surface.
 *
 * Mirrors `SupportMoneyConfigService.getSupportMoneyConfig()` in the backend,
 * field for field. It arrives as a JSON **string** under one key of the
 * home-config map (`support_money.card.json`) rather than as a structured SDUI
 * component, so that the whole feature costs `core/commonMain` — shared with the
 * frozen Android app — no new type. See `docs/SUPPORT_FEATURE_HANDOVER.md`.
 *
 * ## Everything here is copy the backend can change without an app release
 * Amounts, labels, the reward-screen script, the badge window. The client is a
 * renderer; it decides nothing about what the card says. The one thing it does
 * decide is *when* to show the banner, because that depends on device-local
 * history the server deliberately does not hold (see [SupportStore]).
 *
 * ## Why a fallback exists at all
 * The first launch on a cold install renders before any SDUI fetch returns, and
 * an offline launch never gets one. [SupportMoneyConfigDefaults.FALLBACK] is a
 * copy of the backend's own default with `enabled = false`, so the honest
 * outcome of "we could not ask the server" is that nothing is shown — never a
 * pay button with no URL behind it.
 */
@Serializable
data class SupportMoneyConfig(
    val type: String = "support_money_card",
    val id: String = "support_main",
    /** Master switch. `false` → render nothing, anywhere. */
    val enabled: Boolean = false,
    /** Abstract glyph token: heart | coffee | pint | ticket | slice. */
    val icon: String = "heart",
    val heading: String = "",
    val body: String = "",
    @SerialName("impact_line") val impactLine: String = "",
    @SerialName("board_hero") val boardHero: List<SupportLine> = emptyList(),
    val tiers: List<SupportTier> = emptyList(),
    val currency: String = "GBP",
    @SerialName("default_tier_id") val defaultTierId: String = "",
    @SerialName("custom_amount") val customAmount: SupportCustomAmount = SupportCustomAmount(),
    val cta: SupportCta = SupportCta(),
    val thanks: SupportThanks = SupportThanks(),
    val badge: SupportBadge = SupportBadge(),
) {
    /** The tier the sheet opens on: the server's default, else the first. */
    val defaultTier: SupportTier?
        get() = tiers.firstOrNull { it.id == defaultTierId } ?: tiers.firstOrNull()

    /** True when there is something the user can actually pay through. */
    val isPayable: Boolean
        get() = enabled && (tiers.any { it.checkoutUrl.isNotBlank() } || cta.urlOneoff.isNotBlank())
}

@Serializable
data class SupportLine(val l: String = "", val r: String = "")

@Serializable
data class SupportTier(
    val id: String = "",
    @SerialName("amount_minor") val amountMinor: Int = 0,
    val label: String = "",
    /** The sentence shown under the ladder while this rung is selected. */
    val hint: String = "",
    /**
     * The thank-you for THIS amount, on the reward screen. `{amount}` fills in.
     *
     * Per-tier because gratitude lands harder when it names the specific thing
     * the specific amount paid for. [SupportThanks.note] is the fallback, and
     * what a custom amount always gets.
     */
    val thanks: String = "",
    /**
     * This tier's own checkout link. A Stripe Payment Link carries its price in
     * the link rather than in a query parameter, so each amount is its own URL —
     * which is what makes picking £8 and paying £8 a single tap.
     *
     * Served with `?client_reference_id={uid}` already on it. Blank until the
     * operator creates the link, in which case [SupportCta.urlOneoff] stands in.
     */
    val url: String = "",
) {
    /** Non-blank checkout URL for this tier, or "" if it has none of its own. */
    val checkoutUrl: String get() = url.trim()
}

@Serializable
data class SupportCustomAmount(
    val enabled: Boolean = false,
    @SerialName("min_minor") val minMinor: Int = 100,
    @SerialName("max_minor") val maxMinor: Int = 50_000,
    val hint: String = "",
)

@Serializable
data class SupportCta(
    /** `native_pay` → the platform's own sheet appears on the checkout page. */
    val method: String = "native_pay",
    val label: String = "Pay {amount}",
    val note: String = "",
    @SerialName("url_oneoff") val urlOneoff: String = "",
    @SerialName("url_monthly") val urlMonthly: String = "",
)

@Serializable
data class SupportThanks(
    @SerialName("title_lines") val titleLines: List<String> = emptyList(),
    @SerialName("board_lines") val boardLines: List<SupportLine> = emptyList(),
    val note: String = "",
    val confetti: Boolean = true,
)

/**
 * The Supporter mark.
 *
 * **No `durationDays`, deliberately.** The client does not decide who is a
 * supporter: `supportMoney.isActiveSupporter` on the profile response does,
 * computed server-side against a window that is never sent. Carrying a copy of
 * that window here would be a second authority on a one-authority question, and
 * the two would disagree the moment an operator moved it. It also took the day
 * count out of the copy, which is the right outcome twice over — "Supporter for
 * 30 days" was a countdown on a gift, and it read like a subscription about to
 * lapse.
 */
@Serializable
data class SupportBadge(
    val label: String = "Supporter",
    @SerialName("show_on_home") val showOnHome: Boolean = false,
)

object SupportMoneyConfigDefaults {

    /** The home-config key carrying the whole payload as a JSON string. */
    const val CARD_KEY = "support_money.card.json"

    /**
     * What renders before the first successful SDUI fetch, and forever if the
     * server is unreachable.
     *
     * `enabled = false` on purpose. A fallback whose job is to be shown when we
     * could not ask the server must not assert that a paid feature is live — it
     * has no checkout URLs to offer, and a support card with a dead button is
     * worse than no support card. It exists so the parse path has a total
     * result, not so the feature can run without a backend.
     */
    val FALLBACK = SupportMoneyConfig(
        enabled = false,
        icon = "heart",
        heading = "Keep Stationly free",
        body = "We work hard to keep Stationly fast, free and completely ad free. " +
            "No adverts, no tracking, and nothing locked behind a paywall. " +
            "Servers and live data cost real money every month, so if Stationly " +
            "has earned its place on your phone, a little support keeps it running.",
        impactLine = "Every contribution goes straight into running costs. " +
            "Nothing gets unlocked, because nothing is locked.",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Read the card out of the home-config string map.
     *
     * Total by construction: a missing key, malformed JSON, or a payload from a
     * newer backend that this build cannot fully model all end at [FALLBACK]
     * rather than at an exception on the home screen's composition path. A
     * config that parses but names no payable tier is also treated as absent —
     * `isPayable` is what every surface actually gates on.
     */
    fun parse(homeConfig: Map<String, String>): SupportMoneyConfig {
        val raw = homeConfig[CARD_KEY]?.takeIf { it.isNotBlank() } ?: return FALLBACK
        return runCatching { json.decodeFromString<SupportMoneyConfig>(raw) }.getOrElse { FALLBACK }
    }
}

/**
 * The server's abstract `icon` token as something renderable.
 *
 * The payload names a metaphor, not an asset, so the heart can become a pint
 * with a redeploy. An emoji renders every token without shipping five images
 * for four that will never be used.
 *
 * The glyph is 14sp and up everywhere it appears. The one place a support mark
 * has to render smaller than that is the badge on the user's avatar, which is
 * a drawn vector for exactly that reason: an emoji at 9sp inside a 16dp ring is
 * a smudge on every device and a different smudge on each one.
 */
internal fun iconGlyph(icon: String): String =
    when (icon.lowercase()) {
        "coffee" -> "☕"
        "pint" -> "🍺"
        "ticket" -> "🎫"
        "slice" -> "🍕"
        // Amber rather than red, because every other accent in the app is the
        // brand amber and a red heart would be the one place that is not. It is
        // also the default for an unrecognised token: a metaphor added
        // server-side should look slightly plain on an old build, never leave a
        // blank circle.
        else -> "💛"
    }

/**
 * Money as the user reads it: 800 → "£8", 850 → "£8.50".
 *
 * Whole amounts lose the ".00" deliberately. Every rung of the ladder is a whole
 * number of pounds, and "£8.00" on a button reads like a checkout total —
 * priced, itemised, owed. "£8" reads like what it is, which is an amount the user
 * is choosing to give. The decimal comes back the moment an amount needs it, so
 * a custom £7.50 is never silently rounded.
 */
fun formatMoney(amountMinor: Int, currency: String = "GBP"): String {
    val symbol = when (currency.uppercase()) {
        "GBP" -> "£"
        "USD" -> "$"
        "EUR" -> "€"
        else -> ""
    }
    val whole = amountMinor / 100
    val pence = amountMinor % 100
    val amount = if (pence == 0) "$whole" else "$whole.${pence.toString().padStart(2, '0')}"
    return "$symbol$amount"
}

/**
 * Fill the `{token}` placeholders the payload's strings carry.
 *
 * The server writes copy like `"Pay {amount}"` and `"That's {n} boards. Nice."`
 * precisely so the wording can change without a release; substitution has to
 * happen here because only the client knows the selected amount and the board
 * count. An unknown token is left as-is rather than blanked — a visible
 * `{whatever}` is a bug report, a silently empty sentence is not.
 */
fun String.fillTokens(vararg pairs: Pair<String, String>): String {
    var out = this
    for ((token, value) in pairs) out = out.replace("{$token}", value)
    return out
}
