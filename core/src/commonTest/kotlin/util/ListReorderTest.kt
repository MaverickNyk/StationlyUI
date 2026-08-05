package util

import com.stationly.core.util.ListReorder.moved
import kotlin.test.Test
import kotlin.test.assertEquals

/** Moving a station up or down the home screen's order. */
class ListReorderTest {

    private val list = listOf("A", "B", "C", "D")

    @Test
    fun `moving down shuffles the items it passes up one`() {
        assertEquals(listOf("B", "C", "A", "D"), list.moved(0, 2))
    }

    @Test
    fun `moving up shuffles the items it passes down one`() {
        assertEquals(listOf("A", "D", "B", "C"), list.moved(3, 1))
    }

    @Test
    fun `one step is a swap with the neighbour`() {
        // What the arrows in the station list actually do, every time.
        assertEquals(listOf("B", "A", "C", "D"), list.moved(0, 1))
        assertEquals(listOf("A", "B", "D", "C"), list.moved(3, 2))
    }

    @Test
    fun `moving an item onto itself changes nothing`() {
        assertEquals(list, list.moved(2, 2))
    }

    @Test
    fun `indices a stale caller could hand it are survivable`() {
        // The list changed under the UI — another screen deleted a station.
        assertEquals(list, list.moved(0, 9))
        assertEquals(list, list.moved(-1, 1))
        assertEquals(emptyList<String>(), emptyList<String>().moved(0, 0))
    }
}
