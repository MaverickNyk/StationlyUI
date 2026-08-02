package com.stationly.core.platform

actual object LiveStream {
    actual fun notifyForeground() = LiveStreamManager.notifyForeground()
    actual fun notifyBackground() = LiveStreamManager.notifyBackground()
    actual fun notifyPullToRefresh() = LiveStreamManager.notifyPullToRefresh()
}
