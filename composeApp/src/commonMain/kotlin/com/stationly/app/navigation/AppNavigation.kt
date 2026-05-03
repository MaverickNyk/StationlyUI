package com.stationly.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stationly.app.ui.login.LoginScreen
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.app.ui.profile.ProfileScreen
import com.stationly.app.ui.selection.SelectionScreen
import com.stationly.app.ui.summary.SummaryScreen

@Composable
fun AppNavigation(
    authProvider: PlatformAuthProvider,
    startLoggedIn: Boolean = false
) {
    val navController = rememberNavController()
    val startDestination = if (startLoggedIn) "summary" else "auth/login"

    NavHost(navController = navController, startDestination = startDestination) {

        composable("auth/login") {
            LoginScreen(
                screenType = "login",
                authProvider = authProvider,
                onNavigateToSummary = {
                    navController.navigate("summary") {
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
            val oobCode = backStackEntry.arguments?.getString("oobCode") ?: ""
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
                }
            )
        }
    }
}
