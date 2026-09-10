package com.stationly.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.stationly.app.platform.NotificationAuthState
import com.stationly.app.platform.notificationAuthState
import com.stationly.app.platform.requestNotificationAuthorization

/**
 * Asks the user for notification permission once. Port of Android
 * `ui/common/NotificationPermissionEffect.kt`.
 *
 * Without the grant, every backend-driven notification and every
 * status-change auto-alert silently no-ops — the payload arrives, gets
 * parsed, then drops on the floor.
 *
 * Behaviour:
 *   - No-op once the user has decided (granted OR denied). iOS only ever
 *     shows the system dialog once per install; re-requesting after a denial
 *     returns `false` without any UI, so there is nothing to badger with.
 *   - Fires the system prompt on first composition after install.
 *
 * Android needs a SharedPreferences "we asked" flag because
 * `checkSelfPermission` cannot distinguish "denied" from "never prompted".
 * iOS reports `notDetermined` natively, so the flag has no iOS counterpart —
 * `NotificationAuthState.NOT_DETERMINED` IS the flag.
 *
 * Placement: invoked from [com.stationly.app.ui.summary.SummaryScreen] — the
 * first authenticated screen — exactly like Android. Asking on the login
 * screen would be premature: users haven't seen what notifications buy them
 * yet, and iOS gives us precisely one chance.
 */
@Composable
fun NotificationPermissionEffect(
    /**
     * Fired whenever the user makes a decision — grant or deny — so observers
     * can re-evaluate dependent state (e.g. the home's "notifications off"
     * banner). Defaults to no-op.
     */
    onDecision: (granted: Boolean) -> Unit = {},
) {
    LaunchedEffect(Unit) {
        if (notificationAuthState() != NotificationAuthState.NOT_DETERMINED) return@LaunchedEffect
        onDecision(requestNotificationAuthorization())
    }
}
