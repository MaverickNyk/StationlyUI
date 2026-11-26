package com.stationly.core.config

import kotlinx.datetime.TimeZone

/**
 * When a line-status change may become a notification: every rule SERVED.
 *
 * ## Why served
 * v1 compiled this decision into the app (any move off or back to Good Service,
 * "Service Closed" counted as severe). Users got a notification every day: each
 * line closing at night and "recovering" in the morning, bus roadworks, Minor
 * Delays flapping. Nothing could be changed without a release. The backend now
 * serves every rule as a `notify.*` key (`notificationPolicyService.ts`), and
 * this reads them with CLAMPED defaults, the same contract as [BoardPolicy]:
 * a missing or malformed value falls back to the compiled default here, which
 * is the same as the backend's default, so the first deploy changes nothing.
 *
 * The backend decides WHEN (the wait and the check); the phone applies what is
 * personal to it (level, mute, caps, quiet hours). iOS: docs/IOS_NOTIFICATIONS.md.
 */
data class NotificationPolicy(
    /** Kill switch for every line-status alert. */
    val enabled: Boolean,
    /** The backend waits, re-checks and sends `line_alert`; else this phone does, which a sleeping phone cannot. */
    val serverDecides: Boolean,
    /** Share of devices (0..100) that alert at all; see `LineAlertPreferences.bucket`. */
    val rolloutPercent: Int,
    /** Alert-worthy severities at the default "Severe only" level. */
    val alertSeverities: Set<String>,
    /** Alert-worthy severities at the opt-in "All changes" level. */
    val allSeverities: Set<String>,
    /** Per-mode replacements of the two sets above (`notify.line.alertSeverities.bus`). */
    val alertSeveritiesByMode: Map<String, Set<String>>,
    val allSeveritiesByMode: Map<String, Set<String>>,
    /** Which TfL modes may alert at all (lowercase). */
    val modes: Set<String>,
    /** Where a user who never touched the setting starts. */
    val defaultLevel: AlertLevel,
    /** A disruption must still be there this long after it appeared. */
    val confirmAfterMs: Long,
    /** Tries to learn the line's status at the re-check before giving up. */
    val confirmRetries: Int,
    /** At most one alert per line in this window. */
    val cooldownMs: Long,
    /** At most this many alerts per day across all lines. */
    val maxPerDay: Int,
    /** What a line easing off does to OUR earlier alert. */
    val recovery: RecoveryMode,
    /** An alert takes itself down after this long; 0 never. */
    val expireAfterMs: Long,
    /** What the Mute button on an alert does; 0 hides the button. */
    val muteMs: Long,
    /** Quiet hours in [quietZone], minutes after midnight. */
    val quietStartMin: Int,
    val quietEndMin: Int,
    val quietMode: QuietMode,
    /** "device" (people sleep by their own clock) or a named time zone. */
    val quietZone: String,
    /** Rules for notifications the backend SENDS, as opposed to line alerts. */
    val remote: Remote,
    /** How old the device's copy of the config may be before a refetch. */
    val configMaxAgeMs: Long,
    /** A refresh push is spread over this long, so installs do not all fetch at once. */
    val configJitterMs: Long,
    val copy: Copy,
) {
    enum class AlertLevel {
        SEVERE, ALL, OFF;
        companion object {
            fun parse(raw: String?): AlertLevel? = when (raw?.trim()?.lowercase()) {
                "severe" -> SEVERE
                "all" -> ALL
                "off" -> OFF
                else -> null
            }
        }
        val wire: String get() = name.lowercase()
    }

    enum class RecoveryMode { SILENT, ALERT, OFF }
    enum class QuietMode { SILENT, DROP }

    data class Remote(
        val enabled: Boolean,
        /** Payload types dropped on arrival: stops a campaign that has already been sent. */
        val disabledTypes: Set<String>,
        val maxPerDay: Int,
        /** Types never capped, quietened or switched off. */
        val exemptTypes: Set<String>,
    )

    data class Copy(
        val alertTitle: String,
        val alertBody: String,
        val alertBodyNoReason: String,
        val recoveredTitle: String,
        val recoveredBody: String,
        val recoveredBodyGood: String,
        val muteAction: String,
        /** The caption under each level in the app's "Line alerts" setting. */
        val levelSevere: String,
        val levelAll: String,
        val levelOff: String,
    )

    /** The severities that alert at [level] on a line of [mode]. Empty when OFF. */
    fun severitiesFor(level: AlertLevel, mode: String): Set<String> = when (level) {
        AlertLevel.SEVERE -> alertSeveritiesByMode[mode.trim().lowercase()] ?: alertSeverities
        AlertLevel.ALL -> allSeveritiesByMode[mode.trim().lowercase()] ?: allSeverities
        AlertLevel.OFF -> emptySet()
    }

    /** The clock quiet hours and the daily caps run on. */
    fun zone(): TimeZone =
        if (quietZone == DEVICE_ZONE) TimeZone.currentSystemDefault()
        else runCatching { TimeZone.of(quietZone) }.getOrElse { TimeZone.currentSystemDefault() }

    companion object {
        const val KEY_ENABLED = "notify.line.enabled"
        const val KEY_ALERT = "notify.line.alertSeverities"
        const val KEY_ALL = "notify.line.allSeverities"
        const val KEY_MODES = "notify.line.modes"
        const val KEY_LEVEL = "notify.line.defaultLevel"
        const val KEY_CONFIRM = "notify.line.confirmAfterMs"
        const val KEY_COOLDOWN = "notify.line.cooldownMs"
        const val KEY_MAX_PER_DAY = "notify.line.maxPerDay"
        const val KEY_RECOVERY = "notify.line.recovery"
        const val KEY_QUIET_START = "notify.quiet.start"
        const val KEY_QUIET_END = "notify.quiet.end"
        const val KEY_QUIET_MODE = "notify.quiet.mode"
        const val KEY_CONFIG_MAX_AGE = "notify.config.maxAgeMs"
        const val KEY_COPY_ALERT_TITLE = "notify.copy.alertTitle"
        const val KEY_COPY_ALERT_BODY = "notify.copy.alertBody"
        const val KEY_COPY_ALERT_BODY_NO_REASON = "notify.copy.alertBodyNoReason"
        const val KEY_COPY_RECOVERED_TITLE = "notify.copy.recoveredTitle"
        const val KEY_COPY_RECOVERED_BODY = "notify.copy.recoveredBody"
        const val KEY_COPY_LEVEL_SEVERE = "notify.copy.levelSevere"
        const val KEY_COPY_LEVEL_ALL = "notify.copy.levelAll"
        const val KEY_COPY_LEVEL_OFF = "notify.copy.levelOff"

        const val KEY_DECIDER = "notify.line.decider"
        const val KEY_ROLLOUT = "notify.line.rolloutPercent"
        const val KEY_CONFIRM_RETRIES = "notify.line.confirmRetries"
        const val KEY_EXPIRE = "notify.line.expireAfterMs"
        const val KEY_MUTE = "notify.line.muteMs"
        const val KEY_QUIET_ZONE = "notify.quiet.zone"
        const val KEY_REMOTE_ENABLED = "notify.remote.enabled"
        const val KEY_REMOTE_DISABLED = "notify.remote.disabledTypes"
        const val KEY_REMOTE_MAX = "notify.remote.maxPerDay"
        const val KEY_REMOTE_EXEMPT = "notify.remote.exemptTypes"
        const val KEY_JITTER = "notify.config.jitterMs"
        const val KEY_COPY_RECOVERED_GOOD = "notify.copy.recoveredBodyGood"
        const val KEY_COPY_MUTE = "notify.copy.muteAction"

        private const val MAX_COPY = 120
        private const val DEVICE_ZONE = "device"
        private const val HOUR = 60 * 60_000L

        val DEFAULT = NotificationPolicy(
            enabled = true,
            serverDecides = true,
            rolloutPercent = 100,
            alertSeveritiesByMode = emptyMap(),
            allSeveritiesByMode = emptyMap(),
            confirmRetries = 5,
            expireAfterMs = 4 * HOUR,
            muteMs = 12 * HOUR,
            quietZone = DEVICE_ZONE,
            remote = Remote(enabled = true, disabledTypes = emptySet(), maxPerDay = 3, exemptTypes = setOf("system")),
            configJitterMs = 2 * 60_000L,
            alertSeverities = setOf("Severe Delays", "Suspended", "Part Suspended"),
            allSeverities = setOf(
                "Severe Delays", "Suspended", "Part Suspended",
                "Minor Delays", "Reduced Service", "Part Closure",
            ),
            modes = setOf("tube", "overground", "elizabeth-line", "dlr", "tram"),
            defaultLevel = AlertLevel.SEVERE,
            confirmAfterMs = 10 * 60_000L,
            cooldownMs = 2 * 60 * 60_000L,
            maxPerDay = 3,
            recovery = RecoveryMode.SILENT,
            quietStartMin = 22 * 60 + 30,
            quietEndMin = 6 * 60 + 30,
            quietMode = QuietMode.SILENT,
            configMaxAgeMs = 6 * 60 * 60_000L,
            copy = Copy(
                alertTitle = "{line} · {severity}",
                alertBody = "{reason}",
                alertBodyNoReason = "Tap to see your board.",
                recoveredTitle = "{line} · {severity}",
                recoveredBody = "Easing off. Earlier: {previous}.",
                recoveredBodyGood = "Back to normal. Earlier: {previous}.",
                muteAction = "Mute for 12 hours",
                levelSevere = "Only severe delays and suspensions on your lines. Quiet overnight.",
                levelAll = "Minor delays and part closures too. Quiet overnight.",
                levelOff = "No line alerts. Your boards still show every change.",
            ),
        )

        /** "HH:MM" → minutes after midnight, or null when it is not a time. */
        fun parseClock(raw: String?): Int? {
            val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(raw ?: return null) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h !in 0..23 || min !in 0..59) return null
            return h * 60 + min
        }

        /**
         * Read the served map. Every value is clamped so that no config, however
         * wrong, makes the app noisier than a deliberate setting would: the
         * confirm delay is at least 0 and at most 2 h, the cooldown at most a
         * day, the daily cap 0..50.
         */
        fun resolve(m: Map<String, String>): NotificationPolicy {
            val d = DEFAULT
            fun set(key: String, default: Set<String>) =
                if (m[key]?.trim() == "-") emptySet() // the served way to say "none"
                else RemoteConfig.list(m, key, default.toList()).toSet()
            fun bool(key: String, default: Boolean) = when (m[key]?.trim()?.lowercase()) {
                "false", "0", "off", "no" -> false
                "true", "1", "on", "yes" -> true
                else -> default
            }
            fun byMode(prefix: String) = m.keys.filter { it.startsWith("$prefix.") }.mapNotNull { key ->
                RemoteConfig.list(m, key, emptyList()).takeIf { it.isNotEmpty() }
                    ?.let { key.removePrefix("$prefix.").lowercase() to it.toSet() }
            }.toMap()
            return NotificationPolicy(
                enabled = bool(KEY_ENABLED, d.enabled),
                serverDecides = when (m[KEY_DECIDER]?.trim()?.lowercase()) {
                    "server" -> true
                    "device" -> false
                    else -> d.serverDecides
                },
                rolloutPercent = RemoteConfig.int(m, KEY_ROLLOUT, d.rolloutPercent, 0, 100),
                alertSeveritiesByMode = byMode(KEY_ALERT),
                allSeveritiesByMode = byMode(KEY_ALL),
                confirmRetries = RemoteConfig.int(m, KEY_CONFIRM_RETRIES, d.confirmRetries, 1, 10),
                expireAfterMs = RemoteConfig.long(m, KEY_EXPIRE, d.expireAfterMs, 0L, 24 * HOUR),
                muteMs = RemoteConfig.long(m, KEY_MUTE, d.muteMs, 0L, 7 * 24 * HOUR),
                quietZone = m[KEY_QUIET_ZONE]?.trim()?.takeIf { z ->
                    z == DEVICE_ZONE || (z.isNotEmpty() && runCatching { TimeZone.of(z) }.isSuccess)
                } ?: d.quietZone,
                remote = Remote(
                    enabled = bool(KEY_REMOTE_ENABLED, d.remote.enabled),
                    disabledTypes = set(KEY_REMOTE_DISABLED, d.remote.disabledTypes),
                    maxPerDay = RemoteConfig.int(m, KEY_REMOTE_MAX, d.remote.maxPerDay, 0, 50),
                    exemptTypes = set(KEY_REMOTE_EXEMPT, d.remote.exemptTypes),
                ),
                configJitterMs = RemoteConfig.long(m, KEY_JITTER, d.configJitterMs, 0L, HOUR),
                alertSeverities = set(KEY_ALERT, d.alertSeverities),
                allSeverities = set(KEY_ALL, d.allSeverities),
                modes = set(KEY_MODES, d.modes).map { it.lowercase() }.toSet(),
                defaultLevel = AlertLevel.parse(m[KEY_LEVEL]) ?: d.defaultLevel,
                confirmAfterMs = RemoteConfig.long(m, KEY_CONFIRM, d.confirmAfterMs, 0L, 2 * 60 * 60_000L),
                cooldownMs = RemoteConfig.long(m, KEY_COOLDOWN, d.cooldownMs, 0L, 24 * 60 * 60_000L),
                maxPerDay = RemoteConfig.int(m, KEY_MAX_PER_DAY, d.maxPerDay, 0, 50),
                recovery = when (m[KEY_RECOVERY]?.trim()?.lowercase()) {
                    "silent" -> RecoveryMode.SILENT
                    "alert" -> RecoveryMode.ALERT
                    "off" -> RecoveryMode.OFF
                    else -> d.recovery
                },
                quietStartMin = parseClock(m[KEY_QUIET_START]) ?: d.quietStartMin,
                quietEndMin = parseClock(m[KEY_QUIET_END]) ?: d.quietEndMin,
                quietMode = when (m[KEY_QUIET_MODE]?.trim()?.lowercase()) {
                    "silent" -> QuietMode.SILENT
                    "drop" -> QuietMode.DROP
                    else -> d.quietMode
                },
                configMaxAgeMs = RemoteConfig.long(m, KEY_CONFIG_MAX_AGE, d.configMaxAgeMs, 5 * 60_000L, 7 * 24 * 60 * 60_000L),
                copy = Copy(
                    alertTitle = RemoteConfig.text(m, KEY_COPY_ALERT_TITLE, d.copy.alertTitle, MAX_COPY),
                    alertBody = RemoteConfig.text(m, KEY_COPY_ALERT_BODY, d.copy.alertBody, MAX_COPY),
                    alertBodyNoReason = RemoteConfig.text(m, KEY_COPY_ALERT_BODY_NO_REASON, d.copy.alertBodyNoReason, MAX_COPY),
                    recoveredTitle = RemoteConfig.text(m, KEY_COPY_RECOVERED_TITLE, d.copy.recoveredTitle, MAX_COPY),
                    recoveredBody = RemoteConfig.text(m, KEY_COPY_RECOVERED_BODY, d.copy.recoveredBody, MAX_COPY),
                    recoveredBodyGood = RemoteConfig.text(m, KEY_COPY_RECOVERED_GOOD, d.copy.recoveredBodyGood, MAX_COPY),
                    muteAction = RemoteConfig.text(m, KEY_COPY_MUTE, d.copy.muteAction, MAX_COPY),
                    levelSevere = RemoteConfig.text(m, KEY_COPY_LEVEL_SEVERE, d.copy.levelSevere, MAX_COPY),
                    levelAll = RemoteConfig.text(m, KEY_COPY_LEVEL_ALL, d.copy.levelAll, MAX_COPY),
                    levelOff = RemoteConfig.text(m, KEY_COPY_LEVEL_OFF, d.copy.levelOff, MAX_COPY),
                ),
            )
        }
    }
}

/** The adopted [NotificationPolicy]; refreshed with the rest of home-config. */
object NotificationPolicyStore {
    var current: NotificationPolicy = NotificationPolicy.DEFAULT
        private set

    fun refresh(strings: Map<String, String>) {
        if (strings.isEmpty()) return
        current = NotificationPolicy.resolve(strings)
    }

    fun resetForTest() {
        current = NotificationPolicy.DEFAULT
    }
}
