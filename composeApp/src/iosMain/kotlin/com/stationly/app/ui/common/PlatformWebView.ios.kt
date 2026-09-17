package com.stationly.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject

/** `NSURLErrorCancelled`, spelled out because the constant is not in the Kotlin bindings. */
private const val NSURL_ERROR_CANCELLED = -999L

/**
 * `WKWebView` in a Compose slot. The iOS half of [PlatformWebView].
 *
 * JavaScript is left ON, unlike Android's screen which defaults it off. The
 * links this browser is pointed at are TfL's own status and fares pages, and
 * those do not render usefully without it — Android's default was chosen for
 * static first-party policy pages, which is a different kind of content. Any
 * caller sending genuinely untrusted URLs here should reconsider before this
 * grows more entry points.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformWebView(
    url: String,
    handle: WebViewHandle,
    modifier: Modifier,
) {
    // Both remembered against the HANDLE. The delegate carries the callbacks
    // that write loading and error state, and rebuilding either on a
    // recomposition would drop navigation events on the floor mid-load.
    val delegate = remember(handle) { StationlyWebDelegate(handle) }
    val webView = remember(handle) {
        WKWebView(
            frame = CGRectZero.readValue(),
            configuration = WKWebViewConfiguration(),
        ).apply {
            navigationDelegate = delegate
            // Without a UI delegate, a `target="_blank"` link is a dead tap:
            // WebKit asks for a new web view, gets nothing, and drops the
            // navigation silently. TfL's status pages use them.
            UIDelegate = delegate
            allowsBackForwardNavigationGestures = true
        }
    }

    // Wiring the handle's commands lives here, not in the `remember` factory
    // above, because it needs an UNDO. `handle.goBackAction` closes over the
    // web view, the web view's delegate closes over the handle: a retain cycle
    // that outlives the screen unless it is broken on the way out. Leaving it
    // in place kept a whole WKWebView, and whatever page it had loaded, alive
    // for the rest of the session — once per link the user ever opened.
    DisposableEffect(webView) {
        handle.goBackAction = { if (webView.canGoBack) webView.goBack() }
        handle.reloadAction = { webView.reload() }
        onDispose {
            webView.stopLoading()
            webView.navigationDelegate = null
            webView.UIDelegate = null
            handle.goBackAction = null
            handle.reloadAction = null
        }
    }

    // Exactly one load per (view, url).
    //
    // This was an `update` lambda guarded on `view.URL == null`, which is only
    // nil until a load COMMITS — so two updates arriving before the first byte
    // both passed the guard and kicked off competing loads. It also could not
    // react to `url` changing at all.
    LaunchedEffect(webView, url) {
        val target = NSURL.URLWithString(url)
        if (target == null) handle.onFailed()
        else webView.loadRequest(NSURLRequest(uRL = target))
    }

    UIKitView(factory = { webView }, modifier = modifier)
}

@OptIn(ExperimentalForeignApi::class)
private class StationlyWebDelegate(
    private val handle: WebViewHandle,
) : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol {

    // `@ObjCSignatureOverride` on all four navigation callbacks: these
    // Objective-C selectors differ only in their argument LABELS (didStart… /
    // didFinish…, didFail… / didFailProvisional…), and Kotlin erases labels, so
    // each pair collapses to the same Kotlin signature and the compiler rejects
    // them as conflicting overloads. The annotation is how Kotlin/Native says
    // "these really are distinct ObjC methods".

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        handle.onStarted(canGoBack = webView.canGoBack)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        handle.onProgressed(
            canGoBack = webView.canGoBack,
            url = webView.URL?.absoluteString,
            title = webView.title,
            done = true,
        )
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        reportFailure(withError)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        reportFailure(withError)
    }

    /**
     * A CANCELLED load is not a failed one.
     *
     * WebKit reports `NSURLErrorCancelled` for every navigation it supersedes:
     * a server-side redirect, a user tapping a second link before the first
     * settles, and — every single time — our own `stopLoading()` on the way
     * out of the screen. Treating those as failures put the "Couldn't load the
     * page" screen over pages that were loading perfectly well.
     */
    private fun reportFailure(error: NSError) {
        val cancelled = error.domain == NSURLErrorDomain && error.code == NSURL_ERROR_CANCELLED
        if (!cancelled) handle.onFailed()
    }

    /**
     * `target="_blank"` and `window.open` land here, with a null return
     * meaning "no new view". Loading the request into the EXISTING view keeps
     * the user inside one screen with one back stack, which is the whole
     * premise of this browser.
     */
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        if (forNavigationAction.targetFrame == null) {
            webView.loadRequest(forNavigationAction.request)
        }
        return null
    }
}
