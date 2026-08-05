package com.stationly.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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

    // iOS-style push/pop: new screen slides in from the right, the previous one
    // slides out to the left; reversed on back. Gives the app a native feel
    // without touching screen content (the board, etc. stay intact).
    val slide = tween<androidx.compose.ui.unit.IntOffset>(300)
    val fade = tween<Float>(300)
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { slideIntoContainer(SlideDirection.Left, slide) + fadeIn(fade) },
        exitTransition = { slideOutOfContainer(SlideDirection.Left, slide) + fadeOut(fade) },
        popEnterTransition = { slideIntoContainer(SlideDirection.Right, slide) + fadeIn(fade) },
        popExitTransition = { slideOutOfContainer(SlideDirection.Right, slide) + fadeOut(fade) },
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
                    navController.navigate("selection")
                },
                onOpenStationSettings = { stationId, mode, stationName ->
                    settingsStation = Triple(stationId, mode, stationName)
                    navController.navigate("station/settings")
                },
                onNavigateToProfile = { navController.navigate("profile") },
                onOpenScreensaver = { navController.navigate("dream/settings") },
                onOpenHomeSettings = { navController.navigate("home/settings") }
            )
        }

        composable("home/settings") {
            HomeSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenScreensaver = { navController.navigate("dream/settings") },
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
                    onBack = { navController.popBackStack() },
                    onEditLines = {
                        pendingEditStation = target
                        navController.navigate("selection")
                    },
                )
            }
        }

        composable("selection") {
            SelectionScreen(
                editStation = pendingEditStation,
                onNavigateToSummary = {
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
                onOpenScreensaver = { navController.navigate("dream/settings") }
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
