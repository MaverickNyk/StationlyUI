package com.stationly.app.ui.support

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * One [BillingClient], connected on demand and reconnected when Play drops it.
 *
 * Play's service can disconnect at any time (the Play Store updating itself is
 * the common case), and a client that has disconnected cannot be restarted, so
 * [client] builds a fresh one whenever the last one is not ready. Connecting is
 * serialised: a purchase and the launch-time resume racing each other share one
 * connection rather than opening two.
 */
internal class PlayBillingConnection(
    private val context: Context,
    private val listener: PurchasesUpdatedListener,
) {
    private val lock = Mutex()
    private var current: BillingClient? = null

    /** A ready client, or null when Play billing is not available on this device. */
    suspend fun client(): BillingClient? = lock.withLock {
        current?.takeIf { it.isReady }?.let { return@withLock it }
        current?.endConnection()
        current = null

        val fresh = BillingClient.newBuilder(context)
            .setListener(listener)
            // One-time products only. Required by Billing 7, and it is what lets
            // a cash-at-a-shop payment arrive as PENDING rather than failing.
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()

        if (fresh.connect()) {
            current = fresh
            fresh
        } else {
            fresh.endConnection()
            null
        }
    }

    private suspend fun BillingClient.connect(): Boolean = suspendCancellableCoroutine { cont ->
        startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (cont.isActive) cont.resume(result.responseCode == BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                // Before setup finished: a failed connect. After: nothing is
                // waiting, and the next client() call reconnects.
                if (cont.isActive) cont.resume(false)
            }
        })
    }
}
