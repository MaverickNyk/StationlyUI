package com.stationly.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A live web view, embedded in the Compose tree.
 *
 * ## Why this exists rather than a presented browser
 * iOS used to open links with `SFSafariViewController`, presented over the
 * top-most view controller. That put a UIKit controller on top of the Compose
 * host and backgrounded the app, and dismissing it left the surface that
 * launched it in a broken state: the explore cards stopped responding to taps
 * entirely, with nothing on screen to explain why and no way back short of
 * force-quitting. It also meant iOS never had the in-app browser Android has
 * had all along (`android/.../ui/common/WebViewScreen.kt`), which is the
 * behaviour this app is supposed to have: the user stays inside Stationly.
 *
 * Keeping the web view INSIDE the composition removes the whole class of
 * problem. Nothing is presented, nothing is backgrounded, and there is no
 * teardown to race against — opening a link is now an ordinary state change
 * like any other screen.
 */
@Composable
expect fun PlatformWebView(
    url: String,
    handle: WebViewHandle,
    modifier: Modifier = Modifier,
)

/**
 * The bridge between the platform web view and the Compose chrome around it.
 *
 * The platform side reports what it knows through [onStarted], [onProgressed]
 * and [onFailed]; the common side reads the resulting state to drive the top
 * bar, and calls [goBack] to unwind in-page navigation before closing the
 * screen. That last part matters on real content: a fares page with footer
 * links would otherwise dump the user out of the browser on their first back
 * gesture rather than returning them to where they came from.
 *
 * The three report methods exist so the two platforms cannot drift. They were
 * `internal set` properties each actual wrote by hand, and Android's half
 * quietly never set `hasError` at all — the error screen was iOS-only, and the
 * rule for what a blank page title means lived in two copies.
 */
@Stable
class WebViewHandle {
    var isLoading: Boolean by mutableStateOf(true)
        private set
    var hasError: Boolean by mutableStateOf(false)
        private set
    var pageTitle: String? by mutableStateOf(null)
        private set
    var canGoBack: Boolean by mutableStateOf(false)
        private set

    /**
     * Where the view actually is, which is not necessarily where it was sent.
     * "Open in browser" handed the system the URL the screen was OPENED with,
     * so a user who had followed two links inside the page was bounced back to
     * the start of it.
     */
    var currentUrl: String? by mutableStateOf(null)
        private set

    internal var goBackAction: (() -> Unit)? = null
    internal var reloadAction: (() -> Unit)? = null

    /** A navigation has begun. Clears any error the retry screen is showing. */
    internal fun onStarted(canGoBack: Boolean) {
        isLoading = true
        hasError = false
        // Also refreshed on START. Updating it only on finish left the back
        // arrow one navigation stale — visible for the whole of a slow load.
        this.canGoBack = canGoBack
    }

    /**
     * A navigation settled. [done] is false for intermediate history updates
     * that move the view without ending the load.
     *
     * Blank titles are common mid-redirect; the previous one is KEPT rather
     * than cleared, so the top bar does not flicker between a real title and
     * nothing.
     */
    internal fun onProgressed(
        canGoBack: Boolean,
        url: String?,
        title: String?,
        done: Boolean,
    ) {
        if (done) isLoading = false
        this.canGoBack = canGoBack
        url?.takeIf { it.isNotBlank() }?.let { currentUrl = it }
        title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
    }

    /** A navigation failed for real — see each actual for what it filters out. */
    internal fun onFailed() {
        isLoading = false
        hasError = true
    }

    fun goBack() { goBackAction?.invoke() }

    fun reload() {
        hasError = false
        isLoading = true
        reloadAction?.invoke()
    }
}
