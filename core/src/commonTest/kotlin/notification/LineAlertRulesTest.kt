package com.stationly.core.notification

import com.stationly.core.config.NotificationPolicy
import com.stationly.core.config.NotificationPolicy.AlertLevel
import com.stationly.core.notification.LineAlertRules.ChangeAction
import com.stationly.core.notification.LineAlertRules.ConfirmAction
import com.stationly.core.notification.LineAlertRules.LineMemory
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every rule that decides whether a line-status change becomes a notification.
 * The v1 complaint ("a notification every day") is pinned by the first test.
 */
class LineAlertRulesTest {

    private val policy = NotificationPolicy.DEFAULT.copy(quietZone = "Europe/London")
    private val severe = AlertLevel.SEVERE
    private val zone = TimeZone.of("Europe/London")

    private fun at(h: Int, m: Int = 0): Long =
        LocalDateTime(2026, 11, 24, h, m).toInstant(zone).toEpochMilliseconds()

    // ── The v1 complaint ──────────────────────────────────────────────────────

    @Test
    fun `a line closing at night and opening in the morning says nothing`() {
        val night = LineAlertRules.onChange("Good Service", "Service Closed", "tube", severe, policy, LineMemory(), at(12))
        val morning = LineAlertRules.onChange("Service Closed", "Good Service", "tube", severe, policy, LineMemory(), at(12))
        assertEquals(ChangeAction.Nothing, night)
        assertEquals(ChangeAction.Nothing, morning)
    }

    @Test
    fun `bus roadworks and planned closures say nothing`() {
        assertEquals(ChangeAction.Nothing, LineAlertRules.onChange("Good Service", "Special Service", "bus", AlertLevel.ALL, policy, LineMemory(), at(12)))
        assertEquals(ChangeAction.Nothing, LineAlertRules.onChange("Good Service", "Planned Closure", "tube", AlertLevel.ALL, policy, LineMemory(), at(12)))
    }

    @Test
    fun `minor delays say nothing at the default level and alert at All changes`() {
        assertEquals(ChangeAction.Nothing, LineAlertRules.onChange("Good Service", "Minor Delays", "tube", severe, policy, LineMemory(), at(12)))
        assertEquals(
            ChangeAction.ScheduleConfirm(policy.confirmAfterMs),
            LineAlertRules.onChange("Good Service", "Minor Delays", "tube", AlertLevel.ALL, policy, LineMemory(), at(12)),
        )
    }

    // ── onChange ──────────────────────────────────────────────────────────────

    @Test
    fun `a severe disruption is scheduled for a re-check not posted`() {
        assertEquals(
            ChangeAction.ScheduleConfirm(policy.confirmAfterMs),
            LineAlertRules.onChange("Good Service", "Severe Delays", "tube", severe, policy, LineMemory(), at(12)),
        )
    }

    @Test
    fun `moving between two disruptions keeps the pending re-check becoming disrupted starts it`() {
        assertEquals(
            ChangeAction.ScheduleConfirm(policy.confirmAfterMs, keepPending = true),
            LineAlertRules.onChange("Severe Delays", "Suspended", "tube", severe, policy, LineMemory(), at(12)),
        )
        assertEquals(
            ChangeAction.ScheduleConfirm(policy.confirmAfterMs, keepPending = false),
            LineAlertRules.onChange("Minor Delays", "Suspended", "tube", severe, policy, LineMemory(), at(12)),
        )
    }

    @Test
    fun `the first sighting of a line never alerts`() {
        assertEquals(ChangeAction.Nothing, LineAlertRules.onChange(null, "Suspended", "tube", severe, policy, LineMemory(), at(12)))
    }

    @Test
    fun `off the kill switch and an unlisted mode all silence it`() {
        val off = NotificationPolicy.resolve(mapOf("notify.line.enabled" to "false"))
        assertEquals(ChangeAction.Nothing, LineAlertRules.onChange("Good Service", "Suspended", "tube", AlertLevel.OFF, policy, LineMemory(), at(12)))
        assertEquals(ChangeAction.Nothing, LineAlertRules.onChange("Good Service", "Suspended", "tube", severe, off, LineMemory(), at(12)))
        assertEquals(ChangeAction.Nothing, LineAlertRules.onChange("Good Service", "Suspended", "bus", severe, policy, LineMemory(), at(12)))
    }

    @Test
    fun `easing off touches only an alert we posted`() {
        val ours = LineMemory(lastAlertAtMs = 1L, alertedSeverity = "Severe Delays", open = true)
        assertEquals(ChangeAction.Recover(silent = true), LineAlertRules.onChange("Severe Delays", "Good Service", "tube", severe, policy, ours, at(12)))
        assertEquals(ChangeAction.Nothing, LineAlertRules.onChange("Severe Delays", "Good Service", "tube", severe, policy, LineMemory(), at(12)))
    }

    @Test
    fun `the served recovery mode decides how it eases off`() {
        val ours = LineMemory(lastAlertAtMs = 1L, alertedSeverity = "Suspended", open = true)
        val loud = NotificationPolicy.resolve(mapOf("notify.line.recovery" to "alert"))
        val clear = NotificationPolicy.resolve(mapOf("notify.line.recovery" to "off"))
        assertEquals(ChangeAction.Recover(silent = false), LineAlertRules.onChange("Suspended", "Minor Delays", "tube", severe, loud, ours, at(12)))
        assertEquals(ChangeAction.ClearOurs, LineAlertRules.onChange("Suspended", "Minor Delays", "tube", severe, clear, ours, at(12)))
    }

    @Test
    fun `a re-broadcast of the same status is not a change`() {
        assertFalse(LineAlertRules.isChange("Severe Delays", " severe delays "))
        assertTrue(LineAlertRules.isChange("Severe Delays", "Suspended"))
        assertFalse(LineAlertRules.isChange(null, "Suspended"))
    }

    @Test
    fun `off and the kill switch and a dropped mode take our alert down and never announce a recovery`() {
        val ours = LineMemory(1L, "Suspended", open = true)
        val loud = mapOf("notify.line.recovery" to "alert")
        val killed = NotificationPolicy.resolve(loud + ("notify.line.enabled" to "false"))
        val noTube = NotificationPolicy.resolve(loud + ("notify.line.modes" to "dlr"))
        val p = NotificationPolicy.resolve(loud)
        assertEquals(ChangeAction.ClearOurs, LineAlertRules.onChange("Suspended", "Good Service", "tube", AlertLevel.OFF, p, ours, at(12)))
        assertEquals(ChangeAction.ClearOurs, LineAlertRules.onChange("Suspended", "Good Service", "tube", severe, killed, ours, at(12)))
        assertEquals(ChangeAction.ClearOurs, LineAlertRules.onChange("Suspended", "Good Service", "tube", severe, noTube, ours, at(12)))
    }

    @Test
    fun `quiet hours bind a loud recovery too`() {
        val ours = LineMemory(1L, "Suspended", open = true)
        val loud = NotificationPolicy.resolve(mapOf("notify.line.recovery" to "alert"))
        val drop = NotificationPolicy.resolve(mapOf("notify.line.recovery" to "alert", "notify.quiet.mode" to "drop"))
        assertEquals(ChangeAction.Recover(silent = true), LineAlertRules.onChange("Suspended", "Good Service", "tube", severe, loud, ours, at(5, 30), zone))
        assertEquals(ChangeAction.ClearOurs, LineAlertRules.onChange("Suspended", "Good Service", "tube", severe, drop, ours, at(5, 30), zone))
    }

    @Test
    fun `closing for the night is not easing off`() {
        val ours = LineMemory(1L, "Severe Delays", open = true)
        for (s in listOf("Service Closed", "Planned Closure", "Special Service")) {
            assertEquals(ChangeAction.ClearOurs, LineAlertRules.onChange("Severe Delays", s, "tube", severe, policy, ours, at(12)), s)
        }
    }

    // ── onConfirm ─────────────────────────────────────────────────────────────

    @Test
    fun `a disruption still there at the re-check alerts in the day`() {
        assertEquals(
            ConfirmAction.Post(silent = false),
            LineAlertRules.onConfirm("Severe Delays", "tube", severe, policy, LineMemory(), 0, at(8), zone),
        )
    }

    @Test
    fun `a disruption that cleared before the re-check says nothing`() {
        assertEquals(ConfirmAction.Nothing, LineAlertRules.onConfirm("Good Service", "tube", severe, policy, LineMemory(), 0, at(8), zone))
    }

    @Test
    fun `quiet hours post silently or not at all when served as drop`() {
        assertEquals(ConfirmAction.Post(silent = true), LineAlertRules.onConfirm("Suspended", "tube", severe, policy, LineMemory(), 0, at(23, 15), zone))
        assertEquals(ConfirmAction.Post(silent = true), LineAlertRules.onConfirm("Suspended", "tube", severe, policy, LineMemory(), 0, at(5, 59), zone))
        val drop = NotificationPolicy.resolve(mapOf("notify.quiet.mode" to "drop"))
        assertEquals(ConfirmAction.Nothing, LineAlertRules.onConfirm("Suspended", "tube", severe, drop, LineMemory(), 0, at(2), zone))
    }

    @Test
    fun `the cooldown holds a second alert for the same line`() {
        val recent = LineMemory(lastAlertAtMs = at(8), alertedSeverity = "Severe Delays", open = false)
        assertEquals(ConfirmAction.Nothing, LineAlertRules.onConfirm("Severe Delays", "tube", severe, policy, recent, 1, at(9), zone))
        assertEquals(ConfirmAction.Post(silent = false), LineAlertRules.onConfirm("Severe Delays", "tube", severe, policy, recent, 1, at(10, 30), zone))
    }

    @Test
    fun `an open alert in its cooldown is updated silently never left stale`() {
        val open = LineMemory(lastAlertAtMs = at(8), alertedSeverity = "Severe Delays", open = true)
        assertEquals(ConfirmAction.Post(silent = true), LineAlertRules.onConfirm("Suspended", "tube", severe, policy, open, 1, at(8, 20), zone))
    }

    @Test
    fun `the same severity we already told them about is not repeated`() {
        val open = LineMemory(lastAlertAtMs = at(6), alertedSeverity = "Suspended", open = true)
        assertEquals(ConfirmAction.Nothing, LineAlertRules.onConfirm("Suspended", "tube", severe, policy, open, 1, at(12), zone))
    }

    @Test
    fun `the daily cap stops new alerts`() {
        assertEquals(ConfirmAction.Nothing, LineAlertRules.onConfirm("Suspended", "tube", severe, policy, LineMemory(), policy.maxPerDay, at(12), zone))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Test
    fun `quiet hours handle a window that does not cross midnight`() {
        val day = NotificationPolicy.resolve(mapOf("notify.quiet.start" to "13:00", "notify.quiet.end" to "14:00"))
        assertTrue(LineAlertRules.inQuietHours(at(13, 30), day, zone))
        assertFalse(LineAlertRules.inQuietHours(at(14, 0), day, zone))
        val none = NotificationPolicy.resolve(mapOf("notify.quiet.start" to "00:00", "notify.quiet.end" to "00:00"))
        assertFalse(LineAlertRules.inQuietHours(at(3), none, zone))
    }

    @Test
    fun `templates fill every slot`() {
        assertEquals(
            "Victoria · Severe Delays",
            LineAlertRules.fill("{line} · {severity}", "Victoria", "Severe Delays", "", ""),
        )
        assertEquals("Earlier: Suspended.", LineAlertRules.fill("Earlier: {previous}.", "", "", "", "Suspended"))
    }

    @Test
    fun `the day key is the London date`() {
        assertEquals("2026-11-24", LineAlertRules.dayKey(at(0, 30), zone))
    }
}
