package com.stationly.app.ui.support

/**
 * Not wired on Android — and after the cutover, that needs a stronger guard than
 * a comment.
 *
 * ## What this file used to say, and why it stopped being true
 * It said `composeApp`'s Android target existed only so the shared UI kept
 * compiling, that the shipping app was `:android:app` with its own screens, and
 * that no support surface could reach this because "an Android build has neither
 * `enabled` nor a checkout URL". AV2-3.5 ended all three: these composables ARE
 * the Android app, and `enabled` comes from the backend, not the build. One
 * config change — no release, both platforms at once — and Android users would
 * have had the banner, the sheet and the tier ladder, every one of them ending
 * at the empty function below.
 *
 * ## The position now
 * D4 says the money surface ships **built and off** on Android, and Q1 has not
 * decided the Play policy route (Play Billing, or a charity exemption for
 * external checkout). Until it does, [checkoutSupported] is false and every
 * surface gates on it, so "off" is a property of the platform rather than of a
 * document somebody might edit.
 *
 * When Q1 is answered, the whole change is here: `CustomTabsIntent` over the
 * host Activity for an external route, or Play Billing for the other — and one
 * boolean.
 */
actual val checkoutSupported: Boolean = false

/**
 * A no-op rather than a throw.
 *
 * Unreachable while [checkoutSupported] is false, and this is the wrong place to
 * discover that something got past the gate: it is a money path, and a crash
 * there is worse than silence for a user who has just decided to give something.
 */
actual fun openCheckout(url: String) {
    // Intentionally empty — see the KDoc.
}

actual fun dismissCheckout() {
    // Nothing is ever presented — see the note on `openCheckout`.
}
