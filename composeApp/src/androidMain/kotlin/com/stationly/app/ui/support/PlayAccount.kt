package com.stationly.app.ui.support

import java.security.MessageDigest

/**
 * How a Play purchase is tied to a Stationly account. Pure, so it is tested
 * directly, against the same vector the backend's test uses.
 */
internal object PlayAccount {

    /**
     * The id Play carries as the purchase's `obfuscatedAccountId`.
     *
     * `sha256(uid)` as lowercase hex: 64 characters, which is Play's limit, and
     * not the uid itself, which Play asks apps not to send. The backend
     * (`googlePlayIAPService.playAccountId`) computes the same value and refuses
     * a token whose binding does not match the signed-in account. That check is
     * what stops one account claiming another's purchase, so the two
     * implementations must never drift: see `PlayAccountTest`.
     */
    fun idFor(uid: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * The id this purchase is recorded under on the device, matching the
     * server's `google_<orderId>` ledger row. Play always sends an order id for
     * a completed purchase; the token suffix only covers a malformed one, so
     * the device's duplicate check still has something stable to key on.
     */
    fun sessionId(orderId: String?, purchaseToken: String): String =
        "google_" + (orderId?.takeIf { it.isNotBlank() } ?: purchaseToken.takeLast(16))
}
