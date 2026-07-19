package com.stationly.app.ui.dream

import com.stationly.app.platform.fetchMetNoForecast
import com.stationly.app.platform.lastKnownLatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * Background poller publishing the current temperature for the device's
 * last-known location. Port of Android `dream/WeatherStation.kt`.
 *
 * Why met.no (Yr): free, no API key, generous rate limits. Required: a
 * descriptive User-Agent and no polling faster than ~10 minutes per
 * coordinate — we use 30 minutes.
 *
 * Why last-known-location only: we deliberately don't request fresh fixes —
 * burning GPS for a screensaver decoration would be hostile. When no cached
 * fix exists we fall back to central London (Stationly's home network) so
 * the chip always renders.
 *
 * Singleton-per-process so two dream compositions during a quick rotation
 * don't fire duplicate network requests.
 */
object WeatherStation {

    private val _temperatureC = MutableStateFlow<Int?>(null)
    val temperatureC: StateFlow<Int?> = _temperatureC.asStateFlow()

    /**
     * met.no's symbol code for the next hour (e.g. "clearsky_day",
     * "partlycloudy_night", "rain"). Consumers map this to an emoji.
     */
    private val _symbolCode = MutableStateFlow<String?>(null)
    val symbolCode: StateFlow<String?> = _symbolCode.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    private var lastFetchMs: Long = 0L

    private const val REFRESH_INTERVAL_MS = 30L * 60L * 1000L  // 30 min
    private const val USER_AGENT =
        "Stationly/1.0 (https://stationly.co.uk; contact@stationly.co.uk)"

    // Central London — fallback when no device location is available.
    private const val LONDON_LAT = 51.5074
    private const val LONDON_LON = -0.1278

    private val json = Json { ignoreUnknownKeys = true }

    /** Start the 30-minute polling loop. Safe to call repeatedly. */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                // Cache for 30 minutes — we re-enter `start()` on every
                // re-composition of the dream, so this guard prevents
                // needless calls.
                val sinceLast = Clock.System.now().toEpochMilliseconds() - lastFetchMs
                if (sinceLast > REFRESH_INTERVAL_MS || _temperatureC.value == null) {
                    refreshOnce()
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    /** Cancel the poll loop; the scope stays alive for a future [start]. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun refreshOnce() {
        val location = lastKnownLatLon()
        val lat = location?.first ?: LONDON_LAT
        val lon = location?.second ?: LONDON_LON
        val body = fetchMetNoForecast(lat, lon, USER_AGENT) ?: return
        val parsed = parseForecast(body) ?: return
        lastFetchMs = Clock.System.now().toEpochMilliseconds()
        _temperatureC.value = parsed.first
        _symbolCode.value = parsed.second
    }

    /** Returns (tempC, symbolCode) or null when the payload doesn't parse. */
    private fun parseForecast(body: String): Pair<Int, String?>? = runCatching {
        val firstSlot = json.parseToJsonElement(body).jsonObject
            .getValue("properties").jsonObject
            .getValue("timeseries").jsonArray
            .first().jsonObject
            .getValue("data").jsonObject
        val celsius = firstSlot
            .getValue("instant").jsonObject
            .getValue("details").jsonObject
            .getValue("air_temperature").jsonPrimitive.double
        // symbol_code lives under next_1_hours (tight forecast window),
        // falling back to next_6_hours.
        val symbol = (firstSlot["next_1_hours"] ?: firstSlot["next_6_hours"])
            ?.jsonObject?.get("summary")
            ?.jsonObject?.get("symbol_code")
            ?.jsonPrimitive?.content
        celsius.roundToInt() to symbol
    }.getOrNull()
}
