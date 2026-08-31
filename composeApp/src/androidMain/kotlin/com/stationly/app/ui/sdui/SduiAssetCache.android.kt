package com.stationly.app.ui.sdui

/**
 * The Android half of [SduiAssetCache], deliberately empty.
 *
 * `composeApp` compiles for Android, but the shipping Android app is the
 * separate native `android/` module and the widget guide is iOS-only for now
 * (`docs/SDUI.md` §1: iOS first, Android is phase 2). Nothing on this target
 * asks for a cached asset, so an implementation here would be untested code
 * kept alive for a caller that does not exist.
 *
 * Returning null is the contract's own "no media on this device" answer, which
 * `SduiDemoMedia` already handles by falling back to the poster image. So when
 * Android does adopt the guide, it renders stills correctly on the day it turns
 * up and this file is the one place to fill in.
 */
actual object SduiAssetCache {
    actual suspend fun localPath(url: String): String? = null
    actual fun cachedPath(url: String): String? = null
}
