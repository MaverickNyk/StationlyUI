package com.stationly.core.platform

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DriverFactory): com.stationly.db.StationlyDatabase {
    val driver = driverFactory.createDriver()
    return com.stationly.db.StationlyDatabase(driver)
}
