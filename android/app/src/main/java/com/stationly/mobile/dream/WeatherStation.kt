package com.stationly.mobile.dream

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Background poller that publishes the current temperature for the device's
 * last-known location.
 *
 * Why met.no (Yr): free, no API key, generous rate limits. Required: send a
 * descriptive User-Agent and don't poll faster than every ~10 minutes per
 * coordinate — we use 30 minutes which is comfortably under cap.
 *
 * Why last-known-location only: we deliberately *don't* request fresh GPS
 * fixes. The dream runs in the background on a docked device — burning GPS
 * for a screensaver decoration would be hostile. If the user has used any
 * other app recently (Maps, etc.) the cached location is fine. If nothing
 * has populated a location yet, we just don't show a temperature.
 *
 * If `ACCESS_FINE_LOCATION` is denied at runtime we silently no-op.
 *
 * Singleton-per-process via [of] so two dream instances during a quick
 * orientation change don't fire duplicate network requests.
 */
class WeatherStation private constructor(private val appContext: Context) {

    private val _temperatureC = MutableStateFlow<Int?>(null)
    val temperatureC: StateFlow<Int?> = _temperatureC.asStateFlow()

    /**
     * met.no's symbol code for the next hour (e.g. "clearsky_day",
     * "partlycloudy_night", "rain"). Consumers map this to an emoji.
     */
    private val _symbolCode = MutableStateFlow<String?>(null)
    val symbolCode: StateFlow<String?> = _symbolCode.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var lastFetchMs: Long = 0L

    /** Start the 30-minute polling loop. Safe to call repeatedly. */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                // Cache for 30 minutes — we re-enter `start()` on every
                // orientation change, so this guard prevents needless calls.
                val sinceLast = System.currentTimeMillis() - lastFetchMs
                if (sinceLast > REFRESH_INTERVAL_MS || _temperatureC.value == null) {
                    refreshOnce()
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    /**
     * Cancel the poll loop. We don't cancel the [scope] — keep it alive so a
     * future [start] doesn't have to spin up a new scope.
     */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun refreshOnce() {
        // Prefer the device's last-known fix; if location is unavailable
        // (permission denied, or nothing has populated a cached fix yet) fall
        // back to central London so the strip always shows a temperature
        // instead of vanishing. London is Stationly's home network, so it's a
        // sensible default for the screensaver.
        val location = readLastKnownLocation()
        val lat = location?.latitude ?: LONDON_LAT
        val lon = location?.longitude ?: LONDON_LON
        val fetched = fetchForecast(lat, lon) ?: return
        lastFetchMs = System.currentTimeMillis()
        _temperatureC.value = fetched.tempC
        _symbolCode.value = fetched.symbolCode
    }

    private data class Forecast(val tempC: Int, val symbolCode: String?)

    @SuppressLint("MissingPermission")
    private fun readLastKnownLocation(): Location? {
        // Accept EITHER fine or coarse. On Android 12+ the user can grant
        // "Approximate" location only, which yields COARSE but not FINE. We
        // only read a cached last-known fix here, so coarse is plenty. When
        // neither is granted we return null and the caller falls back to
        // London rather than showing nothing.
        val granted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        // Cheapest path: ask every enabled provider for whatever they've
        // already cached. Pick the most recent. No active GPS request — we
        // don't want a screensaver burning a fix.
        return lm.allProviders
            .mapNotNull { provider ->
                try { lm.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            }
            .maxByOrNull { it.time }
    }

    private suspend fun fetchForecast(lat: Double, lon: Double): Forecast? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.met.no/weatherapi/locationforecast/2.0/compact" +
                "?lat=${"%.4f".format(lat)}&lon=${"%.4f".format(lon)}")
            (url.openConnection() as HttpURLConnection).run {
                connectTimeout = 8_000
                readTimeout = 8_000
                // met.no's terms of service: identify yourself with a contact.
                setRequestProperty("User-Agent", USER_AGENT)
                try {
                    if (responseCode !in 200..299) return@withContext null
                    val json = JSONObject(inputStream.bufferedReader().use { it.readText() })
                    val firstSlot = json
                        .getJSONObject("properties")
                        .getJSONArray("timeseries")
                        .getJSONObject(0)
                        .getJSONObject("data")
                    val celsius = firstSlot
                        .getJSONObject("instant")
                        .getJSONObject("details")
                        .getDouble("air_temperature")
                    // symbol_code lives under next_1_hours (preferred — it's
                    // a tight forecast window) falling back to next_6_hours.
                    val symbol = firstSlot.optJSONObject("next_1_hours")
                        ?.optJSONObject("summary")
                        ?.optString("symbol_code", null)
                        ?: firstSlot.optJSONObject("next_6_hours")
                            ?.optJSONObject("summary")
                            ?.optString("symbol_code", null)
                    Forecast(celsius.roundToInt(), symbol)
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w("WeatherStation", "Temperature fetch failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30L * 60L * 1000L  // 30 min
        private const val USER_AGENT =
            "Stationly/1.0 (https://stationly.co.uk; contact@stationly.co.uk)"

        // Central London — fallback coordinates when no device location is
        // available, so the dream's weather chip always renders.
        private const val LONDON_LAT = 51.5074
        private const val LONDON_LON = -0.1278

        @Volatile private var INSTANCE: WeatherStation? = null
        fun of(context: Context): WeatherStation =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WeatherStation(context.applicationContext).also { INSTANCE = it }
            }
    }
}
