package com.stationly.mobile.service

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stationly.core.config.NotificationPolicyStore
import com.stationly.core.config.SduiConfig
import com.stationly.core.model.LineStatus
import com.stationly.core.model.notification.NotificationPayload
import com.stationly.core.notification.LineAlertCoordinator
import com.stationly.core.notification.LineAlertPlatform
import com.stationly.mobile.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Android's hooks for line-status alerts. Everything that DECIDES (the
 * served policy, the rules, what the device remembers, the words) is shared in
 * core's [LineAlertCoordinator]. iOS cannot run this flow reliably and must
 * decide on the backend instead: docs/IOS_NOTIFICATIONS.md. See docs/LINE_ALERTS.md.
 */
object LineAlerts {

    /** A line status arrived for a line this device rides. */
    suspend fun onStatusChange(context: Context, previous: LineStatus?, current: LineStatus) =
        LineAlertCoordinator.onStatusChange(platform(context), previous, current)

    /** The backend's confirmed alert, or the end of one. */
    suspend fun onServerAlert(context: Context, status: LineStatus, tier: String, cleared: Boolean) =
        LineAlertCoordinator.onServerAlert(platform(context), status, tier, cleared)

    /** The Mute button on an alert. */
    suspend fun mute(context: Context, line: String) = LineAlertCoordinator.mute(platform(context), line)

    /** Both jobs read the backend; neither is worth starting without a network. */
    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun platform(context: Context): LineAlertPlatform = AndroidLineAlertPlatform(context.applicationContext)

    private class AndroidLineAlertPlatform(private val context: Context) : LineAlertPlatform {
        override fun requestConfigRefresh() = ConfigRefreshWorker.enqueue(context, "stale")
        override val deepLinkScheme: String = BuildConfig.DEEP_LINK_SCHEME
        override fun scheduleConfirm(line: String, mode: String, delayMs: Long, keepPending: Boolean) =
            ConfirmWorker.schedule(context, line, mode, delayMs, keepPending)
        override fun cancelConfirm(line: String) = ConfirmWorker.cancel(context, line)
        override fun post(payload: NotificationPayload) = NotificationDispatcher.dispatch(context, payload)
        override fun cancel(notificationId: Int) = NotificationDispatcher.cancel(context, notificationId)
        override fun isShowing(notificationId: Int): Boolean = runCatching {
            NotificationManagerCompat.from(context).activeNotifications.any { it.id == notificationId }
        }.getOrDefault(true) // unknown: assume showing, so an update is never lost
    }

    /**
     * The backend asked every install to refetch home-config (`config_refresh`
     * push, sent after a served value changed).
     *
     * A job, not a fetch inside the push handler, for two reasons. The push
     * reaches every install at once, so each one waits a random
     * moment within the served `notify.config.jitterMs` first: the backend sees the
     * fetches spread out instead of all in the same second. And a job runs once the network
     * is there and survives the push handler being ended, which a coroutine
     * started from it does not.
     */
    class ConfigRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val ok = SduiConfig.refreshFromNetwork()
            Log.d("LineAlerts", "config_refresh (${inputData.getString(KEY_REASON)}): ${if (ok) "adopted" else "fetch failed, kept cache"}")
            // A few retries with WorkManager's backoff, then give up: the next
            // app open or stale-config check fetches it anyway.
            return when {
                ok -> Result.success()
                runAttemptCount < MAX_ATTEMPTS - 1 -> Result.retry()
                else -> Result.success()
            }
        }

        companion object {
            private const val KEY_REASON = "reason"
            private const val MAX_ATTEMPTS = 3

            fun enqueue(context: Context, reason: String?) {
                val request = OneTimeWorkRequestBuilder<ConfigRefreshWorker>()
                    .setInitialDelay((0L..NotificationPolicyStore.current.configJitterMs).random(), TimeUnit.MILLISECONDS)
                    .setConstraints(needsNetwork)
                    .setInputData(workDataOf(KEY_REASON to (reason ?: "push")))
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("config_refresh", ExistingWorkPolicy.KEEP, request)
            }
        }
    }

    /**
     * The delayed re-check: one unique job per line. It is REPLACED when the
     * line has just become disrupted (so a flap keeps pushing it back and only
     * a disruption that holds for the whole window is looked at) and KEPT when
     * the line moves between two disruptions (so one that never recovers still
     * alerts on time).
     * WorkManager survives the process dying in between, which a coroutine
     * delay inside the push handler would not.
     */
    class ConfirmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val line = inputData.getString(KEY_LINE) ?: return Result.success()
            val mode = inputData.getString(KEY_MODE) ?: return Result.success()
            // Backs off 30 s, 1, 2, 4 min: the served five tries are about 8 minutes.
            val outcome = try {
                LineAlertCoordinator.confirm(platform(applicationContext), line, mode)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("LineAlerts", "$line: re-check failed", e)
                LineAlertCoordinator.ConfirmOutcome.RETRY
            }
            val again = outcome == LineAlertCoordinator.ConfirmOutcome.RETRY && runAttemptCount < NotificationPolicyStore.current.confirmRetries - 1
            return if (again) Result.retry() else Result.success()
        }

        companion object {
            private const val KEY_LINE = "line"
            private const val KEY_MODE = "mode"
            private fun name(line: String) = "line_alert_confirm_$line"

            fun schedule(context: Context, line: String, mode: String, delayMs: Long, keepPending: Boolean) {
                val request = OneTimeWorkRequestBuilder<ConfirmWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setConstraints(needsNetwork)
                    .setInputData(workDataOf(KEY_LINE to line, KEY_MODE to mode))
                    .build()
                // KEEP when the line moved between two disruptions (the wait
                // already running is the right one); REPLACE when it has just
                // become disrupted. See LineAlertRules.ChangeAction.ScheduleConfirm.
                val policy = if (keepPending) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
                WorkManager.getInstance(context).enqueueUniqueWork(name(line), policy, request)
            }

            fun cancel(context: Context, line: String) {
                WorkManager.getInstance(context).cancelUniqueWork(name(line))
            }
        }
    }
}
