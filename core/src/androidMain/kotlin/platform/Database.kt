package com.stationly.core.platform

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.stationly.db.StationlyDatabase

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = StationlyDatabase.Schema,
            context = context,
            name = "stationly.db",
            callback = object : AndroidSqliteDriver.Callback(StationlyDatabase.Schema) {
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    super.onUpgrade(db, oldVersion, newVersion)
                    // Version 1 is the released v1 app. See [V1Upgrade].
                    if (oldVersion == 1) V1Upgrade.mark(context)
                }
            },
        )
    }
}
