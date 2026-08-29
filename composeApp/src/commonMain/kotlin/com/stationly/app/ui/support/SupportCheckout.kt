package com.stationly.app.ui.support

/**
 * Open a checkout page in a real browser.
 *
 * ## Why this is not [com.stationly.app.ui.common.LocalOpenUrl]
 * Every other link in the app goes through `LocalOpenUrl`, which keeps http(s)
 * inside Stationly's own `InAppBrowserScreen` — a WKWebView. That is the right
 * home for an article or a fares page and the wrong one for this: **Apple Pay
 * does not render in an embedded web view.** A checkout opened there degrades
 * silently to card-entry-by-hand, which turns a one-tap contribution into a
 * form, at the exact moment the user has decided to give money.
 *
 * So this seam exists to say "a real browser, with the platform's own payment
 * sheet available" — `SFSafariViewController` on iOS, Custom Tabs on Android, a
 * new tab on web. It is one call rather than a general-purpose opener because
 * one call is all the money path needs, and a wider seam would eventually get
 * used for something that should have stayed in the app.
 *
 * ## Attribution
 * The URL arrives with `?client_reference_id={uid}` already on it, from the
 * server. [checkoutUrlFor] does the substitution. Opening a checkout without it
 * still takes the money and reaches the webhook with nobody to credit, which
 * can only be undone by a human reading the Stripe dashboard — so the
 * substitution is not optional and [checkoutUrlFor] refuses to produce a URL
 * that still contains the placeholder.
 */
expect fun openCheckout(url: String)

/**
 * Close the checkout browser, if one is open.
 *
 * ## Why this is not optional
 * The deep link back from checkout ACTIVATES the app; it does not dismiss what
 * the app has presented. Without this the user pays, the return link fires, the
 * thank-you renders — all of it underneath a Safari sheet still showing the
 * bounce page. From the outside that is indistinguishable from the feature not
 * working, and tapping the page's own "Back to Stationly" button just fires the
 * same link again into the same invisible success.
 *
 * Measured on device before this existed: seven deliveries of one payment, each
 * one processed correctly, none of them visible.
 *
 * Safe to call when nothing is presented.
 */
expect fun dismissCheckout()

/**
 * Fill a checkout template with the signed-in account id.
 *
 * Returns null — meaning "do not open anything" — when there is no uid or no
 * URL. That is a deliberate refusal rather than a best-effort open: a checkout
 * that cannot be attributed is worse than no checkout, because the user pays
 * and gets nothing for it, and neither they nor we find out until they ask
 * where their badge is.
 */
fun checkoutUrlFor(template: String, uid: String?): String? {
    val url = template.trim()
    if (url.isBlank()) return null
    if (!url.contains(UID_TOKEN)) {
        // A link the operator wired without the placeholder. Nothing to
        // substitute, and refusing it would break a perfectly working manual
        // setup — but it cannot be attributed, so it is a last resort.
        return url
    }
    val id = uid?.trim().orEmpty()
    if (id.isEmpty()) return null
    return url.replace(UID_TOKEN, urlEncode(id))
}

private const val UID_TOKEN = "{uid}"

/** RFC 3986 unreserved set — the bytes that never need escaping. */
private val UNRESERVED: Set<Char> =
    (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_', '.', '~')).toSet()

private const val HEX = "0123456789ABCDEF"

/**
 * Percent-encode a uid for a query value.
 *
 * Firebase uids are 28 alphanumerics and need nothing, but this is the one value
 * in the app that travels to a payment processor and comes back as the key to
 * someone's account — so it is encoded on principle rather than on the evidence
 * of today's format.
 *
 * Byte-wise over UTF-8, and the ASCII test is done on the BYTE before it becomes
 * a Char: a `Byte` is signed in Kotlin, so any multi-byte character yields a
 * negative value and `toChar()` on it produces something that is not the
 * character at all. Checking the byte first is what keeps that out of the
 * comparison rather than relying on it to miss.
 */
private fun urlEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val v = byte.toInt() and 0xFF
        val c = v.toChar()
        if (v < 0x80 && c in UNRESERVED) append(c)
        else append('%').append(HEX[v shr 4]).append(HEX[v and 0x0F])
    }
}
