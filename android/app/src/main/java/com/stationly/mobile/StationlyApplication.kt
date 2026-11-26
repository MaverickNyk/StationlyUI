package com.stationly.mobile

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.stationly.core.platform.AppEnvironment
import com.stationly.core.platform.Platform
import com.stationly.mobile.service.ActivityUploadWorker
import com.stationly.mobile.service.AuthIdentityPublisher
import com.stationly.mobile.service.AuthLog
import com.stationly.mobile.service.BroadcastTopic
import com.stationly.mobile.service.FcmTokenRegistrar
import com.stationly.mobile.service.StationlyNotificationChannels
import com.stationly.mobile.widget.DepartureWidgetProvider
import com.stationly.mobile.widget.V1WidgetAdoption
import com.stationly.mobile.widget.WidgetDefaultSize
import com.stationly.core.config.FeatureFlags
import com.stationly.core.config.SduiConfig
import com.stationly.core.repository.StationNoticeStore
import com.stationly.core.service.NetworkModule
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import com.stationly.mobile.util.FreshDataNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StationlyApplication : Application() {
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = DepartureWidgetProvider.triggerRefresh(c)
    }

    override fun onCreate() {
        super.onCreate()
        val env = if (BuildConfig.FLAVOR == "staging") AppEnvironment.STAGING else AppEnvironment.PRODUCTION
        Platform.initialize(this, BuildConfig.STATIONLY_API_KEY, env)
        AuthLog.init(this)
        // Widgets placed by v1 keep their station. Does nothing on any other start.
        V1WidgetAdoption.onAppStart(this)
        // Mirror FirebaseAuth's user into the shared session store. Until this
        // existed, `AuthBridge.swift` was the ONLY writer of the identity keys
        // the shared UI reads, so every Android device drew "?" for its avatar
        // and registered an anonymous device session. See AuthIdentityPublisher.
        AuthIdentityPublisher.start()
        com.stationly.mobile.ui.util.NetworkState.init(this)
        // Register every Stationly notification channel up front so the
        // first FCM-driven status change has a channel to land in
        // without an extra round-trip into the dispatcher's lazy setup.
        // createNotificationChannel is idempotent at the system level so
        // running this on every cold launch is essentially free.
        StationlyNotificationChannels.ensureCreated(this)

        // Push this device's FCM token to the backend so `uid`-targeted
        // admin notifications can resolve to it. Idempotent + cheap
        // (cached "last registered" pair skips the network call when
        // nothing has changed since last launch). Safe to call BEFORE
        // user signs in — FcmTokenRegistrar bails out when no auth.
        FcmTokenRegistrar.ensureRegistered(this)

        // Subscribe to the global broadcast topic, once per install. The topic,
        // its guard and the one event that invalidates the guard all live in
        // `BroadcastTopic` now — they were spelled out here and, after AV2-4.2
        // added the token-rotation re-subscribe, in `FcmMessagingService` too.
        BroadcastTopic.ensureSubscribed(this)

        // The nightly activity drain. Enqueued on every cold start and `KEEP`
        // on the server side of WorkManager, so this is "make sure it is
        // scheduled" rather than "schedule it again" — the work survives
        // process death, reboots and app updates, and ten launches a day still
        // produce one run a day.
        ActivityUploadWorker.schedule(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        redrawOnStationNotices()
        // Last: it can redraw every widget, which needs all of the above.
        WidgetDefaultSize.onAppStart(this)
    }

    /**
     * A closure reaches the widget and the screensaver however it was learned: a
     * push, or the app asking. The home screen watches the store itself.
     *
     * What is stored is read first, so that a process starting does not redraw
     * every widget for what they already show.
     */
    private fun redrawOnStationNotices() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            SduiConfig.ensureLoaded()
            StationNoticeStore.ensureLoaded()
            launch {
                FeatureFlags.on.map { FeatureFlags.STATION_STATUS in it }.distinctUntilChanged().drop(1).collect {
                    // Just switched on: ask, rather than wait for the next open.
                    StationNoticeStore.refresh(Platform.sqlStorage.getAllSelections()) { NetworkModule.tflApi.getStationNotices(it) }
                    FreshDataNotifier.notifyAll(this@StationlyApplication)
                }
            }
            var before = StationNoticeStore.all.value
            StationNoticeStore.all.collect { now ->
                val changed = (before.keys + now.keys).filter { before[it] != now[it] }
                before = now
                if (FeatureFlags.isOn(FeatureFlags.STATION_STATUS)) {
                    changed.forEach { FreshDataNotifier.notifyPredictions(this@StationlyApplication, it) }
                }
            }
        }
    }
}
