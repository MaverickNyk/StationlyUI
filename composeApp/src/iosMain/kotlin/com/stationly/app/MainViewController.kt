package com.stationly.app

import androidx.compose.ui.window.ComposeUIViewController
import com.stationly.app.platform.IosPlatformAuthProvider
import platform.UIKit.UIViewController

@Suppress("FunctionName", "unused")
fun MainViewController(
    startLoggedIn: Boolean = false,
    deepLinkOobCode: String? = null
): UIViewController {
    val authProvider = IosPlatformAuthProvider()
    return ComposeUIViewController {
        App(
            authProvider    = authProvider,
            startLoggedIn   = startLoggedIn,
            deepLinkOobCode = deepLinkOobCode
        )
    }
}
