package com.stationly.app.ui.support

import android.os.SystemClock
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.google.firebase.auth.FirebaseAuth
import com.stationly.app.platform.AndroidAppContext
import com.stationly.app.platform.currentActivity
import com.stationly.core.service.NetworkModule
import com.stationly.core.service.PlayVerifyOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Google Play Billing for the support tiers: the Android counterpart of iOS's
 * `StoreKitManager.swift`, and why [checkoutSupported] is true on Android.
 *
 * ## The life of one purchase
 * 1. [purchase] opens Play's sheet for the tier's one-time product, bound to
 *    the signed-in account ([PlayAccount.idFor]).
 * 2. Play reports to [onPurchasesUpdated]. A completed purchase raises the
 *    thank-you at once ([SupportReturn]), as iOS does after StoreKit: it is
 *    local and cosmetic, and the server stays the authority on the badge. Any
 *    other answer is posted as a [StoreNotice] for the sheet to show.
 * 3. The token goes to our backend, which asks Google whether it is real,
 *    records it in the ledger and CONSUMES it. The device never consumes, so a
 *    purchase is never used up before it has been credited.
 * 4. If step 3 does not finish (app killed, no signal, server down), Play keeps
 *    the purchase unconsumed. [resume] finds it, within minutes or on the next launch, and sends it
 *    again; the server's ledger makes that safe to repeat.
 *
 * ## Never a web checkout
 * Play's payments policy requires a tip to the developer inside the app to use
 * Play Billing (D11). The backend sends Android no Stripe links and
 * [openCheckout] is a no-op here, so nothing in this file can reach a browser.
 *
 * ## Threading
 * Everything runs on the main thread: Play delivers [onPurchasesUpdated] there,
 * and [scope] is `Dispatchers.Main`. The billing and network calls suspend
 * rather than block, so the mutable fields below need no locks.
 */
internal object PlayBilling : PurchasesUpdatedListener {

    private const val PRODUCT_PREFIX = "uk.co.stationly.support."

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val connection by lazy { PlayBillingConnection(AndroidAppContext.context, this) }

    /** What the user tapped last, so the thank-you can name the amount. */
    private var lastLaunch: Launch? = null
    private data class Launch(val productId: String, val amountMinor: Int, val tierId: String)

    /** Purchase tokens being sent right now, so a resume racing a live purchase sends once. */
    private val inFlight = mutableSetOf<String>()

    /** When [resume] may look again. Never, once everything it found has been answered. */
    private var nextResumeAtMs = 0L
    private const val RESUME_RETRY_MS = 5 * 60_000L

    private fun retryResumeLater() {
        nextResumeAtMs = SystemClock.elapsedRealtime() + RESUME_RETRY_MS
    }

    // ── Starting a purchase ───────────────────────────────────────────────

    /**
     * Opens Play's purchase sheet for [productId].
     *
     * Returns false only when it cannot even try (no screen to show the sheet
     * over, nobody signed in), so the support sheet can say so at once. Every
     * later failure arrives as a [StoreNotice].
     */
    fun purchase(productId: String, amountMinor: Int, tierId: String): Boolean {
        val activity = currentActivity() ?: return false
        val uid = currentUid() ?: return false
        lastLaunch = Launch(productId, amountMinor, tierId)

        scope.launch {
            val billing = connection.client()
            val product = billing?.productDetails(productId)
            if (billing == null || product == null) {
                // No Play, or a product Play does not know: not set up in Play
                // Console yet, or not visible to this account.
                StoreNotices.post(StoreNotice.UNAVAILABLE)
                return@launch
            }
            val result = billing.launchBillingFlow(activity, flowParams(product, uid))
            when (result.responseCode) {
                BillingResponseCode.OK -> Unit // Play's sheet is up; onPurchasesUpdated takes over.
                BillingResponseCode.ITEM_ALREADY_OWNED -> finishPreviousPurchase()
                else -> StoreNotices.post(StoreNotice.UNAVAILABLE)
            }
        }
        return true
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases.orEmpty().forEach { handle(it, interactive = true) }
            BillingResponseCode.USER_CANCELED -> Unit // They changed their mind; nothing to say.
            BillingResponseCode.ITEM_ALREADY_OWNED -> finishPreviousPurchase()
            else -> StoreNotices.post(StoreNotice.FAILED)
        }
    }

    // ── Finishing purchases ───────────────────────────────────────────────

    /**
     * Sends every unconsumed purchase to the backend.
     *
     * Once per process, unless [force]d (Play says a purchase is still pending
     * credit) or the last look did not settle everything. A process is NOT a
     * launch here: pushes and widgets keep one alive for days, and Google
     * refunds an uncredited purchase after three.
     */
    fun resume(force: Boolean = false) {
        if (!force && SystemClock.elapsedRealtime() < nextResumeAtMs) return
        if (currentUid() == null) return
        nextResumeAtMs = Long.MAX_VALUE
        scope.launch {
            val billing = connection.client() ?: return@launch retryResumeLater()
            val found = billing.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            )
            if (found.billingResult.responseCode != BillingResponseCode.OK) retryResumeLater()
            found.purchasesList.forEach { handle(it, interactive = false) }
        }
    }

    /**
     * Play will not sell a one-time product again while the last purchase of it
     * is unconsumed. Credit that one first; the user can then tap again.
     */
    private fun finishPreviousPurchase() {
        StoreNotices.post(StoreNotice.FINISHING_PREVIOUS)
        resume(force = true)
    }

    /**
     * One purchase from Play, live ([interactive]) or found by [resume].
     */
    private fun handle(purchase: Purchase, interactive: Boolean) {
        val productId = purchase.products.firstOrNull { it.startsWith(PRODUCT_PREFIX) } ?: return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> Unit
            Purchase.PurchaseState.PENDING -> {
                // Not money yet. resume() sends it once Play says it settled.
                if (interactive) StoreNotices.post(StoreNotice.PENDING)
                return
            }
            else -> return
        }

        // Bought on this device by a different account (someone signed out and
        // another signed in). The backend would refuse it, so it is left for
        // its owner rather than sent under the wrong one.
        val uid = currentUid() ?: return
        val boundTo = purchase.accountIdentifiers?.obfuscatedAccountId
        if (boundTo != null && boundTo != PlayAccount.idFor(uid)) return

        val token = purchase.purchaseToken
        if (!inFlight.add(token)) return
        val sessionId = PlayAccount.sessionId(purchase.orderId, token)

        if (interactive) {
            // Celebrate now, as iOS does. Waiting a server round trip to say
            // thank you reads as nothing having happened.
            val tapped = lastLaunch?.takeIf { it.productId == productId }
            SupportReturn.deliver(amount = tapped?.amountMinor?.toString(), tier = tapped?.tierId, sessionId = sessionId)
        }

        scope.launch {
            try {
                val outcome = NetworkModule.sduiApi.verifyPlayPurchase(productId, token)
                // The only trace of whether a payment was credited: the thank-you
                // and the mark show either way. Never the token.
                Log.i("PlayBilling", "verify $productId: $outcome")
                // A purchase finished from a PREVIOUS run gets its thank-you the
                // first time the server credits it, and never again: a retry of
                // one already credited comes back newlyRecorded = false.
                if (!interactive && outcome is PlayVerifyOutcome.Credited && outcome.newlyRecorded) {
                    SupportReturn.deliver(amount = null, tier = null, sessionId = sessionId)
                }
                if (outcome is PlayVerifyOutcome.RetryLater) retryResumeLater()
                // RetryLater and Rejected both leave the purchase unconsumed.
                // RetryLater: the next look sends it again. Rejected: Google
                // refunds an unconsumed purchase after three days, the right
                // ending for a payment the server will not credit.
            } finally {
                inFlight.remove(token)
            }
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private suspend fun BillingClient.productDetails(productId: String): ProductDetails? =
        queryProductDetails(
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()
        ).productDetailsList?.firstOrNull()

    private fun flowParams(product: ProductDetails, uid: String): BillingFlowParams =
        BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build())
            )
            .setObfuscatedAccountId(PlayAccount.idFor(uid))
            .build()

    private fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid
}
