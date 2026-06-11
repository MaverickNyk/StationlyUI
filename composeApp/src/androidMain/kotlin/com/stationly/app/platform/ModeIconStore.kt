package com.stationly.app.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * No-op on Android: the shipping Android app is the native `android/` module
 * with its own ModeIconCache (filesDir/mode_icons). composeApp's Android
 * target has no widget to feed, so callers fall through to the drawn roundel.
 */
actual object ModeIconStore {
    actual suspend fun sync(entries: List<ModeIconEntry>, iconVersion: String?) = Unit
    actual fun hasIcon(mode: String?): Boolean = false
    actual fun cachedIconBitmap(mode: String?): ImageBitmap? = null
}
