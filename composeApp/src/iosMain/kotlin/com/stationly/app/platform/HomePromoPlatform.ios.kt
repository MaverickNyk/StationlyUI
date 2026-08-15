package com.stationly.app.platform

import com.stationly.core.platform.IosAppGroup
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
// ObjC CATEGORY method (UIApplication + UIRemoteNotifications) — surfaces as an
// extension function in K/N, so it needs its own import.
import platform.UIKit.registerForRemoteNotifications
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * WidgetKit is a Swift-only framework — `WidgetCenter` has no Objective-C
 * interface, so Kotlin/Native cannot see it at all. The Swift host probes
 * `WidgetCenter.shared.getCurrentConfigurations` on launch/foreground and
 * writes the answer here (see `iosApp/iosApp/HomeStateProbe.swift`), which is
 * the same App-Group channel the auth identity keys and widget payload use.
 *
 * Absent key → probe hasn't landed yet → `null` (undecided), never `false`.
 */
actual fun hasHomeScreenWidget(): Boolean? {
    val suite = NSUserDefaults(suiteName = APP_GROUP_ID)
    return when (suite.stringForKey(KEY_WIDGET_INSTALLED)) {
        "true"  -> true
        "false" -> false
        else    -> null
    }
}

/**
 * UserNotifications IS an Objective-C framework, so unlike WidgetKit this
 * needs no Swift detour — but `getNotificationSettings` is callback-only,
 * hence the suspend wrapper.
 */
actual suspend fun notificationAuthState(): NotificationAuthState =
    suspendCancellableCoroutine { cont ->
        UNUserNotificationCenter.currentNotificationCenter()
            .getNotificationSettingsWithCompletionHandler { settings ->
                val state = when (settings?.authorizationStatus) {
                    // Provisional/ephemeral both deliver notifications, so
                    // neither should raise the "notifications are off" banner.
                    UNAuthorizationStatusAuthorized,
                    UNAuthorizationStatusProvisional,
                    UNAuthorizationStatusEphemeral -> NotificationAuthState.AUTHORIZED
                    UNAuthorizationStatusDenied    -> NotificationAuthState.DENIED
                    else                           -> NotificationAuthState.NOT_DETERMINED
                }
                if (cont.isActive) cont.resume(state)
            }
    }

actual suspend fun requestNotificationAuthorization(): Boolean =
    suspendCancellableCoroutine { cont ->
        val options = UNAuthorizationOptionAlert or
            UNAuthorizationOptionBadge or
            UNAuthorizationOptionSound
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(options) { granted, _ ->
                // APNs registration is pointless before the user says yes, and
                // must be called on the main thread — the completion handler
                // arrives on an arbitrary one.
                if (granted) {
                    dispatch_async(dispatch_get_main_queue()) {
                        UIApplication.sharedApplication.registerForRemoteNotifications()
                    }
                }
                if (cont.isActive) cont.resume(granted)
            }
    }

/**
 * iOS has no per-app notification deep link the way Android's
 * `ACTION_APP_NOTIFICATION_SETTINGS` does — `UIApplicationOpenSettingsURLString`
 * lands on the app's Settings page, one tap from Notifications. That page is
 * the closest thing the platform offers.
 */
actual fun openAppNotificationSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
}

/**
 * `CFBundleShortVersionString` is the marketing version (MARKETING_VERSION in
 * project.yml) — the same shape as Android's `BuildConfig.VERSION_NAME`, so
 * the shared `app.minVersion` comparison works unchanged across platforms.
 */
actual fun appVersionName(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0"

// `val`, not `const val`: since the staging/production split the App Group
// identifier is read from the Info.plist at runtime (see `IosAppGroup`), so it
// is not a compile-time constant any more.
private val APP_GROUP_ID: String get() = IosAppGroup.ID

/** Written by Swift `HomeStateProbe`; keep the literal in lockstep with it. */
private const val KEY_WIDGET_INSTALLED = "home_widget_installed"
