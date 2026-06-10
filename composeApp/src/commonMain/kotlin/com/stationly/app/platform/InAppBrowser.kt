package com.stationly.app.platform

/**
 * Open an http(s) URL in the platform's in-app browser, keeping the user
 * inside the app — the counterpart of Android's WebView screen.
 *
 * Returns true when handled in-app; false when the caller should fall back to
 * the external handler (non-web schemes like mailto:, or no presenter
 * available). iOS presents an SFSafariViewController over the Compose host.
 */
expect fun openUrlInApp(url: String, title: String?): Boolean
