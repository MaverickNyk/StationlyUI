package com.stationly.mobile

import android.app.Application
import com.stationly.core.platform.Platform
import com.stationly.core.platform.DriverFactory

class StationlyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize KMP Platform with appContext
        Platform.initialize(this)
        
        // Database initialization can also happen here if needed via a singleton
    }
}
