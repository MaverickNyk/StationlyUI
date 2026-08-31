package com.stationly.core.config

import com.stationly.core.model.user.BoardConfig
import com.stationly.core.util.LineStatusRanker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a served config is allowed to do to the board, and what it is not.
 *
 * The interesting cases here are all failure cases. A config that arrives
 * correct needs no defending; the reason this layer exists at all is that one
 * can arrive absent, malformed or absurd, and the board still has to render.
 */
class BoardPolicyTest {

    // ── Absence ──────────────────────────────────────────────────────────

    @Test
    fun `an empty config leaves every compiled default standing`() {
        assertEquals(BoardPolicy.DEFAULT, BoardPolicyStore.resolve(emptyMap()))
    }

    @Test
    fun `an unrelated config leaves every compiled default standing`() {
        assertEquals(
            BoardPolicy.DEFAULT,
            BoardPolicyStore.resolve(mapOf("app.minVersion" to "9.9")),
        )
    }

    @Test
    fun `a value that is not a number is ignored rather than guessed at`() {
        val resolved = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_GRACE to "soon"))
        assertEquals(BoardPolicy.DEFAULT.departedGraceMs, resolved.departedGraceMs)
    }

    @Test
    fun `a blank label is treated as absent, not as an empty label`() {
        val resolved = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_LABEL to "   "))
        assertEquals("Gone", resolved.departedLabel)
    }

    // ── Clamping ─────────────────────────────────────────────────────────

    @Test
    fun `an absurd grace period is clamped to the bound, not to the default`() {
        // Clamping to the DEFAULT would silently discard the intent. Someone who
        // asked for eighty-three minutes meant "as long as possible", and the
        // bound is the honest version of that answer.
        val resolved = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_GRACE to "5000000"))
        assertEquals(120_000L, resolved.departedGraceMs)
    }

    @Test
    fun `a negative grace period cannot shed trains before they are due`() {
        val resolved = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_GRACE to "-90000"))
        assertEquals(0L, resolved.departedGraceMs)
    }

    @Test
    fun `the reserve can never be shallower than the deepest board a user can ask for`() {
        // A reserve below the display depth renders that board short however the
        // user sets it — the rows simply are not in SQL to draw.
        val resolved = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_RESERVE to "1"))
        assertEquals(BoardConfig.MAX_ROWS_PER_PLATFORM, resolved.rowReserve)
        assertTrue(resolved.rowReserve >= BoardConfig.DEFAULT_ROWS_PER_PLATFORM)
    }

    @Test
    fun `a long label is truncated so it cannot squeeze the destination column`() {
        val resolved = BoardPolicyStore.resolve(
            mapOf(BoardPolicy.KEY_LABEL to "Already departed"),
        )
        assertEquals(BoardPolicy.MAX_LABEL_LEN, resolved.departedLabel.length)
    }

    // ── The freshness pair ───────────────────────────────────────────────

    @Test
    fun `retention follows freshness when only freshness is served`() {
        // The footer going grey and the rows going "Gone" are one statement
        // about one payload. Moving one alone would split it in half.
        val resolved = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_FRESH to "90000"))
        assertEquals(90_000L, resolved.freshMs)
        assertEquals(90_000L, resolved.retentionMinAgeMs)
    }

    @Test
    fun `setting both explicitly is honoured — that is someone deciding they differ`() {
        val resolved = BoardPolicyStore.resolve(
            mapOf(
                BoardPolicy.KEY_FRESH to "90000",
                BoardPolicy.KEY_RETENTION to "120000",
            ),
        )
        assertEquals(90_000L, resolved.freshMs)
        assertEquals(120_000L, resolved.retentionMinAgeMs)
    }

    @Test
    fun `red can never come before grey`() {
        val resolved = BoardPolicyStore.resolve(
            mapOf(
                BoardPolicy.KEY_FRESH to "300000",
                BoardPolicy.KEY_STALE to "1000",
            ),
        )
        assertTrue(
            resolved.staleMs >= resolved.freshMs,
            "ladder inverted: fresh=${resolved.freshMs} stale=${resolved.staleMs}",
        )
    }

    // ── The severity list ────────────────────────────────────────────────

    @Test
    fun `a served severity order replaces the compiled one`() {
        val resolved = BoardPolicyStore.resolve(
            mapOf(BoardPolicy.KEY_SEVERITY to "Suspended, Minor Delays , Information"),
        )
        assertEquals(listOf("Suspended", "Minor Delays", "Information"), resolved.severityOrder)
    }

    @Test
    fun `a list with nothing usable in it cannot erase the ordering`() {
        val resolved = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_SEVERITY to " , , "))
        assertEquals(BoardPolicy.DEFAULT_SEVERITY_ORDER, resolved.severityOrder)
    }

    // ── The red set ──────────────────────────────────────────────────────

    @Test
    fun `a served red set is normalised, so TfL's casing cannot break it`() {
        // The board holds a formatted "Severity : Reason" string, not a code, and
        // TfL's own casing is not something to depend on.
        val resolved = BoardPolicyStore.resolve(
            mapOf(BoardPolicy.KEY_RED_SEVERITY to "CLOSED, Part Suspended"),
        )
        assertEquals(setOf("closed", "part suspended"), resolved.redSeverities)
    }

    @Test
    fun `the red set drives the tone, not a second list in the ranker`() {
        // The defect this phase closed: the ranker kept its own copy, so a served
        // vocabulary moved the ordering and left the dot's colour behind.
        val served = BoardPolicyStore.resolve(
            mapOf(BoardPolicy.KEY_RED_SEVERITY to "Minor Delays"),
        )
        assertEquals(LineStatusRanker.Tone.RED, LineStatusRanker.toneOf("Minor Delays", served))
        assertEquals(LineStatusRanker.Tone.AMBER, LineStatusRanker.toneOf("Closed", served))

        // And the compiled default still says the opposite, which is the proof
        // the value travelled rather than the test agreeing with itself.
        assertEquals(LineStatusRanker.Tone.AMBER, LineStatusRanker.toneOf("Minor Delays", BoardPolicy.DEFAULT))
        assertEquals(LineStatusRanker.Tone.RED, LineStatusRanker.toneOf("Closed", BoardPolicy.DEFAULT))
    }

    @Test
    fun `good service is green whatever the red set says`() {
        // A vocabulary edit must never make "Good Service" alarming.
        val absurd = BoardPolicyStore.resolve(
            mapOf(BoardPolicy.KEY_RED_SEVERITY to "Good Service,No Issues"),
        )
        assertEquals(LineStatusRanker.Tone.GREEN, LineStatusRanker.toneOf("Good Service", absurd))
    }

    // ── Operational thresholds ───────────────────────────────────────────

    @Test
    fun `operational thresholds default to compiled values`() {
        val policy = BoardPolicy.DEFAULT
        assertEquals(1, policy.heroUrgencyMin)
        assertEquals(86_400_000L, policy.dropdownCacheTtlMs)
        assertEquals(1_209_600_000L, policy.routeTextMaxAgeMs)
        assertEquals(60_000L, policy.supportFetchIntervalMs)
        assertEquals(14, policy.explorePeakHorizonDays)
        assertEquals(1_800_000L, policy.weatherRefreshIntervalMs)
    }

    @Test
    fun `operational thresholds adopt served values`() {
        val resolved = BoardPolicyStore.resolve(
            mapOf(
                BoardPolicy.KEY_HERO_URGENCY_MIN to "2",
                BoardPolicy.KEY_DROPDOWN_CACHE_TTL to "43200000",
                BoardPolicy.KEY_ROUTE_TEXT_MAX_AGE to "604800000",
                BoardPolicy.KEY_SUPPORT_FETCH_INTERVAL to "30000",
                BoardPolicy.KEY_EXPLORE_PEAK_HORIZON to "21",
                BoardPolicy.KEY_WEATHER_REFRESH_INTERVAL to "900000",
            ),
        )
        assertEquals(2, resolved.heroUrgencyMin)
        assertEquals(43_200_000L, resolved.dropdownCacheTtlMs)
        assertEquals(604_800_000L, resolved.routeTextMaxAgeMs)
        assertEquals(30_000L, resolved.supportFetchIntervalMs)
        assertEquals(21, resolved.explorePeakHorizonDays)
        assertEquals(900_000L, resolved.weatherRefreshIntervalMs)
    }

    @Test
    fun `operational thresholds clamp out-of-range values`() {
        val tooLow = BoardPolicyStore.resolve(
            mapOf(
                BoardPolicy.KEY_HERO_URGENCY_MIN to "-5",
                BoardPolicy.KEY_DROPDOWN_CACHE_TTL to "1000",
                BoardPolicy.KEY_ROUTE_TEXT_MAX_AGE to "1000",
                BoardPolicy.KEY_SUPPORT_FETCH_INTERVAL to "500",
                BoardPolicy.KEY_EXPLORE_PEAK_HORIZON to "0",
                BoardPolicy.KEY_WEATHER_REFRESH_INTERVAL to "10000",
            ),
        )
        assertEquals(0, tooLow.heroUrgencyMin)
        assertEquals(3_600_000L, tooLow.dropdownCacheTtlMs)
        assertEquals(86_400_000L, tooLow.routeTextMaxAgeMs)
        assertEquals(5_000L, tooLow.supportFetchIntervalMs)
        assertEquals(1, tooLow.explorePeakHorizonDays)
        assertEquals(300_000L, tooLow.weatherRefreshIntervalMs)

        val tooHigh = BoardPolicyStore.resolve(
            mapOf(
                BoardPolicy.KEY_HERO_URGENCY_MIN to "100",
                BoardPolicy.KEY_DROPDOWN_CACHE_TTL to "9999999999",
                BoardPolicy.KEY_ROUTE_TEXT_MAX_AGE to "99999999999",
                BoardPolicy.KEY_SUPPORT_FETCH_INTERVAL to "99999999",
                BoardPolicy.KEY_EXPLORE_PEAK_HORIZON to "365",
                BoardPolicy.KEY_WEATHER_REFRESH_INTERVAL to "99999999",
            ),
        )
        assertEquals(10, tooHigh.heroUrgencyMin)
        assertEquals(604_800_000L, tooHigh.dropdownCacheTtlMs)
        assertEquals(7_776_000_000L, tooHigh.routeTextMaxAgeMs)
        assertEquals(600_000L, tooHigh.supportFetchIntervalMs)
        assertEquals(60, tooHigh.explorePeakHorizonDays)
        assertEquals(7_200_000L, tooHigh.weatherRefreshIntervalMs)
    }
}
