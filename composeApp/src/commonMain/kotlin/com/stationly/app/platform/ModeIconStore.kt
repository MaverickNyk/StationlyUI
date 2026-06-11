package com.stationly.app.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Lightweight payload type for [ModeIconStore.sync] — mirrors Android's
 * ModeIconCache.ModeSyncEntry so both platforms feed from the same SDUI
 * `/modes` response fields.
 */
data class ModeIconEntry(
    val modeName: String,
    val iconUrl: String?,
    val tintHex: String?,
)

/**
 * On-disk cache for the per-mode roundel icons + tint colours served by the
 * backend's `/modes` endpoint — the shared-UI analog of Android's
 * ModeIconCache. On iOS the files live in the APP GROUP container
 * (`mode_icons/<mode>.png`, `tints.json`, `version.txt`) so the SwiftUI
 * widget extension renders the same roundels as the in-app board
 * (ModeIconProvider in the widget reads the identical layout).
 *
 * Fall-back chain at render time (same as Android):
 *   1. [cachedIconBitmap]    → real backend roundel
 *   2. tints.json colour     → drawn roundel tinted per backend
 *   3. null                  → caller's hardcoded mode colour
 *
 * The Android actual is a no-op: the shipping Android app is the native
 * `android/` module with its own ModeIconCache; composeApp's Android target
 * doesn't drive a widget.
 */
expect object ModeIconStore {
    /** Download missing icons + write the tint map; wipes PNGs on version bump. */
    suspend fun sync(entries: List<ModeIconEntry>, iconVersion: String?)

    /** True when a cached PNG exists for the mode (cheap existence check). */
    fun hasIcon(mode: String?): Boolean

    /** Decoded cached icon, memory-cached after first read; null on miss. */
    fun cachedIconBitmap(mode: String?): ImageBitmap?
}
