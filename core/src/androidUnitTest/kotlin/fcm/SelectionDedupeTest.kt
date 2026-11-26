package com.stationly.core.fcm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.stationly.core.model.UserSelection
import com.stationly.core.repository.SqlStorage
import com.stationly.db.StationlyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The same board must not appear twice, however many rows say it should.
 *
 * ## Found on hardware, not by reading the code
 * A Pixel 7 Pro on 2026-09-06 had three `UserSelectionEntity` rows for Hackney
 * Wick / Mildmay / inbound. The home screen groups CARDS by hub, so they
 * collapsed into one card — and then the hero strip inside it rendered one entry
 * per selection, so the screenshot showed the same 2-minute Stratford departure
 * twice, side by side, saying the same thing.
 *
 * Nothing in the schema prevents it: `UserSelectionEntity`'s primary key is an
 * AUTOINCREMENT `id` and `insertSelection` is a plain `INSERT`, so any path that
 * saves without clearing first appends. Two of that device's three rows carried
 * `parentStationId` and one did not, which dates the blank one to before hubs
 * existed — or to the AV2-3.1 era, when a v1 door and a v2 door were both
 * writing this table.
 *
 * A `UNIQUE` index is the right long-term answer and it is a schema change, so
 * it needs a migration in a `.sq` shared with a build going to TestFlight. This
 * is the read-side repair, which costs nothing and helps every user who already
 * has duplicates on disk.
 */
class SelectionDedupeTest {

    @Test
    fun `three rows for one board read back as one`() = withStorage { storage ->
        repeat(3) { storage.saveSelection(hackneyWick()) }

        assertEquals(1, storage.getAllSelections().size)
    }

    @Test
    fun `the row carrying a hub wins, whichever was inserted first`() = withStorage { storage ->
        // The blank one FIRST, which is the ordering that makes "keep the first
        // row" the wrong rule: `parentStationId` blank means "same as station",
        // and a bus stop restored without it renders one stop as several
        // identically named cards, one per pole.
        storage.saveSelection(hackneyWick(parent = ""))
        storage.saveSelection(hackneyWick(parent = HACKNEY_WICK))

        val kept = storage.getAllSelections().single()
        assertEquals(HACKNEY_WICK, kept.parentStationId)
    }

    @Test
    fun `collapsing a duplicate does not move the boards around it`() = withStorage { storage ->
        // `selectAllSelections` orders by `id`, and its own comment says why that
        // matters: three surfaces read "your first station" off this order — the
        // widget's configuration picker, the gallery's recommendations, and the
        // station a newly added widget takes. A duplicate collapsing must not
        // promote or demote anything.
        storage.saveSelection(board(EUSTON, "victoria", "inbound"))
        storage.saveSelection(hackneyWick())
        storage.saveSelection(hackneyWick())
        storage.saveSelection(board(EUSTON, "northern", "outbound"))

        assertEquals(
            listOf(
                Triple(EUSTON, "victoria", "inbound"),
                Triple(HACKNEY_WICK, "mildmay", "inbound"),
                Triple(EUSTON, "northern", "outbound"),
            ),
            storage.getAllSelections().map { Triple(it.station, it.line, it.direction) },
        )
    }

    @Test
    fun `two directions of one line are two boards, not a duplicate`() = withStorage { storage ->
        // The dedupe key includes the direction, and this is the test that says
        // so. Dropping it would silently delete half of every two-direction
        // board on the phone — the same shape of loss the PredictionEntity
        // direction column exists to prevent.
        storage.saveSelection(board(HACKNEY_WICK, "mildmay", "inbound"))
        storage.saveSelection(board(HACKNEY_WICK, "mildmay", "outbound"))

        assertEquals(2, storage.getAllSelections().size)
    }

    @Test
    fun `deleting a board removes every row that named it`() = withStorage { storage ->
        // `deleteSelection` matches on (station, line, direction) rather than
        // `id`, so it takes the whole duplicate group. Worth pinning: a delete
        // that removed one row would leave the board on screen and read as the
        // delete having failed.
        repeat(3) { storage.saveSelection(hackneyWick()) }
        storage.deleteSelection(HACKNEY_WICK, "mildmay", "inbound")

        assertEquals(emptyList(), storage.getAllSelections())
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun withStorage(block: (SqlStorage) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        StationlyDatabase.Schema.create(driver).value
        try {
            block(SqlStorage(StationlyDatabase(driver)))
        } finally {
            driver.close()
        }
    }

    private fun hackneyWick(parent: String = HACKNEY_WICK) =
        board(HACKNEY_WICK, "mildmay", "inbound").copy(parentStationId = parent)

    private fun board(station: String, line: String, direction: String) = UserSelection(
        mode = "overground",
        line = line,
        station = station,
        parentStationId = station,
        stationName = "Hackney Wick Rail Station",
        direction = direction,
        destinations = emptyList(),
        destinationIds = emptyList(),
    )

    private companion object {
        const val HACKNEY_WICK = "910GHACKNYW"
        const val EUSTON = "940GZZLUEUS"
    }
}
