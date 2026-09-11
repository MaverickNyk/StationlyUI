package com.stationly.app.ui.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS has no in-app update and will not get one.
 *
 * An App Store update is the App Store's to perform; an app cannot install its
 * own binary. So every entry point here answers "no", and `UpdateSurfaces` does
 * what it has always done on this platform — opens the listing.
 *
 * A no-op rather than a `TODO()`: this is not unimplemented, it is the correct
 * and permanent answer, and a throw would turn the one button on the blocking
 * screen into a crash.
 */
actual object InAppUpdate {

    actual val supported: Boolean = false

    private val _readyToInstall = MutableStateFlow(false)
    actual val readyToInstall: StateFlow<Boolean> = _readyToInstall.asStateFlow()

    actual suspend fun startFlexible(): Boolean = false

    actual suspend fun startImmediate(): Boolean = false

    actual fun completeInstall() = Unit

    actual fun dismissReady() = Unit
}
