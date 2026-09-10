package com.stationly.app.platform

import com.stationly.core.model.sdui.DeviceInfo

/**
 * Stable per-install device identity for multi-device session tracking —
 * the iOS/composeApp counterpart of `android/`'s `DeviceIdProvider`.
 *
 * The backend keys its `sessions` map by [deviceId] so a station's
 * subscription is only released when the user's LAST device logs out.
 * The id must therefore survive logout (which wipes regular app storage)
 * and never rotate for the lifetime of the install.
 */
expect object DeviceIdentity {
    fun deviceId(): String
    fun deviceInfo(): DeviceInfo
}
