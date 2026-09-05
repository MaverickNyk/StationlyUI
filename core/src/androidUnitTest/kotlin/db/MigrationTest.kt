package com.stationly.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.stationly.db.StationlyDatabase
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The migration, against a real database at the real old shape.
 *
 * ## Why this test is the one that matters
 * `Schema.create` runs ONLY on an empty database. Every development device is a
 * fresh install, so a wrong migration is invisible everywhere except on the
 * phones that have had the app longest — and by then it is a crash in the field.
 * This is the only place that failure can be seen before a user sees it.
 *
 * ## The two shapes
 * `version 1` means two different schemas, because the version number is shared
 * across platforms and the platforms diverged (see
 * `docs/android-v2/analysis/MIGRATION.md` §1.4b):
 *
 *  - **Android**, released, built from the OLD `.sq` — the shape in
 *    `fixtures/v1/schema-v1.sql`. This is what the migration is FOR.
 *  - **iOS**, TestFlight, built from the CURRENT `.sq` by `Schema.create`. It is
 *    stamped version 1 and has nothing to migrate, and the migration must refuse
 *    it without touching anything.
 *
 * Both are tested. The second is not a curiosity: without the guard, an iOS
 * device would have had its filters silently deleted.
 */
class MigrationTest {

    private val repoRoot = File(
        System.getProperty("stationly.repoRoot")
            ?: error("stationly.repoRoot is not set — see core/build.gradle.kts testOptions"),
    )

    private val v1Schema = File(repoRoot, "docs/android-v2/fixtures/v1/schema-v1.sql")

    private val temporaryFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temporaryFiles.forEach { it.delete() }
    }

    /**
     * A real file rather than `:memory:`.
     *
     * The thing under test is what happens to a database that already exists on
     * disk, and a file makes that literal. It also removes any question about
     * whether two driver connections would see the same in-memory database.
     */
    private fun driver(): SqlDriver {
        val file = File.createTempFile("migration-${UUID.randomUUID()}", ".db")
            .also { it.delete(); temporaryFiles += it }
        return JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    }

    private fun SqlDriver.exec(sql: String) = execute(null, sql, 0)

    /** Apply the checked-in v1 DDL, statement by statement. */
    private fun SqlDriver.createV1Schema() {
        v1Schema.readText()
            .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { exec(it) }
    }

    private fun SqlDriver.query(sql: String): List<List<String?>> = executeQuery(
        identifier = null,
        sql = sql,
        parameters = 0,
        mapper = { cursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.next().value) {
                // `PRAGMA table_info` returns six columns; the widest query here
                // is that, so six covers every call.
                rows += (0 until 6).map { runCatching { cursor.getString(it) }.getOrNull() }
            }
            QueryResult.Value(rows.toList())
        },
    ).value

    private fun SqlDriver.tableInfo(table: String) =
        query("PRAGMA table_info($table)").map { it.take(6).joinToString("|") }

    private fun SqlDriver.tableNames() =
        query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .mapNotNull { it.firstOrNull() }

    private fun SqlDriver.indexNames() =
        query("SELECT name FROM sqlite_master WHERE type='index' AND sql IS NOT NULL ORDER BY name")
            .mapNotNull { it.firstOrNull() }

    private fun SqlDriver.count(table: String) =
        query("SELECT COUNT(*) FROM $table").single().first()!!.toInt()

    /** A v1 database with the rows a real phone would have. */
    private fun v1DatabaseWithRows(): SqlDriver = driver().apply {
        createV1Schema()
        // A tube board, and a bus hub whose two directions resolve to different
        // poles — the case every hub bug hides behind.
        exec(
            """
            INSERT INTO UserSelectionEntity(mode, line, station, stationName, direction, destinations, destinationIds)
            VALUES ('tube','victoria','940GZZLUKSX','King''s Cross St. Pancras','southbound','[]','[]'),
                   ('bus','39','490008805N','Smithwood Close','inbound','[]','[]'),
                   ('bus','39','490012211N','Smithwood Close','outbound','[]','[]')
            """.trimIndent()
        )
        exec(
            """
            INSERT INTO PredictionEntity(stationId, lineId, destination, platform, eta, isDue, timestamp, targetEpochMs)
            VALUES ('940GZZLUKSX','victoria','Brixton','Platform 1','2026-09-05T09:14:00Z',0,1757062800000,1757063640000)
            """.trimIndent()
        )
        exec(
            """
            INSERT INTO LineStatusEntity(mode, line, statusSeverityDescription, reason, lastUpdatedTime, timestamp)
            VALUES ('tube','victoria','Good Service',NULL,'2026-09-05T09:00:00Z',1757062800000)
            """.trimIndent()
        )
        exec("INSERT INTO SyncStatusEntity(stationId, lineId, lastSyncMs) VALUES ('940GZZLUKSX','victoria',1757062800000)")
    }

    private fun SqlDriver.migrateToLatest() =
        StationlyDatabase.Schema.migrate(this, 1, StationlyDatabase.Schema.version).value

    // ── The Android upgrade ─────────────────────────────────────────────────

    @Test
    fun `the user's boards survive, in the order they were added`() {
        val driver = v1DatabaseWithRows()
        driver.migrateToLatest()

        assertEquals(3, driver.count("UserSelectionEntity"))
        // `id` is insertion order and `selectAllSelections` orders by it. Three
        // surfaces read "the user's first station" off that order — the widget's
        // configuration picker, the gallery's recommendations, and the station a
        // newly added widget takes — so a rebuild that renumbered would reshuffle
        // all three silently.
        assertEquals(
            listOf("1|940GZZLUKSX", "2|490008805N", "3|490012211N"),
            driver.query("SELECT id, station FROM UserSelectionEntity ORDER BY id")
                .map { "${it[0]}|${it[1]}" },
        )
    }

    @Test
    fun `new columns land on the defaults the schema documents`() {
        val driver = v1DatabaseWithRows()
        driver.migrateToLatest()

        val rows = driver.query(
            "SELECT parentStationId, filterMode, viaKeys, patternIds, routeResolvedAt FROM UserSelectionEntity"
        )
        // Blank parentStationId is not a gap — it means "same as station", which
        // is the correct reading of a row saved before hubs existed.
        assertTrue(rows.all { it[0] == "" }, "parentStationId should be blank: $rows")
        assertTrue(rows.all { it[1] == "ALL" }, "filterMode should be ALL: $rows")
        assertTrue(rows.all { it[2] == "" && it[3] == "" })
        assertTrue(rows.all { it[4] == "0" })
    }

    @Test
    fun `the departure cache is dropped, and the line status is not`() {
        val driver = v1DatabaseWithRows()
        driver.migrateToLatest()

        // Dropped on purpose: old rows have no `direction`, which is now in the
        // primary key and in every board query's WHERE. Backfilling it is
        // guesswork on a bus hub, and the payoff is nil — departures older than
        // about two minutes are filtered out on read anyway, and FCM repopulates
        // within seconds of launch.
        assertEquals(0, driver.count("PredictionEntity"))
        assertEquals(0, driver.count("SyncStatusEntity"))
        // Untouched by the migration, and cheap to keep.
        assertEquals(1, driver.count("LineStatusEntity"))
    }

    @Test
    fun `the activity table arrives and accepts a row`() {
        val driver = v1DatabaseWithRows()
        driver.migrateToLatest()

        driver.exec("INSERT INTO ActivityEventEntity(id, uid, name, t, props) VALUES ('e1','u1','opened',1,'{}')")
        assertEquals(1, driver.count("ActivityEventEntity"))
    }

    @Test
    fun `both directions of one line can now coexist`() {
        // The entire point of the primary-key change. Before it, syncing the
        // eastbound board clear-and-replaced the westbound board's rows, so the
        // two directions overwrote each other on every frame.
        val driver = v1DatabaseWithRows()
        driver.migrateToLatest()

        driver.exec(
            """
            INSERT INTO PredictionEntity(stationId, lineId, direction, destination, platform, eta, timestamp)
            VALUES ('940GZZLUKSX','victoria','southbound','Brixton','P1','09:14',1),
                   ('940GZZLUKSX','victoria','northbound','Brixton','P1','09:14',1)
            """.trimIndent()
        )
        assertEquals(2, driver.count("PredictionEntity"))
    }

    @Test
    fun `no scaffolding is left behind`() {
        val driver = v1DatabaseWithRows()
        driver.migrateToLatest()

        val leftovers = driver.tableNames().filter { it.endsWith("_v1") }
        assertTrue(leftovers.isEmpty(), "temporary rebuild tables survived: $leftovers")
    }

    // ── The assertion that catches .sq / .sqm drift ─────────────────────────

    @Test
    fun `a migrated database is indistinguishable from a created one`() {
        // The `.sqm` duplicates its DDL from the `.sq` by hand, and until
        // `verifyMigrations` is enabled (AV2-2.3) nothing in the build compares
        // them. This does. Drift here would surface only on UPGRADED installs —
        // never on the fresh ones a developer tests with.
        val migrated = v1DatabaseWithRows().apply { migrateToLatest() }
        val created = driver().apply { StationlyDatabase.Schema.create(this).value }

        assertEquals(created.tableNames(), migrated.tableNames(), "table sets differ")
        assertEquals(created.indexNames(), migrated.indexNames(), "index sets differ")

        created.tableNames().forEach { table ->
            assertEquals(
                created.tableInfo(table),
                migrated.tableInfo(table),
                "column definitions differ for $table (name, type, notnull, default and PK position are all compared)",
            )
        }
    }

    // ── Refusing the shapes it must not touch ───────────────────────────────

    @Test
    fun `an iOS-shaped database is refused, and nothing in it is touched`() {
        // iOS ran `Schema.create` against the CURRENT .sq, so its databases are
        // stamped version 1 while already holding everything this migration
        // adds. Running the migration on one would copy only the eight columns
        // an Android v1 row has and silently destroy the rest.
        //
        // `CREATE TABLE ActivityEventEntity` is the FIRST statement in 1.sqm for
        // exactly this reason. It throws before any destructive statement runs —
        // which is why the data below is still intact even though this test
        // wraps the call in no transaction of its own. The real drivers add one;
        // the ordering is what makes it safe without one.
        val driver = driver().apply { StationlyDatabase.Schema.create(this).value }
        driver.exec(
            """
            INSERT INTO UserSelectionEntity(mode, line, station, parentStationId, stationName, direction,
                                            destinations, destinationIds, filterMode, viaKeys, patternIds)
            VALUES ('bus','39','490008805N','490012211N','Smithwood Close','inbound',
                    '[]','["940GZZLUHR1"]','DESTINATIONS','bank','p123')
            """.trimIndent()
        )

        val failure = assertFailsWith<Exception> { driver.migrateToLatest() }
        assertContains(
            failure.message.orEmpty() + failure.cause?.message.orEmpty(),
            "ActivityEventEntity",
            message = "the guard should be the activity table, not something further in: ${failure.message}",
        )

        assertEquals(
            listOf(listOf("490012211N", "DESTINATIONS", "bank", "p123", null, null)),
            driver.query("SELECT parentStationId, filterMode, viaKeys, patternIds FROM UserSelectionEntity")
                .map { it.take(6) },
        )
        assertTrue(driver.tableNames().none { it.endsWith("_v1") })
    }

    @Test
    fun `running the migration twice fails rather than corrupting`() {
        // Migrations are not idempotent and should not pretend to be. A second
        // run hits the same guard as the iOS shape, for the same reason: the
        // activity table is already there.
        val driver = v1DatabaseWithRows()
        driver.migrateToLatest()
        val before = driver.count("UserSelectionEntity")

        assertFailsWith<Exception> { driver.migrateToLatest() }
        assertEquals(before, driver.count("UserSelectionEntity"))
    }
}
