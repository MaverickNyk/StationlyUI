package com.stationly.app.platform

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * `startActivityForResult`, awaited — the one piece of plumbing every Android
 * `actual` that has to ask the user something needs.
 *
 * The shared contracts are suspend functions (`signInWithGoogleInteractive`,
 * `requestNotificationAuthorization`, and location behind
 * `getCurrentLocation`), because that is the shape iOS's callback-based APIs
 * wrap into. Android's equivalent is an `ActivityResultLauncher`, which is
 * built for composables and lifecycle owners rather than for a suspend call
 * deep inside a view model. This bridges the two.
 *
 * ## Why the registry directly, and not `rememberLauncherForActivityResult`
 * The callers are not composables. The three-argument
 * [androidx.activity.result.ActivityResultRegistry.register] — the overload
 * with no `LifecycleOwner` — is the one that permits registering at an
 * arbitrary moment; it hands the unregistering back to us, which the `finally`
 * does on every path including cancellation.
 *
 * A unique key per launch, because a key still registered from a previous
 * launch would collide.
 *
 * ## What this deliberately does not survive
 * **Process death while the other app is on screen loses the result.** The
 * registry holds a pending result for a key nobody has re-registered, and
 * nothing here re-registers it — the coroutine that was awaiting it died with
 * the process. The user taps the button again. Surviving that would mean
 * hoisting each flow into saved Activity state, which is a large change for a
 * window measured in the seconds a system dialog is up, with a one-tap recovery.
 *
 * @param activity the Activity to launch from and register against.
 */
internal suspend fun <I, O> awaitActivityResult(
    activity: ComponentActivity,
    contract: ActivityResultContract<I, O>,
    input: I,
): O = withContext(Dispatchers.Main.immediate) {
    val key = "stationly:activity-result:${launchCount.incrementAndGet()}"
    var launcher: ActivityResultLauncher<I>? = null
    try {
        suspendCancellableCoroutine { continuation ->
            val registered = activity.activityResultRegistry.register(key, contract) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            launcher = registered
            registered.launch(input)
        }
    } finally {
        launcher?.unregister()
    }
}

/** Distinct registry keys across concurrent or repeated launches. */
private val launchCount = AtomicInteger()

/**
 * The Activity to put a system dialog in front of: the one currently on screen.
 *
 * Null when the app has none — a background refresh, or the moment after the
 * last Activity is destroyed. Every caller treats that as "cannot ask", which
 * is the honest answer rather than a crash.
 */
internal fun currentActivity(): ComponentActivity? =
    AndroidAppContext.activity as? ComponentActivity
