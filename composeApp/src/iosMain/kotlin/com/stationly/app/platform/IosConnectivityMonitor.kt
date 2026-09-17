package com.stationly.app.platform

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_get_status
import platform.darwin.dispatch_get_main_queue

actual fun getConnectivityFlow(): Flow<Boolean> = callbackFlow {
    val monitor = nw_path_monitor_create()
    nw_path_monitor_set_update_handler(monitor) { path ->
        val status = nw_path_get_status(path)
        trySend(status == nw_path_status_satisfied)
    }
    nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
    nw_path_monitor_start(monitor)
    awaitClose {
        nw_path_monitor_cancel(monitor)
    }
}
