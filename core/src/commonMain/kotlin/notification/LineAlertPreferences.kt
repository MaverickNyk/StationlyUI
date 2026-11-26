package com.stationly.core.notification

import com.stationly.core.config.NotificationPolicy
import com.stationly.core.platform.Platform

/**
 * The user's own "Line alerts" level (Severe only / All changes / Off).
 *
 * Unset means "whatever the backend says new users start on"
 * (`notify.line.defaultLevel`), so moving that default reaches everybody who
 * never touched the setting, and nobody who did.
 *
 * Durable: signing out wipes ordinary storage, and a user who chose Off must not
 * find alerts back on after signing in again. Not identity, so safe to keep.
 */
object LineAlertPreferences {
    private const val KEY = "notify.line.level"

    /** The stored choice, or null when the user never made one. */
    suspend fun stored(): NotificationPolicy.AlertLevel? =
        runCatching { NotificationPolicy.AlertLevel.parse(Platform.storageManager.loadDurable(KEY)) }.getOrNull()

    /** The level in force: the user's choice, else the served default. */
    suspend fun effective(policy: NotificationPolicy): NotificationPolicy.AlertLevel =
        stored() ?: policy.defaultLevel

    suspend fun set(level: NotificationPolicy.AlertLevel) {
        runCatching { Platform.storageManager.saveDurable(KEY, level.wire) }
    }

    private const val BUCKET = "notify.line.bucket"

    /** This device's place (0..99) in a gradual rollout. Chosen once, then kept. */
    suspend fun bucket(): Int = runCatching {
        Platform.storageManager.loadDurable(BUCKET)?.toIntOrNull()?.takeIf { it in 0..99 }
            ?: (0..99).random().also { Platform.storageManager.saveDurable(BUCKET, it.toString()) }
    }.getOrDefault(0)
}
