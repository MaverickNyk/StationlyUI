package com.stationly.core.repository

import com.stationly.core.config.FeatureFlags
import com.stationly.core.model.StationNotice
import com.stationly.core.model.StationNotices
import com.stationly.core.model.UserSelection
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the phone keeps, replaces and forgets. */
class StationNoticeStoreTest {

    private val later = System.currentTimeMillis() + 3_600_000

    private fun notice(id: String, expiresAt: Long = later) =
        StationNotice(id, "closed", "Station closed", emptyList(), "Station closed", "why", from = 0, to = later, expiresAt = expiresAt)

    private fun board(id: String) = UserSelection(
        mode = "tube", line = "piccadilly", station = id, stationName = id, direction = "inbound",
        destinations = emptyList(), destinationIds = emptyList(),
    )

    private fun covering(vararg ids: String) = ids.associateWith { 1_000L }

    private val held get() = StationNoticeStore.all.value.keys

    @BeforeTest
    fun on() {
        StationNoticeStore.resetForTest()
        FeatureFlags.refresh(mapOf("flag.stationStatus.show" to "true"))
    }

    @AfterTest
    fun off() {
        StationNoticeStore.resetForTest()
        FeatureFlags.resetForTest()
    }

    @Test
    fun `an answer replaces what it covers and leaves the rest alone`() = runBlocking {
        StationNoticeStore.adopt(StationNotices(covering("a", "b", "c"), listOf(notice("a"), notice("b"))))
        // The backend has just restarted: it knows about "a" only.
        assertTrue(StationNoticeStore.adopt(StationNotices(covering("a"), emptyList())))
        assertEquals(setOf("b"), held)
        assertFalse(StationNoticeStore.adopt(StationNotices()))
        assertEquals(setOf("b"), held)
        StationNoticeStore.adopt(StationNotices(covering("b"), emptyList()))
        assertEquals(emptySet(), held)
    }

    @Test
    fun `a push closes and clears and rubbish does neither`() = runBlocking {
        val sent = """{"id":"a","kind":"closed","title":"Station closed","details":["Due to a fire alert"],"strip":"Station closed",
            "reason":"r","directions":[],"lines":[],"from":0,"to":$later,"expiresAt":$later,"somethingNew":1}"""
        assertTrue(StationNoticeStore.adoptPush(sent))
        for (bad in listOf(null, "", "x", "{}", """{"id":"a"}""", """{"kind":"none"}""", "[]")) assertFalse(StationNoticeStore.adoptPush(bad), "$bad")
        assertEquals(setOf("a"), held)
        assertFalse(StationNoticeStore.adoptPush("""{"id":"b","kind":"none"}"""))
        assertTrue(StationNoticeStore.adoptPush("""{"id":"a","kind":"none"}"""))
        assertEquals(emptySet(), held)
    }

    @Test
    fun `an older message never overwrites a newer one, whatever order they arrive in`() = runBlocking {
        val closed = { at: Long -> """{"id":"a","kind":"closed","title":"t","strip":"s","to":$later,"expiresAt":$later,"at":$at}""" }
        assertTrue(StationNoticeStore.adoptPush(closed(200)))
        assertFalse(StationNoticeStore.adoptPush("""{"id":"a","kind":"none","at":100}"""), "the late 'cleared' is ignored")
        assertEquals(setOf("a"), held)
        assertFalse(StationNoticeStore.adopt(StationNotices(mapOf("a" to 150L), emptyList())), "so is an answer asked before it")
        assertTrue(StationNoticeStore.adoptPush("""{"id":"a","kind":"none","at":300}"""))
        assertFalse(StationNoticeStore.adoptPush(closed(250)), "and a late 'closed' after it reopened")
        assertEquals(emptySet(), held)
        assertTrue(StationNoticeStore.adopt(StationNotices(mapOf("a" to 400L), listOf(notice("a")))))
        assertEquals(setOf("a"), held)
    }

    @Test
    fun `one stop's old answer does not cost another its new one`() = runBlocking {
        StationNoticeStore.adopt(StationNotices(mapOf("rail" to 500L, "bus" to 500L), listOf(notice("rail"), notice("bus"))))
        // Buses were last read before that; rail since.
        StationNoticeStore.adopt(StationNotices(mapOf("rail" to 600L, "bus" to 100L), emptyList()))
        assertEquals(setOf("bus"), held)
    }

    @Test
    fun `an expired notice is dropped`() = runBlocking {
        StationNoticeStore.adopt(StationNotices(covering("a", "b"), listOf(notice("a", expiresAt = 1), notice("b"))))
        assertEquals(setOf("b"), held)
    }

    @Test
    fun `asking is one request, not repeated at once, and a failure changes nothing`() = runBlocking {
        val asked = mutableListOf<List<String>>()
        suspend fun ask(now: Long, vararg ids: String, answer: () -> StationNotices) =
            StationNoticeStore.refresh(ids.map(::board), now) { asked += it; answer() }

        assertTrue(ask(100_000, "a", "a", "b") { StationNotices(covering("a", "b"), listOf(notice("a"))) })
        assertFalse(ask(105_000, "a") { error("asked again at once") })
        assertEquals(listOf(listOf("a", "b")), asked)
        assertFalse(ask(106_000, "c") { StationNotices(covering("c")) }, "a new stop is asked about")
        assertEquals(2, asked.size)
        assertFalse(ask(200_000, "a") { error("offline") })
        assertEquals(setOf("a"), held)
        assertTrue(ask(200_001, "a") { StationNotices(covering("a")) }, "a failure may be tried again straight away")
        assertEquals(emptySet(), held)
    }

    @Test
    fun `off it asks nothing`() = runBlocking {
        FeatureFlags.resetForTest()
        assertFalse(StationNoticeStore.refresh(listOf(board("a")), 1) { error("asked") })
    }
}
