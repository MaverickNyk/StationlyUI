package com.stationly.app

import androidx.compose.runtime.Composable
import com.stationly.app.navigation.AppNavigation
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.app.ui.theme.StationlyTheme

@Composable
fun App(
    authProvider: PlatformAuthProvider,
    startLoggedIn: Boolean = false,
    deepLinkOobCode: String? = null
) {
    StationlyTheme {
        AppNavigation(
            authProvider    = authProvider,
            startLoggedIn   = startLoggedIn,
            deepLinkOobCode = deepLinkOobCode
        )
    }
}
