package com.stationly.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.theme.LocalThemeTokens

/**
 * Stationly's own browser. The iOS counterpart of Android's `WebViewScreen`,
 * and what replaced `SFSafariViewController`.
 *
 * ## It is meant to read as one of our screens
 * Not as a browser that happens to be embedded. So it wears exactly the chrome
 * every other pushed screen in this app wears — a `CenterAlignedTopAppBar` on
 * the theme background with a back arrow on the left and a bold 18sp title,
 * the same shape `DreamSettingsScreen` and the rest use — and the root pushes
 * it in from the right like a navigation destination. There is no address bar,
 * no domain chip, no browser furniture. A user should feel they tapped through
 * to a page in Stationly, not that they were handed off somewhere.
 *
 * The one concession is the "open in browser" action on the right, kept
 * because a page you cannot share or bookmark is a dead end, and it is the
 * single affordance that admits this content is someone else's.
 *
 * Not a general browser. No tabs, no downloads. If a link ever needs those —
 * an OAuth flow, say — send that one URL to the system handler instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppBrowserScreen(
    url: String,
    title: String?,
    onClose: () -> Unit,
) {
    val t = LocalThemeTokens.current
    val uriHandler = LocalUriHandler.current
    val handle = remember(url) { WebViewHandle() }

    Scaffold(
        // Opaque, and it swallows taps.
        //
        // This screen animates in OVER the whole app rather than replacing it,
        // so the home screen is still composed and still hit-testable
        // underneath. Without a consumer here, a tap landing in the web view's
        // margins reached the board behind it and navigated the app out from
        // under the page the user was reading.
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            // OUR label first, the page's own title only as a
                            // fallback. The other way round meant the bar
                            // opened as "TfL Fares" and then swapped itself to
                            // "Transport for London | Every Journey Matters"
                            // the moment the page settled — a visible flicker,
                            // into a title that is longer, truncated, and not
                            // in our voice. Never the URL: a raw tfl.gov.uk
                            // path is the one thing that would make this read
                            // as a browser rather than a screen.
                            text = title ?: handle.pageTitle ?: "",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            // Back unwinds the page's own history first and
                            // only then leaves. A fares page with footer links
                            // would otherwise throw the user out of the screen
                            // entirely on their first back press.
                            if (handle.canGoBack) handle.goBack() else onClose()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (handle.canGoBack) "Back" else "Close",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    },
                    actions = {
                        // The page they are LOOKING at, not the one the
                        // screen was opened with. Following two links inside
                        // the page and then tapping this used to bounce the
                        // user back to where they started.
                        IconButton(onClick = {
                            runCatching { uriHandler.openUri(handle.currentUrl ?: url) }
                        }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = "Open in browser",
                                tint = t.textSubtle,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor    = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
                // Indeterminate: WKWebView's progress is not wired through the
                // handle, and a bar that honestly says "still working" beats a
                // percentage that stalls at 80.
                AnimatedVisibility(handle.isLoading, enter = fadeIn(), exit = fadeOut()) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = t.primary,
                        trackColor = t.borderSubtle,
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            PlatformWebView(url = url, handle = handle, modifier = Modifier.fillMaxSize())

            if (handle.hasError) ErrorState(onRetry = handle::reload)
        }
    }
}

/** Shown over the web view when the main frame failed. Retry reloads in place. */
@Composable
private fun ErrorState(onRetry: () -> Unit) {
    val t = LocalThemeTokens.current
    Column(
        Modifier.fillMaxSize().background(t.canvas).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Couldn't load the page",
            color = t.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Check your connection and try again.",
            color = t.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "Retry",
            color = t.onPrimaryContainer,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 18.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(t.primaryContainer)
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
