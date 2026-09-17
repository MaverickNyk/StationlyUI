package com.stationly.app.platform

import androidx.compose.runtime.Composable

// The composeApp android target is a build-verification surface only — the
// shipped Android app (android/) has the real DreamService + WeatherStation.
// These actuals just have to compile.

actual object DreamPrefsBackend {
    private val memory = mutableMapOf<String, String>()
    actual fun get(key: String): String? = memory[key]
    actual fun set(key: String, value: String?) {
        if (value == null) memory.remove(key) else memory[key] = value
    }
}

@Composable
actual fun KeepScreenAwake() { /* no-op — android/ uses DreamService */ }

actual suspend fun fetchMetNoForecast(lat: Double, lon: Double, userAgent: String): String? = null

actual fun lastKnownLatLon(): Pair<Double, Double>? = null
