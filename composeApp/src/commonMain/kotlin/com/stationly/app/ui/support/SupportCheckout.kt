package com.stationly.app.ui.support

/**
 * How a contribution is taken, per platform. Read this first.
 *
 * | Platform | Route                         | Entry point                |
 * |----------|-------------------------------|----------------------------|
 * | iOS      | StoreKit 2 (App Store)        | [startNativePurchase]      |
 * | Android  | Play Billing (Google Play)    | [startNativePurchase]      |
 * | Website  | Stripe Payment Links (JS)     | not this code              |
 *
 * Both store apps MUST take tips through their store (App Store 3.1.1, Play's
 * payments policy; decision D11), so the web-checkout seam below is dormant in
 * both apps: the backend serves them no Stripe links, and both actuals of
 * [openCheckout] are no-ops. It stays because [SupportViewModel.payTier] falls
 * back to it for a client that names no platform, and because a third client
 * without a store would use it.
 *
 * Open a checkout page in a real browser.
 *
 * ## Why this is not [com.stationly.app.ui.common.LocalOpenUrl]
 * Every other link in the app goes through `LocalOpenUrl`, which keeps http(s)
 * inside Stationly's own `InAppBrowserScreen`, a web view. That is the right
 * home for an article and the wrong one for a checkout: Apple Pay and Google
 * Pay do not render in an embedded web view, so a one-tap contribution would
 * degrade into a card form.
 *
 * ## Attribution
 * The URL arrives with `?client_reference_id={uid}` already on it, from the
 * server. [checkoutUrlFor] does the substitution. Opening a checkout without it
 * still takes the money and reaches the webhook with nobody to credit, which
 * can only be undone by a human reading the Stripe dashboard, so the
 * substitution is not optional and [checkoutUrlFor] refuses to produce a URL
 * that still contains the placeholder.
 */
expect fun openCheckout(url: String)

/**
 * Whether this platform can actually complete a payment.
 *
 * ## Why a capability and not just a config flag
 * `SupportMoneyConfig.enabled` comes from the BACKEND, so it can be turned on
 * for every client at once with no release. Gating only on it would let one
 * config change put a tier ladder in front of a platform with nothing behind
 * its button. So the surfaces gate on `SupportMoneyConfig.isOfferable`, which
 * is the config AND this: an operator's switch and a platform that can honour
 * it (decision D8).
 *
 * True on iOS (StoreKit) and on Android (Play Billing, since AV2-7.4). On
 * Android the backend switch is `SUPPORT_MONEY_ANDROID_ENABLED`.
 */
expect val checkoutSupported: Boolean

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
 * Start a purchase in the platform store: StoreKit 2 on iOS, Play Billing on
 * Android.
 *
 * Returns true when the store took over. The store's own sheet, and on Android
 * any [StoreNotice] it raises, are the answer from there. False means it could
 * not even start (no store, nobody signed in), and the sheet says so at once.
 */
expect fun startNativePurchase(productId: String, amountMinor: Int, tierId: String): Boolean

/**
 * This platform's store product for [tier]: the App Store id on iOS, the Play id
 * on Android. Blank when the server sent none, which [startNativePurchase] is
 * then never asked about.
 */
expect fun nativeProductId(tier: SupportTier): String

/**
 * Finish any store purchase a previous run left unfinished.
 *
 * Android only in practice: a Play purchase is not credited until our server
 * has verified it, and the app can die between Play's sheet closing and that
 * call. Play keeps such a purchase unconsumed, and this sends it again. iOS
 * already does the same from StoreKit's `Transaction.updates` in Swift.
 * Idempotent and cheap to call more than once.
 */
expect fun resumeNativePurchases()

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
