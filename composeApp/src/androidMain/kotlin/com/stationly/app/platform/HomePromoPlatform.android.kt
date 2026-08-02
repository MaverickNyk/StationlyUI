package com.stationly.app.platform

// The composeApp android target is a build-verification surface only — the
// shipped Android app (android/) has the real AppWidgetManager probe,
// NotificationPermissionEffect and BuildConfig.VERSION_NAME. These actuals
// just have to compile.

actual fun hasHomeScreenWidget(): Boolean? = null

actual suspend fun notificationAuthState(): NotificationAuthState =
    NotificationAuthState.AUTHORIZED

actual suspend fun requestNotificationAuthorization(): Boolean = true

actual fun openAppNotificationSettings() { /* no-op — android/ opens Settings */ }

actual fun appVersionName(): String = "0"
