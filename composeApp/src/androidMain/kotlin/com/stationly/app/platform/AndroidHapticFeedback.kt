package com.stationly.app.platform

actual fun performHaptic(type: HapticType) {
    // No-op for Android target in composeApp (unused Target)
}
