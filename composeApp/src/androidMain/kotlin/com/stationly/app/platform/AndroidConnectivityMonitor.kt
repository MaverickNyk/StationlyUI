package com.stationly.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Whether the device has internet, as a flow, for the offline banner.
 *
 * Mirrors `android/`'s `NetworkState` — same signal, same judgement call —
 * rather than sharing it, because `:composeApp` cannot see `:android:app`.
 * The one difference is shape: `NetworkState` is a process-wide `StateFlow`
 * initialised from `Application.onCreate` and also read by the widget's
 * RemoteViews builder, while this is a per-collector `callbackFlow` because
 * that is what the shared `expect` asks for.
 */
actual fun getConnectivityFlow(): Flow<Boolean> {
    val cm = AndroidAppContext.context
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return flowOf(true)

    return callbackFlow {
        // Emit BEFORE registering. A default-network callback only speaks when
        // something changes, so a device that is simply online and stays online
        // would otherwise leave this flow silent — and the collector holding an
        // "unknown" state renders the offline banner over a working app.
        trySend(isOnline(cm))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                // NOT `false`: losing Wi-Fi while mobile data is up is a "lost"
                // for that network and a hand-off for the device. Re-ask.
                trySend(isOnline(cm))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }

        // Can throw on some emulators and very old installs — `NetworkState`
        // found this the hard way. The initial emission above already has the
        // answer for right now; failing to register costs us later changes, not
        // correctness at launch, so degrade rather than propagate.
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess

        awaitClose {
            if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }
        // onCapabilitiesChanged is chatty — it fires for signal strength,
        // metering and validation changes that all leave INTERNET where it was.
        // The collector re-renders a banner on every emission.
        .distinctUntilChanged()
}

/**
 * `NET_CAPABILITY_INTERNET` and deliberately not `NET_CAPABILITY_VALIDATED`.
 *
 * A captive portal counts as connected for our purposes: FCM may still land
 * through a transparent proxy, and under-triggering the offline banner is the
 * better failure. Same call `android/`'s `NetworkState` makes; keep the two in
 * step until AV2-3.5 deletes one of them.
 */
private fun isOnline(cm: ConnectivityManager): Boolean {
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
