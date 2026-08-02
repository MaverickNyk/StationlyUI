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

/**
 * Has the user actually placed a Stationly widget?
 *
 * `null` means "not probed yet" — the caller must NOT decide anything from
 * it (showing the "add a widget" promo to someone who already has one, for
 * the half-second before the probe lands, is worse than showing nothing).
 *
 * Android reads `AppWidgetManager.getAppWidgetIds` directly. iOS's
 * equivalent (`WidgetCenter.getCurrentConfigurations`) is Swift-only, so the
 * Swift host probes it and drops the answer in the App Group — same
 * Kotlin↔Swift channel the auth identity keys and widget payload already use.
 */
expect fun hasHomeScreenWidget(): Boolean?

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

/**
 * Installed app version, as the marketing version string ("1.0", "1.2.3").
 * Feeds the SDUI `app.minVersion` force-update gate, which compares against
 * this with [com.stationly.app.ui.util.isVersionBelow].
 */
expect fun appVersionName(): String
