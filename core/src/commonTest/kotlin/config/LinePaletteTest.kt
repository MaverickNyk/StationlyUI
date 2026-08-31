package com.stationly.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guarantees provided by [LinePalette] and [LinePalette.resolve].
 *
 * Covers the failure modes and edge cases: the prefix trap, malformed hex
 * values, merge-over-defaults, and theme override precedence.
 */
class LinePaletteTest {

    // ── Defaults & Absence ───────────────────────────────────────────────

    @Test
    fun `an empty config returns the compiled default palette`() {
        val resolved = LinePalette.resolve(emptyMap())
        assertEquals(LinePalette.DEFAULT, resolved)
        assertEquals(21, resolved.brand.size)
        assertEquals(9, resolved.dark.size)
        assertEquals(3, resolved.light.size)
        assertEquals(8, resolved.modes.size)
        assertEquals("#DC241F", resolved.modeDefault)
    }

    @Test
    fun `an unrelated config leaves every compiled default standing`() {
        val resolved = LinePalette.resolve(mapOf("app.minVersion" to "9.9", "auth.timeout" to "30"))
        assertEquals(LinePalette.DEFAULT, resolved)
    }

    // ── Merge-over-defaults ──────────────────────────────────────────────

    @Test
    fun `serving a single line overrides only that line and preserves all others`() {
        val resolved = LinePalette.resolve(
            mapOf("line.color.central" to "#FF0000"),
        )
        // Overridden entry
        assertEquals("#FF0000", resolved.hexFor("central"))
        // All other 20 lines remain at their compiled default
        assertEquals(LinePalette.DEFAULT_BRAND["bakerloo"], resolved.hexFor("bakerloo"))
        assertEquals(LinePalette.DEFAULT_BRAND["northern"], resolved.hexFor("northern"))
        assertEquals(LinePalette.DEFAULT_BRAND["victoria"], resolved.hexFor("victoria"))
        assertEquals(21, resolved.brand.size)
    }

    @Test
    fun `serving a single mode overrides only that mode and preserves defaults`() {
        val resolved = LinePalette.resolve(
            mapOf("mode.color.dlr" to "#00FFFF"),
        )
        assertEquals("#00FFFF", resolved.modeHex("dlr"))
        assertEquals("#DC241F", resolved.modeHex("tube"))
        assertEquals("#EE7C0E", resolved.modeHex("overground"))
        assertEquals(8, resolved.modes.size)
    }

    @Test
    fun `serving mode color default overrides the fallback for unmapped modes`() {
        val resolved = LinePalette.resolve(
            mapOf("mode.color.default" to "#123456"),
        )
        assertEquals("#123456", resolved.modeDefault)
        assertEquals("#123456", resolved.modeHex("unmapped_transport_mode"))
    }

    // ── Prefix Trap ──────────────────────────────────────────────────────

    @Test
    fun `dark and light line prefixes do not pollute the base brand map`() {
        // `line.color.dark.northern` starts with `line.color.`.
        // A naive prefix match would interpret "dark.northern" as a brand line id.
        val resolved = LinePalette.resolve(
            mapOf(
                "line.color.dark.northern" to "#999999",
                "line.color.light.northern" to "#555555",
                "line.color.northern" to "#000000",
            ),
        )

        // Brand map must not contain "dark.northern" or "light.northern"
        assertNull(resolved.brand["dark.northern"])
        assertNull(resolved.brand["light.northern"])
        assertEquals("#000000", resolved.brand["northern"])

        // Dark and light overrides must be properly filed
        assertEquals("#999999", resolved.dark["northern"])
        assertEquals("#555555", resolved.light["northern"])
    }

    @Test
    fun `mode color default key is not filed as a transport mode in modes map`() {
        val resolved = LinePalette.resolve(
            mapOf("mode.color.default" to "#FF1122"),
        )
        assertNull(resolved.modes["default"], "'default' must not be added to modes map")
        assertEquals("#FF1122", resolved.modeDefault)
    }

    // ── Malformed Hex Rejection ──────────────────────────────────────────

    @Test
    fun `malformed hex values are rejected and compiled defaults stand`() {
        val invalidHexInputs = mapOf(
            "line.color.central" to "red",
            "line.color.victoria" to "#123",
            "line.color.bakerloo" to "#12345678",
            "line.color.piccadilly" to "123456",
            "line.color.jubilee" to "#GGGGGG",
            "line.color.northern" to "",
            "line.color.district" to "   ",
            "line.color.dark.northern" to "transparent",
            "line.color.light.northern" to "#12",
            "mode.color.dlr" to "cyan",
            "mode.color.default" to "#BADHEX",
        )

        val resolved = LinePalette.resolve(invalidHexInputs)

        // All bad inputs must be rejected; compiled defaults must stand
        assertEquals(LinePalette.DEFAULT_BRAND["central"], resolved.hexFor("central"))
        assertEquals(LinePalette.DEFAULT_BRAND["victoria"], resolved.hexFor("victoria"))
        assertEquals(LinePalette.DEFAULT_BRAND["bakerloo"], resolved.hexFor("bakerloo"))
        assertEquals(LinePalette.DEFAULT_BRAND["piccadilly"], resolved.hexFor("piccadilly"))
        assertEquals(LinePalette.DEFAULT_BRAND["jubilee"], resolved.hexFor("jubilee"))
        assertEquals(LinePalette.DEFAULT_BRAND["northern"], resolved.hexFor("northern"))
        assertEquals(LinePalette.DEFAULT_BRAND["district"], resolved.hexFor("district"))
        assertEquals(LinePalette.DEFAULT_DARK["northern"], resolved.dark["northern"])
        assertEquals(LinePalette.DEFAULT_LIGHT["northern"], resolved.light["northern"])
        assertEquals(LinePalette.DEFAULT_MODES["dlr"], resolved.modeHex("dlr"))
        assertEquals(LinePalette.DEFAULT_MODE_FALLBACK, resolved.modeDefault)
    }

    @Test
    fun `valid hex strings are normalised to uppercase`() {
        val resolved = LinePalette.resolve(
            mapOf(
                "line.color.central" to "  #abcdef  ",
                "line.color.dark.northern" to "#1a2b3c",
            ),
        )
        assertEquals("#ABCDEF", resolved.hexFor("central"))
        assertEquals("#1A2B3C", resolved.dark["northern"])
    }

    // ── HexForTheme Override Precedence ──────────────────────────────────

    @Test
    fun `hexForTheme honours dark override over brand on dark surfaces`() {
        // Northern line: brand is #000000, dark override is #888888, light override is #6E6A66
        val palette = LinePalette.DEFAULT
        assertEquals("#000000", palette.hexFor("northern"))
        assertEquals("#888888", palette.hexForTheme("northern", isDark = true))
        assertEquals("#6E6A66", palette.hexForTheme("northern", isDark = false))
    }

    @Test
    fun `hexForTheme falls back to brand when no theme override exists`() {
        // Central line has no dark or light override
        val palette = LinePalette.DEFAULT
        assertEquals("#E32017", palette.hexFor("central"))
        assertEquals("#E32017", palette.hexForTheme("central", isDark = true))
        assertEquals("#E32017", palette.hexForTheme("central", isDark = false))
    }

    @Test
    fun `served theme overrides take precedence over compiled overrides`() {
        val resolved = LinePalette.resolve(
            mapOf(
                "line.color.dark.central" to "#FF5555",
                "line.color.light.central" to "#AA0000",
            ),
        )
        assertEquals("#FF5555", resolved.hexForTheme("central", isDark = true))
        assertEquals("#AA0000", resolved.hexForTheme("central", isDark = false))
        assertEquals("#E32017", resolved.hexFor("central"))
    }

    // ── Case Insensitivity & Lookups ─────────────────────────────────────

    @Test
    fun `line lookups are case insensitive`() {
        val palette = LinePalette.DEFAULT
        assertEquals("#000000", palette.hexFor("NORTHERN"))
        assertEquals("#000000", palette.hexFor("Northern"))
        assertEquals("#888888", palette.hexForTheme("NORTHERN", isDark = true))
        assertEquals("#6E6A66", palette.hexForTheme("Northern", isDark = false))
    }

    @Test
    fun `mode lookups are case insensitive`() {
        val palette = LinePalette.DEFAULT
        assertEquals("#00A4A7", palette.modeHex("DLR"))
        assertEquals("#EE7C0E", palette.modeHex("Overground"))
        assertEquals("#DC241F", palette.modeHex("TUBE"))
    }

    @Test
    fun `unmapped line or null returns null for hexFor and hexForTheme`() {
        val palette = LinePalette.DEFAULT
        assertNull(palette.hexFor(null))
        assertNull(palette.hexFor("bus"))
        assertNull(palette.hexFor("unknown-line"))

        assertNull(palette.hexForTheme(null, isDark = true))
        assertNull(palette.hexForTheme("bus", isDark = true))
        assertNull(palette.hexForTheme("unknown-line", isDark = false))
    }

    @Test
    fun `unmapped mode or null falls back to modeDefault`() {
        val palette = LinePalette.DEFAULT
        assertEquals("#DC241F", palette.modeHex(null))
        assertEquals("#DC241F", palette.modeHex("flying-carpet"))
    }
}
