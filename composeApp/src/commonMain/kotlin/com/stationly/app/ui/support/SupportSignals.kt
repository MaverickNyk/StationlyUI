package com.stationly.app.ui.support

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Two things that happen OUTSIDE the home screen and have to reach it.
 *
 * Same shape and the same reasoning as [com.stationly.app.ui.summary.BoardFocus]:
 * a flow the running composition is already collecting, rather than a navigation
 * argument. On iOS the Compose host reads its constructor parameters once, in
 * `makeUIViewController`; `updateUIViewController` is a no-op. Anything threaded
 * through there reaches a cold start and nothing else — and a checkout return
 * almost always finds the app alive, because `SFSafariViewController` was
 * presented over it and never left.
 *
 * Both carry a nonce for the same reason `BoardFocus` does: adding a second
 * board, or contributing twice, has to be a NEW event. Keyed on their payload
 * alone the second would be `equals` to the first and no effect would re-run.
 *
 * Single-threaded by construction — raised from the main thread (a navigation
 * callback, a URL callback) and consumed from a composition effect.
 */
object SupportMoment {

    /** A board the user just added, and the nonce that makes a repeat count. */
    data class BoardAdded(val nonce: Long)

    private val _boardAdded = MutableStateFlow<BoardAdded?>(null)
    val boardAdded: StateFlow<BoardAdded?> = _boardAdded.asStateFlow()

    private var seq = 0L

    /**
     * A NEW station was saved and the user is being returned to the home screen.
     *
     * Raised only for a brand-new board, never for an edit. Editing the lines on
     * a board you already have is maintenance, not an achievement, and following
     * it with "nice, that's 3 boards" would read as the app congratulating you
     * for using it.
     */
    fun boardAdded() {
        _boardAdded.value = BoardAdded(nonce = ++seq)
    }

    /** The home screen has acted on the moment. */
    fun consumeBoardAdded() {
        _boardAdded.value = null
    }
}

/**
 * A return from checkout, delivered by the platform's deep-link callback.
 *
 * `<scheme>://support-money/thanks?tier=t8&amount=800&session_id=cs_...`
 *
 * ## Nothing here is trusted
 * These values come back through the user's own browser and are therefore
 * user-controlled. They decide what the thank-you screen SAYS, and nothing else:
 * the authoritative record is the Stripe webhook, which the backend verifies by
 * signature and writes server-side. Someone who pastes this URL into Safari can
 * award themselves a cosmetic badge on their own phone; they cannot make the
 * server believe a payment happened. That asymmetry is what makes the
 * optimistic, instant thank-you safe to show before the webhook lands.
 */
object SupportReturn {

    data class Thanks(
        val amountMinor: Int,
        val tierId: String,
        val sessionId: String,
        val nonce: Long,
    )

    private val _thanks = MutableStateFlow<Thanks?>(null)
    val thanks: StateFlow<Thanks?> = _thanks.asStateFlow()

    private var seq = 0L

    /**
     * Raise a checkout return.
     *
     * Takes the raw query values because the caller is a URL parser, not a
     * domain object: `amount` arrives as a string and may be absent entirely
     * (the custom-amount link carries no `tier`/`amount`, since the figure is
     * chosen on Stripe's page). An absent or unparseable amount is 0, which the
     * thank-you renders as gratitude without a figure rather than as "£0".
     */
    fun deliver(amount: String?, tier: String?, sessionId: String?) {
        _thanks.value = Thanks(
            amountMinor = amount?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0,
            tierId = tier?.trim().orEmpty(),
            sessionId = sessionId?.trim().orEmpty(),
            nonce = ++seq,
        )
    }

    /** The thank-you has been played. */
    fun consume() {
        _thanks.value = null
    }
}

/**
 * What the platform store said about a purchase the user just started, when it
 * did not end in a payment.
 *
 * ## Why a signal, and why it lands in the sheet
 * The store answers asynchronously, after [SupportViewModel.payTier] has already
 * returned "opened". By then the only thing the user is looking at is the
 * sheet, still open behind the store's own UI, so that is where the answer is
 * shown: in the line under the Pay button, the same slot that says "couldn't
 * open checkout". A toast would appear somewhere else, in the platform's
 * styling, with copy no one can change without a release.
 *
 * Every wording is a home-config key with a compiled fallback, like the rest
 * of the support copy, and follows the same voice rules (no dashes).
 *
 * Raised today by Android's Play Billing. iOS keeps StoreKit's own alerts.
 */
enum class StoreNotice(
    /** The home-config key whose value replaces [fallback]. */
    val key: String,
    /** Compiled-in copy for a backend that does not serve [key] yet. */
    val fallback: String,
    /** Shown in the error colour. Pending is not an error: the money is on its way. */
    val isError: Boolean,
) {
    /** The store could not show the product: not set up, offline, or no store app. */
    UNAVAILABLE(
        key = "support_money.store.unavailable",
        fallback = "We couldn't open the payment right now. Please try again in a moment.",
        isError = true,
    ),

    /** The store showed the sheet and the payment then failed. */
    FAILED(
        key = "support_money.store.failed",
        fallback = "That payment didn't go through. Please try again.",
        isError = true,
    ),

    /** Paid by a method that settles later. Credited automatically once it does. */
    PENDING(
        key = "support_money.store.pending",
        fallback = "Your payment is pending. We'll add it as soon as it's confirmed.",
        isError = false,
    ),

    /**
     * The previous purchase of this tier is still being credited, and the store
     * will not sell the same one-time product again until it is.
     */
    FINISHING_PREVIOUS(
        key = "support_money.store.finishing",
        fallback = "Finishing your last payment first. Give it a moment, then try again.",
        isError = false,
    ),
    ;

    /** This notice's copy: the served wording, or the fallback. */
    fun text(strings: Map<String, String>): String = strings[key]?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * The latest [StoreNotice], from whichever platform store raised it.
 *
 * A plain flow rather than a callback, for the reason [SupportReturn] is one:
 * the store answers from platform code that knows nothing about screens.
 * [SupportViewModel] collects it into its state, and clears it whenever the
 * sheet opens, closes or starts a new payment, so a stale notice never sits
 * under a fresh tap.
 */
object StoreNotices {
    private val _notice = MutableStateFlow<StoreNotice?>(null)
    val notice: StateFlow<StoreNotice?> = _notice.asStateFlow()

    fun post(notice: StoreNotice) {
        _notice.value = notice
    }

    fun clear() {
        _notice.value = null
    }
}
