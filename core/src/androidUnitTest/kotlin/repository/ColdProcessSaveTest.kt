package com.stationly.core.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.AndroidStorageManager
import com.stationly.db.StationlyDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A process a push started holds no boards in memory. Saving one must not lose the rest. */
class ColdProcessSaveTest {

    private fun row(station: String) = UserSelection(
        mode = "tube", line = "victoria", station = station, stationName = station,
        direction = "southbound", destinations = emptyList(), destinationIds = emptyList(),
    )

    private fun storage(): SqlStorage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        StationlyDatabase.Schema.create(driver)
        return SqlStorage(StationlyDatabase(driver))
    }

    private fun repository(storage: SqlStorage) =
        SelectionRepository(AndroidStorageManager { error("no preferences here") }, storage)

    /** The cache is process-wide, so a cold process is imitated by loading an empty table. */
    private fun coldProcess() = runBlocking { repository(storage()).initialize() }

    private fun SqlStorage.stations() = getAllSelections().map { it.station }.toSet()

    @Test
    fun `a save in a cold process keeps every other board`() = runBlocking {
        val disk = storage().apply { saveSelection(row("V1")); saveSelection(row("V1B")) }
        coldProcess()
        repository(disk).saveSelection(row("IPHONE"), null)
        assertEquals(setOf("V1", "V1B", "IPHONE"), disk.stations())
    }

    @Test
    fun `an edit in a cold process is kept`() = runBlocking {
        val disk = storage().apply { saveSelection(row("V1")); saveSelection(row("V1B")) }
        coldProcess()
        repository(disk).updateSelectionInPlace(row("V1B").copy(parentStationId = "HUB"))
        assertEquals(setOf("V1", "V1B"), disk.stations())
        assertEquals("HUB", disk.getAllSelections().single { it.station == "V1B" }.parentStationId)
    }

    @Test
    fun `the first board of an empty account is saved`() = runBlocking {
        val disk = storage()
        coldProcess()
        repository(disk).saveSelection(row("FIRST"), null)
        assertEquals(setOf("FIRST"), disk.stations())
    }

    @Test
    fun `a loaded cache is left alone`() = runBlocking {
        val disk = storage().apply { saveSelection(row("V1")) }
        coldProcess()
        val repo = repository(disk)
        repo.loadIfEmpty()
        disk.clearSelections() // mid-write, as a screen's save is between its two steps
        repo.loadIfEmpty()
        assertEquals(listOf("V1"), repo.selections.value.map { it.station })
    }
}
