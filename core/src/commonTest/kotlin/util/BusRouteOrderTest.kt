package util

import com.stationly.core.util.BusRouteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class BusRouteOrderTest {

    /** Putney Station, exactly as the picker listed it (QA 2026-11-23, #7). */
    @Test
    fun `routes sort by number, not by text`() {
        val asServed = listOf("14", "337", "37", "39", "424", "430", "85", "93", "N74")
        assertEquals(
            listOf("14", "37", "39", "85", "93", "337", "424", "430", "N74"),
            BusRouteOrder.sorted(asServed) { it },
        )
    }

    @Test
    fun `lettered routes follow the day routes, night routes come last`() {
        val mixed = listOf("N155", "W7", "C10", "5", "N74", "W3", "EL1", "220")
        assertEquals(
            listOf("5", "220", "C10", "EL1", "W3", "W7", "N74", "N155"),
            BusRouteOrder.sorted(mixed) { it },
        )
    }

    @Test
    fun `a Bus prefix is ignored`() {
        assertEquals(listOf("Bus 9", "Bus 10"), BusRouteOrder.sorted(listOf("Bus 10", "Bus 9")) { it })
    }
}
