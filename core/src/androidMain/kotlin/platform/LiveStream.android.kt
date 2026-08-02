package com.stationly.core.platform

/**
 * Android keeps FCM + REST for predictions/line status — the live stream is
 * iOS-only (see LiveStream.ios.kt). Every member here is a deliberate no-op
 * so this actual never changes Android's runtime behaviour.
 */
actual object LiveStream {
    actual fun notifyForeground() {}
    actual fun notifyBackground() {}
    actual fun notifyPullToRefresh() {}
}
