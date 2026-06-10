package com.stationly.app.platform

import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIApplication

/**
 * Presents an SFSafariViewController over the top-most view controller —
 * the iOS-native "in-app browser" (Reader, share sheet, Safari cookies,
 * swipe-down dismiss) without leaving the app.
 */
actual fun openUrlInApp(url: String, title: String?): Boolean {
    val nsUrl = NSURL.URLWithString(url) ?: return false
    // SFSafariViewController only accepts http/https; mailto:, App Store
    // links etc. must go to the external handler.
    val scheme = nsUrl.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false

    val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return false
    var top = root
    while (top.presentedViewController != null) top = top.presentedViewController!!
    top.presentViewController(SFSafariViewController(uRL = nsUrl), animated = true, completion = null)
    return true
}
