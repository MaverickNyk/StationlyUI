package com.stationly.mobile.dream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Live updates: registers a BroadcastReceiver for [ACTION_DREAM_REFRESH]
 * — our own action, distinct from the widget's component-targeted broadcast.
 * FCM lands → predictions written to SQL → FCM service fires the dream
 * broadcast → [refreshTick] increments → composables observing it re-read
 * SQL and re-render.
 */
class StationlyDreamService : DreamService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        /**
         * Broadcast action the FCM service fires after writing fresh predictions /
         * line status to SQL. The dream subscribes to this to trigger a re-read.
         *
         * Why not reuse ACTION_UPDATE_WIDGET? That intent is dispatched with
         * `setComponent(...DepartureWidgetProvider)` — component-targeted intents
         * only reach the named receiver, so a dynamically-registered listener in
         * a different component (like us) would never see them.
         */
        const val ACTION_DREAM_REFRESH = "com.stationly.mobile.ACTION_DREAM_REFRESH"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    /** Monotonic tick incremented whenever FCM lands a new prediction. */
    private val _refreshTick = MutableStateFlow(0L)
    val refreshTick = _refreshTick.asStateFlow()

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.d("Dream", "Dream refresh broadcast received → re-reading SQL")
            _refreshTick.value = System.currentTimeMillis()
        }
    }

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

        // Listen for the dedicated dream-refresh broadcast that the FCM service
        // fires after writing fresh predictions / line status to SQL. (We can't
        // reuse the widget broadcast — it's component-targeted to the widget
        // provider and never reaches dynamically-registered receivers.)
        val filter = IntentFilter(ACTION_DREAM_REFRESH)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updateReceiver, filter)
        }
        android.util.Log.d("Dream", "Dream attached — listening for $ACTION_DREAM_REFRESH")

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@StationlyDreamService)
            setViewTreeViewModelStoreOwner(this@StationlyDreamService)
            setViewTreeSavedStateRegistryOwner(this@StationlyDreamService)
            setContent {
                DreamHost(refreshTick = refreshTick)
            }
        }
        setContentView(composeView)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { unregisterReceiver(updateReceiver) }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }
}
