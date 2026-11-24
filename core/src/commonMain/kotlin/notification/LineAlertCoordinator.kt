package com.stationly.core.notification

import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.config.NotificationPolicy
import com.stationly.core.config.NotificationPolicyStore
import com.stationly.core.config.SduiConfig
import com.stationly.core.model.LineStatus
import com.stationly.core.model.UserSelection
import com.stationly.core.model.notification.NotificationAction
import com.stationly.core.model.notification.NotificationChannelIds
import com.stationly.core.model.notification.NotificationPayload
import com.stationly.core.notification.LineAlertRules.ChangeAction
import com.stationly.core.notification.LineAlertRules.ConfirmAction
import com.stationly.core.notification.LineAlertRules.LineMemory
import com.stationly.core.platform.Platform
import com.stationly.core.platform.StorageManager
import com.stationly.core.service.TflApiServiceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What a platform must provide for line-status alerts. Everything else (the
 * served policy, the rules, what the device remembers, the words on the
 * notification) is shared in [LineAlertCoordinator].
 *
 * Android: WorkManager for the re-check, `NotificationDispatcher` to post
 * (`android/.../service/LineAlerts.kt`).
 *
 * NOT for iOS: it delivers silent pushes and runs background tasks when it
 * likes, so a timed re-check on the device would fail quietly on real phones.
 * There the backend decides. See docs/IOS_NOTIFICATIONS.md.
 */
interface LineAlertPlatform {
    /**
     * Call [LineAlertCoordinator.confirm] for [line] after [delayMs], once there
     * is a network, and again later if it answers RETRY. A pending one is replaced, unless [keepPending], in which case it is left alone.
     */
    fun scheduleConfirm(line: String, mode: String, delayMs: Long, keepPending: Boolean)
    fun cancelConfirm(line: String)
    /** Show it. False when the user has blocked it (the app, or that channel). */
    fun post(payload: NotificationPayload): Boolean
    fun cancel(notificationId: Int)
    /**
     * Whether our notification [notificationId] is still on screen. A user who
     * swiped an alert away has dismissed it: a recovery or escalation must not
     * put it back.
     */
    fun isShowing(notificationId: Int): Boolean
    /** Refetch home-config in the background, soon, without blocking the caller. */
    fun requestConfigRefresh()
    /** The build's deep-link scheme (`stationly` or `stationly-staging`). */
    val deepLinkScheme: String
}

/**
 * Line-status alerts, end to end, where the device decides (Android): apply the SERVED policy
 * ([NotificationPolicyStore]) through the pure [LineAlertRules], remember what
 * this device has already said, and hand the platform a ready-made
 * [NotificationPayload].
 *
 * Replaces v1's compiled-in rule (any move off or back to Good Service
 * alerted, "Service Closed" counted as severe), which users experienced as a
 * notification every day. See docs/LINE_ALERTS.md.
 */
object LineAlertCoordinator {

    private val log = { msg: String -> println("LineAlerts: $msg") }

    /**
     * One decision at a time. The push handler and the re-check job both read
     * and write this device's alert memory, and can run at the same moment.
     */
    private val lock = Mutex()

    /** The re-check either finished, or could not learn the line's status and should be tried again. */
    enum class ConfirmOutcome { DONE, RETRY }

    /** [Memory.read], counting an alert the user has swiped away as closed. */
    private suspend fun memoryOf(platform: LineAlertPlatform, memory: Memory, line: String): LineMemory {
        val m = memory.read(line)
        return if (m.open && !platform.isShowing(notificationIdFor(line))) m.copy(open = false) else m
    }

    /**
     * A status push arrived for a line this device rides. [stored] is what the
     * shared status table held before it, used only the first time this device
     * hears about the line (null then = first sighting, which never alerts).
     */
    suspend fun onStatusChange(platform: LineAlertPlatform, stored: LineStatus?, current: LineStatus) =
        onStatusChange(platform, stored, current, LiveEnv)

    /** As of now: is the disruption that scheduled this still worth an alert? */
    suspend fun confirm(platform: LineAlertPlatform, line: String, mode: String): ConfirmOutcome =
        confirm(platform, line, mode, LiveEnv)

    internal suspend fun onStatusChange(
        platform: LineAlertPlatform, stored: LineStatus?, current: LineStatus, env: LineAlertEnv,
    ): Unit = lock.withLock {
        val memory = Memory(env.storage)
        val line = current.id.lowercase()
        // Compared with what the last PUSH said, not with the status table: the
        // widget's own refresh (every unlock) writes the same row over REST, so
        // by the time the push lands the table can already hold the new status
        // and the change would look like none at all.
        val previous = memory.seen(line) ?: stored?.statusSeverityDescription
        memory.markSeen(line, current.statusSeverityDescription)
        if (!LineAlertRules.isChange(previous, current.statusSeverityDescription)) return@withLock

        // The CACHED policy, deliberately: this runs inside a push handler the
        // OS may end at any moment, and waiting on the network here could lose
        // the re-check altogether. A stale one is refetched in the background,
        // and the re-check refetches before anything is posted.
        val policy = env.policy()
        if (env.configIsStale(policy)) platform.requestConfigRefresh()
        val level = levelFor(line, policy, memory, env)
        val mem = memoryOf(platform, memory, line)

        val action = LineAlertRules.onChange(
            previous, current.statusSeverityDescription, current.mode, level, policy, mem, env.now(),
        )
        log("$line: \"$previous\" → \"${current.statusSeverityDescription}\" ($level) → $action")
        when (action) {
            is ChangeAction.ScheduleConfirm ->
                platform.scheduleConfirm(line, current.mode, action.delayMs, action.keepPending)
            is ChangeAction.Recover -> {
                platform.cancelConfirm(line)
                platform.post(
                    payloadFor(platform, current, mem.alertedSeverity ?: previous.orEmpty(), recovered = true, silent = action.silent, env = env),
                )
                memory.close(line)
            }
            ChangeAction.ClearOurs -> {
                platform.cancelConfirm(line)
                platform.cancel(notificationIdFor(line))
                memory.close(line)
            }
            ChangeAction.Nothing -> platform.cancelConfirm(line)
        }
    }

    internal suspend fun confirm(
        platform: LineAlertPlatform, line: String, mode: String, env: LineAlertEnv,
    ): ConfirmOutcome {
        // Network first, outside the lock: it must not hold up the push handler.
        env.refreshConfigIfStale(env.policy())
        // Still a line this device rides? A board removed meanwhile must not alert.
        if (env.selections().none { it.line.equals(line, ignoreCase = true) }) {
            log("$line: re-check skipped, no board rides it now")
            return ConfirmOutcome.DONE
        }
        // The line's status NOW, from the backend, never the stored copy. Status
        // pushes are sent once per change and live 90 seconds, so a phone that
        // was offline when the line recovered still holds "Severe Delays", and
        // would announce a disruption that is over. Not knowing means not
        // alerting: try again later.
        val current = env.fetchStatus(line, mode) ?: run {
            log("$line: re-check could not fetch the status, not alerting")
            return ConfirmOutcome.RETRY
        }
        lock.withLock { confirmLocked(platform, line, mode, current, env) }
        return ConfirmOutcome.DONE
    }

    private suspend fun confirmLocked(
        platform: LineAlertPlatform, line: String, mode: String, current: LineStatus, env: LineAlertEnv,
    ) {
        val memory = Memory(env.storage)
        val policy = env.policy()
        val level = levelFor(line, policy, memory, env)
        val now = env.now()
        val mem = memoryOf(platform, memory, line)
        val today = memory.alertsToday(now, policy.zone())
        val action = LineAlertRules.onConfirm(current.statusSeverityDescription, mode, level, policy, mem, today, now)
        log("$line: re-check \"${current.statusSeverityDescription}\" ($level, today=$today) → $action")
        if (action !is ConfirmAction.Post) {
            env.record(ActivityEvents.NOTIFICATION_SUPPRESSED, mapOf("type" to TYPE, "line" to line, "reason" to "recheck"))
            return
        }

        // Silently bringing an alert already in the shade up to date is not a
        // NEW alert: it neither restarts the cooldown nor spends today's budget.
        val isUpdate = mem.open && action.silent
        val shown = platform.post(
            payloadFor(platform, current, mem.alertedSeverity.orEmpty(), recovered = false, silent = action.silent, env = env),
        )
        // Nobody saw it, so it happened to nobody: no allowance spent, nothing
        // to recover from, and not counted as shown.
        if (!shown) {
            env.record(ActivityEvents.NOTIFICATION_SUPPRESSED, mapOf("type" to TYPE, "line" to line, "reason" to "blocked"))
            return
        }
        memory.recordAlert(line, current.statusSeverityDescription, now, countIt = !isUpdate, zone = policy.zone())
        env.record(ActivityEvents.NOTIFICATION_POSTED, mapOf("type" to TYPE, "line" to line, "silent" to action.silent.toString()))
    }

    /**
     * The user pressed Mute on [line]'s alert: take it down and say nothing
     * more about that line for the served while. Other lines are unaffected.
     */
    suspend fun mute(platform: LineAlertPlatform, line: String) = mute(platform, line, LiveEnv)

    internal suspend fun mute(platform: LineAlertPlatform, line: String, env: LineAlertEnv): Unit = lock.withLock {
        val key = line.lowercase()
        val memory = Memory(env.storage)
        memory.mute(key, env.now() + env.policy().muteMs)
        memory.close(key)
        platform.cancelConfirm(key)
        platform.cancel(notificationIdFor(key))
        env.record(ActivityEvents.NOTIFICATION_MUTED, mapOf("type" to TYPE, "line" to key))
    }

    /** Off for a muted line, and for a device outside the rollout; else the user's level. */
    private suspend fun levelFor(
        line: String, policy: NotificationPolicy, memory: Memory, env: LineAlertEnv,
    ): NotificationPolicy.AlertLevel = when {
        memory.mutedUntil(line) > env.now() -> NotificationPolicy.AlertLevel.OFF
        env.bucket() >= policy.rolloutPercent -> NotificationPolicy.AlertLevel.OFF
        else -> env.level(policy)
    }

    private const val TYPE = "line_status_change"

    /** The in-place action on an alert's Mute button, `mute_line:<line>`. */
    const val MUTE_ACTION_PREFIX = "mute_line:"

    /** One notification per line: a later post REPLACES the earlier one. */
    fun notificationIdFor(line: String): Int = ("line_status::" + line.lowercase()).hashCode()

    /** The notification for [status], worded by the served `notify.copy.*`. */
    private fun payloadFor(
        platform: LineAlertPlatform,
        status: LineStatus,
        previousSeverity: String,
        recovered: Boolean,
        silent: Boolean,
        env: LineAlertEnv,
    ): NotificationPayload {
        val policy = env.policy()
        // "Bus 21", as the boards say it: a bare number is not a title.
        val lineLabel = (status.name.takeIf { it.isNotBlank() } ?: status.id.replaceFirstChar { it.uppercase() })
            .let { if (status.mode.equals("bus", ignoreCase = true)) "Bus $it" else it }
        val sev = status.statusSeverityDescription.trim()
        val good = sev.equals(LineAlertRules.GOOD_SERVICE, ignoreCase = true)
        val reason = status.reason?.trim().orEmpty()
        val copy = policy.copy
        val title = LineAlertRules.fill(if (recovered) copy.recoveredTitle else copy.alertTitle, lineLabel, sev, reason, previousSeverity)
        val body = LineAlertRules.fill(
            when {
                recovered -> if (good) copy.recoveredBodyGood else copy.recoveredBody
                reason.isNotEmpty() -> copy.alertBody
                else -> copy.alertBodyNoReason
            },
            lineLabel, sev, reason, previousSeverity,
        ).ifBlank { copy.alertBodyNoReason }

        val station = env.selections()
            .firstOrNull { it.line.equals(status.id, ignoreCase = true) }
            // The HUB, which is what the home screen focuses by; `station` is
            // the pole, and on a bus stop the two differ.
            ?.groupingId
        val severity = when {
            recovered && good -> "success"
            recovered -> "warning"
            policy.severitiesFor(NotificationPolicy.AlertLevel.SEVERE, status.mode).any { it.equals(sev, ignoreCase = true) } -> "danger"
            else -> "warning"
        }
        return NotificationPayload(
            type = TYPE,
            title = title,
            body = body,
            severity = severity,
            subtitle = if (recovered) "Service update" else "Line alert",
            channel = NotificationChannelIds.LINE_STATUS,
            priority = if (silent) "low" else "high",
            color = com.stationly.core.util.TflLineColors.hexFor(status.id),
            style = "bigText",
            deepLink = station?.let { "${platform.deepLinkScheme}://home?station=$it" },
            groupKey = "stationly_line_status",
            notificationId = notificationIdFor(status.id),
            lineId = status.id,
            lineName = lineLabel,
            previousStatus = previousSeverity,
            newStatus = sev,
            silent = silent,
            timeoutAfterMs = policy.expireAfterMs.takeIf { it > 0 },
            actions = if (recovered || policy.muteMs <= 0) emptyList() else listOf(
                NotificationAction(label = copy.muteAction, action = MUTE_ACTION_PREFIX + status.id.lowercase()),
            ),
        )
    }

    /**
     * What this device remembers about its own line alerts, in the shared
     * key/value store, so both platforms keep it the same way.
     */
    internal class Memory(private val storage: StorageManager) {
        @Serializable
        private data class Stored(
            val last: Long = 0L,
            val sev: String? = null,
            val open: Boolean = false,
            /** The severity the last push named, whatever was decided about it. */
            val seen: String? = null,
            val mutedUntil: Long = 0L,
        )

        @Serializable
        private data class Day(val day: String = "", val count: Int = 0)

        private val json = Json { ignoreUnknownKeys = true }
        private fun lineKey(line: String) = "line_alert_mem_$line"

        private suspend fun load(line: String): Stored = runCatching {
            storage.loadString(lineKey(line))?.let { json.decodeFromString<Stored>(it) }
        }.getOrNull() ?: Stored()

        private suspend fun save(line: String, s: Stored) {
            runCatching { storage.saveString(lineKey(line), json.encodeToString(s)) }
        }

        suspend fun read(line: String): LineMemory = load(line).let { LineMemory(it.last, it.sev, it.open) }

        suspend fun seen(line: String): String? = load(line).seen

        suspend fun markSeen(line: String, severity: String) = save(line, load(line).copy(seen = severity.trim()))

        suspend fun mutedUntil(line: String): Long = load(line).mutedUntil

        suspend fun mute(line: String, untilMs: Long) = save(line, load(line).copy(mutedUntil = untilMs))

        suspend fun alertsToday(nowMs: Long, zone: TimeZone): Int {
            val d = runCatching { storage.loadString(DAY_KEY)?.let { json.decodeFromString<Day>(it) } }.getOrNull() ?: Day()
            return if (d.day == LineAlertRules.dayKey(nowMs, zone)) d.count else 0
        }

        suspend fun recordAlert(line: String, severity: String, nowMs: Long, countIt: Boolean, zone: TimeZone) {
            val prev = load(line)
            save(line, prev.copy(sev = severity.trim(), open = true, last = if (countIt) nowMs else prev.last))
            if (countIt) {
                val day = Day(LineAlertRules.dayKey(nowMs, zone), alertsToday(nowMs, zone) + 1)
                runCatching { storage.saveString(DAY_KEY, json.encodeToString(day)) }
            }
        }

        suspend fun close(line: String) = save(line, load(line).copy(open = false))

        private companion object {
            const val DAY_KEY = "line_alert_day"
        }
    }
}

/** What the coordinator reads from the outside, so a test can stand in for all of it. */
internal interface LineAlertEnv {
    val storage: StorageManager
    fun now(): Long
    fun policy(): NotificationPolicy
    suspend fun level(policy: NotificationPolicy): NotificationPolicy.AlertLevel
    suspend fun bucket(): Int
    fun selections(): List<UserSelection>
    fun record(name: String, props: Map<String, String>)
    /** The line's status from the backend, or null when it could not be fetched. */
    suspend fun fetchStatus(line: String, mode: String): LineStatus?
    suspend fun configIsStale(policy: NotificationPolicy): Boolean
    suspend fun refreshConfigIfStale(policy: NotificationPolicy)
}

private object LiveEnv : LineAlertEnv {
    private const val STATUS_TIMEOUT_MS = 10_000L
    override val storage: StorageManager get() = Platform.storageManager
    override fun now(): Long = Clock.System.now().toEpochMilliseconds()
    override fun policy(): NotificationPolicy = NotificationPolicyStore.current
    override suspend fun level(policy: NotificationPolicy) = LineAlertPreferences.effective(policy)
    override suspend fun bucket() = LineAlertPreferences.bucket()
    override fun record(name: String, props: Map<String, String>) = ActivityLog.record(name, props)
    override fun selections(): List<UserSelection> = Platform.sqlStorage.getAllSelections()
    override suspend fun fetchStatus(line: String, mode: String): LineStatus? {
        // The job can start a moment before the network is really usable: on a
        // Pixel the first request often cannot resolve the host, and the next,
        // seconds later, can. Worth a few seconds before falling back to the
        // platform's own retry, which is half a minute away.
        for (pauseMs in listOf(0L, 3_000L, 8_000L)) {
            delay(pauseMs)
            fetchOnce(line, mode)?.let { return it }
        }
        return null
    }

    private suspend fun fetchOnce(line: String, mode: String): LineStatus? = try {
        val all = withTimeoutOrNull(STATUS_TIMEOUT_MS) { TflApiServiceFactory.create().getLineStatuses(line, mode) }
        // Matched on id: announcing another line's status would be worse than
        // announcing nothing.
        val match = all?.firstOrNull { it.id.equals(line, ignoreCase = true) }
        if (match == null) println("LineAlerts: $line: no status (${if (all == null) "timed out" else "${all.size} rows, none for it"})")
        match
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("LineAlerts: $line: status fetch failed: ${e::class.simpleName}: ${e.message?.take(120)}")
        null
    }
    override suspend fun configIsStale(policy: NotificationPolicy) = SduiConfig.isStale(policy.configMaxAgeMs)
    override suspend fun refreshConfigIfStale(policy: NotificationPolicy) = SduiConfig.refreshIfStale(policy.configMaxAgeMs)
}
