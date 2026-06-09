package com.stationly.app.ui.common

import androidx.compose.runtime.compositionLocalOf

/**
 * Shape of "open this URL somewhere appropriate" — first arg is the URL,
 * second arg is an optional display title (used by the Android in-app
 * WebView's top bar; ignored by the external-browser fallback).
 *
 * Compose-Multiplatform port of the Android `ui/common/UrlOpener.kt`. iOS has
 * no in-app WebView screen yet (a later parity phase), so the default wiring
 * provided in `App.kt` hands off to the platform `UriHandler` (Safari /
 * `SFSafariViewController`). Screens deep in the tree (ExploreSection fare
 * dialog, Profile link rows, Announcement CTAs) read this local so they don't
 * each need to know how URLs get opened.
 */
typealias OpenUrl = (url: String, title: String?) -> Unit

/** No-op default; `App.kt` provides a real opener backed by `LocalUriHandler`. */
val LocalOpenUrl = compositionLocalOf<OpenUrl> { { _, _ -> } }
