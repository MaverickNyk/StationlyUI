package com.stationly.app

import androidx.compose.ui.window.ComposeUIViewController
import com.stationly.app.platform.IosPlatformAuthProvider
import platform.UIKit.UIViewController

/**
 * Entry point called from Swift ContentView.
 *
 * @param startLoggedIn  true when Firebase token is already present in NSUserDefaults —
 *                       Swift checks this before calling in and passes the result.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(
    startLoggedIn: Boolean = false,
    deepLinkOobCode: String? = null
): UIViewController {
    val authProvider = IosPlatformAuthProvider()
    return ComposeUIViewController {
        App(
            authProvider   = authProvider,
            startLoggedIn  = startLoggedIn,
            deepLinkOobCode = deepLinkOobCode
        )
    }
}
