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
     * ## Why lazy registration is enough
     * Registering on first access means the tracker starts during the first
     * composition — inside `Activity.onCreate`, before `onActivityResumed` has
     * fired — so for a few milliseconds there is a resumed Activity that this
     * object does not know about. That window closes as soon as `onCreate`
     * returns, and everything reading this is a response to a user touching the
     * screen, which cannot happen before then. The alternative is a
     * `ContentProvider` or an `androidx.startup` `Initializer` purely to be
     * eager, which is a manifest entry and a dependency for a race with nobody
     * in it.
     */
    val activity: Activity?
        get() {
            ensureTracking()
            return activityRef?.get()
        }

    @Synchronized
    private fun ensureTracking() {
        if (tracking) return
        val app = context.applicationContext as? Application ?: return
        tracking = true
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
