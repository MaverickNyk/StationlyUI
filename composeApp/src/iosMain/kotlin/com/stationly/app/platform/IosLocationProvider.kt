package com.stationly.app.platform

import com.stationly.app.ui.selection.LocationProvider
import com.stationly.app.ui.selection.NoOpLocationProvider
import com.stationly.app.ui.selection.platformLocationProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.*
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.math.abs

actual fun platformLocationProvider(): LocationProvider = IosLocationProvider()

/**
 * iOS CLLocationManager-based LocationProvider.
 *
 * Uses requestLocation() (one-shot, best available fix) with kCLLocationAccuracyHundredMeters.
 * Returns immediately from the 2-minute location cache when available and still fresh.
 *
 * Requires NSLocationWhenInUseUsageDescription in Info.plist.
 */
class IosLocationProvider : LocationProvider {

    override suspend fun getCurrentLocation(): Pair<Double, Double>? {
        // Fast path: last known location if < 2 minutes old
        CLLocationManager().location?.let { cached ->
            if (abs(cached.timestamp.timeIntervalSinceNow) < 120.0) {
                return Pair(cached.coordinate.latitude, cached.coordinate.longitude)
            }
        }

        return suspendCancellableCoroutine { cont ->
            val fetcher = LocationFetcher { result ->
                cont.resume(result)
            }
            // Keep a strong reference — CLLocationManager holds its delegate weakly,
            // and the KN GC could collect the delegate before the callback fires.
            activeRequest = fetcher
            cont.invokeOnCancellation {
                fetcher.cancel()
                activeRequest = null
            }
            fetcher.start()
        }
    }

    companion object {
        private var activeRequest: LocationFetcher? = null
    }
}

private class LocationFetcher(
    private val onResult: (Pair<Double, Double>?) -> Unit
) : NSObject(), CLLocationManagerDelegateProtocol {

    private val manager = CLLocationManager()
    private var settled = false

    fun start() {
        manager.delegate = this
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusAuthorizedWhenInUse,
            kCLAuthorizationStatusAuthorizedAlways -> manager.requestLocation()
            kCLAuthorizationStatusNotDetermined    -> manager.requestWhenInUseAuthorization()
            else                                   -> deliver(null)
        }
    }

    fun cancel() {
        manager.stopUpdatingLocation()
        manager.delegate = null
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val loc = didUpdateLocations.lastOrNull() as? CLLocation
        deliver(loc?.let { Pair(it.coordinate.latitude, it.coordinate.longitude) })
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        deliver(null)
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusAuthorizedWhenInUse,
            kCLAuthorizationStatusAuthorizedAlways -> manager.requestLocation()
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted       -> deliver(null)
            else                                   -> {} // still waiting for user decision
        }
    }

    private fun deliver(result: Pair<Double, Double>?) {
        if (settled) return
        settled = true
        manager.delegate = null
        onResult(result)
    }
}
