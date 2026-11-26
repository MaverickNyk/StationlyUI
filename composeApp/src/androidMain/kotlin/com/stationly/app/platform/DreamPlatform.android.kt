package com.stationly.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.stationly.core.platform.Platform
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The screensaver's platform half — real since AV2-6.1.
 *
 * These four were placeholders: an in-memory map, a no-op, and two functions
 * that returned null. Harmless while nothing reached them, and that stopped
 * being a build detail at AV2-3.5 — this module is the shipped Android app now,
 * and the only thing keeping the user away from them was
 * [openSystemScreensaverSettings] answering `true` and sending them to the
 * system screen instead.
 *
 * Every one of them is ported from `com.stationly.mobile.dream`, which has run
 * them on real devices since v1. Delegation was not possible — the dependency
 * runs `:android:app` → `:composeApp` — so this is the same behaviour, written
 * against the same rules, with the reasons carried over rather than rediscovered.
 */

/**
 * The screensaver's settings, on disk.
 *
 * ## The file name and the keys are v1's, deliberately
 * `com.stationly.mobile.dream.DreamSettings` writes `layout`, `theme`,
 * `clock_style` and `station_id` into `StationlyDreamPrefs`, and the shared
 * store uses the same four names. Writing anywhere else would mean every v1 user
 * opening v2 finds a screensaver reset to defaults — a small loss that nobody
 * would report and nobody could explain.
 *
 * ## Its own file, not `StationlyPrefs`
 * Logout wipes that one. A screensaver's layout is not identity and has no
 * business being erased by a sign-out; the shared store namespaces per account
 * on top of this, which is a different question from whether the bytes survive.
 */
actual object DreamPrefsBackend {

    /** Must match `com.stationly.mobile.dream.DreamSettings.FILE`. */
    private const val FILE = "StationlyDreamPrefs"

    private val prefs get() =
        AndroidAppContext.context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    actual fun get(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()

    actual fun set(key: String, value: String?) {
        runCatching {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }
}

/**
 * Hold the screen on while the dream is showing.
 *
 * ## Inside a real dream this does nothing, and that is correct
 * `StationlyDreamService` sets `isScreenBright = true`, and the system keeps the
 * screen on for the whole time a dream is bound — that is what a screensaver is.
 * There is no Activity in that tree, so the walk below finds no window and the
 * effect is a no-op.
 *
 * What it covers is the OTHER host: the shared `DreamHost` rendered inside the
 * app, which iOS uses for its in-app screensaver and which Android would use if
 * `dream/settings` is ever routed here. There the window is an Activity's and
 * the flag is the only thing standing between a docked phone and the display
 * timeout.
 *
 * Cleared on dispose, always. A screen-on flag left set outlives the composable
 * that asked for it and keeps a phone awake in somebody's pocket — the one
 * failure here that costs a real battery.
 */
@Composable
actual fun KeepScreenAwake() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>()
            .firstOrNull()
            ?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * met.no's compact forecast, as raw JSON.
 *
 * `HttpURLConnection` rather than the app's Ktor client, matching v1 and iOS's
 * `NSURLSession`: this is one polite call every thirty minutes from a screen
 * nobody is touching, and it has no business initialising the app's networking
 * stack to make it.
 *
 * The User-Agent is not optional. met.no's terms require a contact address and
 * they return 403 without one, which would show up as a weather chip that simply
 * never appears.
 */
actual suspend fun fetchMetNoForecast(lat: Double, lon: Double, userAgent: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://api.met.no/weatherapi/locationforecast/2.0/compact" +
                    "?lat=${"%.4f".format(lat)}&lon=${"%.4f".format(lon)}",
            )
            (url.openConnection() as HttpURLConnection).run {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", userAgent)
                try {
                    if (responseCode !in 200..299) return@withContext null
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
        }.getOrNull()
    }

/**
 * The last location anything on this device happened to cache. Never a fresh fix.
 *
 * ## Two rules carried over from v1, both of them the point
 * **Coarse counts.** On Android 12+ a user can grant "Approximate" only, which
 * yields `ACCESS_COARSE_LOCATION` and not `ACCESS_FINE_LOCATION`. Checking only
 * for fine is how a granted permission reads as denied — AV2-3.3 found the same
 * bug in nearby-station search, where it made v1 refuse to search at all.
 *
 * **No active request.** A screensaver burning a GPS fix for a temperature
 * reading is hostile. Every enabled provider is asked for what it already holds,
 * and the most recent answer wins; if nothing has a fix, the caller falls back
 * rather than waking the radio.
 */
actual fun lastKnownLatLon(): Pair<Double, Double>? {
    val context = AndroidAppContext.context
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return runCatching {
        manager.allProviders
            .mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    }.getOrNull()
}

/**
 * Settings → Display → Screen saver, which is where Android's screensaver
 * actually lives. Carried over from v1's home-screen "Set as Screensaver"
 * promo, fallback included.
 *
 * `ACTION_DREAM_SETTINGS` is a public Settings action but not every OEM build
 * exposes it, so a failure falls back to the display settings screen the
 * screensaver sits one tap inside. `FLAG_ACTIVITY_NEW_TASK` because this starts
 * from the application context.
 *
 * Returns true even if both attempts throw. The alternative on a false is to
 * open the in-app screen, and that screen writes to a map that dies with the
 * process — sending the user somewhere that cannot work is worse than sending
 * them nowhere.
 */
actual fun openSystemScreensaverSettings(): Boolean {
    val context = Platform.appContext
    val open = { action: String ->
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    runCatching { open(Settings.ACTION_DREAM_SETTINGS) }
        .recoverCatching { open(Settings.ACTION_DISPLAY_SETTINGS) }
    return true
}
