package com.stationly.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import com.stationly.app.navigation.AppNavigation
import com.stationly.app.platform.openUrlInApp
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
    StationlyThemeHost {
        CompositionLocalProvider(LocalOpenUrl provides openUrl) {
            AppNavigation(
                authProvider    = authProvider,
                startLoggedIn   = startLoggedIn,
                deepLinkOobCode = deepLinkOobCode
            )
        }
    }
}
