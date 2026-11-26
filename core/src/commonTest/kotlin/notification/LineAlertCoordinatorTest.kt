package com.stationly.core.notification

import com.stationly.core.config.NotificationPolicy
import com.stationly.core.config.NotificationPolicy.AlertLevel
import com.stationly.core.model.LineStatus
import com.stationly.core.model.UserSelection
import com.stationly.core.model.notification.NotificationPayload
import com.stationly.core.notification.LineAlertCoordinator.ConfirmOutcome
import com.stationly.core.platform.StorageManager
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wiring around [LineAlertRules]: what is remembered, what is read, and
 * what the platform is asked to do. Every bug the review of this feature found
 * lived here rather than in the rules, so each has a test.
 */
class LineAlertCoordinatorTest {

    private class FakeStorage : StorageManager {
        val strings = mutableMapOf<String, String>()
        override suspend fun saveString(key: String, value: String) { strings[key] = value }
        override suspend fun loadString(key: String): String? = strings[key]
        override suspend fun saveSelections(selections: List<UserSelection>) = unused()
        override suspend fun loadSelections(): List<UserSelection> = unused()
        override suspend fun saveLineStatus(lineId: String, statusJson: String) = unused()
        override suspend fun loadLineStatus(lineId: String): String? = unused()
        override suspend fun clearCache() = unused()
        override suspend fun clearAll() = unused()
        override suspend fun saveDurable(key: String, value: String) = unused()
        override suspend fun loadDurable(key: String): String? = unused()
        override suspend fun removeDurable(key: String) = unused()
        private fun unused(): Nothing = throw UnsupportedOperationException("not used here")
    }

    private class FakePlatform : LineAlertPlatform {
        override val deepLinkScheme = "stationly"
        val posts = mutableListOf<NotificationPayload>()
        val showing = mutableSetOf<Int>()
        var pending = false
        var lastKeep: Boolean? = null
        var refreshes = 0
        override fun scheduleConfirm(line: String, mode: String, delayMs: Long, keepPending: Boolean) {
            pending = true; lastKeep = keepPending
        }
        override fun cancelConfirm(line: String) { pending = false }
        var blocked = false
        override fun post(payload: NotificationPayload): Boolean {
            if (blocked) return false
            posts += payload; showing += payload.notificationId!!
            return true
        }
        override fun cancel(notificationId: Int) { showing -= notificationId }
        override fun isShowing(notificationId: Int) = notificationId in showing
        override fun requestConfigRefresh() { refreshes++ }
    }

    private class FakeEnv : LineAlertEnv {
        override val storage = FakeStorage()
        var time = at(12)
        /** The phone deciding by itself; the server-decided cases set [serverDecides]. */
        var policy = NotificationPolicy.DEFAULT.copy(quietZone = "Europe/London", serverDecides = false)
        var place = 0
        val events = mutableListOf<String>()
        override suspend fun bucket() = place
        override fun record(name: String, props: Map<String, String>) { events += name }
        var level = AlertLevel.SEVERE
        var backend: LineStatus? = null
        var boards = listOf("victoria")
        var stale = false
        override fun now() = time
        override fun policy() = policy
        override suspend fun level(policy: NotificationPolicy) = level
        override fun selections() = boards.map {
            UserSelection(
                mode = "tube", line = it, station = "S", parentStationId = "HUB", stationName = "S", direction = "inbound",
                destinations = emptyList(), destinationIds = emptyList(),
            )
        }
        override suspend fun fetchStatus(line: String, mode: String) = backend
        override suspend fun configIsStale(policy: NotificationPolicy) = stale
        override suspend fun refreshConfigIfStale(policy: NotificationPolicy) {}
    }

    private val platform = FakePlatform()
    private val env = FakeEnv()

    private suspend fun push(severity: String, stored: String? = null) = LineAlertCoordinator.onStatusChange(
        platform, stored?.let { status(it) }, status(severity), env,
    )

    private suspend fun recheck(severity: String?): ConfirmOutcome {
        env.backend = severity?.let { status(it) }
        return LineAlertCoordinator.confirm(platform, "victoria", "tube", env)
    }

    /** A disruption announced and still showing. */
    private suspend fun alerted(severity: String = "Severe Delays") {
        push("Good Service", stored = "Good Service")
        push(severity)
        recheck(severity)
        assertEquals(1, platform.posts.size)
    }

    @Test
    fun `a disruption that holds is announced once and eases off silently`() = runTest {
        alerted()
        assertFalse(platform.posts[0].silent)
        assertEquals("Victoria · Severe Delays", platform.posts[0].title)
        push("Good Service")
        assertEquals(2, platform.posts.size)
        assertTrue(platform.posts[1].silent)
        assertEquals(platform.posts[0].notificationId, platform.posts[1].notificationId)
        assertEquals("Back to normal. Earlier: Severe Delays.", platform.posts[1].body)
    }

    @Test
    fun `the widgets own refresh cannot swallow a change`() = runTest {
        push("Good Service", stored = "Good Service")
        // An unlock refreshed the status table over REST before the push landed.
        push("Suspended", stored = "Suspended")
        assertTrue(platform.pending)
    }

    @Test
    fun `a missed recovery push cannot announce a disruption that is over`() = runTest {
        push("Good Service", stored = "Good Service")
        push("Severe Delays")
        assertEquals(ConfirmOutcome.DONE, recheck("Good Service"))
        assertEquals(0, platform.posts.size)
    }

    @Test
    fun `an unknown status is tried again and never announced`() = runTest {
        push("Good Service", stored = "Good Service")
        push("Severe Delays")
        assertEquals(ConfirmOutcome.RETRY, recheck(null))
        assertEquals(0, platform.posts.size)
    }

    @Test
    fun `a board removed before the re-check says nothing`() = runTest {
        push("Good Service", stored = "Good Service")
        push("Suspended")
        env.boards = emptyList()
        assertEquals(ConfirmOutcome.DONE, recheck("Suspended"))
        assertEquals(0, platform.posts.size)
    }

    @Test
    fun `the first status ever heard says nothing`() = runTest {
        push("Suspended")
        assertFalse(platform.pending)
    }

    @Test
    fun `an alert the user swiped away is not put back by a recovery`() = runTest {
        alerted()
        platform.showing.clear()
        push("Good Service")
        assertEquals(1, platform.posts.size)
    }

    @Test
    fun `turning alerts off takes ours down without another word`() = runTest {
        alerted()
        env.level = AlertLevel.OFF
        push("Good Service")
        assertEquals(1, platform.posts.size)
        assertTrue(platform.showing.isEmpty())
    }

    @Test
    fun `closing for the night takes the alert down`() = runTest {
        alerted()
        push("Service Closed")
        assertEquals(1, platform.posts.size)
        assertTrue(platform.showing.isEmpty())
    }

    @Test
    fun `an escalation inside the cooldown updates silently and spends no budget`() = runTest {
        alerted()
        push("Suspended")
        assertEquals(true, platform.lastKeep)
        env.time += 20 * 60_000L
        recheck("Suspended")
        assertTrue(platform.posts[1].silent)
        assertEquals(1, LineAlertCoordinator.Memory(env.storage).alertsToday(env.time, TimeZone.of("Europe/London")))
    }

    @Test
    fun `a re-broadcast leaves a pending re-check alone`() = runTest {
        push("Good Service", stored = "Good Service")
        push("Suspended")
        push("Suspended")
        assertTrue(platform.pending)
    }

    @Test
    fun `a flap that clears cancels its re-check`() = runTest {
        push("Good Service", stored = "Good Service")
        push("Suspended")
        push("Good Service")
        assertFalse(platform.pending)
        assertEquals(0, platform.posts.size)
    }

    @Test
    fun `a stale policy is refetched in the background`() = runTest {
        env.stale = true
        push("Good Service", stored = "Suspended")
        assertEquals(1, platform.refreshes)
    }

    @Test
    fun `mute takes the alert down and keeps the line quiet until it ends`() = runTest {
        alerted()
        assertEquals("mute_line:victoria", platform.posts[0].actions.single().action)
        LineAlertCoordinator.mute(platform, "Victoria", env)
        assertTrue(platform.showing.isEmpty())
        push("Good Service"); push("Suspended")
        assertFalse(platform.pending)
        assertEquals(1, platform.posts.size)
        env.time += 13 * 60 * 60_000L
        push("Good Service"); push("Suspended")
        assertTrue(platform.pending)
        assertTrue("notification.muted" in env.events)
    }

    @Test
    fun `mute pressed while a re-check waits stops it`() = runTest {
        push("Good Service", stored = "Good Service"); push("Suspended")
        LineAlertCoordinator.mute(platform, "victoria", env)
        recheck("Suspended")
        assertEquals(0, platform.posts.size)
    }

    @Test
    fun `a device outside the rollout stays silent and one inside does not`() = runTest {
        env.policy = env.policy.copy(rolloutPercent = 10); env.place = 10
        push("Good Service", stored = "Good Service"); push("Suspended")
        assertFalse(platform.pending)
        env.place = 9
        push("Good Service"); push("Suspended")
        assertTrue(platform.pending)
    }

    @Test
    fun `an alert expires by itself and links to the hub`() = runTest {
        alerted()
        assertEquals(4 * 60 * 60_000L, platform.posts[0].timeoutAfterMs)
        assertEquals("stationly://home?station=HUB", platform.posts[0].deepLink)
    }

    @Test
    fun `a blocked alert is not counted and spends nothing`() = runTest {
        platform.blocked = true
        push("Good Service", stored = "Good Service"); push("Suspended"); recheck("Suspended")
        assertEquals(0, LineAlertCoordinator.Memory(env.storage).alertsToday(env.time, env.policy.zone()))
        assertTrue("notification.posted" !in env.events && "notification.suppressed" in env.events)
        // Unblocked: the next disruption is announced, not held by a cooldown.
        platform.blocked = false
        push("Good Service"); push("Suspended"); recheck("Suspended")
        assertEquals(1, platform.posts.size)
    }

    @Test
    fun `a hidden mute button is not on the alert`() = runTest {
        env.policy = env.policy.copy(muteMs = 0)
        alerted()
        assertTrue(platform.posts[0].actions.isEmpty())
    }

    private companion object {
        fun at(h: Int) = LocalDateTime(2026, 11, 24, h, 0).toInstant(TimeZone.of("Europe/London")).toEpochMilliseconds()
        fun status(severity: String) = LineStatus(
            id = "victoria", name = "Victoria", mode = "tube",
            statusSeverityDescription = severity, reason = null, lastUpdatedTime = "",
        )
    }

    // ── Decided by the backend ────────────────────────────────────────────────

    private fun serverDecides() { env.policy = env.policy.copy(serverDecides = true) }
    private suspend fun server(severity: String, tier: String = "severe", cleared: Boolean = false) =
        LineAlertCoordinator.onServerAlert(platform, status(severity), tier, cleared, env)

    @Test
    fun `when the backend decides the phone schedules nothing and fetches nothing`() = runTest {
        serverDecides()
        push("Good Service", stored = "Good Service")
        push("Severe Delays")
        assertFalse(platform.pending)
        env.backend = null
        server("Severe Delays")
        assertEquals(1, platform.posts.size)
        assertFalse(platform.posts.single().silent)
    }

    @Test
    fun `a phone that missed both status pushes is still told and cleared`() = runTest {
        serverDecides()
        server("Suspended")
        assertEquals(1, platform.posts.size)
        server("Good Service", cleared = true)
        assertEquals(2, platform.posts.size)
        assertTrue(platform.posts.last().silent)
        server("Good Service", cleared = true)
        assertEquals(2, platform.posts.size)
    }

    @Test
    fun `the ordinary recovery push ends a backend alert and the clear adds nothing`() = runTest {
        serverDecides()
        server("Suspended")
        push("Good Service")
        server("Good Service", cleared = true)
        assertEquals(2, platform.posts.size)
    }

    @Test
    fun `the backend alert obeys level mute rollout cap cooldown and quiet hours`() = runTest {
        serverDecides()
        server("Severe Delays", tier = "all")
        env.level = AlertLevel.OFF
        server("Severe Delays")
        env.level = AlertLevel.SEVERE
        env.policy = env.policy.copy(rolloutPercent = 0)
        server("Severe Delays")
        env.policy = env.policy.copy(rolloutPercent = 100, maxPerDay = 0)
        server("Severe Delays")
        env.policy = env.policy.copy(maxPerDay = 3)
        LineAlertCoordinator.mute(platform, "victoria", env)
        server("Severe Delays")
        assertEquals(0, platform.posts.size)
        env.time = at(12) + 13 * 60 * 60_000L // mute over; 01:00, quiet
        server("Severe Delays")
        assertTrue(platform.posts.single().silent)
        platform.showing.clear() // swiped
        server("Severe Delays") // a repeat inside the cooldown
        assertEquals(1, platform.posts.size)
    }

    @Test
    fun `the backends severities win over an older copy on the phone`() = runTest {
        serverDecides()
        server("Reduced Service")
        assertEquals(1, platform.posts.size)
    }

    @Test
    fun `a line not ridden and a phone told to decide itself ignore the backend`() = runTest {
        server("Suspended")
        serverDecides()
        env.boards = listOf("central")
        server("Suspended")
        assertEquals(0, platform.posts.size)
    }
}
