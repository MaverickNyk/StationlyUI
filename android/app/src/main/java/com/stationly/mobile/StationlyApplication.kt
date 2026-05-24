package com.stationly.mobile

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.stationly.core.platform.AppEnvironment
import com.stationly.core.platform.Platform
import com.stationly.mobile.service.AuthLog
import com.stationly.mobile.service.FcmTokenRegistrar
import com.stationly.mobile.service.StationlyNotificationChannels
import com.stationly.mobile.widget.DepartureWidgetProvider

class StationlyApplication : Application() {
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = DepartureWidgetProvider.triggerRefresh(c)
    }

    override fun onCreate() {
        super.onCreate()
        val env = if (BuildConfig.FLAVOR == "staging") AppEnvironment.STAGING else AppEnvironment.PRODUCTION
        Platform.initialize(this, BuildConfig.STATIONLY_API_KEY, env)
        AuthLog.init(this)
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

        // Subscribe to the global broadcast topic. Powers
        // `audience: { type: "all" }` admin pushes WITHOUT any
        // Firestore reads — FCM handles the fan-out internally to every
        // device subscribed. SharedPrefs tracks the subscription so we
        // only hit the FCM SDK once per cold-launch (after a successful
        // subscribe, the IO is local to the GMS process).
        val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("subscribed_all_topic", false)) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .subscribeToTopic("stationly_all")
                .addOnSuccessListener { prefs.edit().putBoolean("subscribed_all_topic", true).apply() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }
}
