package com.stationly.app.ui.support

/**
 * Not wired on Android.
 *
 * `composeApp`'s Android target exists so the shared UI keeps compiling for it;
 * the shipping Android app is `:android:app`, which does not depend on this
 * module and has its own screens. Rather than half-implement a Custom Tabs
 * launch that nothing calls and nobody tests, this states the position: the
 * Android money path is a separate piece of work, and when it lands it belongs
 * here as `CustomTabsIntent` over the host activity.
 *
 * A no-op rather than a throw. This is reachable only from a support surface,
 * and every one of those is already gated on `enabled` plus a non-blank
 * checkout URL — neither of which an Android build has. Crashing on a code path
 * that cannot be reached would be a worse answer than doing nothing on one that
 * can.
 */
actual fun openCheckout(url: String) {
    // Intentionally empty — see the KDoc.
}

actual fun dismissCheckout() {
    // Nothing is ever presented — see the note on `openCheckout`.
}
