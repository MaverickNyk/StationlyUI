package com.stationly.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Current POST_NOTIFICATIONS authorization, in the shared UI's vocabulary.
 *
 * ## Why this needs a stored flag and iOS does not
 * `checkSelfPermission` returns exactly two answers, granted and not-granted.
 * It cannot tell **denied** apart from **never asked** — and that is precisely
 * the distinction the shared UI branches on. `NotificationPermissionEffect`
 * prompts only on [NotificationAuthState.NOT_DETERMINED], and
 * `SummaryViewModel` raises the "notifications are off" banner only on
 * [NotificationAuthState.DENIED]. iOS reports `notDetermined` natively; Android
 * has to remember, which is what [NotificationPermissionStore] is for.
 */
actual suspend fun notificationAuthState(): NotificationAuthState {
    // Below API 33 the permission is granted at install time and there is no
    // runtime state to report. v1's effect returns early for the same reason.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return NotificationAuthState.AUTHORIZED
    }
    if (NotificationPermissionStore.isGranted()) return NotificationAuthState.AUTHORIZED
    // Not granted. Asked before ⇒ they said no. Never asked ⇒ still open.
    return if (NotificationPermissionStore.hasAsked()) {
        NotificationAuthState.DENIED
    } else {
        NotificationAuthState.NOT_DETERMINED
    }
}

/**
 * Fire the system prompt once, and record that we did.
 *
 * Returns the standing answer rather than launching when there is nothing to
 * ask: below API 33, when it is already granted, or when no Activity is on
 * screen to host the dialog.
 */
actual suspend fun requestNotificationAuthorization(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

    if (NotificationPermissionStore.isGranted()) {
        // Keep the flag honest. The user can grant this from system Settings,
        // which never comes through here; v1 does the same reconciliation on
        // every composition of its effect.
        NotificationPermissionStore.remember(granted = true)
        return true
    }

    val activity = currentActivity() ?: return false
    val granted = runCatching {
        awaitActivityResult(
            activity,
            ActivityResultContracts.RequestPermission(),
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }.getOrDefault(false)

    NotificationPermissionStore.remember(granted)
    return granted
}

/**
 * Android has a real per-app notification screen, so unlike iOS — which can
 * only reach the app's Settings page, one tap away — this lands directly on it.
 *
 * `FLAG_ACTIVITY_NEW_TASK` because the context here is the application's:
 * starting an Activity from a non-Activity context without it throws.
 */
actual fun openAppNotificationSettings() {
    val context = AndroidAppContext.context

    val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isSuccess) return

    // Fall back to the app's details page, which every Settings app resolves.
    // `ACTION_APP_NOTIFICATION_SETTINGS` has existed since API 26 — the whole
    // range this app supports — but OEM Settings builds have been known not to
    // answer it, and an unhandled ActivityNotFoundException would crash the app
    // from a settings row that is meant to be inert.
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Where "we already asked about notifications" is written down.
 *
 * ## This is v1's flag, deliberately, not a new one
 * The file and both keys are what the **shipped app** already writes, from
 * `com.stationly.mobile.ui.common.NotificationPermissionEffect`. Choosing a
 * different file would not fail, it would *forget*: every user who has already
 * answered would read back as `NOT_DETERMINED` after the cutover. The shared
 * effect would then call `requestNotificationAuthorization()`, and Android —
 * which never re-shows a dialog it has already shown — would return the
 * standing answer with no UI at all. Nothing on screen, nothing in a log. A
 * user who had *denied* would meanwhile stop seeing the banner that explains
 * why no alerts arrive, because their state would read as undecided rather than
 * denied.
 *
 * Same shape of trap as the device id in AV2-3.2, and pinned the same way:
 * `V1V2StorageContractTest` reads these off both classes and compares them.
 * AV2-3.5 deletes v1's half and this contract with it.
 */
object NotificationPermissionStore {

    /** Must match `com.stationly.mobile.ui.common.NotificationPermissionEffect`. */
    private const val PREFS = "StationlyPrefs"
    private const val KEY_ASKED = "post_notifications_asked"
    private const val KEY_LAST_GRANTED = "post_notifications_granted"

    internal fun isGranted(): Boolean = ContextCompat.checkSelfPermission(
        AndroidAppContext.context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    internal fun hasAsked(): Boolean = prefs().getBoolean(KEY_ASKED, false)

    internal fun remember(granted: Boolean) {
        prefs().edit()
            .putBoolean(KEY_ASKED, true)
            .putBoolean(KEY_LAST_GRANTED, granted)
            .apply()
    }

    private fun prefs() =
        AndroidAppContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
