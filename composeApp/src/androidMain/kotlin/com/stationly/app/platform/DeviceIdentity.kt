package com.stationly.app.platform

import android.content.Context
import android.os.Build
import com.stationly.core.model.sdui.DeviceInfo
import java.util.UUID

/**
 * The install's device identity, read from the same place the shipped app
 * already writes it.
 *
 * ## The file name and the key are the contract
 * `android/`'s `DeviceIdProvider` stores this in SharedPreferences file
 * `StationlyDevice` under `device_id`, and this reads and writes exactly that.
 * Not for tidiness — because the alternative is a v1 user opening v2 and being
 * issued a *new* identity. The backend keys its `sessions` map by this id and
 * only releases a station's subscription when the LAST device signs out, so a
 * changed id leaves the old session on the account forever: a ghost that logout
 * can never release, holding subscriptions for a device that no longer exists.
 * iOS spent two days on precisely this. See [`ios-device-id-ghost-sessions`].
 *
 * `:composeApp` cannot import `DeviceIdProvider` — the dependency runs the other
 * way — so the two agree by writing to the same file rather than by sharing
 * code. AV2-3.5 deletes one of them; until then the pair must be edited
 * together, which is why the names are spelled out as constants below.
 *
 * ## Its own preferences file, still
 * Not `StationlyPrefs`, which logout wipes. The id has to survive logout, or
 * signing out orphans a session and signing back in creates a duplicate.
 */
actual object DeviceIdentity {

    /** Must match `com.stationly.mobile.service.DeviceIdProvider`. */
    private const val PREFS = "StationlyDevice"
    private const val KEY = "device_id"

    /**
     * Cached after the first read. Not the source of truth — the preferences
     * file is — but `deviceId()` is called on every state sync and every
     * pending-op replay, and those should not each be a disk read.
     */
    @Volatile
    private var cached: String? = null

    actual fun deviceId(): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: readOrCreate().also { cached = it }
        }
    }

    private fun readOrCreate(): String {
        val prefs = AndroidAppContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        // commit(), not apply(). An id that reached callers but never reached
        // disk would be re-generated on the next cold start, which is the exact
        // failure this object exists to prevent — and it would only show up as
        // duplicate sessions on the backend, days later.
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY, it).commit() }
    }

    actual fun deviceInfo(): DeviceInfo = DeviceInfo(
        platform = "android",
        osVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        // Read from the installed package rather than a BuildConfig: :composeApp
        // is a library and has no BuildConfig of the app that includes it. This
        // is what makes the account's device list read "… · v1.0-staging"
        // instead of trailing off — the previous stub sent null.
        appVersion = runCatching {
            val ctx = AndroidAppContext.context
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull(),
    )
}
