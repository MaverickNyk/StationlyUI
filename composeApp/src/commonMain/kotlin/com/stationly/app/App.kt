package com.stationly.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import com.stationly.app.navigation.AppNavigation
import com.stationly.app.ui.common.LocalOpenUrl
import com.stationly.app.ui.common.OpenUrl
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.app.ui.theme.StationlyTheme

@Composable
fun App(
    authProvider: PlatformAuthProvider,
    startLoggedIn: Boolean = false,
    deepLinkOobCode: String? = null
) {
    val uriHandler = LocalUriHandler.current
    // iOS has no in-app WebView screen yet (a later parity phase), so hand off
    // to the platform handler (Safari). Title is ignored until the WebView lands.
    val openUrl: OpenUrl = remember(uriHandler) {
        { url, _ -> runCatching { uriHandler.openUri(url) } }
    }
    StationlyTheme {
        CompositionLocalProvider(LocalOpenUrl provides openUrl) {
            AppNavigation(
                authProvider    = authProvider,
                startLoggedIn   = startLoggedIn,
                deepLinkOobCode = deepLinkOobCode
            )
        }
    }
}
