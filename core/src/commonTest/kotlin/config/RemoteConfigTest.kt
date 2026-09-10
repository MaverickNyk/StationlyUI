package com.stationly.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The guarantees every served string and number relies on.
 *
 * [BoardPolicyTest] covers what the board does with these; this covers the
 * accessor itself, because copy now flows through it too (`AuthStrings`) and the
 * blank-is-absent rule is what stops a config typo rendering an empty error box
 * on a screen where someone is already stuck.
 */
class RemoteConfigTest {

    private val m = mapOf(
        "a.num" to "42",
        "a.text" to "hello",
        "a.blank" to "   ",
        "a.junk" to "not a number",
        "a.list" to " x , y ,, z ",
        "a.empty" to "",
    )

    @Test
    fun `a missing key is the default`() {
        assertEquals(7L, RemoteConfig.long(m, "nope", 7L, 0L, 100L))
        assertEquals("d", RemoteConfig.text(m, "nope", "d", 10))
        assertEquals(listOf("d"), RemoteConfig.list(m, "nope", listOf("d")))
    }

    @Test
    fun `an unparseable number is the default, never a guess`() {
        assertEquals(7L, RemoteConfig.long(m, "a.junk", 7L, 0L, 100L))
        assertEquals(7, RemoteConfig.int(m, "a.junk", 7, 0, 100))
    }

    @Test
    fun `out of range clamps to the bound, not to the default`() {
        // The value carries intent even when it is absurd; the bound is the
        // honest version of that intent, and the default discards it.
        assertEquals(10L, RemoteConfig.long(m, "a.num", 5L, 0L, 10L))
        assertEquals(100L, RemoteConfig.long(m, "a.num", 5L, 100L, 200L))
    }

    @Test
    fun `blank text is absent, not an empty string`() {
        // The rule that stops a half-finished backend edit shipping a screen
        // with no words on it.
        assertEquals("d", RemoteConfig.text(m, "a.blank", "d", 10))
        assertEquals("d", RemoteConfig.text(m, "a.empty", "d", 10))
    }

    @Test
    fun `text is trimmed and length-capped`() {
        assertEquals("hello", RemoteConfig.text(m, "a.text", "d", 10))
        assertEquals("hel", RemoteConfig.text(m, "a.text", "d", 3))
    }

    @Test
    fun `a list is trimmed and emptied out`() {
        assertEquals(listOf("x", "y", "z"), RemoteConfig.list(m, "a.list", listOf("d")))
    }

    @Test
    fun `a list with nothing usable falls back rather than erasing`() {
        assertEquals(listOf("d"), RemoteConfig.list(mapOf("k" to " , , "), "k", listOf("d")))
    }
}
