package com.stationly.app.platform

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

actual fun performHaptic(type: HapticType) {
    when (type) {
        HapticType.TAP -> {
            val generator = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
            generator.prepare()
            generator.impactOccurred()
        }
        HapticType.SUCCESS -> {
            val generator = UINotificationFeedbackGenerator()
            generator.prepare()
            generator.notificationOccurred(notificationType = UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
        }
        HapticType.ERROR -> {
            val generator = UINotificationFeedbackGenerator()
            generator.prepare()
            generator.notificationOccurred(notificationType = UINotificationFeedbackType.UINotificationFeedbackTypeError)
        }
    }
}
