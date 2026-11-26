package com.stationly.mobile.widget

import com.stationly.core.model.UserSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A widget placed by v1 keeps the station it was showing. */
class V1WidgetAdoptionTest {

    private fun row(station: String, hub: String = "", line: String = "39", direction: String = "inbound") = UserSelection(
        mode = "bus", line = line, station = station, parentStationId = hub, stationName = station,
        direction = direction, destinations = emptyList(), destinationIds = emptyList(),
    )

    @Test
    fun `every unbound widget gets the first station which is what v1 drew`() {
        assertEquals(
            mapOf(4 to "A", 9 to "A"),
            V1WidgetAdoption.plan(listOf(4, 9), emptySet(), listOf(row("A"), row("B"))),
        )
    }

    @Test
    fun `a widget that already has a station is left alone`() {
        assertEquals(mapOf(9 to "A"), V1WidgetAdoption.plan(listOf(4, 9), setOf(4), listOf(row("A"))))
    }

    @Test
    fun `no stations binds nothing`() {
        assertTrue(V1WidgetAdoption.plan(listOf(4), emptySet(), emptyList()).isEmpty())
    }

    @Test
    fun `the note waits for a station only while there are widgets to give it to`() {
        assertTrue(V1WidgetAdoption.mustWait(listOf(4), emptyList()))
        assertFalse(V1WidgetAdoption.mustWait(listOf(4), listOf(row("A"))))
        assertFalse(V1WidgetAdoption.mustWait(emptyList(), emptyList()))
        assertFalse(V1WidgetAdoption.mustWait(emptyList(), listOf(row("A"))))
    }

    @Test
    fun `a binding follows its bus stop when the row gains its hub`() {
        assertEquals(
            mapOf(4 to "HUB"),
            V1WidgetAdoption.moves(mapOf(4 to "A"), listOf(row("A")), listOf(row("A", hub = "HUB"), row("B"))),
        )
    }

    @Test
    fun `a binding whose group still exists does not move`() {
        assertTrue(V1WidgetAdoption.moves(mapOf(4 to "A"), listOf(row("A")), listOf(row("A"), row("B"))).isEmpty())
    }

    @Test
    fun `a removed station is not replaced by another`() {
        assertTrue(V1WidgetAdoption.moves(mapOf(4 to "A"), listOf(row("A")), listOf(row("B"))).isEmpty())
    }
}
