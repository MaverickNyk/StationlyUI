package com.stationly.mobile.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.stationly.core.notification.LineAlertCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Buttons on a notification that act in place instead of opening the app.
 * Not exported, and only the app's own alerts carry one (`RemoteNotifications`
 * strips them from anything sent).
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val line = action.removePrefix(LineAlertCoordinator.MUTE_ACTION_PREFIX)
        if (line == action || line.isBlank()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Muting reads a served duration; this process may be brand new.
                com.stationly.core.config.SduiConfig.ensureLoaded()
                LineAlerts.mute(context, line)
            } catch (e: Exception) {
                Log.e("LineAlerts", "mute $line failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ACTION = "action"

        fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, NotificationActionReceiver::class.java).putExtra(EXTRA_ACTION, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
