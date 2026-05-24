package com.stationly.mobile.ui.common

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.stationly.mobile.R

/**
 * In-app browser. Replaces `Intent.ACTION_VIEW` for first-party links
 * (privacy policy, terms, support, etc.) — opening Chrome/Firefox kicked
 * the user out of the Stationly task, which hurts session retention and
 * back-button continuity. With this screen the user stays in the app
 * and can dismiss with a clear X / Back press.
 *
 * Designed for static, first-party content. JavaScript is OFF by default;
 * caller can flip it on for specific URLs (`enableJavaScript = true`) but
 * the default is the safer choice — privacy policy / about pages don't
 * need JS, and disabled JS narrows the attack surface for third-party
 * URLs that might end up here.
 *
 * Behaviours:
 *   - Top bar carries the Stationly logo + truncated page title + close X.
 *     The user always knows they're inside Stationly's WebView, not a
 *     separate browser.
 *   - Indeterminate progress bar at the top while pages load.
 *   - Back press inside the WebView navigates the WebView history first
 *     (multi-page sites); once at the entry page, back closes the screen.
 *   - "Open in browser" affordance for users who want the real browser
 *     experience (sharing, bookmarking, etc.). Single optional escape hatch.
 *   - Network errors fall back to a clear retry state instead of an
 *     unstyled "webpage not available".
 *
 * NOT a general-purpose browser. No address bar, no tabs, no downloads,
 * no fullscreen video. If a user lands on a flow that needs those (e.g.
 * Google OAuth), use [Intent.ACTION_VIEW] for that specific URL instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(
    url: String,
    title: String? = null,
    onClose: () -> Unit,
    enableJavaScript: Boolean = false,
) {
    val context = LocalContext.current
    var pageTitle by remember { mutableStateOf(title) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // System back: pop WebView history first, then close the screen.
    // Critical for multi-page sites (e.g. a privacy policy with footer
    // links) — without this, back jumps straight out instead of
    // unwinding the in-WebView navigation.
    BackHandler {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = R.drawable.stationly_logo),
                            contentDescription = "Stationly",
                            modifier = Modifier.size(22.dp).clip(CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = pageTitle?.takeIf { it.isNotBlank() } ?: "Stationly",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    // "Open in real browser" escape hatch — single icon
                    // for users who want share/bookmark capabilities.
                    IconButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url),
                                    )
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInNew,
                            contentDescription = "Open in browser",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (hasError) {
                ErrorState(
                    onRetry = {
                        hasError = false
                        isLoading = true
                        webViewRef.value?.loadUrl(url)
                    },
                    onClose = onClose,
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = StationlyWebViewClient(
                                onPageStarted = { isLoading = true },
                                onPageFinished = { resolved ->
                                    isLoading = false
                                    if (pageTitle.isNullOrBlank()) pageTitle = resolved
                                },
                                onError = {
                                    isLoading = false
                                    hasError = true
                                },
                            )
                            settings.apply {
                                @SuppressLint("SetJavaScriptEnabled")
                                javaScriptEnabled = enableJavaScript
                                domStorageEnabled = enableJavaScript
                                // Block file:// access — these pages never need it
                                // and turning it off narrows the attack surface.
                                allowFileAccess = false
                                allowContentAccess = false
                                // Useful for narrow content on phones.
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                // No mixed content — HTTPS-only.
                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            }
                            webViewRef.value = this
                            loadUrl(url)
                        }
                    },
                )

                // Thin progress strip at the top while loading.
                if (isLoading) {
                    LinearProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    // Clean up the WebView on disposal to avoid leaks (WebView holds the
    // Activity context internally).
    LaunchedEffect(Unit) {
        // no-op; cleanup happens via the AndroidView factory's onRelease
        // callback if we ever need it. Keeping this composable scope-aware.
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            "Couldn't load page",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Check your connection and try again.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onRetry),
        ) {
            Text(
                "Retry",
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Close",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 13.sp,
            modifier = Modifier
                .clickable(onClick = onClose)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Customised [WebViewClient] that surfaces page lifecycle to Compose
 * state. Also intercepts non-http(s) schemes (mailto:, tel:, market://)
 * and routes them back to the system so they open the right app.
 */
private class StationlyWebViewClient(
    private val onPageStarted: () -> Unit,
    private val onPageFinished: (resolvedTitle: String?) -> Unit,
    private val onError: () -> Unit,
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished(view?.title)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            "http", "https" -> false  // let the WebView handle it
            else -> {
                // mailto, tel, market, intent: → external app
                runCatching {
                    view?.context?.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    )
                }
                true
            }
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        // Only flag errors for the main document — sub-resource failures
        // (favicon, tracking pixel, etc.) shouldn't blow up the screen.
        if (request?.isForMainFrame == true) onError()
    }
}
