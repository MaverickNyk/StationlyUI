package com.stationly.app.ui.common

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Android half of [PlatformWebView].
 *
 * composeApp's Android target is DEV-ONLY — the shipping Android app has its
 * own richer `WebViewScreen`. This exists so the shared screen compiles and is
 * usable when running composeApp on an Android device during development, and
 * is deliberately the minimum that behaves correctly.
 *
 * "Correctly" now includes failing visibly: this used to report only
 * `onPageFinished`, so a dead connection left the progress bar spinning
 * forever and the shared error/retry screen — which iOS showed — was
 * unreachable on Android.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun PlatformWebView(
    url: String,
    handle: WebViewHandle,
    modifier: Modifier,
) {
    val context = LocalContext.current

    // Built OUTSIDE the AndroidView factory, mirroring the iOS actual, so the
    // effects below can address it: the load has to be keyed on `url` rather
    // than baked into a factory that runs once, and the view has to be
    // destroyed on the way out.
    val webView = remember(handle) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    handle.onStarted(canGoBack = view.canGoBack())
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    handle.onProgressed(
                        canGoBack = view.canGoBack(),
                        url = url,
                        title = view.title,
                        done = true,
                    )
                }

                // In-page history moves (pushState, fragment jumps) end no
                // load, but they do change what "back" and "open in browser"
                // mean. Reported without clearing `isLoading`.
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    handle.onProgressed(
                        canGoBack = view.canGoBack(),
                        url = url,
                        title = view.title,
                        done = false,
                    )
                }

                // MAIN FRAME ONLY. A page whose analytics beacon or a single
                // web font 404s is not a page that failed to load, and
                // treating it as one would cover perfectly readable content
                // with a retry screen.
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) handle.onFailed()
                }
            }
        }
    }

    // Wiring the handle's commands needs an UNDO: `goBackAction` closes over
    // the web view and the client closes over the handle, so the pair outlives
    // the screen unless it is broken on the way out.
    DisposableEffect(webView) {
        handle.goBackAction = { if (webView.canGoBack()) webView.goBack() }
        handle.reloadAction = { webView.reload() }
        onDispose {
            handle.goBackAction = null
            handle.reloadAction = null
            webView.stopLoading()
        }
    }

    // Exactly one load per (view, url) — and it reacts to `url` changing,
    // which a factory-time `loadUrl` could not.
    LaunchedEffect(webView, url) { webView.loadUrl(url) }

    // `destroy()` belongs in onRelease, not in the DisposableEffect above:
    // it must not run until AndroidView has actually let go of the view.
    AndroidView(
        factory = { webView },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}
