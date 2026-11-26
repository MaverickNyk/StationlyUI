package com.stationly.mobile.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stationly.app.platform.DeviceIdentity
import com.stationly.app.sync.UserStateSync
import com.stationly.core.activity.ActivityUploader
import com.stationly.core.service.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The nightly drain of the activity queue — Android's half of a job iOS has
 * been doing alone.
 *
 * `ActivityUploader` is shared and has been complete since before this branch;
 * what it has never had on Android is anything to *call* it. iOS drives it from
 * `ActivityUploadScheduler` (a `BGProcessingTask` at 03:00 on a charger) and
 * from a foreground safety net. Android had neither, so every event
 * `ActivityLog.record` wrote went into `ActivityEventEntity` and stayed there:
 * a queue with a cap, filling up and dropping its oldest rows, on every Android
 * install since the trail shipped.
 *
 * ## Why WorkManager and not a coroutine on app start
 * Because the point is the device that is NOT being used. An upload on launch
 * reports the activity of someone who is opening the app anyway, and misses the
 * week where they did not. WorkManager survives process death, reboots and app
 * updates, and is the only Android API that will wake a process on a schedule
 * the user is not driving.
 *
 * ## Android's version is more reliable than iOS's, and still not a promise
 * iOS decides whether a background task runs at all, learning from when the user
 * opens the app; a phone in Low Power Mode gets none. WorkManager is deferred
 * rather than optional — but OEM battery managers kill it, so the same safety
 * net applies: [flushIfStaleOnForeground] uploads on app open when the oldest
 * queued event has aged past `ActivityUploader.STALE_AFTER_MS`. On a healthy
 * device that reads one integer out of SQLite and returns.
 */
class ActivityUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The board list's nightly safety net, riding on a wake the app already
        // has. Every board change pushes within seconds, so this is normally a
        // no-op — but "normally" excludes the case it exists for: the app killed
        // inside the debounce window, or a push that failed while offline. The
        // local list is right, the account's is stale, and nothing else would
        // ever notice, because the next push only fires on the next EDIT.
        // `BoardPushGate` makes it free when nothing is pending.
        runCatching { UserStateSync.flushNow() }

        val info = DeviceIdentity.deviceInfo()
        return try {
            val uploaded = ActivityUploader.flush(
                api = NetworkModule.sduiApi,
                deviceId = DeviceIdentity.deviceId(),
                platform = info.platform ?: "android",
                appVersion = info.appVersion,
            )
            Log.d(TAG, "nightly flush: ${if (uploaded) "uploaded" else "nothing queued"}")
            Result.success()
        } catch (e: Exception) {
            // Retry, not failure. The uploader already deletes what the server
            // refuses as malformed, so anything reaching here is transport —
            // offline, 401, 5xx — and is worth backing off and trying again.
            Log.w(TAG, "nightly flush failed; will retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ActivityUpload"

        /**
         * The unique name is the lock. `KEEP` means a cold start does not reset
         * the schedule, so an app opened ten times a day still gets one run a
         * day rather than ten deferred ones — and the work survives an app
         * update, which is when a `REPLACE` would otherwise push the next run a
         * full period into the future, every time.
         */
        private const val WORK_NAME = "stationly_activity_upload"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ActivityUploadWorker>(
                // One a day, with the last six hours of each period as the flex
                // window so WorkManager can batch this with whatever else the
                // device is doing rather than waking the radio for it alone.
                24, TimeUnit.HOURS,
                6, TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        // Not "charging". iOS requires a charger because its
                        // task type does; requiring one here would mean a phone
                        // that charges in the morning never reports at all.
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                // Aim the first run at the quiet hours. An approximation and
                // nothing more — WorkManager honours the constraints and its own
                // batching, not a wall clock — but it costs one subtraction and
                // it keeps the common case off the user's commute.
                .setInitialDelay(millisUntilNextQuietHour(), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Upload on app open if the queue has gone stale — the safety net for a
         * device whose background work never runs.
         *
         * Detached deliberately: this must not be cancelled by the Activity that
         * happened to trigger it going away, and it must not delay anything on
         * screen. Failures are silent by design; the next foreground tries again.
         */
        fun flushIfStaleOnForeground() {
            CoroutineScope(Dispatchers.IO).launch {
                val info = DeviceIdentity.deviceInfo()
                runCatching {
                    ActivityUploader.flushIfStale(
                        api = NetworkModule.sduiApi,
                        deviceId = DeviceIdentity.deviceId(),
                        platform = info.platform ?: "android",
                        appVersion = info.appVersion,
                    )
                }.onSuccess { if (it) Log.d(TAG, "foreground flush: queue was stale, uploaded") }
            }
        }

        /** Millis from now until the next 03:00 local. */
        private fun millisUntilNextQuietHour(): Long {
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 3)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now) || this == now) add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
