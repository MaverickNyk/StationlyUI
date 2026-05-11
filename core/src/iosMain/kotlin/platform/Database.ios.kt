package com.stationly.core.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.stationly.db.StationlyDatabase

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(StationlyDatabase.Schema, "stationly.db")
    }
}
