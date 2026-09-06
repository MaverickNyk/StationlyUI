package com.stationly.app.platform

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import com.stationly.core.platform.Platform

// These four `actual`s are PLACEHOLDERS, and since AV2-3.5 that is a live fact
// rather than a build detail: this module is the shipped Android app now, not a
// build-verification surface. The real screensaver is `android/`'s
// `StationlyDreamService` — real prefs, real keep-awake, real weather — and it
// is bound by the OS, not launched from inside the app.
//
// Nothing reaches these on Android, because `openSystemScreensaverSettings()`
// below sends the user to the system screensaver settings instead of to the
// in-app screen that would use them. EPIC-06 either replaces them with the real
// implementations one package away, or deletes the Daydream (Q3).

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
