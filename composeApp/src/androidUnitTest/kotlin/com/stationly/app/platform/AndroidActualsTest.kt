package com.stationly.app.platform

import android.os.Build
import android.view.HapticFeedbackConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The parts of AV2-3.2's `actual`s that are not Android.
 *
 * A JVM unit test cannot open a `SharedPreferences` or fire a haptic, and
 * pulling in Robolectric to pretend otherwise would buy a slow test of
 * Android's behaviour rather than of ours. What is worth pinning is narrower
 * and more fragile than that: two pure decisions, each of which has a **second
 * implementation living in `android/`** that it must agree with, and neither of
 * which any compiler is watching.
 */
class ModeIconFileNameTest {

    /**
     * The one that would cost a user their roundels.
     *
     * `ModeIconStore` and the shipped app's `ModeIconCache` read and write the
     * same directory, and they find each other's files only by computing the
     * same name. If these two ever disagree the symptom is not an error: the
     * shared UI silently re-downloads every icon into a second set of files, and
     * the home-screen widget keeps rendering from the first.
     *
     * Cases below are the real mode names the backend serves, plus the two
     * shapes that exercise the substitution.
     */
    @Test
    fun `matches ModeIconCache safeName`() {
        // The reference implementation, transcribed from
        // android/.../util/ModeIconCache.kt. If that file changes, this fails.
        fun safeName(mode: String) = mode.lowercase().replace(Regex("[^a-z0-9-]"), "_")

        listOf(
            "tube", "Tube", "TUBE",
            "national-rail", "elizabeth-line", "dlr", "overground",
            "bus", "tram", "river-bus",
            "National Rail",        // a space
            "cable/car",            // a slash
            "Thames Clipper (RB1)", // brackets and spaces
            "",                     // degenerate, must not throw
        ).forEach {
            assertEquals(safeName(it), modeIconFileName(it), "diverged on: \"$it\"")
        }
    }

    @Test
    fun `hyphens survive and everything else outside a-z0-9 does not`() {
        // Spelled out rather than left to the transcription above, because the
        // hyphen is the character it would be most natural to "tidy up" out of
        // the character class — and every multi-word mode the backend serves
        // ("national-rail", "elizabeth-line") is hyphenated.
        assertEquals("national-rail", modeIconFileName("national-rail"))
        assertEquals("a_b", modeIconFileName("a b"))
        assertEquals("a_b", modeIconFileName("a.b"))
        assertEquals("a_b", modeIconFileName("A/B"))
        assertEquals("mode1", modeIconFileName("Mode1"))
    }
}

class HapticMappingTest {

    /**
     * A selection must not feel like a tap. The shared `HapticType` KDoc is
     * explicit about why — firing an impact at every detent of a drag makes a
     * control feel struck rather than turned — and that intent survives only as
     * long as the two map to different constants.
     */
    @Test
    fun `selection is a lighter effect than a tap, on every API level`() {
        listOf(26, 29, 30, 34, 35).forEach { sdk ->
            assertNotEquals(
                hapticConstantFor(HapticType.TAP, sdk),
                hapticConstantFor(HapticType.SELECTION, sdk),
                "TAP and SELECTION collapsed to one effect on API $sdk",
            )
            assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticConstantFor(HapticType.SELECTION, sdk))
        }
    }

    /** Success and failure have to feel different, including below API 30. */
    @Test
    fun `success and error stay distinguishable below the API that names them`() {
        listOf(26, 27, 28, 29).forEach { sdk ->
            assertNotEquals(
                hapticConstantFor(HapticType.SUCCESS, sdk),
                hapticConstantFor(HapticType.ERROR, sdk),
                "SUCCESS and ERROR collapsed to one effect on API $sdk",
            )
        }
    }

    /**
     * CONFIRM and REJECT are API 30. Calling them on 26–29 throws, and the
     * throw would happen inside a haptic — a place nobody is watching, at the
     * end of a flow the user has just completed.
     */
    @Test
    fun `the API 30 constants are used only from API 30`() {
        listOf(26, 27, 28, 29).forEach { sdk ->
            HapticType.entries.forEach { type ->
                val c = hapticConstantFor(type, sdk)
                assertTrue(
                    c != HapticFeedbackConstants.CONFIRM && c != HapticFeedbackConstants.REJECT,
                    "$type resolved to an API 30 constant on API $sdk",
                )
            }
        }
        assertEquals(
            HapticFeedbackConstants.CONFIRM,
            hapticConstantFor(HapticType.SUCCESS, Build.VERSION_CODES.R),
        )
        assertEquals(
            HapticFeedbackConstants.REJECT,
            hapticConstantFor(HapticType.ERROR, Build.VERSION_CODES.R),
        )
    }

    /** Every variant maps to something; a new HapticType must be a compile error. */
    @Test
    fun `every haptic type resolves`() {
        HapticType.entries.forEach { hapticConstantFor(it, 34) }
    }
}
