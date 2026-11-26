package com.stationly.app.ui.update

import kotlinx.coroutines.flow.StateFlow

/**
 * Taking an update without leaving the app — which is a thing exactly one of the
 * two platforms can do.
 *
 * iOS has no equivalent and is not going to: an App Store update is the App
 * Store's business, so `UpdateSurfaces` links out and that is the whole story
 * there. Android has Play In-App Updates, and `README.md` rule 3 is explicit
 * that Android should not inherit an iOS workaround for an iOS-only constraint.
 * So the shared surfaces ask this first and fall back to the store link.
 *
 * ## Two flows, because there are two statements
 * - [startImmediate] for the BLOCKED verdict. Play takes the screen and the user
 *   cannot proceed without updating, which is precisely what the blocking screen
 *   already says — so the button stops being "go and find the update" and starts
 *   being the update.
 * - [startFlexible] for the NUDGE. The download runs in the background while the
 *   app stays usable, and [readyToInstall] turns true when it has finished. The
 *   user is asked before anything restarts, because a restart they did not ask
 *   for is a worse interruption than the dialog they just dismissed.
 *
 * ## Every entry point can answer "no", and the caller must handle it
 * Sideloaded builds, debug builds, devices with no Play Store, an account with
 * no update available, a flow the user cancels — all of them are ordinary, and
 * all of them return false rather than throwing. False means "link out", which
 * is the behaviour that existed before this and is never wrong.
 */
expect object InAppUpdate {

    /** Whether this platform has any such thing. */
    val supported: Boolean

    /**
     * A flexible update has finished downloading and is waiting to be installed.
     *
     * Always false on a platform where [supported] is false, so the surface that
     * renders it simply never appears there.
     */
    val readyToInstall: StateFlow<Boolean>

    /** The nudge path. Returns whether the flow actually started. */
    suspend fun startFlexible(): Boolean

    /** The blocked path. Returns whether the flow actually started. */
    suspend fun startImmediate(): Boolean

    /** Install what [readyToInstall] is announcing. Restarts the app. */
    fun completeInstall()

    /**
     * Stop asking for this app run.
     *
     * Not a snooze and not a cancel: the update stays downloaded and Play
     * installs it on the next natural restart, so there is nothing to schedule
     * and nothing to undo. Ask once per launch.
     */
    fun dismissReady()
}
