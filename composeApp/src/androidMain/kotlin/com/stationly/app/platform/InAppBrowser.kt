package com.stationly.app.platform

// composeApp's Android target is dev-only (the shipping android/ app has its
// own WebView screen via UrlOpener) — fall back to the external handler.
actual fun openUrlInApp(url: String, title: String?): Boolean = false
