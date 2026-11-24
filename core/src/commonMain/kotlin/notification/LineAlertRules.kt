package com.stationly.core.notification

import com.stationly.core.config.NotificationPolicy
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Whether a line-status change becomes a notification. Pure: every input is a
 * parameter, so every rule is a unit test (`LineAlertRulesTest`).
 *
 * Two moments, because "is this worth telling somebody" cannot be answered the
 * instant a status changes:
 *
 *  1. [onChange], when the status arrives. A disruption that might be worth an
 *     alert is only SCHEDULED for a re-check ([NotificationPolicy.confirmAfterMs]
 *     later); a short flap is over by then and never alerts. A line easing off
 *     touches only the alert WE posted, per [NotificationPolicy.recovery].
 *  2. [onConfirm], at the re-check, with the line's status at THAT moment. Only
 *     here do the cooldown, the daily cap and quiet hours apply.
 *
 * What it replaces (v1, compiled into the app): any move off or back to Good
 * Service alerted, and "Service Closed" counted as severe, so every line
 * produced a pair of loud notifications every night.
 */
object LineAlertRules {

    const val GOOD_SERVICE = "Good Service"

    /** What this device remembers about alerts for one line. */
    data class LineMemory(
        /** When we last POSTED an alert (loud or silent) for this line; 0 = never. */
        val lastAlertAtMs: Long = 0L,
        /** The severity that alert named. */
        val alertedSeverity: String? = null,
        /** Whether that alert is still ours to update (no recovery since). */
        val open: Boolean = false,
    )

    sealed interface ChangeAction {
        /** Nothing to do. Also cancels any pending re-check for the line. */
        data object Nothing : ChangeAction
        /**
         * Re-check this line after [delayMs]. [keepPending]: a re-check already
         * waiting stays as it is. True when the line moved between two
         * alert-worthy states (Severe Delays → Suspended): it has been disrupted
         * the whole time, and restarting the wait on every such move would let
         * a line that never recovers postpone its alert for ever. False when it
         * has just become disrupted, which starts the wait afresh.
         */
        data class ScheduleConfirm(val delayMs: Long, val keepPending: Boolean = false) : ChangeAction
        /** Our open alert should now say the line has eased off. */
        data class Recover(val silent: Boolean) : ChangeAction
        /** Remove our open alert (recovery = off). */
        data object ClearOurs : ChangeAction
    }

    sealed interface ConfirmAction {
        data object Nothing : ConfirmAction
        /** Post (or replace) the line's alert. */
        data class Post(val silent: Boolean) : ConfirmAction
    }

    /** Whether [severity] on a line of [mode] alerts at [level] under [policy]. */
    fun isAlertWorthy(
        severity: String,
        mode: String,
        level: NotificationPolicy.AlertLevel,
        policy: NotificationPolicy,
    ): Boolean {
        if (!policy.enabled) return false
        if (mode.trim().lowercase() !in policy.modes) return false
        val set = policy.severitiesFor(level, mode)
        return set.any { it.equals(severity.trim(), ignoreCase = true) }
    }

    /**
     * A status arrived. [previous] is null the first time this device sees the
     * line, which never alerts: the first impression of the app must not be
     * "your line is broken" about something already true when it was installed.
     */
    fun onChange(
        previous: String?,
        current: String,
        mode: String,
        level: NotificationPolicy.AlertLevel,
        policy: NotificationPolicy,
        memory: LineMemory,
        nowMs: Long,
        zone: TimeZone = policy.zone(),
    ): ChangeAction {
        if (previous == null) return ChangeAction.Nothing
        // The same status re-broadcast is not a change. Callers must not act on
        // this answer for an unchanged status (it would cancel a pending
        // re-check); they skip the call instead. See [isChange].
        if (!isChange(previous, current)) return ChangeAction.Nothing
        if (isAlertWorthy(current, mode, level, policy)) {
            return ChangeAction.ScheduleConfirm(
                delayMs = policy.confirmAfterMs,
                keepPending = isAlertWorthy(previous, mode, level, policy),
            )
        }
        // Not alert-worthy now. Only OUR alert is ever touched by a recovery:
        // a line easing off that we never alerted about says nothing.
        if (!memory.open) return ChangeAction.Nothing

        // Alerts are off (kill switch, the user's own Off, a mode no longer
        // served): take ours down and say nothing. "Off" must mean off, a
        // recovery included.
        val silenced = !policy.enabled || level == NotificationPolicy.AlertLevel.OFF ||
            mode.trim().lowercase() !in policy.modes
        // "Eased off" is only true of Good Service or a milder disruption. A
        // line that went from Severe Delays to Service Closed has not eased.
        val eased = current.trim().equals(GOOD_SERVICE, ignoreCase = true) ||
            policy.severitiesFor(NotificationPolicy.AlertLevel.ALL, mode).any { it.equals(current.trim(), ignoreCase = true) }
        if (silenced || !eased) return ChangeAction.ClearOurs

        val quiet = inQuietHours(nowMs, policy, zone)
        if (quiet && policy.quietMode == NotificationPolicy.QuietMode.DROP) return ChangeAction.ClearOurs
        return when (policy.recovery) {
            NotificationPolicy.RecoveryMode.SILENT -> ChangeAction.Recover(silent = true)
            // Quiet hours bind a recovery as much as an alert.
            NotificationPolicy.RecoveryMode.ALERT -> ChangeAction.Recover(silent = quiet)
            NotificationPolicy.RecoveryMode.OFF -> ChangeAction.ClearOurs
        }
    }

    /**
     * The re-check. [current] is the line's status NOW, not the one that
     * scheduled this; [alertsToday] counts alerts already posted today.
     */
    fun onConfirm(
        current: String,
        mode: String,
        level: NotificationPolicy.AlertLevel,
        policy: NotificationPolicy,
        memory: LineMemory,
        alertsToday: Int,
        nowMs: Long,
        zone: TimeZone = policy.zone(),
    ): ConfirmAction {
        if (!isAlertWorthy(current, mode, level, policy)) return ConfirmAction.Nothing

        // Already telling them exactly this.
        if (memory.open && memory.alertedSeverity.equals(current.trim(), ignoreCase = true)) {
            return ConfirmAction.Nothing
        }

        // Too soon after the last alert for this line, or over today's budget:
        // an alert that is still OURS is quietly brought up to date (so it never
        // shows a stale severity); otherwise nothing.
        val coolingDown = memory.lastAlertAtMs > 0 && nowMs - memory.lastAlertAtMs < policy.cooldownMs
        val overBudget = alertsToday >= policy.maxPerDay
        if (coolingDown || overBudget) {
            return if (memory.open) ConfirmAction.Post(silent = true) else ConfirmAction.Nothing
        }

        if (inQuietHours(nowMs, policy, zone)) {
            return when (policy.quietMode) {
                NotificationPolicy.QuietMode.SILENT -> ConfirmAction.Post(silent = true)
                NotificationPolicy.QuietMode.DROP -> ConfirmAction.Nothing
            }
        }
        return ConfirmAction.Post(silent = false)
    }

    /** Whether the severity text actually moved (case- and space-insensitive). */
    fun isChange(previous: String?, current: String): Boolean =
        previous != null && !previous.trim().equals(current.trim(), ignoreCase = true)

    /** Whether [nowMs] falls in quiet hours. A window may cross midnight. */
    fun inQuietHours(nowMs: Long, policy: NotificationPolicy, zone: TimeZone = policy.zone()): Boolean {
        val start = policy.quietStartMin
        val end = policy.quietEndMin
        if (start == end) return false
        val t = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)
        val m = t.hour * 60 + t.minute
        return if (start < end) m in start until end else (m >= start || m < end)
    }

    /** The day an alert counts towards, as "YYYY-MM-DD" in [zone]. */
    fun dayKey(nowMs: Long, zone: TimeZone): String =
        Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date.toString()

    /** Fill `{line}` `{severity}` `{reason}` `{previous}` in a served template. */
    fun fill(template: String, line: String, severity: String, reason: String, previous: String): String =
        template
            .replace("{line}", line)
            .replace("{severity}", severity)
            .replace("{reason}", reason)
            .replace("{previous}", previous)
            .trim()

}
