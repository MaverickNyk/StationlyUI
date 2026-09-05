package com.stationly.app.platform

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptics through the focused window's view, not through `Vibrator`.
 *
 * Two reasons, and neither is convenience. `View.performHapticFeedback`
 * respects the user's system touch-feedback setting, so turning haptics off in
 * Settings actually turns them off; `Vibrator.vibrate` ignores it. And it needs
 * no `VIBRATE` permission, which means adopting the shared UI does not add a
 * permission to an app that is already live.
 *
 * No Activity on screen means no haptic. That is correct rather than degraded:
 * a haptic is feedback for a touch, and there is nothing to give feedback to.
 */
actual fun performHaptic(type: HapticType) {
    val view: View = AndroidAppContext.activity?.window?.decorView ?: return
    val constant = hapticConstantFor(type, Build.VERSION.SDK_INT)

    // performHapticFeedback touches the view hierarchy, so it belongs on the
    // main thread. Most callers are composables and already there; the support
    // and selection view models are not, and a haptic is not worth a crash.
    if (Looper.myLooper() == Looper.getMainLooper()) {
        view.performHapticFeedback(constant)
    } else {
        Handler(Looper.getMainLooper()).post { view.performHapticFeedback(constant) }
    }
}

/**
 * [HapticType] → `HapticFeedbackConstants`, given an SDK level.
 *
 * The SDK level is a parameter rather than a read of `Build.VERSION.SDK_INT` so
 * this stays a pure function: the mapping is a set of taste decisions, and
 * taste decisions are worth a test.
 *
 * - [HapticType.TAP] → `VIRTUAL_KEY`, the system's "that registered" tick.
 * - [HapticType.SELECTION] → `CLOCK_TICK`, which is lighter and drier. The
 *   shared `HapticType` KDoc is explicit that a detent must not feel like a
 *   press: firing an impact per stop through a drag makes a control feel struck
 *   rather than turned. `CLOCK_TICK` is Android's answer to iOS's selection
 *   generator.
 * - [HapticType.SUCCESS] / [HapticType.ERROR] → `CONFIRM` / `REJECT`, which
 *   exist only from API 30. Below that they fall back to `VIRTUAL_KEY` and
 *   `LONG_PRESS`: a heavier, longer buzz for the failure, so the two outcomes
 *   still feel different on an API 26–29 device.
 */
internal fun hapticConstantFor(type: HapticType, sdkInt: Int): Int = when (type) {
    HapticType.TAP -> HapticFeedbackConstants.VIRTUAL_KEY
    HapticType.SELECTION -> HapticFeedbackConstants.CLOCK_TICK
    HapticType.SUCCESS ->
        if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY
    HapticType.ERROR ->
        if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS
}
