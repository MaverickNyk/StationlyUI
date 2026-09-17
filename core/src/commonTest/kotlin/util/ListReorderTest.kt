package util

import com.stationly.core.util.ListReorder.moved
import com.stationly.core.util.ListReorder.slotOf
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
        // The list changed under the UI: another screen deleted a station.
        assertEquals(list, list.moved(0, 9))
        assertEquals(list, list.moved(-1, 1))
        assertEquals(emptyList<String>(), emptyList<String>().moved(0, 0))
    }

    @Test
    fun `nothing moves when no row is being dragged`() {
        list.indices.forEach { assertEquals(it, slotOf(it, from = -1, to = 0)) }
    }

    @Test
    fun `the dragged row takes the slot it hovers over`() {
        assertEquals(2, slotOf(index = 0, from = 0, to = 2))
        assertEquals(1, slotOf(index = 3, from = 3, to = 1))
    }

    @Test
    fun `only the rows between the two ends shuffle`() {
        // Dragging A (0) down onto C (2): B and C step up, D is untouched.
        assertEquals(0, slotOf(index = 1, from = 0, to = 2))
        assertEquals(1, slotOf(index = 2, from = 0, to = 2))
        assertEquals(3, slotOf(index = 3, from = 0, to = 2))
    }

    @Test
    fun `slotOf and moved always agree`() {
        // The contract the drop depends on: when the finger lifts, the caller
        // commits `moved`, and the row that was standing in slot N must be the
        // one the new list puts at index N. Disagree by one and every drop ends
        // with the whole list jumping.
        for (from in list.indices) {
            for (to in list.indices) {
                val committed = list.moved(from, to)
                list.indices.forEach { index ->
                    assertEquals(
                        list[index],
                        committed[slotOf(index, from, to)],
                        "row $index dragging $from -> $to",
                    )
                }
            }
        }
    }
}
