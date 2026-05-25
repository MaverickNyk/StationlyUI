package com.stationly.mobile.ui.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide connectivity flag. Initialised once from
 * `StationlyApplication.onCreate` with a default-network callback so the
 * flag flips as the device gains/loses internet.
 *
 * Compose surfaces collect via `collectAsState()`; non-Compose paths
 * (the widget RemoteViews builder) can read `.value` directly — Android
 * instantiates the Application before delivering broadcasts, so the
 * initial `init()` has always run by the time anything reads this.
 *
 * NET_CAPABILITY_INTERNET is intentional (not NET_CAPABILITY_VALIDATED) —
 * a captive portal still counts as "connected" for our purposes: FCM may
 * still land via a transparent proxy, and we'd rather under-trigger the
 * "Offline" fallback than over-trigger it.
 */
object NetworkState {
    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline

    fun init(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        _isOnline.value = computeOnline(cm)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnline.value = true
                }
                override fun onLost(network: Network) {
                    _isOnline.value = computeOnline(cm)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _isOnline.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        }
        // registerDefaultNetworkCallback can throw on some emulators / very old
        // installs. The initial computeOnline above keeps us correct at launch;
        // we just won't react to later changes. Acceptable degradation.
    }

    private fun computeOnline(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
