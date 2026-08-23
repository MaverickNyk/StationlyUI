package com.stationly.app.ui.summary

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The signal that carries "the user just chose Expanded/Collapsed" from the
 * settings screen to the home screen.
 *
 * Worth testing despite being twenty lines: this is the third attempt at the
 * feature, and the two that failed both failed on the mechanism rather than the
 * rule. The cases below are the ones that broke.
 */
class BoardExpansionTest {

    @BeforeTest fun setUp() = BoardExpansion.clear()
    @AfterTest fun tearDown() = BoardExpansion.clear()

    @Test
    fun `a request survives until it is consumed`() {
        // The whole point. The home screen is NOT composed when this is raised —
        // it is sitting behind the settings screen — so anything that expires on
        // its own, or lives in a composition, loses the change.
        BoardExpansion.request("940GZZLUKSX", expanded = false)
        assertEquals(mapOf("940GZZLUKSX" to false), BoardExpansion.pending.value)
    }

    @Test
    fun `two stations can be waiting at once`() {
        // Settings for A, back, settings for B, back — with the home screen
        // never getting a frame in between. A single slot drops the first.
        BoardExpansion.request("A", expanded = false)
        BoardExpansion.request("B", expanded = true)
        assertEquals(mapOf("A" to false, "B" to true), BoardExpansion.pending.value)
    }

    @Test
    fun `the latest choice for one station wins`() {
        BoardExpansion.request("A", expanded = false)
        BoardExpansion.request("A", expanded = true)
        assertEquals(mapOf("A" to true), BoardExpansion.pending.value)
    }

    @Test
    fun `consuming one leaves the others pending`() {
        // The screen removes only what it applied. A request for a station that
        // has not loaded yet must not be dropped for being early.
        BoardExpansion.request("A", expanded = false)
        BoardExpansion.request("B", expanded = true)
        BoardExpansion.consume(listOf("A"))
        assertEquals(mapOf("B" to true), BoardExpansion.pending.value)
    }

    @Test
    fun `consuming nothing is not consuming everything`() {
        BoardExpansion.request("A", expanded = false)
        BoardExpansion.consume(emptyList())
        assertTrue(BoardExpansion.pending.value.containsKey("A"))
    }

    @Test
    fun `a blank id is not a request`() {
        BoardExpansion.request("   ", expanded = true)
        assertTrue(BoardExpansion.pending.value.isEmpty())
    }

    @Test
    fun `sign-out drops everything`() {
        // Station ids are TfL naptans and are shared across accounts. A request
        // left behind would apply to whoever signs in next if they track the
        // same station.
        BoardExpansion.request("940GZZLUKSX", expanded = false)
        BoardExpansion.clear()
        assertTrue(BoardExpansion.pending.value.isEmpty())
    }
}
