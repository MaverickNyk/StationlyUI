package com.stationly.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.stationly.app.navigation.AppNavigation
import com.stationly.app.ui.common.AppBusy
import com.stationly.app.ui.common.InAppBrowserScreen
import com.stationly.app.ui.common.LoadingOverlay
import com.stationly.app.ui.common.LocalOpenUrl
import com.stationly.app.ui.common.OpenUrl
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.app.ui.support.SupportReturn
import com.stationly.app.ui.support.dismissCheckout
import com.stationly.app.ui.support.SupportSheet
import com.stationly.app.ui.support.SupportThanksOverlay
import com.stationly.app.ui.support.SupportViewModel
import com.stationly.app.ui.support.LocalSupport
import com.stationly.app.ui.theme.StationlyThemeHost
import com.stationly.app.ui.update.UpdateSurfaces
import androidx.compose.runtime.LaunchedEffect
import com.stationly.core.config.ReleaseGate
import com.stationly.core.service.NetworkModule
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * A link open in Stationly's own browser.
 *
 * A named pair rather than `Pair<String, String?>`, whose `.first` / `.second`
 * at the call site say nothing about which of two strings is the URL.
 */
private data class BrowserLink(val url: String, val title: String?)

private fun isWebUrl(url: String): Boolean =
    // Matched on the PREFIX rather than `substringBefore(':')`: that returns
    // the whole string when there is no colon at all, so a bare "tfl.gov.uk"
    // was classed as a non-web scheme and handed to the system, which cannot
    // open it either.
    url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)

@Composable
fun App(
    authProvider: PlatformAuthProvider,
    startLoggedIn: Boolean = false,
    deepLinkOobCode: String? = null
) {
    val uriHandler = LocalUriHandler.current

    /**
     * The link currently open in Stationly's own browser, or null.
     *
     * ## Why a state flag and not a presented controller
     * iOS used to hand http(s) links to `openUrlInApp`, which presented an
     * `SFSafariViewController` over the top-most view controller. That put a
     * UIKit controller above the Compose host and backgrounded the app, and
     * coming back left whatever launched it in an unrecoverable state — the
     * explore cards stopped responding to taps, with nothing on screen to
     * explain it and no fix short of force-quitting. Two attempts to sequence
     * the dismissal around it did not hold, because the race was never the
     * real problem: presenting a controller over Compose was.
     *
     * It also left iOS without the in-app browser Android has always had
     * (`android/.../ui/common/WebViewScreen.kt`).
     *
     * Opening a link is now an ordinary state change at the app root, and
     * [InAppBrowserScreen] is an ordinary screen. Nothing is presented,
     * nothing is backgrounded, and there is no teardown to race.
     */
    var browserLink: BrowserLink? by remember { mutableStateOf(null) }

    /**
     * The link the browser should RENDER, which lags [browserLink] by one exit
     * animation. Closing sets `browserLink` to null immediately, and without
     * somewhere to hold the previous value the screen would blank on the first
     * frame of its slide-out: the user would watch an empty panel leave rather
     * than the page they were reading.
     *
     * Written from the OPEN callback, in the same breath as [browserLink] —
     * never from the composition, and never from an effect. An effect runs
     * after the frame that started the enter animation, so the panel slid in
     * empty for one frame and then filled. A plain event handler has neither
     * problem: both states are set before anything recomposes.
     */
    var renderedLink: BrowserLink? by remember { mutableStateOf(null) }

    val openUrl: OpenUrl = remember(uriHandler) {
        { url, title ->
            // http(s) stays inside Stationly. Everything else — mailto:, App
            // Store, tel: — has to leave, because a web view cannot service it.
            if (isWebUrl(url)) {
                val link = BrowserLink(url, title)
                renderedLink = link
                browserLink = link
            } else {
                runCatching { uriHandler.openUri(url) }
            }
        }
    }

    /**
     * Work that ends in a NAVIGATION, covered from the app ROOT.
     *
     * Deliberately here and not inside `AppNavigation`: a [LoadingOverlay] is a
     * composable belonging to whichever screen raises it, so it dies with that
     * screen. Deleting a station covered its own teardown and then popped
     * itself, at which point the cover went too and the user watched the home
     * screen assemble — cards re-flowing into the deleted one's space, the pager
     * clamping back to page zero.
     *
     * Above the NavHost, so it spans the pop. Inside [StationlyThemeHost],
     * because it paints with theme colours. See [AppBusy] for what raises it and
     * what brings it down.
     */
    val busyLabel by AppBusy.label.collectAsState()

    /**
     * The support feature's state, held at the ROOT rather than per screen.
     *
     * Three things force it up here. The thank-you has to survive the user
     * tapping through to Profile mid-celebration. The checkout return arrives
     * from a platform callback that knows nothing about which screen is showing.
     * And the banner's decision — may we ask at all — is read by the home screen
     * but written by the profile card, which is a different subtree.
     */
    val supportViewModel: SupportViewModel = viewModel {
        SupportViewModel(uidProvider = { authProvider.currentUserUid() })
    }
    val support by supportViewModel.uiState.collectAsState()

    /**
     * Resolve the update verdict, from the ROOT rather than from a screen.
     *
     * Here because the verdict is a property of the install and every entry
     * point has to be covered: the old check ran in `SummaryViewModel`, so a
     * launch straight into a board, a widget tap or a deep link never reached
     * it. `App` is the one composable all of those pass through.
     *
     * Cache first, then network. The cached document makes a cold launch decide
     * immediately rather than after a round trip, and it is the only answer
     * available offline. A failed fetch leaves the cached verdict standing and
     * is deliberately silent — "we could not ask" is not evidence of anything,
     * and the hard gate does not depend on this call succeeding anyway: a build
     * the backend refuses to serve gets a 426 on its next request whatever this
     * document says.
     */
    LaunchedEffect(Unit) {
        ReleaseGate.loadFromCache()
        runCatching { NetworkModule.sduiApi.getReleasePolicy() }
            .onSuccess { ReleaseGate.adopt(it) }
    }

    // A return from checkout, raised by the platform's deep-link callback.
    val pendingThanks by SupportReturn.thanks.collectAsState()
    LaunchedEffect(pendingThanks?.nonce) {
        pendingThanks?.let {
            // FIRST. The deep link activates the app but leaves the checkout
            // sheet presented over it, so everything below this line would
            // otherwise happen behind Safari, invisibly.
            dismissCheckout()
            supportViewModel.onCheckoutReturned(it)
            SupportReturn.consume()
        }
    }

    StationlyThemeHost {
        CompositionLocalProvider(
            LocalOpenUrl provides openUrl,
            LocalSupport provides supportViewModel,
        ) {
            Box(Modifier.fillMaxSize()) {
                AppNavigation(
                    authProvider    = authProvider,
                    startLoggedIn   = startLoggedIn,
                    deepLinkOobCode = deepLinkOobCode
                )
                LoadingOverlay(visible = busyLabel != null, label = busyLabel)

                // Pushed in from the right, over everything including any open
                // sheet, so it reads as a destination the user navigated TO
                // rather than a panel that appeared. Above LoadingOverlay for
                // the same reason a pushed screen sits above its parent.
                AnimatedVisibility(
                    visible = browserLink != null,
                    enter = slideInHorizontally(tween(260)) { it },
                    exit = slideOutHorizontally(tween(220)) { it },
                ) {
                    renderedLink?.let { link ->
                        InAppBrowserScreen(
                            url = link.url,
                            title = link.title,
                            onClose = { browserLink = null },
                        )
                    }
                }

                // ── Support ──────────────────────────────────────────────
                //
                // Both live at the root, above every screen and above the
                // in-app browser, because both outlive the screen that raised
                // them: the sheet is opened from Profile and from the home
                // banner, and the thank-you must not be cancelled by whatever
                // navigation the user does while it is playing.
                if (support.sheetVisible) {
                    SupportSheet(
                        config = support.config,
                        strings = support.strings,
                        contributionCount = support.contributionCount,
                        onPay = { tier -> supportViewModel.payTier(tier) },
                        onDismiss = { supportViewModel.closeSheet() },
                    )
                }

                SupportThanksOverlay(
                    visible = support.thanksAmountMinor != null,
                    config = support.config,
                    strings = support.strings,
                    amountMinor = support.thanksAmountMinor ?: 0,
                    contributionCount = support.contributionCount,
                    onDismiss = { supportViewModel.onThanksDismissed() },
                )

                // ── Update gate ──────────────────────────────────────────
                //
                // LAST, so it composes above everything: every destination, the
                // in-app browser, the support sheet and the thank-you. A block
                // that anything can draw over is not a block, and the surfaces
                // above are exactly the ones that outlive a screen.
                UpdateSurfaces()
            }
        }
    }
}
