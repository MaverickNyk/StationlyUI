package com.stationly.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
                onNavigateToSelection = { navController.navigate("selection") },
                onNavigateToProfile = { navController.navigate("profile") }
            )
        }

        composable("selection") {
            SelectionScreen(
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
