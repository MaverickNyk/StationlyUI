package com.stationly.app.platform

enum class HapticType {
    TAP, SUCCESS, ERROR
}

expect fun performHaptic(type: HapticType)
