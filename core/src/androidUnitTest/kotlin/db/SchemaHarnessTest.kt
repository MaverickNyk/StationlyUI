package com.stationly.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.stationly.db.StationlyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the harness the migration tests will be written against.
 *
 * Not a test of the schema — a test that we can *reach* the schema from an
 * ordinary JVM unit test at all. The migration is the one piece of this
 * programme whose failure mode is invisible on a development device (every one
 * of them is a fresh install, and `Schema.create` only ever runs on an empty
 * database), so the ability to open a real SQLite file, put it at a chosen
 * version, and read back what happened is the whole safety net.
 *
 * If this file stops compiling, the migration tests have no floor to stand on.
 */
class SchemaHarnessTest {

    private fun driver() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @Test
    fun `schema creates on an empty database`() {
        val driver = driver()
        StationlyDatabase.Schema.create(driver).value

        val tables = driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
            parameters = 0,
            mapper = { cursor ->
                val found = mutableListOf<String>()
                while (cursor.next().value) found += cursor.getString(0)!!
                app.cash.sqldelight.db.QueryResult.Value(found.toList())
            }
        ).value

        // Named individually rather than compared as a set: a new table added to
        // the .sq should not fail this test, but a missing one must.
        listOf(
            "UserSelectionEntity",
            "PredictionEntity",
            "LineStatusEntity",
            "SyncStatusEntity",
            "ActivityEventEntity",
        ).forEach { assertTrue(it in tables, "missing table $it; found $tables") }

        driver.close()
    }

    /**
     * A deliberate tripwire, and the only test here that is *meant* to fail.
     *
     * SQLDelight derives the database version from the migration COUNT, so this
     * number is the contract between `StationlyDatabase.sq` and
     * `migrations/N.sqm`: no migrations means version 1, one migration means
     * version 2. The failure mode being guarded is a schema change made to the
     * `.sq` alone — which reaches fresh installs and nothing else, and then
     * fails at runtime on the phones that have had the app longest.
     *
     * Bumped to 2 by AV2-2.1, which added `migrations/1.sqm` — the Android
     * upgrade. It fired exactly as intended. **Bump it again with the next
     * migration; do not delete it to make it pass.**
     */
    @Test
    fun `the schema version matches the migration count`() {
        assertEquals(2L, StationlyDatabase.Schema.version)
    }
}
