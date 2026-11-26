package com.stationly.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The served alert policy: every value can be moved from the backend, and no
 * value, however wrong, can make the app noisier than intended.
 */
class NotificationPolicyTest {

    @Test
    fun `an empty map is the compiled default`() {
        assertEquals(NotificationPolicy.DEFAULT, NotificationPolicy.resolve(emptyMap()))
    }

    @Test
    fun `the default never alerts on the nightly or planned statuses`() {
        val d = NotificationPolicy.DEFAULT
        for (quiet in listOf("Service Closed", "Planned Closure", "Special Service", "Good Service")) {
            assertFalse(quiet in d.alertSeverities, quiet)
            assertFalse(quiet in d.allSeverities, quiet)
        }
        assertFalse("bus" in d.modes)
    }

    @Test
    fun `the kill switch reads the usual spellings and ignores nonsense`() {
        assertFalse(NotificationPolicy.resolve(mapOf("notify.line.enabled" to "false")).enabled)
        assertFalse(NotificationPolicy.resolve(mapOf("notify.line.enabled" to " OFF ")).enabled)
        assertTrue(NotificationPolicy.resolve(mapOf("notify.line.enabled" to "maybe")).enabled)
    }

    @Test
    fun `served lists modes and level are adopted`() {
        val p = NotificationPolicy.resolve(
            mapOf(
                "notify.line.alertSeverities" to "Suspended",
                "notify.line.modes" to "Tube, BUS",
                "notify.line.defaultLevel" to "all",
            ),
        )
        assertEquals(setOf("Suspended"), p.alertSeverities)
        assertEquals(setOf("tube", "bus"), p.modes)
        assertEquals(NotificationPolicy.AlertLevel.ALL, p.defaultLevel)
    }

    @Test
    fun `numbers are clamped to their bounds`() {
        val p = NotificationPolicy.resolve(
            mapOf(
                "notify.line.confirmAfterMs" to "-5",
                "notify.line.cooldownMs" to "999999999999",
                "notify.line.maxPerDay" to "1000",
                "notify.config.maxAgeMs" to "1",
            ),
        )
        assertEquals(0L, p.confirmAfterMs)
        assertEquals(24 * 60 * 60_000L, p.cooldownMs)
        assertEquals(50, p.maxPerDay)
        assertEquals(5 * 60_000L, p.configMaxAgeMs)
    }

    @Test
    fun `quiet hours parse HH-MM and fall back on anything else`() {
        val p = NotificationPolicy.resolve(mapOf("notify.quiet.start" to "23:05", "notify.quiet.end" to "25:00"))
        assertEquals(23 * 60 + 5, p.quietStartMin)
        assertEquals(NotificationPolicy.DEFAULT.quietEndMin, p.quietEndMin)
    }

    @Test
    fun `recovery and quiet modes fall back when unknown`() {
        val p = NotificationPolicy.resolve(mapOf("notify.line.recovery" to "off", "notify.quiet.mode" to "loud"))
        assertEquals(NotificationPolicy.RecoveryMode.OFF, p.recovery)
        assertEquals(NotificationPolicy.QuietMode.SILENT, p.quietMode)
    }

    @Test
    fun `the store adopts a refresh and ignores an empty one`() {
        NotificationPolicyStore.resetForTest()
        NotificationPolicyStore.refresh(mapOf("notify.line.maxPerDay" to "1"))
        assertEquals(1, NotificationPolicyStore.current.maxPerDay)
        NotificationPolicyStore.refresh(emptyMap())
        assertEquals(1, NotificationPolicyStore.current.maxPerDay)
        NotificationPolicyStore.resetForTest()
    }

    @Test
    fun `a mode may have its own severities`() {
        val p = NotificationPolicy.resolve(mapOf("notify.line.alertSeverities.bus" to "Suspended, Diverted"))
        assertEquals(setOf("Suspended", "Diverted"), p.severitiesFor(NotificationPolicy.AlertLevel.SEVERE, "Bus"))
        assertEquals(NotificationPolicy.DEFAULT.alertSeverities, p.severitiesFor(NotificationPolicy.AlertLevel.SEVERE, "tube"))
    }

    @Test
    fun `a dash empties a list and nonsense zones fall back`() {
        val p = NotificationPolicy.resolve(
            mapOf("notify.remote.exemptTypes" to "-", "notify.quiet.zone" to "Mars/One", "notify.line.rolloutPercent" to "500"),
        )
        assertTrue(p.remote.exemptTypes.isEmpty())
        assertEquals("device", p.quietZone)
        assertEquals(100, p.rolloutPercent)
        assertEquals("Europe/London", NotificationPolicy.resolve(mapOf("notify.quiet.zone" to "Europe/London")).zone().id)
    }

    /**
     * The contract both repos rest on: deploying the backend, or losing it,
     * changes nothing. Copied from `NOTIFICATION_POLICY_KEYS` in
     * `notificationPolicyService.ts`; change a default there and here together.
     */
    @Test
    fun `the backends defaults are the apps defaults`() {
        val served = mapOf(
                "notify.line.enabled" to "true",
                "notify.line.decider" to "server",
                "notify.line.rolloutPercent" to "100",
                "notify.line.defaultLevel" to "severe",
                "notify.line.alertSeverities" to "Severe Delays,Suspended,Part Suspended",
                "notify.line.allSeverities" to "Severe Delays,Suspended,Part Suspended,Minor Delays,Reduced Service,Part Closure",
                "notify.line.modes" to "tube,overground,elizabeth-line,dlr,tram",
                "notify.line.confirmAfterMs" to "600000",
                "notify.line.confirmRetries" to "5",
                "notify.line.cooldownMs" to "7200000",
                "notify.line.maxPerDay" to "3",
                "notify.line.recovery" to "silent",
                "notify.line.expireAfterMs" to "14400000",
                "notify.line.muteMs" to "43200000",
                "notify.quiet.start" to "22:30",
                "notify.quiet.end" to "06:30",
                "notify.quiet.mode" to "silent",
                "notify.quiet.zone" to "device",
                "notify.remote.enabled" to "true",
                "notify.remote.disabledTypes" to "-",
                "notify.remote.maxPerDay" to "3",
                "notify.remote.exemptTypes" to "system",
                "notify.config.maxAgeMs" to "21600000",
                "notify.config.jitterMs" to "120000",
                "notify.copy.alertTitle" to "{line} · {severity}",
                "notify.copy.alertBody" to "{reason}",
                "notify.copy.alertBodyNoReason" to "Tap to see your board.",
                "notify.copy.recoveredTitle" to "{line} · {severity}",
                "notify.copy.recoveredBody" to "Easing off. Earlier: {previous}.",
                "notify.copy.recoveredBodyGood" to "Back to normal. Earlier: {previous}.",
                "notify.copy.muteAction" to "Mute for 12 hours",
                "notify.copy.levelSevere" to "Only severe delays and suspensions on your lines. Quiet overnight.",
                "notify.copy.levelAll" to "Minor delays and part closures too. Quiet overnight.",
                "notify.copy.levelOff" to "No line alerts. Your boards still show every change.",
        )
        assertEquals(NotificationPolicy.DEFAULT, NotificationPolicy.resolve(served))
    }

    @Test
    fun `every served key is read`() {
        // A value no default has, for each scalar key, must change the policy.
        val changed = mapOf(
            "notify.line.enabled" to "false", "notify.line.decider" to "device", "notify.line.rolloutPercent" to "7", "notify.line.defaultLevel" to "off",
            "notify.line.alertSeverities" to "X", "notify.line.allSeverities" to "X", "notify.line.modes" to "x",
            "notify.line.confirmAfterMs" to "1", "notify.line.confirmRetries" to "2", "notify.line.cooldownMs" to "1",
            "notify.line.maxPerDay" to "9", "notify.line.recovery" to "off", "notify.line.expireAfterMs" to "1",
            "notify.line.muteMs" to "1", "notify.quiet.start" to "01:02", "notify.quiet.end" to "01:02",
            "notify.quiet.mode" to "drop", "notify.quiet.zone" to "Europe/London", "notify.remote.enabled" to "false",
            "notify.remote.disabledTypes" to "x", "notify.remote.maxPerDay" to "9", "notify.remote.exemptTypes" to "-",
            "notify.config.maxAgeMs" to "400000", "notify.config.jitterMs" to "1", "notify.copy.alertTitle" to "x",
            "notify.copy.alertBody" to "x", "notify.copy.alertBodyNoReason" to "x", "notify.copy.recoveredTitle" to "x",
            "notify.copy.recoveredBody" to "x", "notify.copy.recoveredBodyGood" to "x", "notify.copy.muteAction" to "x", "notify.copy.levelSevere" to "x",
            "notify.copy.levelAll" to "x", "notify.copy.levelOff" to "x",
        )
        assertEquals(34, changed.size)
        for ((key, value) in changed) {
            assertTrue(NotificationPolicy.resolve(mapOf(key to value)) != NotificationPolicy.DEFAULT, key)
        }
    }
}
