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
