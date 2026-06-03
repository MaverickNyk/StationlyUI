package com.stationly.mobile.service

import android.content.Context
import android.os.Build
import com.stationly.core.model.sdui.DeviceInfo
import java.util.UUID

/**
 * Stable per-install device identifier used for multi-device session tracking.
 *
 * The backend keys its `sessions` map by this id so it can tell devices apart:
 * a station's subscription is only released when the user's LAST device logs
 * out (1→0), so signing out of one of several devices doesn't knock the others
 * off a board they're still watching.
 *
 * Stored in its OWN SharedPreferences file (`StationlyDevice`) — NOT the main
 * `StationlyPrefs`, which `FirebaseAuthManager.logout()` wipes. The id must
 * survive logout so the same device presents the same identity when it signs
 * back in (otherwise logout would orphan a session and login would create a
 * duplicate). It is generated once on first use and never rotated.
 */
object DeviceIdProvider {
    private const val PREFS = "StationlyDevice"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString(KEY, id).apply()
        }
    }

    /**
     * Device metadata stored alongside this device's session on the backend, so
     * the user doc shows a readable inventory of where the account is signed in
     * (e.g. "Google Pixel 8 · Android 14 · v1.0-staging").
     */
    fun info(context: Context): DeviceInfo {
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return DeviceInfo(
            platform   = "android",
            osVersion  = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            model      = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            appVersion = appVersion,
        )
    }
}
