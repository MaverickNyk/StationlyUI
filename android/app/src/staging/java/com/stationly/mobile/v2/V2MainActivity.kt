package com.stationly.mobile.v2

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.stationly.app.AndroidPlatformAuthProvider
import com.stationly.app.App

/**
 * The v2 host: the Android half of a contract that is one function signature.
 *
 * iOS's half is `MainViewController.kt` — twenty lines that build a
 * `PlatformAuthProvider` and hand it to `App()`. This is the same twenty lines
 * with an Activity around them. Everything the user sees below `setContent`
 * lives in `:composeApp` and is the identical code iOS runs.
 *
 * ## Why this file is in `src/staging`
 * It is the SECOND door into a live app, and it is deliberately temporary.
 * `MainActivity` stays the launcher, the deep-link target and the shipped
 * experience until AV2-3.5; this one exists so the shared UI can be exercised
 * on a real device without betting the prod build on it. The `:composeApp`
 * dependency is scoped to the staging flavour for the same reason (see
 * `build.gradle.kts` — it moves the whole Compose runtime up a minor version),
 * so this file physically cannot compile into a prod build.
 *
 * At AV2-3.5 this moves to `src/main` and becomes the launcher. See the handoff
 * note in `docs/android-v2/epics/EPIC-03-host-cutover.md` about keeping the
 * `com.stationly.mobile.MainActivity` component name alive as an alias when it
 * does: home-screen pins reference the component, not the package.
 */
class V2MainActivity : ComponentActivity() {

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
     * `rememberSaveable` around the same decision (see `MainActivity.kt`). The
     * shared `AppNavigation` takes the answer as a parameter instead, so on
     * Android the saving has to happen HERE, at the host boundary.
     */
    private var startLoggedIn: Boolean = false

    /**
     * The `oobCode` from an inbound `stationly://reset` link.
     *
     * Nothing routes here yet — `MainActivity` still owns every intent-filter,
     * and AV2-3.4 is what gives this Activity per-flavour ones. It is wired now
     * because it is part of the host signature iOS already implements, and
     * because it makes the staging door drivable by hand:
     *
     *     adb shell am start -n com.stationly.mobile/.v2.V2MainActivity \
     *         -a android.intent.action.VIEW -d "stationly://reset?oobCode=…"
     *
     * State rather than a plain value so a link arriving at an already-running
     * instance reaches the composition through [onNewIntent].
     */
    private var pendingResetOobCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        // `Platform.initialize` is NOT called here on purpose. It runs in
        // StationlyApplication.onCreate, which Android guarantees to complete
        // before any component of the process starts — including this one. The
        // staging manifest deliberately does not override android:name, so a
        // staging build has the same Application class as prod. (Asserted
        // statically by V2HostManifestTest, since there is no runtime flag on
        // Platform to check.)
        // The Activity, not the application context: every interactive sign-in
        // this provider grows in AV2-3.4 needs one to launch from. Held only by
        // this Activity and by the composition, which die together, so there is
        // nothing here to leak.
        val authProvider = AndroidPlatformAuthProvider(this)

        startLoggedIn = if (savedInstanceState?.containsKey(KEY_START_LOGGED_IN) == true) {
            savedInstanceState.getBoolean(KEY_START_LOGGED_IN)
        } else {
            authProvider.isLoggedIn()
        }

        pendingResetOobCode = resetOobCodeFrom(intent)

        setContent {
            App(
                authProvider    = authProvider,
                startLoggedIn   = startLoggedIn,
                deepLinkOobCode = pendingResetOobCode,
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_START_LOGGED_IN, startLoggedIn)
    }

    /**
     * Every launch of this Activity arrives here rather than building a second
     * instance, because the manifest declares `launchMode="singleTask"`.
     *
     * The reasoning is inherited from `MainActivity` and is load-bearing there:
     * under the default "standard" mode a relaunch spawned a NEW task with a
     * fresh Activity, the tasks piled up (three observed at once), and returning
     * to a freshly-restored instance found the NavHost back stack empty — a
     * blank screen. It applies here identically, and this Activity additionally
     * carries its own `taskAffinity` so the two doors cannot shuffle each
     * other's back stacks while both exist.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resetOobCodeFrom(intent)?.let { pendingResetOobCode = it }
    }

    /**
     * Matched on the HOST alone, deliberately. v1 matches scheme AND host, but
     * v1 has one scheme; AV2-3.4 gives this door two — `stationly` on prod and
     * `stationly-staging` on staging, because iOS shipped a hardcoded
     * `"stationly"` that silently dropped every staging link. Only the manifest
     * decides which schemes reach this Activity, so re-checking one here would
     * be a second, quieter place to get the same thing wrong.
     */
    private fun resetOobCodeFrom(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.host != "reset") return null
        return uri.getQueryParameter("oobCode")?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val KEY_START_LOGGED_IN = "v2_start_logged_in"
    }
}
