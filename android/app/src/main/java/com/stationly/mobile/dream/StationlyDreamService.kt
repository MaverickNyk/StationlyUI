package com.stationly.mobile.dream

import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
import com.stationly.app.ui.dream.DreamHost
import com.stationly.app.ui.theme.StationlyThemeHost
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Stationly Android screensaver (Daydream).
 *
 * The system binds this service when the device is docked or charging AND the
 * user has chosen Stationly under Settings → Display → Screensaver. We don't
 * trigger it from app code — it's a pure system feature.
 *
 * Hosts a [ComposeView] for the actual UI. DreamService is a [android.app.Service]
 * which is NOT a LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner
 * out of the box, so we implement those ourselves and set them as the tree
 * owners on the ComposeView — that's what makes coroutines + Flow + animations
 * work inside the dream.
 *
 * ## What it hosts, since AV2-6.2
 * `:composeApp`'s `DreamHost` — the same screensaver iOS runs, reading the v2
 * board model. v1's copy in this package rendered one selection; the shared one
 * renders the whole board, filters applied, which is what the rest of the app
 * has shown since the cutover.
 *
 * Live updates need no plumbing here any more. The shared host collects
 * `FreshDataNotifier.events`, the process-wide flow that the FCM service already
 * emits to after writing predictions to SQL — so the dream refreshes from the
 * same signal as the home screen and the widget, rather than from a broadcast
 * that existed only because v1's dream could not hear it.
 */
class StationlyDreamService : DreamService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Dream tuning:
        //   isInteractive   = true  → touches are delivered to the views (so
        //                             the rows ScrollView can be dragged) and
        //                             do NOT auto-dismiss the dream. Exit is
        //                             via the power button / system gesture.
        //   isFullscreen    = true  → hide status bar
        //   isScreenBright  = true  → keep brightness at normal (not the dim
        //                             AOD-style brightness)
        isInteractive  = true
        isFullscreen   = true
        isScreenBright = true

        // Render edge-to-edge through the display cutout. Combined with the
        // FullscreenBoardLayout's symmetric cutout-mirror padding, this lets
        // the dream draw across the entire screen while the board itself is
        // visually centred — the camera-side cutout is mirrored by a
        // matching padding on the opposite side. We also clear
        // decorFitsSystemWindows so Compose receives the full insets
        // (including DisplayCutout) on every render.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window?.let { w ->
                val attrs = w.attributes
                attrs.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                w.attributes = attrs
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            }
        }

        // No broadcast receiver any more. The shared `DreamHost` collects
        // `com.stationly.core.util.FreshDataNotifier.events` — the same flow the
        // home screen and the widget refresh from, in this same process — so a
        // push that writes predictions reaches the dream directly. The old
        // ACTION_DREAM_REFRESH broadcast existed because v1's dream had no way
        // to hear the shared flow: it was a second delivery mechanism for one
        // signal, and two mechanisms for one signal is how they stop agreeing.

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@StationlyDreamService)
            setViewTreeViewModelStoreOwner(this@StationlyDreamService)
            setViewTreeSavedStateRegistryOwner(this@StationlyDreamService)
            setContent {
                // The SHARED dream, as of AV2-6.2. `StationlyThemeHost` is what
                // provides `LocalAppTheme`, which is how the dream's SYSTEM
                // theme setting resolves to the APP's choice rather than to the
                // device's — the same precedence v1 had, now expressed by
                // composing the app's theme host rather than by reading a
                // preferences file twice.
                StationlyThemeHost {
                    DreamHost(onExit = { finish() })
                }
            }
        }
        setContentView(composeView)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }
}
