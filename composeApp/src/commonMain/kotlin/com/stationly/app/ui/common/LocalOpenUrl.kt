package com.stationly.app.ui.common

import androidx.compose.runtime.compositionLocalOf

/**
 * Shape of "open this URL somewhere appropriate" — first arg is the URL,
 * second arg is the title the in-app browser's top bar should wear (ignored by
 * the external-browser fallback).
 *
 * Compose-Multiplatform port of the Android `ui/common/UrlOpener.kt`. The
 * wiring in `App.kt` keeps http(s) inside Stationly's own
 * `InAppBrowserScreen` on every platform and hands everything else — mailto:,
 * tel:, App Store — to the system `UriHandler`. Screens deep in the tree
 * (the explore sheets, Profile link rows, Announcement CTAs) read this local
 * so they don't each need to know how URLs get opened.
 */
typealias OpenUrl = (url: String, title: String?) -> Unit

/** No-op default; `App.kt` provides a real opener backed by `LocalUriHandler`. */
val LocalOpenUrl = compositionLocalOf<OpenUrl> { { _, _ -> } }
