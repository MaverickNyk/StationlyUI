package com.stationly.app.platform

import androidx.compose.runtime.Composable

/**
 * Platform backing for the dream's key/value preferences.
 *
 * Android keeps dream prefs in their own SharedPreferences file
 * ("StationlyDreamPrefs") so the logout `clearAll()` on the main app prefs
 * doesn't wipe the user's screensaver choices. The iOS equivalent of that
 * isolation is the APP-GROUP NSUserDefaults suite — the standard domain is
 * the one `storageManager.clearAll()` wipes on logout (the same wipe that
 * once ate the firebase identity keys), while the group suite survives.
 */
expect object DreamPrefsBackend {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

/**
 * Keep the screen awake while the composable is in composition — the iOS
 * stand-in for Android's DreamService, which only runs while the device is
 * docked/charging with the screen forced on. A screensaver that lets the
 * phone lock after 30 seconds isn't a screensaver.
 */
@Composable
expect fun KeepScreenAwake()

/**
 * met.no forecast fetch (compact endpoint). Returns the raw JSON body or
 * null on any failure — the weather chip simply doesn't render then.
 * Kept expect/actual (NSURLSession on iOS) instead of dragging a Ktor
 * client into the UI layer for one polite 30-minute poll.
 */
expect suspend fun fetchMetNoForecast(lat: Double, lon: Double, userAgent: String): String?

/**
 * The device's LAST-KNOWN location, or null when nothing is cached /
 * permission was never granted. Mirrors Android WeatherStation's rule:
 * never request a fresh fix for a screensaver decoration —
 * `CLLocationManager.location` is exactly that cached value on iOS.
 */
expect fun lastKnownLatLon(): Pair<Double, Double>?

/**
 * Open the platform's OWN screensaver configuration, or return `false` when
 * this platform configures the screensaver inside the app.
 *
 * ## Why this exists
 * The shared home settings offer a "Screensaver" row, and until AV2-3.5 that
 * was an iOS-only question: iOS has no system screensaver, so the row opens
 * [DreamSettingsScreen][com.stationly.app.ui.dream.DreamSettingsScreen] and the
 * app owns the whole feature.
 *
 * Android does not work that way, and the cutover made it matter. Android's
 * screensaver is a **Daydream**, bound by the OS and configured in Settings →
 * Display → Screen saver. `android/`'s `StationlyDreamService` is the real one,
 * with real persistence in `StationlyDreamPrefs`, real keep-awake, and real
 * weather. `composeApp`'s Android `actual`s for all of that are placeholders
 * (EPIC-06, gated on Q3) — written when this target was a build-verification
 * surface rather than the shipped app.
 *
 * So on Android the in-app screen would have taken the user's settings, put
 * them in a map that dies with the process, and changed nothing about the
 * screensaver they actually have. v1's home screen sent them to
 * `ACTION_DREAM_SETTINGS` instead, which is the affordance that works, and this
 * keeps that.
 *
 * Returns a Boolean rather than being a capability flag on its own so there is
 * one call site and no way to check the flag and then forget to act on it.
 */
expect fun openSystemScreensaverSettings(): Boolean
