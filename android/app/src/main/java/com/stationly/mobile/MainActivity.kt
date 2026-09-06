package com.stationly.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.stationly.app.AndroidPlatformAuthProvider
import com.stationly.app.App
import com.stationly.core.model.deeplink.DeepLinkRoute
import com.stationly.core.model.deeplink.parseDeepLink
import com.stationly.mobile.service.UserSyncCoordinator
import com.stationly.mobile.ui.common.StagingBanner
import com.stationly.mobile.widget.WidgetConfigureActivity
import com.stationly.mobile.widget.WidgetPinner
import kotlinx.coroutines.launch

/**
 * The Android host: the Android half of a contract that is one function
 * signature.
 *
 * iOS's half is `MainViewController.kt` — twenty lines that build a
 * `PlatformAuthProvider` and hand it to `App()`. This is those twenty lines with
 * an Activity around them, plus the three extra deep links only Android
 * registers. Everything the user sees below `setContent` lives in `:composeApp`
 * and is the identical code iOS runs.
 *
 * ## What this file replaced (AV2-3.5)
 * Until the cutover this class was v1's own UI: a 460-line `NavHost` over
 * `com.stationly.mobile.ui.{summary,selection,profile,login}`, and the shared UI
 * lived one icon away in a staging-only `V2MainActivity`. That second door is
 * gone and this is the one left.
 *
 * **The class NAME is load-bearing and must not change.** Home-screen pins and
 * launcher shortcuts reference the component
 * (`com.stationly.mobile/.MainActivity`), not the package, so promoting
 * `.v2.V2MainActivity` and deleting this name would have greyed out the icon of
 * every v1 user who had pinned one. Moving the host INTO the old name costs
 * nothing and needs no `<activity-alias>`.
 */
class MainActivity : ComponentActivity() {

    /**
     * Whether the user was signed in when this Activity was FIRST created, held
     * across process death rather than recomputed.
     *
     * `AppNavigation` turns this into its `startDestination`, and a NavHost can
     * only restore a saved back stack onto the same start destination it was
     * built with. Recomputing `isLoggedIn()` on a restore asks Firebase a
     * question it may not have rehydrated the answer to yet: it says "no user",
     * the start destination flips to `auth/login`, and a back stack rooted at
     * `summary` has nowhere to land — a blank screen.
     *
     * That is not hypothetical. v1 shipped this bug and fixed it with a
     * `rememberSaveable` around the same decision. `AppNavigation` now saves the
     * resolved destination itself, which closes the two branches this cannot
     * reach (they ask the auth provider, not the host) — but it still has to be
     * told the same answer twice in a row, so the saving stays here too.
     */
    private var startLoggedIn: Boolean = false

    /** The `oobCode` from an inbound `…://reset` link. */
    private var pendingResetOobCode by mutableStateOf<String?>(null)

    /** A `…://auth` link landed: a password reset completed in the browser. */
    private var passwordResetComplete by mutableStateOf(false)

    /**
     * How many `…://verified` codes this Activity has applied.
     *
     * A counter and not a flag, because the second link has to be as visible as
     * the first: a user who taps an expired link, gets a new mail and taps again
     * would otherwise set an already-true boolean and change nothing. Read by
     * the shared verify screen, which re-runs its own check on every new value.
     */
    private var emailVerifiedSignal by mutableIntStateOf(0)

    private lateinit var authProvider: AndroidPlatformAuthProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        // `Platform.initialize` is NOT called here on purpose. It runs in
        // StationlyApplication.onCreate, which Android guarantees to complete
        // before any component of the process starts — including this one.
        // (Asserted statically by HostManifestTest, since there is no runtime
        // flag on Platform to check.)
        //
        // The Activity, not the application context: interactive Google sign-in
        // launches the account chooser for a result and needs one. Held only by
        // this Activity and by the composition, which die together, so there is
        // nothing here to leak.
        //
        // `default_web_client_id` is generated by the google-services plugin
        // from THIS flavour's google-services.json, so a staging build signs in
        // against the staging OAuth client and prod against prod's. It is read
        // here, in the module that has the generated `R`, rather than looked up
        // by name inside `:composeApp` — see the provider's KDoc for why that
        // matters to a release build specifically.
        authProvider = AndroidPlatformAuthProvider(
            context           = this,
            googleWebClientId = getString(R.string.default_web_client_id),
        )

        startLoggedIn = if (savedInstanceState?.containsKey(KEY_START_LOGGED_IN) == true) {
            savedInstanceState.getBoolean(KEY_START_LOGGED_IN)
        } else {
            authProvider.isLoggedIn()
        }

        handleDeepLink(intent)

        setContent {
            Box(Modifier.fillMaxSize()) {
                App(
                    authProvider               = authProvider,
                    startLoggedIn              = startLoggedIn,
                    deepLinkOobCode            = pendingResetOobCode,
                    emailVerifiedSignal        = emailVerifiedSignal,
                    showPasswordResetSuccess   = passwordResetComplete,
                    onPasswordResetBannerShown = { passwordResetComplete = false },
                    // Home settings → "Widget stations". No `appWidgetId`, so
                    // the screen opens in manager mode: every placed widget and
                    // the station it shows. Null on iOS, where an app cannot
                    // read a widget's configuration at all — the row is hidden
                    // rather than shown and broken.
                    onManageWidgets = {
                        // `this@MainActivity`: inside `setContent` the receiver
                        // is the composable scope, not the Activity.
                        startActivity(WidgetConfigureActivity.managerIntent(this@MainActivity))
                    },
                    // The Android front door: ask the LAUNCHER to place a widget
                    // already bound to this station, so the user confirms one
                    // dialog instead of hunting through the widget gallery and
                    // then answering a picker. Null where the launcher refuses
                    // pin requests, which hides the row rather than showing one
                    // that does nothing — `canPin` is read at composition, not
                    // cached, because a launcher can be swapped.
                    onAddWidgetForStation = if (WidgetPinner.canPin(this@MainActivity)) {
                        { groupingId -> WidgetPinner.pin(this@MainActivity, groupingId) }
                    } else {
                        null
                    },
                )
                // Outside `App` because it is the one thing on screen the shared
                // UI cannot know: `BuildConfig.FLAVOR` belongs to this module.
                // One implementation, in the module that has the fact.
                StagingBanner(Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    /**
     * The foreground fallback for a `user_sync` push this device missed.
     *
     * ## Why this is on the Activity and not in the shared resume hook
     * AV2-4.1 asked for it in `SummaryScreen`'s `ON_RESUME` observer, where iOS
     * would get it too. It cannot go there yet: `UserSyncCoordinator` lives in
     * `:android:app` and `:composeApp` cannot import it — the dependency runs
     * the other way. The alternatives were a second reconcile implementation in
     * `composeApp/androidMain`, which is the divergence this codebase keeps
     * paying for, or lifting the coordinator up a module, which is AV2-4.3's
     * job and is where it is going anyway. So this is v1's arrangement,
     * restored exactly, until the coordinator moves.
     *
     * `MainActivity.onResume` is also the more faithful trigger: it fires on app
     * foreground, where the screen's observer fires on every screen resume too.
     * The coordinator debounces at 15 minutes either way, and a push-triggered
     * reconcile passes `force = true` and bypasses it.
     *
     * ## Why it was gone, and why that was survivable
     * AV2-3.5 deleted v1's `MainActivity`, and this call with it, leaving
     * `reconcile` with no caller at all. Nothing broke visibly, because the push
     * it backs up did not reach the v2 board model either — a fallback for
     * nothing. AV2-4.1 fixes both halves in one change, which is the only order
     * in which either is testable.
     */
    override fun onResume() {
        super.onResume()
        UserSyncCoordinator.reconcile(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_START_LOGGED_IN, startLoggedIn)
    }

    /**
     * Every launch of this Activity arrives here rather than building a second
     * instance, because the manifest declares `launchMode="singleTask"`.
     *
     * That is load-bearing: under the default "standard" mode a relaunch spawned
     * a NEW task with a fresh Activity, the tasks piled up (three observed at
     * once), and returning to a freshly-restored instance found the NavHost back
     * stack empty — a blank screen. v1 shipped it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Route an inbound link, against the scheme THIS build answers to.
     *
     * The routing itself lives in `core` as [parseDeepLink]; this turns a route
     * into Compose state. Two reasons it is not a `when` over `uri.scheme`:
     *
     * 1. **The scheme is per-flavour.** Prod answers `stationly://`, staging
     *    `stationly-staging://`. A literal here would match neither on staging:
     *    the link would arrive (the manifest filter uses the same placeholder)
     *    and be dropped, silently. That is the bug iOS shipped, and it cost two
     *    days because nothing errors and taps simply do nothing.
     *    `BuildConfig.DEEP_LINK_SCHEME` and the manifest's `${deepLinkScheme}`
     *    are set from one argument in build.gradle.kts, so what we register and
     *    what we accept cannot disagree.
     * 2. One table, in `core`, tested against the recorded v1 behaviour in
     *    `docs/android-v2/fixtures/v1/deeplinks.json`.
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        when (val route = parseDeepLink(uri.toString(), BuildConfig.DEEP_LINK_SCHEME)) {
            is DeepLinkRoute.PasswordResetComplete -> passwordResetComplete = true
            is DeepLinkRoute.Home                  -> { /* just opens the app — no-op */ }
            is DeepLinkRoute.VerifyEmail           -> applyVerification(route.oobCode)
            is DeepLinkRoute.ResetPassword         -> pendingResetOobCode = route.oobCode
            // Includes a known host arriving WITHOUT its code: v1 checked
            // isNullOrBlank and did nothing rather than opening a reset screen
            // that cannot complete. `parseDeepLink` keeps that.
            is DeepLinkRoute.Unhandled             -> Unit
        }
    }

    /**
     * Apply a verification code, then tell the composition to look again.
     *
     * The apply is a network call and cannot be awaited before `setContent` —
     * blocking the launcher on Firebase is worse than any screen this can show.
     * So a cold start composes at `auth/verify-email` (correct: at that instant
     * the account is not verified yet) and [emailVerifiedSignal] moves it on
     * when the call returns. A warm start is already resumed, so the verify
     * screen's own ON_RESUME poll has been and gone — the signal is the only
     * edge it will see.
     *
     * **A failure deliberately shows nothing.** The user lands on the verify
     * screen, which is where an expired link should leave them: it explains the
     * situation and carries the "Resend email" button. v1 raised its own error
     * banner from the view model; the host has no channel into the shared
     * screen's state, and widening one for this would be a poor trade.
     */
    private fun applyVerification(oobCode: String) {
        lifecycleScope.launch {
            authProvider.applyEmailVerificationCode(oobCode)
                .onSuccess { emailVerifiedSignal += 1 }
        }
    }

    private companion object {
        const val KEY_START_LOGGED_IN = "start_logged_in"
    }
}
