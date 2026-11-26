package com.stationly.core.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.UserProfileResponse
import com.stationly.core.model.user.Board
import com.stationly.core.model.user.BoardSelection
import com.stationly.core.platform.NotificationManager
import com.stationly.core.platform.StorageManager
import com.stationly.core.platform.WidgetManager
import com.stationly.core.service.SduiApiService
import com.stationly.core.service.TflApiService
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import com.stationly.db.StationlyDatabase
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import com.stationly.core.config.BoardQuota
import kotlin.test.assertEquals

/**
 * The owner's steps of 2026-11-24 through the real sync code: delete a board on
 * Android, then add one. Live iOS 1.1 was left with only the new one; run on its
 * source (`fab76d6`), this reproduced it.
 */
class BackgroundSyncReplayTest {

    private inline fun <reified T> fake(crossinline answer: (String, Array<Any?>) -> Any?): T = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(T::class.java),
    ) { _, m, a -> answer(m.name, a ?: emptyArray<Any?>()) } as T

    private val prefs = mutableMapOf<String, String>()
    private val storage = fake<StorageManager> { name, a ->
        when (name) {
            "loadString", "loadDurable" -> prefs[a[0] as String]
            "saveString", "saveDurable" -> { prefs[a[0] as String] = a[1] as String; Unit }
            else -> Unit
        }
    }

    private val sql = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
        StationlyDatabase.Schema.create(it)
        SqlStorage(StationlyDatabase(it))
    }

    private fun row(id: String) = UserSelection(
        mode = "tube", line = "victoria", station = id, stationName = id, direction = "southbound",
        destinations = emptyList(), destinationIds = emptyList(),
    )

    private var cloud = listOf<String>()
    private var rev = 0L
    private val api = fake<SduiApiService> { name, _ ->
        when (name) {
            "getUserStateRev" -> rev
            "getUserProfile" -> UserProfileResponse(
                uid = "u", email = "", displayName = "", stations = emptyList(), boardsUpdatedAt = 1, stateRev = rev,
                boards = cloud.map { Board(id = it, name = it, selections = listOf(BoardSelection(it, "victoria", "tube", "southbound"))) },
            )
            else -> error("unexpected $name")
        }
    }

    private val selections = SelectionRepository(storage, sql)
    private val lifecycle = StationLifecycleUseCase(
        selections,
        DepartureRepository(fake<TflApiService> { n, _ -> error(n) }, storage, sql, SyncPredictionsUseCase(sql)),
        fake<NotificationManager> { _, _ -> Unit }, fake<WidgetManager> { _, _ -> Unit }, sql, storage,
    )
    private val sync = UserSyncRepository(api, sql, storage)

    private fun push(vararg boards: String, fromV1: Boolean = false) = runBlocking {
        cloud = boards.toList(); rev++
        sync.reconcileBoards("u", lifecycle, rev, keepLocalBoards = fromV1)
    }

    private fun onPhone() = sql.getAllSelections().map { it.station }.toSet()

    /** A process iOS started for a push: nothing in memory, four boards on disk. */
    private fun backgroundProcess(vararg boards: String) = runBlocking {
        sql.clearSelections()
        selections.initialize() // empties the process-wide list, as a new process has it
        boards.forEach { sql.saveSelection(row(it)) }
    }

    @Test
    fun `a background process loses nothing when a board is added`() {
        backgroundProcess("A", "B", "C", "D")
        push("A", "B", "C")
        assertEquals(setOf("A", "B", "C"), onPhone())
        push("A", "B", "C", "NEW")
        assertEquals(setOf("A", "B", "C", "NEW"), onPhone())
    }

    @Test
    fun `nor when one push both adds and removes`() {
        backgroundProcess("A", "B", "C")
        push("A", "C", "NEW")
        assertEquals(setOf("A", "C", "NEW"), onPhone())
    }

    @Test
    fun `a phone already left short gets the rest at the next change`() {
        backgroundProcess("NEW")
        push("A", "B", "NEW")
        assertEquals(setOf("A", "B", "NEW"), onPhone())
    }

    @Test
    fun `with a screen loaded nothing changes`() {
        backgroundProcess("A", "B", "C", "D")
        runBlocking { selections.initialize() }
        push("A", "B", "C")
        push("A", "B", "C", "NEW")
        assertEquals(setOf("A", "B", "C", "NEW"), onPhone())
    }

    @Test
    fun `removing the last board in the background leaves none`() {
        backgroundProcess("A")
        push()
        assertEquals(emptySet(), onPhone())
    }

    // ── The first launch after a v1 → v2 upgrade (2026-11-26) ──
    //
    // An account used on iOS (or a v2 phone) holds a real board list; the v1
    // phone holds what v1 let it add, which the board list has never seen. The
    // upgrade must MERGE: keep the phone's board and bring in the account's.

    // The served limit, as the merge must have one before it leaves anything out.
    private val served = BoardQuota.servedLimit
    @BeforeTest fun servedLimit() { BoardQuota.servedLimit = { 4 } }
    @AfterTest fun reset() { BoardQuota.servedLimit = served }

    @Test
    fun `the first launch after a v1 upgrade keeps the phone's board and adds the account's`() {
        backgroundProcess("V1")
        push("A", "B", "C", fromV1 = true)
        assertEquals(setOf("V1", "A", "B", "C"), onPhone())
    }

    @Test
    fun `but not past the station limit - the Pixel's five`() {
        backgroundProcess("V1")
        push("A", "B", "C", "D", fromV1 = true)
        assertEquals(setOf("V1", "A", "B", "C"), onPhone())
    }

    @Test
    fun `with no served limit to go by it leaves nothing out`() {
        BoardQuota.servedLimit = { null } // no fresh answer from the server
        backgroundProcess("V1")
        push("A", "B", "C", "D", fromV1 = true)
        assertEquals(setOf("V1", "A", "B", "C", "D"), onPhone())
    }

    @Test
    fun `an ordinary sync is never cut, so a lowered limit removes nothing`() {
        backgroundProcess("A", "B", "C", "D", "E")
        push("A", "B", "C", "D", "E")
        assertEquals(setOf("A", "B", "C", "D", "E"), onPhone())
    }

    @Test
    fun `and a board both sides have is not set up twice`() {
        backgroundProcess("A")
        push("A", "B", fromV1 = true)
        assertEquals(listOf("A", "B"), sql.getAllSelections().map { it.station }.sorted())
    }

    @Test
    fun `after the upgrade the account is the truth again`() {
        backgroundProcess("V1")
        push("A", "B", fromV1 = true)
        push("A", "B", "V1") // the merged list, as the upgrade publishes it
        push("A", "V1") // B deleted on the other device
        assertEquals(setOf("A", "V1"), onPhone())
    }

    @Test
    fun `boards brought in by a merge keep the account's order`() {
        backgroundProcess("V1")
        push("A", "B", "C", fromV1 = true)
        assertEquals(listOf("A", "B", "C", "V1"), sql.getAllSelections().map { it.station })
    }

    @Test
    fun `a reconcile says whether it applied the account, which a full save depends on`() {
        backgroundProcess("A")
        push("A", "B")
        assertEquals(true, sync.lastReconcileApplied)
        runBlocking { sync.reconcileBoards("u", lifecycle, rev) } // same rev: the gate stays shut
        assertEquals(false, sync.lastReconcileApplied)
    }
}
