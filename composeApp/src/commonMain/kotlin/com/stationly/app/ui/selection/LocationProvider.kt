package com.stationly.app.ui.selection

interface LocationProvider {
    /**
     * Makes sure the app may ask for a location, prompting if it has to.
     * True when it may. Suspends for as long as the user takes over the system
     * dialog, so callers must NOT put a deadline around this.
     *
     * Split out of [getCurrentLocation] (QA 2026-11-23): the station step caps
     * the wait for a FIX at a few seconds, and that cap used to cover the
     * permission dialog too, so someone slower than it to tap "Allow" was told
     * their location could not be found. Default true: a platform whose host
     * asks for permission itself (iOS, via `onRequestLocationPermission`) has
     * nothing to do here.
     */
    suspend fun ensurePermission(): Boolean = true

    suspend fun getCurrentLocation(): Pair<Double, Double>?
}

object NoOpLocationProvider : LocationProvider {
    override suspend fun getCurrentLocation(): Pair<Double, Double>? = null
}

// Returns the best available LocationProvider for the current platform
expect fun platformLocationProvider(): LocationProvider
