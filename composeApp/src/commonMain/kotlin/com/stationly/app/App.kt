package com.stationly.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.stationly.app.navigation.AppNavigation
import com.stationly.app.platform.openUrlInApp
import com.stationly.app.ui.common.AppBusy
import com.stationly.app.ui.common.LoadingOverlay
import com.stationly.app.ui.common.LocalOpenUrl
import com.stationly.app.ui.common.OpenUrl
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.app.ui.theme.StationlyThemeHost

@Composable
fun App(
    authProvider: PlatformAuthProvider,
    startLoggedIn: Boolean = false,
    deepLinkOobCode: String? = null
) {
    val uriHandler = LocalUriHandler.current
    // In-app first (SFSafariViewController on iOS — the user stays inside the
    // app, like Android's WebView screen); external handler only for non-web
    // schemes (mailto:, App Store) or when no presenter is available.
    val openUrl: OpenUrl = remember(uriHandler) {
        { url, title ->
            val handledInApp = runCatching { openUrlInApp(url, title) }.getOrDefault(false)
            if (!handledInApp) runCatching { uriHandler.openUri(url) }
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

    StationlyThemeHost {
        CompositionLocalProvider(LocalOpenUrl provides openUrl) {
            Box(Modifier.fillMaxSize()) {
                AppNavigation(
                    authProvider    = authProvider,
                    startLoggedIn   = startLoggedIn,
                    deepLinkOobCode = deepLinkOobCode
                )
                LoadingOverlay(visible = busyLabel != null, label = busyLabel)
            }
        }
    }
}
