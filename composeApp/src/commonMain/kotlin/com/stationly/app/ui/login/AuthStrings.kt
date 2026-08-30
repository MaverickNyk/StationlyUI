package com.stationly.app.ui.login

import com.stationly.core.config.RemoteConfig

/**
 * Everything the auth flow can say to a user, and the one rule it enforces on
 * their password.
 *
 * ## Why this is server-driven when the rest of the flow's chrome is not
 * The login screen's LAYOUT already comes down from `/sdui/app/login`. Its
 * failures did not, and failures are the half that actually needs to move:
 * layout is decided once by a designer, while error wording is rewritten after
 * watching real people misread it. Every message below was a string literal in
 * [LoginViewModel], which meant that changing "No account found with this email"
 * — the single most common thing a confused user sees — required an App Store
 * release.
 *
 * The MAPPING stays in the client. Which Firebase code means which message is a
 * behavioural fact about Firebase, not a preference, and a server that could
 * re-point `wrong-password` at the "account not found" text could only ever make
 * the app lie. The server owns the words; the client owns which words apply.
 *
 * ## Every accessor goes through [RemoteConfig.text]
 * So a served blank is treated as absent rather than honoured. A config typo on
 * an error message is uniquely bad: it renders an empty error box on the screen
 * where the user is already stuck, with no way to tell whether something went
 * wrong or nothing did.
 */
class AuthStrings(private val map: Map<String, String> = emptyMap()) {

    private fun s(key: String, default: String) =
        RemoteConfig.text(map, key, default, maxLen = MAX_MESSAGE_LEN)

    // ── Sign-in and registration failures, keyed by what Firebase reports ──

    val wrongPassword get() = s(
        "auth.error.wrong_password",
        "Incorrect email or password. Try again or reset your password.",
    )
    val userNotFound get() = s(
        "auth.error.user_not_found",
        "No account found with this email. Create an account to get started.",
    )
    val emailInUse get() = s(
        "auth.error.email_in_use",
        "This email is already registered. Sign in instead.",
    )
    val weakPassword get() = s(
        "auth.error.weak_password",
        "Please use a stronger password (at least $passwordMinLength characters).",
    )
    val tooManyRequests get() = s(
        "auth.error.too_many_requests",
        "Too many failed attempts. Please wait a moment and try again.",
    )
    val noNetwork get() = s(
        "auth.error.no_network",
        "No internet connection. Check your connection and try again.",
    )
    val generic get() = s(
        "auth.error.generic",
        "Something went wrong. Please try again.",
    )

    // ── Reaching us at all ──

    val backendUnreachable get() = s(
        "auth.error.backend_unreachable",
        "Could not connect to Stationly servers.",
    )

    /**
     * Shown after Firebase accepted the credentials but our own sync failed, at
     * which point the client has already signed back out. Distinct from
     * [backendUnreachable] because the user did nothing wrong and their password
     * was fine — telling them "could not connect" alone invites them to retype a
     * password that was correct.
     */
    val syncRollback get() = s(
        "auth.error.sync_rollback",
        "We couldn't reach our servers to finish signing you in. " +
            "Please check your connection and try again.",
    )

    // ── Password reset ──

    val resetEmailRequired get() = s(
        "auth.error.reset_email_required",
        "Enter your email to receive a reset link.",
    )
    val resetSendFailed get() = s(
        "auth.error.reset_send_failed",
        "Could not send reset link. Please try again.",
    )
    val resetLinkInvalid get() = s(
        "auth.error.reset_link_invalid",
        "Invalid reset link",
    )
    val resetLinkExpired get() = s(
        "auth.error.reset_link_expired",
        "This reset link has expired. Please request a new one.",
    )
    val resetFailed get() = s(
        "auth.error.reset_failed",
        "Could not reset password. Please try again.",
    )

    // ── Email verification ──

    val sessionExpired get() = s(
        "auth.error.session_expired",
        "Your session expired. Please sign in again.",
    )
    val stillUnverified get() = s(
        "auth.error.still_unverified",
        "Still not verified. Tap the link in the email and try again.",
    )
    val resendFailed get() = s(
        "auth.error.resend_failed",
        "Couldn't resend right now. Please try again in a minute.",
    )

    // ── Account removed elsewhere ──

    val accountRemoved get() = s(
        "auth.notice.account_removed",
        "Your account was deleted on another device, so you've been signed out here.",
    )

    // ── The one rule, not just its wording ──

    /**
     * Minimum password length, floored at Firebase's own six.
     *
     * Serving a value BELOW what Firebase enforces would produce the worst
     * possible outcome: the client accepts the password, the network call fails
     * with `weak-password`, and the user is told their password is too short by
     * a form that just told them it was fine. The floor makes that unreachable
     * whatever the payload says.
     */
    val passwordMinLength: Int get() =
        RemoteConfig.int(map, "auth.password.min_length", default = 6, min = 6, max = 64)

    /** The inline validation message, with the served length substituted. */
    val passwordTooShort: String get() =
        s("auth.error.password_too_short", "Password must be at least {n} characters")
            .replace("{n}", passwordMinLength.toString())

    companion object {
        /**
         * An error box is a few lines on a phone. Past this a served message is
         * far more likely to be a mistake than a considered sentence, and a
         * runaway value would push the form's buttons off the screen.
         */
        const val MAX_MESSAGE_LEN = 240

        /** Used before any config has loaded, and whenever one cannot be. */
        val DEFAULT = AuthStrings()
    }
}
