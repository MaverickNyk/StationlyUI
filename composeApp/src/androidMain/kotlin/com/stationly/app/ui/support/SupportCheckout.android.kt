package com.stationly.app.ui.support

/**
 * Android takes a contribution through Google Play Billing ([PlayBilling]),
 * never a web checkout. See [checkoutSupported] in `commonMain`.
 *
 * ## Why Play Billing (Q1, answered)
 * Google Play's payments policy treats a tip to the developer as a digital
 * purchase, which must go through Play's own billing; the exemption for external
 * checkout covers registered charities only, and Stationly is not one. So this
 * mirrors iOS's StoreKit route rather than the website's Stripe one.
 *
 * ## Still "built and off" until the backend says otherwise
 * This is `true` so the platform CAN take a payment. Whether it is OFFERED is
 * `SupportMoneyConfig.enabled`, which the backend serves from
 * `SUPPORT_MONEY_ANDROID_ENABLED`, off until the Play products exist. Both have
 * to agree before an Android user sees a tier ladder.
 */
actual val checkoutSupported: Boolean = true

/**
 * A no-op, deliberately. Android never opens a web checkout (see the class note
 * on [PlayBilling]); the backend sends Android no Stripe links, and this is the
 * second guard behind that.
 */
actual fun openCheckout(url: String) {
    // Intentionally empty.
}

actual fun dismissCheckout() {
    // Nothing is ever presented; Play's sheet dismisses itself.
}

actual fun startNativePurchase(productId: String, amountMinor: Int, tierId: String): Boolean =
    PlayBilling.purchase(productId, amountMinor, tierId)

actual fun nativeProductId(tier: SupportTier): String = tier.googleProductId

actual fun resumeNativePurchases() = PlayBilling.resume()
