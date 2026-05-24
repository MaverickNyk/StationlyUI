package com.stationly.mobile.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks the user for the POST_NOTIFICATIONS runtime permission once.
 *
 * Android 13 (API 33) added the user-grant requirement for any
 * notification posting. Without this prompt, every backend-driven
 * notification + every status-change auto-alert silently no-ops
 * inside [NotificationDispatcher.areNotificationsEnabled] — the
 * payload arrives, gets parsed, then drops on the floor.
 *
 * Behaviour:
 *   - No-op on API < 33 (permission auto-granted at install time).
 *   - No-op if already granted (re-launch / subsequent screens).
 *   - Triggers the system permission dialog on first composition
 *     after install. Android handles "deny" + "don't ask again"
 *     itself via the system UI; we don't need to re-prompt.
 *   - Persists "we asked" in SharedPrefs (KEY_ASKED) so we don't
 *     fire the launcher on every recomposition even when the user
 *     hasn't decided yet (e.g. they backgrounded the app with the
 *     dialog open). Android's launcher is idempotent in practice
 *     but the prefs flag makes the intent explicit + auditable.
 *
 * Placement: invoke from inside [SummaryScreen] (the first
 * authenticated screen). Asking on the login screen would feel
 * premature — users haven't seen what notifications buy them yet.
 *
 * If you ever need a richer "soft" pre-prompt (a Compose dialog
 * explaining WHY before the system prompt fires), extend this
 * effect with a state flag + a Dialog composable; the launcher
 * call would then be gated on the user confirming the soft prompt.
 */
@Composable
fun NotificationPermissionEffect() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val prefs = remember {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Persist outcome so we can tell, later, whether the user has
        // chosen vs never been asked. The system itself remembers the
        // grant state — this flag is only for our own UX decisions
        // (e.g. showing a "we can't notify you" hint if denied).
        prefs.edit()
            .putBoolean(KEY_ASKED, true)
            .putBoolean(KEY_LAST_GRANTED, granted)
            .apply()
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            // Idempotent — make sure our prefs flag matches reality
            // in case the user toggled from system settings.
            if (!prefs.getBoolean(KEY_LAST_GRANTED, false)) {
                prefs.edit().putBoolean(KEY_LAST_GRANTED, true).apply()
            }
            return@LaunchedEffect
        }

        val alreadyAsked = prefs.getBoolean(KEY_ASKED, false)
        if (alreadyAsked) {
            // We've shown the system prompt before and the user chose.
            // Don't badger them — Android will only re-show the prompt
            // if the user goes to system Settings and resets the permission,
            // and our flag prevents us launching it again here.
            return@LaunchedEffect
        }

        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val PREFS = "StationlyPrefs"
private const val KEY_ASKED = "post_notifications_asked"
private const val KEY_LAST_GRANTED = "post_notifications_granted"
