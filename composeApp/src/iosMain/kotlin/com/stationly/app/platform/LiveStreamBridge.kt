package com.stationly.app.platform

import com.stationly.core.platform.LiveStream as CoreLiveStream

// Exported from composeApp so Swift can access it via composeApp.xcframework —
// same reasoning as PushPayloadBridge: Swift only sees what's declared in the
// ROOT framework module's ObjC header, not core's own declarations directly.
object LiveStreamBridge {
    fun notifyForeground() = CoreLiveStream.notifyForeground()
    fun notifyBackground() = CoreLiveStream.notifyBackground()
}
