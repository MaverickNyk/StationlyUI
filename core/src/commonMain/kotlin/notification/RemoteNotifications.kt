package com.stationly.core.notification

import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.config.NotificationPolicy
import com.stationly.core.config.NotificationPolicyStore
import com.stationly.core.model.notification.NotificationPayload
import com.stationly.core.platform.Platform
import com.stationly.core.platform.StorageManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * Whether a notification the backend SENT (announcements, tips, account
 * notices) is shown on this device. Line alerts are decided by
 * [LineAlertRules]; this is the same idea for everything else, so no sender can
 * be noisier than the served policy allows, and a campaign already sent can
 * still be stopped.
 */
object RemoteNotificationRules {

    sealed interface Decision {
        data class Show(val silent: Boolean) : Decision
        data class Drop(val reason: String) : Decision
    }

    fun decide(
        payload: NotificationPayload,
        policy: NotificationPolicy,
        shownToday: Int,
        nowMs: Long,
        versionCode: Int,
        zone: TimeZone = policy.zone(),
    ): Decision {
        // True of every type, exempt or not: showing it would be wrong, not just unwanted.
        if (payload.expiresAtMs != null && nowMs >= payload.expiresAtMs) return Decision.Drop("expired")
        if (payload.minVersionCode != null && versionCode < payload.minVersionCode) return Decision.Drop("version")
        if (payload.maxVersionCode != null && versionCode > payload.maxVersionCode) return Decision.Drop("version")

        val r = policy.remote
        if (r.exemptTypes.any { it.equals(payload.type, ignoreCase = true) }) return Decision.Show(payload.silent)
        if (!r.enabled) return Decision.Drop("off")
        if (r.disabledTypes.any { it.equals(payload.type, ignoreCase = true) }) return Decision.Drop("type")
        if (shownToday >= r.maxPerDay) return Decision.Drop("cap")
        if (LineAlertRules.inQuietHours(nowMs, policy, zone)) {
            return when (policy.quietMode) {
                NotificationPolicy.QuietMode.SILENT -> Decision.Show(silent = true)
                NotificationPolicy.QuietMode.DROP -> Decision.Drop("quiet")
            }
        }
        return Decision.Show(payload.silent)
    }
}

/** Applies [RemoteNotificationRules] on a device; `show` is the platform's own poster, false when the user blocked it. */
object RemoteNotifications {

    private const val DAY_KEY = "remote_notif_day"
    private val lock = Mutex()

    suspend fun handle(payload: NotificationPayload, versionCode: Int, show: (NotificationPayload) -> Boolean) = handle(
        payload, versionCode, NotificationPolicyStore.current, Platform.storageManager,
        Clock.System.now().toEpochMilliseconds(), ActivityLog::record, show,
    )

    internal suspend fun handle(
        payload: NotificationPayload,
        versionCode: Int,
        policy: NotificationPolicy,
        storage: StorageManager,
        nowMs: Long,
        record: (String, Map<String, String>) -> Unit,
        show: (NotificationPayload) -> Boolean,
    ): Unit = lock.withLock {
        val day = LineAlertRules.dayKey(nowMs, policy.zone())
        val shown = runCatching { storage.loadString(DAY_KEY) }.getOrNull()
            ?.split("|")?.takeIf { it.size == 2 && it[0] == day }?.get(1)?.toIntOrNull() ?: 0
        when (val d = RemoteNotificationRules.decide(payload, policy, shown, nowMs, versionCode)) {
            is RemoteNotificationRules.Decision.Drop ->
                record(ActivityEvents.NOTIFICATION_SUPPRESSED, mapOf("type" to payload.type, "reason" to d.reason))
            is RemoteNotificationRules.Decision.Show -> {
                // In-place actions are the app's own: a sent one could mute lines
                // the user never muted.
                val seen = show(payload.copy(silent = d.silent, actions = payload.actions.filter { it.action == null && it.deepLink.isNotBlank() }))
                if (!seen) {
                    record(ActivityEvents.NOTIFICATION_SUPPRESSED, mapOf("type" to payload.type, "reason" to "blocked"))
                    return@withLock
                }
                // An exempt notice must not use up the room an announcement has.
                if (policy.remote.exemptTypes.none { it.equals(payload.type, ignoreCase = true) }) {
                    runCatching { storage.saveString(DAY_KEY, "$day|${shown + 1}") }
                }
                record(ActivityEvents.NOTIFICATION_POSTED, mapOf("type" to payload.type, "silent" to d.silent.toString()))
            }
        }
    }
}
