package com.stationly.mobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.stationly.core.model.notification.NotificationChannelIds

/**
 * One-shot registration for every Stationly user-facing notification
 * channel. Called from [com.stationly.mobile.StationlyApplication] on
 * cold launch — `createNotificationChannel` is idempotent at the
 * system level so the cost of running it every launch is negligible.
 *
 * Why separate channels per type rather than one catch-all?
 *   - Android Settings shows the per-channel switches to the user;
 *     having "Line status alerts" as its own row gives users granular
 *     mute control without us having to ship in-app preferences for
 *     every category.
 *   - Channel importance is fixed at creation time — a HIGH-importance
 *     line-status push and a LOW-importance promo can't share one
 *     channel, or the promo would either spam or the status would whimper.
 *
 * Channel IDs come from the KMP-shared
 * [NotificationChannelIds] so the backend's `channel` payload field
 * can target the same constants without an Android-only re-declaration.
 */
object StationlyNotificationChannels {

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                NotificationChannelIds.LINE_STATUS,
                "Line status alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Major status changes on lines you've added to your board — " +
                    "Severe Delays, Part Suspended, Service Closed, and recoveries."
                enableLights(true)
                enableVibration(true)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                NotificationChannelIds.ANNOUNCEMENT,
                "Announcements",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "New Stationly features and tips."
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                NotificationChannelIds.SYSTEM,
                "System",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Account events, security notices, app updates."
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                NotificationChannelIds.PROMO,
                "Promotions",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Offers and Stationly news."
            }
        )
    }
}
