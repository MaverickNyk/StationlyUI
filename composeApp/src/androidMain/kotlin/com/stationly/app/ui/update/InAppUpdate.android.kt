package com.stationly.app.ui.update

import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.stationly.app.platform.AndroidAppContext
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Play In-App Updates — the update Android can take without leaving the app.
 *
 * ## What it is actually asking
 * `AppUpdateManager` answers about the build **Play thinks this install should
 * have**, which is a different question from the one `ReleaseGate` asks. The
 * gate reads our own `/release-policy` and decides whether this version is still
 * allowed; Play decides whether it has a newer APK to give. They normally agree,
 * and when they do not, Play is the one that can act: a gate that says "update"
 * while Play has nothing to offer must still send the user to the listing, which
 * is what returning false does.
 *
 * ## Why this answers "no" on every development device
 * The API only works for an app INSTALLED FROM PLAY. A sideloaded debug build —
 * which is every build this branch has ever run — gets `UPDATE_NOT_AVAILABLE`, or
 * an `install error` from a `FakeAppUpdateManager`-less test track. That is
 * correct behaviour and not a bug to chase, and it is also why none of this can
 * be verified before an internal-track release. Everything below therefore fails
 * SOFT: the store link is the fallback and it is never wrong.
 *
 * ## Flexible vs immediate
 * The nudge starts a FLEXIBLE update: Play downloads in the background, the app
 * stays usable, and [readyToInstall] goes true when the bytes have landed. The
 * install itself restarts the app, so the user is asked first — a restart nobody
 * asked for is a worse interruption than the dialog they just dismissed.
 *
 * The blocked verdict starts an IMMEDIATE one: Play takes the screen and the
 * user cannot proceed without updating. That is not an escalation, it is the
 * same statement the blocking screen is already making, done by the component
 * that can act on it.
 */
actual object InAppUpdate {

    private const val TAG = "InAppUpdate"

    /**
     * Not a result code we read. `startUpdateFlowForResult` requires one, and
     * the honest source of truth for what happened is the install listener plus
     * the next `appUpdateInfo` — not an Activity result that a process death
     * during a Play-hosted flow would lose anyway.
     */
    private const val REQUEST_CODE = 0xA0DE

    actual val supported: Boolean = true

    private val _readyToInstall = MutableStateFlow(false)
    actual val readyToInstall: StateFlow<Boolean> = _readyToInstall.asStateFlow()

    private val manager: AppUpdateManager? by lazy {
        runCatching { AppUpdateManagerFactory.create(AndroidAppContext.context) }.getOrNull()
    }

    /**
     * Registered once, and never unregistered.
     *
     * The listener's whole job is to outlive the composable that started the
     * download — the user carries on using the app, which is the point of a
     * flexible update, and the screen they started it from may be long gone when
     * the bytes land.
     */
    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            Log.d(TAG, "flexible update downloaded — waiting for the user")
            _readyToInstall.value = true
        }
    }

    actual suspend fun startFlexible(): Boolean = start(AppUpdateType.FLEXIBLE)

    actual suspend fun startImmediate(): Boolean = start(AppUpdateType.IMMEDIATE)

    actual fun completeInstall() {
        _readyToInstall.value = false
        runCatching { manager?.completeUpdate() }
            .onFailure { Log.w(TAG, "completeUpdate failed", it) }
    }

    actual fun dismissReady() {
        _readyToInstall.value = false
    }

    private suspend fun start(type: Int): Boolean {
        val manager = manager ?: return false
        // An Activity, because Play renders its own UI over ours. Null means the
        // app is not foregrounded, in which case there is nobody to show it to.
        val activity = AndroidAppContext.activity ?: return false

        val info = awaitUpdateInfo(manager) ?: return false
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
            Log.d(TAG, "no Play update available (availability=${info.updateAvailability()})")
            return false
        }
        if (!info.isUpdateTypeAllowed(type)) {
            // Play refuses some combinations — a flexible update on a metered
            // connection it has decided against, an immediate one for an install
            // it cannot replace in place. Falling back to the listing is the
            // right answer and the user is told nothing they cannot act on.
            Log.d(TAG, "Play refused update type $type")
            return false
        }

        return runCatching {
            if (type == AppUpdateType.FLEXIBLE) manager.registerListener(installListener)
            @Suppress("DEPRECATION")
            manager.startUpdateFlowForResult(info, type, activity, REQUEST_CODE)
            true
        }.getOrElse {
            Log.w(TAG, "could not start the Play update flow", it)
            runCatching { manager.unregisterListener(installListener) }
            false
        }
    }

    /**
     * `appUpdateInfo` is a `Task`, and this module deliberately does not depend
     * on `kotlinx-coroutines-play-services` for one call — the continuation is
     * four lines and adding a coroutines-interop artifact to the SHARED module
     * for it would put a Play dependency in front of the iOS build's face for no
     * reason.
     */
    private suspend fun awaitUpdateInfo(manager: AppUpdateManager): AppUpdateInfo? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                manager.appUpdateInfo
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener {
                        // Every development build lands here. See the KDoc.
                        Log.d(TAG, "appUpdateInfo unavailable: ${it.message}")
                        if (cont.isActive) cont.resume(null)
                    }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
}
