package com.stationly.app.platform

/**
 * OS-level notification authorization, normalised across platforms.
 *
 * Android's `NotificationPermissionEffect` has to keep its own "we asked"
 * flag in SharedPreferences because `checkSelfPermission` cannot tell
 * "denied" apart from "never prompted". iOS reports that distinction
 * natively, so [NOT_DETERMINED] is a real state here rather than a
 * bookkeeping flag.
 */
enum class NotificationAuthState { NOT_DETERMINED, AUTHORIZED, DENIED }

// `hasHomeScreenWidget()` lived here until 2026-08-23, read by exactly one
// caller: the "add a home screen widget" promo. That promo is gone (see
// `SummaryViewModel`), and with it the only question this seam answered.
//
// The Swift probe it read from is NOT gone — `HomeStateProbe` still runs on
// every foreground, because the activity trail derives widget add/remove from
// its snapshot and the extension's refresh ledger reaps deleted widgets
// against it. What went is the one extra App Group key it wrote for the promo.

/** Current OS-level notification authorization. */
expect suspend fun notificationAuthState(): NotificationAuthState

/**
 * Fire the one-time system permission prompt, returning the user's answer.
 * A no-op returning the existing answer once the user has already decided —
 * the OS only ever shows this dialog once per install.
 */
expect suspend fun requestNotificationAuthorization(): Boolean

/** Deep-link to this app's own notification settings page. */
expect fun openAppNotificationSettings()

// ── `appVersionName()` was here ──────────────────────────────────────────
//
// Replaced by `Platform.appVersion()` in `core`. It had to move down a module
// because the version is now read on the NETWORK path — every request carries
// it in `X-Stationly-Client` — and `core` cannot see `composeApp`.
//
// The duplicate was also wrong in one direction: the Android actual returned a
// hardcoded "0", so the About card showed "v0" on Android and any comparison
// against it read as the oldest possible build.
