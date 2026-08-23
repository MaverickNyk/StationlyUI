package com.stationly.app.ui.summary

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Which station to put in front of the user, and what that permits moving.
 *
 * The [BoardFocus.Kind] distinction is the whole point of these tests. Without
 * it a restore behaved like a widget tap and force-expanded the card, which
 * silently cancelled the Expanded/Collapsed setting for an entire session: choose
 * Collapsed, tap back, the restore runs afterwards and reopens it. Both features
 * were correct alone, which is what made it hard to see.
 */
class BoardFocusTest {

    private fun drain() = BoardFocus.target.value?.let { BoardFocus.consume(it) }

    @BeforeTest fun setUp() = drain().let { }
    @AfterTest fun tearDown() = drain().let { }

    @Test
    fun `a widget tap reveals, which may open the card`() {
        BoardFocus.request("940GZZLUKSX")
        assertEquals(BoardFocus.Kind.REVEAL, BoardFocus.target.value?.kind)
    }

    @Test
    fun `coming back restores, which may not touch the card`() {
        BoardFocus.restore("940GZZLUKSX")
        assertEquals(BoardFocus.Kind.RESTORE, BoardFocus.target.value?.kind)
    }

    @Test
    fun `the same station twice is two distinct requests`() {
        // The user may have swiped away in between, so the second must re-run.
        BoardFocus.request("A")
        val first = BoardFocus.target.value
        BoardFocus.request("A")
        val second = BoardFocus.target.value
        assertNotEquals(first, second)
    }

    @Test
    fun `consuming a superseded request does not drop the newer one`() {
        BoardFocus.request("A")
        val stale = BoardFocus.target.value!!
        BoardFocus.request("B")
        BoardFocus.consume(stale)
        assertEquals("B", BoardFocus.target.value?.stationId)
    }

    @Test
    fun `a blank id is not a request`() {
        BoardFocus.request("  ")
        assertNull(BoardFocus.target.value)
    }
}
