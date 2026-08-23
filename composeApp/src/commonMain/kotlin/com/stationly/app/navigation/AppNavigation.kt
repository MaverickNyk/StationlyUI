package com.stationly.app.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stationly.app.ui.dream.DreamHost
import com.stationly.app.ui.dream.DreamSettingsScreen
import com.stationly.app.ui.summary.BoardFocus
import com.stationly.app.ui.login.LoginScreen
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.app.ui.profile.ProfileScreen
import com.stationly.app.ui.selection.SelectionScreen
import com.stationly.app.ui.station.HomeSettingsScreen
import com.stationly.app.ui.station.StationSettingsScreen
import com.stationly.app.ui.summary.SummaryScreen

@Composable
fun AppNavigation(
    authProvider: PlatformAuthProvider,
    startLoggedIn: Boolean = false,
    deepLinkOobCode: String? = null
) {
    val navController = rememberNavController()
    val startDestination = when {
        !startLoggedIn -> "auth/login"
        authProvider.isEmailProvider() && !authProvider.isEmailVerified() -> "auth/verify-email"
        else -> "summary"
    }

    // Deep link: code passed directly from Swift on cold start
    LaunchedEffect(deepLinkOobCode) {
        if (deepLinkOobCode != null) {
            navController.navigate("auth/reset-confirm/$deepLinkOobCode")
        }
    }

    // Deep link: code stored in NSUserDefaults by Swift when app was already running
    val pendingResetCode = remember { authProvider.consumePendingResetCode() }
    LaunchedEffect(pendingResetCode) {
        if (pendingResetCode != null) {
            navController.navigate("auth/reset-confirm/$pendingResetCode")
        }
    }

    /*
     * Which station the settings screen and the line picker are FOR, held here
     * rather than encoded into their routes.
     *
     * A station name is user-facing text with spaces and ampersands in it
     * ("King's Cross St. Pancras", "Elephant & Castle"), so a route argument
     * would need percent encoding on the way in and out for no gain: every
     * screen involved lives in this one NavHost, so the value never has to
     * survive a process boundary. If it is lost to process death the settings
     * route pops itself and the picker simply opens at the mode step.
     */

    /** (grouping id, mode, name) of the station whose settings screen is open. */
    var settingsStation by remember {
        mutableStateOf<Triple<String, String, String>?>(null)
    }

    /**
     * (grouping id, mode, name) of the station the line picker was opened ON, or
     * null when the picker is adding a new station from scratch.
     */
    var pendingEditStation by remember {
        mutableStateOf<Triple<String, String, String>?>(null)
    }

    /**
     * The line the picker should open expanded on, when the user came from one
     * board's row rather than from "Add or edit lines".
     *
     * Cleared alongside [pendingEditStation] on every entry, so a line focused
     * once cannot leak into the next, unrelated visit.
     */
    var pendingFocusLine by remember { mutableStateOf<String?>(null) }

    /**
     * Navigate, but only from the screen that asked.
     *
     * Two taps in quick succession on the same row pushed the destination twice,
     * so backing out of station settings landed on station settings again. It is
     * not a race in the navigator — both taps are real, and by the time the second
     * one is handled the first push is under way but the caller is still composed
     * and still listening.
     *
     * Checking the CURRENT route makes the push idempotent for the duration of the
     * transition: the second tap comes from a screen that is no longer on top, and
     * is dropped. Preferred over disabling the row on tap, which would need the
     * screen to know when to re-enable it — and it gets that wrong on the way back.
     */
    fun navigateFrom(origin: String, route: String) {
        if (navController.currentBackStackEntry?.destination?.route != origin) return
        navController.navigate(route)
    }

    /*
     * iOS push/pop, including the part that makes it read as iOS.
     *
     * Both screens used to travel the full width in opposite directions, which is
     * Android's shared-axis transition wearing a horizontal coat. A UIKit push
     * moves the two layers by DIFFERENT amounts: the arriving screen comes the
     * whole way in from the right, and the one being covered slides only about a
     * third of the way out and dims. That difference is parallax, and it is what
     * makes the new screen look like it is on top of the old one rather than the
     * two of them being on a conveyor belt.
     *
     * The curve matters as much as the distance. A linear-ish `tween(300)` starts
     * and stops abruptly; this is UIKit's own push curve — fast off the mark,
     * long decelerating tail — so the screen arrives and settles instead of
     * halting.
     */
    val push = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    val slide = tween<androidx.compose.ui.unit.IntOffset>(360, easing = push)
    val fade = tween<Float>(360, easing = push)
    /** How far the covered screen travels. A third, as UIKit does it. */
    val parallax = 3
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { slideInHorizontally(slide) { it } },
        exitTransition = {
            slideOutHorizontally(slide) { -it / parallax } + fadeOut(fade, targetAlpha = 0.75f)
        },
        popEnterTransition = {
            slideInHorizontally(slide) { -it / parallax } + fadeIn(fade, initialAlpha = 0.75f)
        },
        popExitTransition = { slideOutHorizontally(slide) { it } },
    ) {

        composable("auth/login") {
            LoginScreen(
                screenType = "login",
                authProvider = authProvider,
                onNavigateToSummary = {
                    navController.navigate("summary") {
                        popUpTo("auth/login") { inclusive = true }
                    }
                },
                onNeedsEmailVerification = {
                    navController.navigate("auth/verify-email") {
                        popUpTo("auth/login") { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.navigate("auth/login") },
                onNavigateToRegister = { navController.navigate("auth/register") },
                onNavigateToForgotPassword = { navController.navigate("auth/forgot-password") }
            )
        }

        composable("auth/register") {
            LoginScreen(
                screenType = "register",
                authProvider = authProvider,
                onNavigateToSummary = {
                    navController.navigate("summary") {
                        popUpTo("auth/login") { inclusive = true }
                    }
                },
                onNeedsEmailVerification = {
                    navController.navigate("auth/verify-email") {
                        popUpTo("auth/login") { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.popBackStack() },
                onNavigateToRegister = {},
                onNavigateToForgotPassword = { navController.navigate("auth/forgot-password") }
            )
        }

        composable("auth/forgot-password") {
            LoginScreen(
                screenType = "forgot-password",
                authProvider = authProvider,
                onNavigateToSummary = {},
                onNavigateToLogin = { navController.popBackStack() },
                onNavigateToRegister = { navController.navigate("auth/register") },
                onNavigateToForgotPassword = {}
            )
        }

        composable("auth/reset-confirm/{oobCode}") { backStackEntry ->
            val oobCode = backStackEntry.savedStateHandle.get<String>("oobCode") ?: ""
            LoginScreen(
                screenType = "reset-confirm",
                authProvider = authProvider,
                resetOobCode = oobCode,
                onNavigateToSummary = {},
                onNavigateToLogin = {
                    navController.navigate("auth/login") {
                        popUpTo("auth/login") { inclusive = true }
                    }
                },
                onNavigateToRegister = {},
                onNavigateToForgotPassword = {}
            )
        }

        composable("auth/verify-email") {
            com.stationly.app.ui.login.VerifyEmailScreen(
                authProvider = authProvider,
                onVerified = {
                    navController.navigate("summary") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onUseDifferentEmail = {
                    navController.navigate("auth/login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable("summary") {
            SummaryScreen(
                onNavigateToSelection = {
                    pendingEditStation = null
                    pendingFocusLine = null
                    navController.navigate("selection")
                },
                onOpenStationSettings = { stationId, mode, stationName ->
                    settingsStation = Triple(stationId, mode, stationName)
                    navigateFrom("summary", "station/settings")
                },
                onNavigateToProfile = { navController.navigate("profile") },
                onOpenHomeSettings = { navController.navigate("home/settings") }
            )
        }

        composable("home/settings") {
            HomeSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenScreensaver = { navController.navigate("dream/settings") },
                // Pushed ON TOP of home settings rather than replacing it: the
                // station list is where the user was, and back should return
                // them to it — same rule as the settings → line picker → back
                // path below.
                onOpenStationSettings = { stationId, mode, stationName ->
                    settingsStation = Triple(stationId, mode, stationName)
                    navigateFrom("home/settings", "station/settings")
                },
            )
        }

        composable("station/settings") {
            // Nothing to show without a station — only reachable via the home
            // screen, which always sets it, but a restored back stack could land
            // here with it cleared.
            val target = settingsStation
            if (target == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                val (stationId, mode, stationName) = target
                StationSettingsScreen(
                    stationId = stationId,
                    stationName = stationName,
                    mode = mode,
                    // ── Come back to the station you were editing ──
                    //
                    // Not "come back to the home screen". A user on page C of a
                    // four-station carousel who opens C's settings and taps back
                    // is still thinking about C, and landing on A makes them
                    // find their way back to a place they never chose to leave.
                    //
                    // Raised here rather than left to the pager's own saved
                    // state, which is not enough on its own: the summary re-reads
                    // its repository on resume, and on any frame where that list
                    // is momentarily empty both the pager and the scroll position
                    // clamp to zero. The list layout is served by the same
                    // request, which expands the card and scrolls it into view.
                    onBack = {
                        BoardFocus.restore(stationId)
                        navController.popBackStack()
                    },
                    onEditLines = { line ->
                        pendingEditStation = target
                        pendingFocusLine = line
                        navController.navigate("selection")
                    },
                )
            }
        }

        composable("selection") {
            SelectionScreen(
                editStation = pendingEditStation,
                focusLine = pendingFocusLine,
                onNavigateToSummary = {
                    // Saving REBUILDS the summary destination (`popUpTo …
                    // inclusive`), which throws away its pager page and scroll
                    // position along with everything else. That is correct for a
                    // brand new station — it goes to the TOP of the home screen,
                    // so the first card already is the one just added — and wrong
                    // for an edit, which would drop the user on someone else's
                    // board after changing this one's lines.
                    pendingEditStation?.let { (stationId, _, _) ->
                        BoardFocus.restore(stationId)
                    }
                    navController.navigate("summary") {
                        popUpTo("summary") { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("profile") {
            ProfileScreen(
                authProvider = authProvider,
                onNavigateBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate("auth/login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        // Screensaver (dream) — iOS home for Android's Daydream port. The
        // settings route mirrors DreamSettingsActivity; the dream route hosts
        // the actual screensaver with keep-awake while composed.
        composable("dream/settings") {
            DreamSettingsScreen(
                onBack = { navController.popBackStack() },
                onStartDream = { navController.navigate("dream") }
            )
        }

        composable("dream") {
            DreamHost(onExit = { navController.popBackStack() })
        }
    }
}
