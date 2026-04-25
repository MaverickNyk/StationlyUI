package com.stationly.mobile

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.stationly.core.platform.Platform
import com.stationly.mobile.widget.DepartureWidgetProvider

class StationlyApplication : Application() {
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = DepartureWidgetProvider.triggerRefresh(c)
    }

    override fun onCreate() {
        super.onCreate()
        Platform.initialize(this, BuildConfig.STATIONLY_API_KEY)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }
}
