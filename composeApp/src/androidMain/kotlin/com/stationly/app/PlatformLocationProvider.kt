// The actual must live in the SAME package as the commonMain expect
// (ui.selection) — this file previously declared `com.stationly.app`, which
// left the expect unmatched and broke the (unshipped) android target.
package com.stationly.app.ui.selection

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.stationly.app.platform.AndroidAppContext
import com.stationly.app.platform.awaitActivityResult
import com.stationly.app.platform.currentActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

actual fun platformLocationProvider(): LocationProvider = AndroidLocationProvider

/**
 * One fix from Play Services' fused provider, asking for the permission first
 * if the user has not been asked yet.
 *
 * ## Why the permission request lives in here
 * v1 put it in the UI: `SelectionScreen` launched the request when the station
 * step opened. The shared `SelectionScreen` is `commonMain` and cannot hold an
 * Android launcher, and the shared `LocationProvider` interface is a single
 * `getCurrentLocation()` with nowhere to say "ask first". So the provider asks
 * — which is what [IosLocationProvider] already does with
 * `requestWhenInUseAuthorization`, so the shared contract already assumes it.
 *
 * The visible difference from v1 is *when*: the shared `SelectionViewModel`
 * calls this from `init` to pre-warm, so the prompt arrives when the selection
 * flow opens rather than one step later at the station picker. Same flow, same
 * screen, one step earlier — and the same place iOS asks today.
 */
object AndroidLocationProvider : LocationProvider {

    /**
     * One permission request at a time.
     *
     * `SelectionViewModel` calls this twice over: once from `init` to pre-warm,
     * and again from `fetchNearbyStations` when the user asks for nearby
     * stations. Without the gate those race and launch two system dialogs, and
     * the second `register`/`launch` on a dialog already showing is how you get
     * a permission result delivered to nobody.
     */
    private val permissionGate = Mutex()

    override suspend fun getCurrentLocation(): Pair<Double, Double>? {
        if (!hasPermission() && !requestPermission()) return null

        val client = LocationServices.getFusedLocationProviderClient(AndroidAppContext.context)
        return runCatching {
            // BALANCED_POWER_ACCURACY, matching v1: this positions a station
            // list, and ~100m is well inside the gap between two stations. HIGH
            // would light up GPS for a precision nothing here spends.
            //
            // getCurrentLocation, not lastLocation: `lastLocation` is null on a
            // device that has not fixed recently — a fresh install being set up
            // indoors is exactly that device — and this is the one call the
            // "nearby stations" list waits on.
            client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).await()
        }.getOrNull()?.let { it.latitude to it.longitude }
        // Null on failure, which the caller already renders as
        // `isGpsUnavailable` — a station search that falls back to the search
        // box. SecurityException is included deliberately: a permission can be
        // revoked between the check above and the call.
    }

    /**
     * Coarse counts.
     *
     * v1 checks `ACCESS_FINE_LOCATION` alone, which means a user who granted
     * **Approximate** on Android 12+ has location and v1 refuses to use it. The
     * fused provider is happy with coarse, and a few hundred metres does not
     * change which stations are nearby.
     */
    private fun hasPermission(): Boolean {
        val context = AndroidAppContext.context
        return listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Both permissions in one request, which is the shape Android asks for.
     *
     * Requesting `ACCESS_FINE_LOCATION` on its own — what v1 does — is
     * documented as wrong on API 31+: the two must be requested together, and
     * the system then offers the user the Precise/Approximate choice. Asking
     * for fine alone is what leaves that dialog without its coarse option.
     *
     * No "we asked" flag, unlike notifications. Android stops showing this
     * dialog by itself after the user dismisses it twice, and returns the
     * standing answer instantly thereafter — so a repeat call costs nothing and
     * a user who changes their mind in Settings is picked up on the next try.
     * That is also v1's behaviour: it re-launches every time the station step
     * opens without permission.
     */
    private suspend fun requestPermission(): Boolean = permissionGate.withLock {
        // Re-check inside the lock: whoever was queued behind a request that
        // just succeeded must not fire a second dialog.
        if (hasPermission()) return@withLock true

        val activity = currentActivity() ?: return@withLock false
        runCatching {
            awaitActivityResult(
                activity,
                ActivityResultContracts.RequestMultiplePermissions(),
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
        // Read the outcome back off the system rather than trusting the result
        // map: granting "Approximate" answers false for FINE and true for
        // COARSE, and `hasPermission()` already knows that either will do.
        hasPermission()
    }
}
