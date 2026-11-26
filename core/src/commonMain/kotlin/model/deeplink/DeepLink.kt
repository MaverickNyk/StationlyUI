package com.stationly.core.model.deeplink

/**
 * Where an incoming link sends the app.
 *
 * ## Why this is in `core` and not in the host
 * It was in the host — `MainActivity.handleDeepLink`, as a `when` over
 * `uri.scheme` and `uri.host`, using `android.net.Uri`. That is fine right up
 * until there are two hosts: AV2-3.5 replaces the v1 Activity with one hosting
 * the shared UI, and iOS has its own copy of the same routing in `onOpenURL`.
 * Three implementations of one four-row table is how the three drift.
 *
 * ## Why the scheme is a PARAMETER and not a constant
 * This is the whole reason the extraction is worth doing now. v1 hardcodes
 * `"stationly"` at every one of the four call sites, and iOS shipped exactly
 * that bug: a hardcoded scheme meant every link into the STAGING app was
 * silently ignored. Nothing errored and no log said anything — taps simply did
 * nothing, for two days.
 *
 * A build's scheme is a fact about the build, so it is passed in. Prod is
 * `stationly`; staging is `stationly-staging`.
 */
sealed interface DeepLinkRoute {

    /** `://home` — open the app, and nothing more. */
    data object Home : DeepLinkRoute

    /**
     * `://auth` — the password reset finished; tell the user they can sign in.
     *
     * Named `auth` rather than anything descriptive because that is the host on
     * links already published in emails we do not control. Renaming it here
     * would break every link already in somebody's inbox.
     */
    data object PasswordResetComplete : DeepLinkRoute

    /** `://verified?oobCode=…` — carry the code to the verification screen. */
    data class VerifyEmail(val oobCode: String) : DeepLinkRoute

    /** `://reset?oobCode=…` — carry the code to the new-password screen. */
    data class ResetPassword(val oobCode: String) : DeepLinkRoute

    /**
     * Nothing to do. The correct answer for a foreign scheme, an unknown host,
     * and — importantly — a known host arriving WITHOUT the code it needs.
     *
     * v1 checked `isNullOrBlank` before navigating, so a bare `://reset` did
     * nothing rather than opening a reset screen with no code in it. Keep that:
     * a screen that cannot complete is worse than no screen.
     */
    data object Unhandled : DeepLinkRoute
}

/**
 * Parse an incoming link against the scheme THIS build answers to.
 *
 * Hand-rolled rather than delegated to a URL type, for two reasons: `core`'s
 * common code has no platform URL parser, and the inputs are entirely ours —
 * four hosts declared in our own manifest and `Info.plist`. A general parser
 * would be more code defending against inputs that cannot arrive.
 */
fun parseDeepLink(url: String, scheme: String): DeepLinkRoute {
    val separator = url.indexOf("://")
    if (separator <= 0) return DeepLinkRoute.Unhandled
    // Case-insensitive: schemes are, and a link typed by hand or rewritten by a
    // mail client can arrive capitalised.
    if (!url.substring(0, separator).equals(scheme, ignoreCase = true)) {
        return DeepLinkRoute.Unhandled
    }

    val body = url.substring(separator + 3)
    val query = body.substringAfter('?', "")
    // The host ends at the first `/`, `?` or `#`, whichever comes first.
    val host = body.substringBefore('?').substringBefore('#').substringBefore('/')

    fun code(): String? = query.split('&')
        .firstOrNull { it.startsWith("oobCode=") }
        ?.removePrefix("oobCode=")
        ?.takeIf { it.isNotBlank() }

    return when (host.lowercase()) {
        "home" -> DeepLinkRoute.Home
        "auth" -> DeepLinkRoute.PasswordResetComplete
        "verified" -> code()?.let(DeepLinkRoute::VerifyEmail) ?: DeepLinkRoute.Unhandled
        "reset" -> code()?.let(DeepLinkRoute::ResetPassword) ?: DeepLinkRoute.Unhandled
        else -> DeepLinkRoute.Unhandled
    }
}
