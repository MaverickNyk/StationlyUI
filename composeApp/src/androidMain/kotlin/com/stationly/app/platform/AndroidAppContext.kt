package com.stationly.app.platform

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.stationly.core.platform.Platform
import java.lang.ref.WeakReference

/**
 * Where `composeApp`'s Android `actual`s get a `Context`, and — separately —
 * where they get the Activity that is currently on screen.
 *
 * ## The context
 * Sourced from [Platform], not held again here. `Platform.initialize` runs in
 * the host's `Application.onCreate`, which Android completes before any
 * component of the process starts, and every other Android platform service in
 * the codebase is already built from that same context. A second holder with
 * its own initialisation call would be a second thing to forget.
 *
 * ## The Activity
 * A `Context` is not enough for all of it. Haptics go through
 * `View.performHapticFeedback`, which needs a view — and going through a view
 * rather than `Vibrator` is deliberate: it respects the user's touch-feedback
 * setting and needs no `VIBRATE` permission. AV2-3.3 and AV2-3.4 need an
 * Activity too, for the POST_NOTIFICATIONS request and for interactive Google
 * sign-in. So the tracker is here rather than three times over.
 *
 * Held weakly, and cleared on pause, so a backgrounded Activity is never kept
 * alive by this object and a haptic fired at a screen the user has left finds
 * nothing to fire at.
 */
internal object AndroidAppContext {

    /** @throws UninitializedPropertyAccessException if the host skipped `Platform.initialize`. */
    val context: Context get() = Platform.appContext

    private var activityRef: WeakReference<Activity>? = null
    private var tracking = false

    /**
     * The Activity currently resumed, or null.
     *
     * ## Lazy registration was NOT enough, and the window was not milliseconds
     * This used to register the tracker on first access, reasoning that anything
     * reading it was a response to a touch and so could not run before
     * `onCreate` returned. That was wrong, and AV2-3.3 caught it on a Pixel 7
     * Pro: the shared `NotificationPermissionEffect` reads this from a
     * `LaunchedEffect` on the summary screen's first composition — no touch
     * involved — and composition runs **after** `onActivityResumed`. So the
     * tracker registered too late to hear the only resume that had happened,
     * and `activity` stayed null.
     *
     * Null is not an error to any caller here; each one treats it as "cannot
     * ask". So the POST_NOTIFICATIONS prompt simply never appeared on a fresh
     * install, silently, and stayed missing until the user happened to
     * background the app and come back — the next real `onActivityResumed`.
     * That is a permission prompt you only get one chance at.
     *
     * Hence [StationlyActivityTracker], a content provider that registers
     * before any Activity exists. The lazy path below is kept as a fallback for
     * a host that somehow starts without it.
     */
    val activity: Activity?
        get() {
            ensureTracking()
            return activityRef?.get()
        }

    /**
     * Begin tracking. Idempotent, and safe to call before [Platform] is
     * initialised because the caller supplies the [Application].
     */
    @Synchronized
    internal fun startTracking(app: Application) {
        if (tracking) return
        tracking = true
        register(app)
    }

    @Synchronized
    private fun ensureTracking() {
        if (tracking) return
        val app = runCatching { context.applicationContext as? Application }.getOrNull() ?: return
        tracking = true
        register(app)
    }

    private fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                activityRef = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                // Only clear if it is still OURS. During an A → B transition B
                // resumes before A pauses, so clearing unconditionally would
                // drop the Activity that just arrived.
                if (activityRef?.get() === activity) activityRef = null
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activityRef?.get() === activity) activityRef = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }
}
