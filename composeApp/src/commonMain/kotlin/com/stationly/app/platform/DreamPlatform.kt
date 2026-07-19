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
