package com.stationly.app.platform

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

/**
 * Feedback generators, created ONCE and kept warm.
 *
 * They used to be allocated fresh on every call, with `prepare()` immediately
 * before firing. `prepare()` is not a formality — it wakes the Taptic Engine, and
 * Apple's own guidance is to call it ahead of the event rather than as part of it,
 * precisely because doing both together stalls. For a button tap nobody notices;
 * during a drag-to-reorder, where a tap fires every time a row crosses another,
 * it is an allocation plus an engine spin-up on the main thread at exactly the
 * moment the list is animating. That reads as the drag stuttering.
 *
 * Held as an object rather than top-level `by lazy` so the whole set is created in
 * one go, on first haptic, on the main thread — which is where every caller is.
 */
private object Generators {
    val lightImpact = UIImpactFeedbackGenerator(
        style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
    )
    val notification = UINotificationFeedbackGenerator()
}

actual fun performHaptic(type: HapticType) {
    when (type) {
        HapticType.TAP -> with(Generators.lightImpact) {
            impactOccurred()
            // Re-arm for the next one. Cheap on an already-warm generator, and it
            // is what keeps a rapid sequence — a drag crossing several rows —
            // feeling like one continuous thing rather than a series of restarts.
            prepare()
        }
        HapticType.SUCCESS -> with(Generators.notification) {
            notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            prepare()
        }
        HapticType.ERROR -> with(Generators.notification) {
            notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
            prepare()
        }
    }
}
