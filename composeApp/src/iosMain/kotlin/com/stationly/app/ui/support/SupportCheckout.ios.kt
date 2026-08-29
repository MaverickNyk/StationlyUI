package com.stationly.app.ui.support

import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * `SFSafariViewController`, presented over whatever is on screen.
 *
 * ## Why not the in-app WebView, and why not Safari proper
 * WKWebView — which is what `InAppBrowserScreen` is — cannot show the Apple Pay
 * sheet. `SFSafariViewController` can: it is a real Safari instance, so the
 * payment sheet, saved cards and Face ID all work exactly as they do on the web,
 * and Stripe's hosted page is built for it.
 *
 * Handing the URL to `UIApplication.openURL` would also work for payment, but it
 * switches apps. The user watches Stationly disappear, pays in Safari, and comes
 * back through a deep link that has to relaunch or resume the app — with the
 * checkout page still sitting in their Safari tabs afterwards. Presenting keeps
 * the whole thing inside Stationly's own task, and dismissal returns them
 * exactly where they were.
 *
 * ## Presented from the top-most controller
 * Not from the root: by the time a user reaches this, the Compose host may have
 * a sheet or a pushed screen above it, and presenting from the root would either
 * fail outright or slide Safari in under whatever is already there. Walking to
 * the top is what makes this work from the profile card and from the home banner
 * alike.
 */
/**
 * The checkout sheet currently on screen, so it can be taken down again.
 *
 * A plain reference rather than a weak one: it holds exactly one view
 * controller, it is replaced on the next open, and [dismissCheckout] clears it.
 * The alternative — asking the window for whatever is presented and dismissing
 * that — would happily dismiss one of the app's own sheets on any path where
 * Safari had already been closed by hand.
 */
private var presentedCheckout: SFSafariViewController? = null

actual fun openCheckout(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    // SFSafariViewController rejects anything that is not http(s) — a malformed
    // or non-web URL would raise an ObjC exception rather than return nil, and
    // an exception here crashes the app on a button tap.
    val scheme = nsUrl.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return

    val presenter = topMostViewController() ?: return
    val safari = SFSafariViewController(uRL = nsUrl)
    // A page sheet, not full screen: the sliver of the app left visible at the
    // top says this is a step inside Stationly that can be swiped away, rather
    // than a place the user has been sent to.
    safari.modalPresentationStyle = UIModalPresentationPageSheet
    presentedCheckout = safari
    presenter.presentViewController(safari, animated = true, completion = null)
}

actual fun dismissCheckout() {
    val safari = presentedCheckout ?: return
    presentedCheckout = null
    // `presentingViewController` is nil once it has already gone — the user
    // swiped it away, or paid and came back through a path that closed it.
    // Calling dismiss on that would walk up to whoever IS presenting and take
    // down the app's own sheet instead.
    if (safari.presentingViewController != null) {
        safari.dismissViewControllerAnimated(true, null)
    }
}

/**
 * The controller currently in front of the user.
 *
 * Found through `connectedScenes` rather than `UIApplication.keyWindow`, which
 * is deprecated and, under the scene lifecycle this app uses, can answer nil at
 * exactly the moment a modal is being presented. The same walk `AuthBridge`
 * does in Swift to present the Apple sign-in sheet, for the same reason.
 *
 * Then follows the presentation chain, because our own sheets are what will
 * usually be in front: presenting from the root while a sheet is up either
 * fails outright or slides Safari in underneath it. Depth-capped so a
 * pathological cycle cannot spin the main thread.
 */
private fun topMostViewController(): UIViewController? {
    val keyWindow = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
        .firstOrNull { it.keyWindow }
    var current: UIViewController = keyWindow?.rootViewController ?: return null
    var guard = 0
    while (guard++ < 32) {
        val next = current.presentedViewController ?: return current
        current = next
    }
    return current
}
