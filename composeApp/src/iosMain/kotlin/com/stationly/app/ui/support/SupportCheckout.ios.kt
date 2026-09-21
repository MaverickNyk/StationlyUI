package com.stationly.app.ui.support

/**
 * On iOS, all support contributions are handled natively via Apple StoreKit 2
 * in compliance with App Store Review Guidelines 3.1.1 and 3.2.1(vii).
 *
 * External web checkout (Stripe) is not used on iOS.
 */
actual fun openCheckout(url: String) {
    // No-op on iOS: all purchases flow through native StoreKit 2 (startNativePurchase).
}

actual fun dismissCheckout() {
    // No-op on iOS: StoreKit 2 manages its own native modal sheet lifecycle.
}

/**
 * Swift-to-Kotlin bridge for StoreKit 2 in-app purchases.
 * Swift registers its handler at app launch (via `StoreKitBridge.shared.handler = ...`).
 */
object StoreKitBridge {
    var handler: ((productId: String, amountMinor: Int, tierId: String) -> Unit)? = null

    fun purchase(productId: String, amountMinor: Int, tierId: String): Boolean {
        val h = handler ?: return false
        h(productId, amountMinor, tierId)
        return true
    }
}

actual fun startNativePurchase(productId: String, amountMinor: Int, tierId: String): Boolean {
    return StoreKitBridge.purchase(productId, amountMinor, tierId)
}
