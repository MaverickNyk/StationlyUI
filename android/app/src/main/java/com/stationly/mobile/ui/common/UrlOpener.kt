package com.stationly.mobile.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.compositionLocalOf

/**
 * Shape of "open this URL somewhere appropriate" — first arg is the URL,
 * second arg is an optional display title for the webview's top bar.
 *
 * Two implementations:
 *   - [defaultExternalOpener] : `Intent.ACTION_VIEW` — boots the user out
 *     to Chrome / their default browser. Last-resort fallback.
 *   - The one provided in MainActivity → wraps the URL in an in-app
 *     [WebViewScreen] so the user stays in Stationly.
 */
typealias OpenUrl = (url: String, title: String?) -> Unit

/**
 * CompositionLocal so screens deep in the tree (SduiComponentRenderer,
 * ProfileScreen's link rows, AnnouncementBanner CTAs) can open URLs
 * without each one having to know about the NavController. AppNavigation
 * provides the in-app WebView wiring at the top of the tree; preview /
 * test contexts fall back to the external opener default.
 */
val LocalOpenUrl = compositionLocalOf<OpenUrl> { defaultExternalOpener(null) }

/**
 * Fallback opener — boots to the OS browser via `Intent.ACTION_VIEW`.
 * `context` is captured at provider time so the inner lambda doesn't
 * need to thread it through every call site. Pass `null` for preview /
 * test contexts where startActivity isn't safe; the call becomes a no-op.
 */
fun defaultExternalOpener(context: Context?): OpenUrl = { url, _ ->
    runCatching {
        context?.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
