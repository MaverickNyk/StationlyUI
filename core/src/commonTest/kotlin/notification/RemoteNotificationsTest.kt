package com.stationly.core.notification

import com.stationly.core.config.NotificationPolicy
import com.stationly.core.model.UserSelection
import com.stationly.core.model.notification.NotificationAction
import com.stationly.core.model.notification.NotificationPayload
import com.stationly.core.notification.RemoteNotificationRules.Decision
import com.stationly.core.platform.StorageManager
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a SENT notification is allowed to do, whoever sent it. */
class RemoteNotificationsTest {

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

    private val zone = TimeZone.of("Europe/London")
    private fun at(h: Int, day: Int = 24) = LocalDateTime(2026, 11, day, h, 0).toInstant(zone).toEpochMilliseconds()
    private fun policy(vararg served: Pair<String, String>) =
        NotificationPolicy.resolve(mapOf("notify.quiet.zone" to "Europe/London") + served)
    private val news = NotificationPayload(type = "announcement", title = "t", body = "b")
    private fun decide(p: NotificationPayload, pol: NotificationPolicy = policy(), shown: Int = 0, now: Long = at(12)) =
        RemoteNotificationRules.decide(p, pol, shown, now, versionCode = 4)

    @Test
    fun `an ordinary one is shown as sent`() {
        assertEquals(Decision.Show(false), decide(news))
        assertEquals(Decision.Show(true), decide(news.copy(silent = true)))
    }

    @Test
    fun `old news and other builds are dropped even when exempt`() {
        val system = news.copy(type = "system")
        assertEquals(Decision.Drop("expired"), decide(system.copy(expiresAtMs = at(12))))
        assertEquals(Decision.Drop("version"), decide(system.copy(minVersionCode = 5)))
        assertEquals(Decision.Drop("version"), decide(system.copy(maxVersionCode = 3)))
        assertEquals(Decision.Show(false), decide(system.copy(expiresAtMs = at(13), minVersionCode = 4, maxVersionCode = 4)))
    }

    @Test
    fun `a campaign can be stopped after it was sent`() {
        assertEquals(Decision.Drop("off"), decide(news, policy("notify.remote.enabled" to "false")))
        assertEquals(Decision.Drop("type"), decide(news, policy("notify.remote.disabledTypes" to "promo,Announcement")))
        assertEquals(Decision.Drop("cap"), decide(news, shown = 3))
    }

    @Test
    fun `quiet hours bind sent ones too`() {
        assertEquals(Decision.Show(true), decide(news, now = at(2)))
        assertEquals(Decision.Drop("quiet"), decide(news, policy("notify.quiet.mode" to "drop"), now = at(2)))
    }

    @Test
    fun `an exempt type is never capped or quietened`() {
        assertEquals(Decision.Show(false), decide(news.copy(type = "system"), policy("notify.remote.enabled" to "false"), shown = 99, now = at(2)))
    }

    @Test
    fun `the cap counts what was shown and starts again next day`() = runTest {
        val storage = FakeStorage()
        val shown = mutableListOf<NotificationPayload>()
        val events = mutableListOf<String>()
        suspend fun send(now: Long) = RemoteNotifications.handle(
            news, 4, policy("notify.remote.maxPerDay" to "2"), storage, now, { n, _ -> events += n }, { shown.add(it) },
        )
        repeat(4) { send(at(12)) }
        assertEquals(2, shown.size)
        assertEquals(2, events.count { it == "notification.suppressed" })
        send(at(12, day = 25))
        assertEquals(3, shown.size)
    }

    @Test
    fun `a blocked one spends nothing and is recorded as blocked`() = runTest {
        val storage = FakeStorage()
        val reasons = mutableListOf<String?>()
        RemoteNotifications.handle(news, 4, policy(), storage, at(12), { _, p -> reasons += p["reason"] }, { false })
        assertEquals(listOf<String?>("blocked"), reasons)
        assertTrue(storage.strings.isEmpty())
    }

    @Test
    fun `an exempt notice does not use up the cap`() = runTest {
        val storage = FakeStorage()
        var shown = 0
        suspend fun send(type: String) = RemoteNotifications.handle(
            news.copy(type = type), 4, policy("notify.remote.maxPerDay" to "1"), storage, at(12), { _, _ -> }, { shown++; true },
        )
        send("system"); send("system"); send("announcement")
        assertEquals(3, shown)
    }

    @Test
    fun `a sent notification cannot carry an in-place action`() = runTest {
        val shown = mutableListOf<NotificationPayload>()
        val p = news.copy(actions = listOf(NotificationAction("Open", "stationly://home"), NotificationAction("Mute", action = "mute_line:victoria")))
        RemoteNotifications.handle(p, 4, policy(), FakeStorage(), at(12), { _, _ -> }, { shown.add(it) })
        assertEquals(listOf("Open"), shown.single().actions.map { it.label })
        assertTrue(shown.single().actions.none { it.action != null })
    }
}
