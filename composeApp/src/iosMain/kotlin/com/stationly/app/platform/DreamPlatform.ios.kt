package com.stationly.app.platform

import com.stationly.core.platform.IosAppGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLLocationManager
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
// Category methods (NSMutableHTTPURLRequest / NSURLSessionAsynchronousConvenience)
// surface as extension functions in K/N — they need explicit imports.
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import kotlin.coroutines.resume

/**
 * Dream prefs live in the APP-GROUP suite (same one DeviceIdentity uses) so
 * they survive the logout-time wipe of the standard NSUserDefaults domain —
 * the iOS mirror of Android's separate "StationlyDreamPrefs" file.
 */
actual object DreamPrefsBackend {
    private val suite = NSUserDefaults(suiteName = IosAppGroup.ID)

    actual fun get(key: String): String? = suite.stringForKey("dream_$key")

    actual fun set(key: String, value: String?) {
        if (value == null) suite.removeObjectForKey("dream_$key")
        else suite.setObject(value, forKey = "dream_$key")
        suite.synchronize()
    }
}

/**
 * `idleTimerDisabled` while the dream is composed — the phone stays lit on
 * the desk dock exactly like an Android Daydream. Restored on dispose so
 * leaving the dream returns normal auto-lock behaviour.
 */
@Composable
actual fun KeepScreenAwake() {
    DisposableEffect(Unit) {
        UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun fetchMetNoForecast(lat: Double, lon: Double, userAgent: String): String? {
    // %.4f — met.no asks clients to truncate coordinates (cache hit rate).
    fun fmt(v: Double): String {
        val scaled = kotlin.math.round(v * 10_000.0) / 10_000.0
        return scaled.toString()
    }
    val url = NSURL.URLWithString(
        "https://api.met.no/weatherapi/locationforecast/2.0/compact" +
            "?lat=${fmt(lat)}&lon=${fmt(lon)}"
    ) ?: return null

    val request = NSMutableURLRequest(uRL = url)
    request.setValue(userAgent, forHTTPHeaderField = "User-Agent")
    request.setTimeoutInterval(8.0)

    return suspendCancellableCoroutine { cont ->
        val handler = { data: NSData?, _: NSURLResponse?, error: NSError? ->
            val body = if (error == null && data != null) {
                @Suppress("CAST_NEVER_SUCCEEDS")
                (NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String)
            } else null
            if (cont.isActive) cont.resume(body)
        }
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request, handler)
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }
}

/**
 * `CLLocationManager.location` is the system's cached most-recent fix —
 * reading it triggers NO location hardware and needs no fresh permission
 * prompt (returns nil when never authorized). Matches Android's
 * "last-known only, never burn GPS for a screensaver" rule.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun lastKnownLatLon(): Pair<Double, Double>? {
    val loc = CLLocationManager().location ?: return null
    return loc.coordinate.useContents { latitude to longitude }
}
