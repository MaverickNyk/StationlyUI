package com.stationly.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stationly.mobile.ui.selection.SelectionScreen
import com.stationly.mobile.ui.summary.SummaryScreen
import com.stationly.mobile.ui.theme.StationlyTheme

/**
 * MainActivity - Android Entry Point
 * 
 * This is the main activity for the Android app.
 * It mirrors the MindTheTimeAndroid MainActivity but uses KMP core.
 * 
 * Key features:
 * - Compose Navigation for screen flow
 * - ViewModel integration with KMP use cases
 * - Splash screen integration
 * - Edge-to-edge UI
 */
class MainActivity : ComponentActivity() {
    private val passwordResetComplete = mutableStateOf(false)
    private val pendingResetOobCode   = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        handleDeepLink(intent)
        setContent {
            StationlyTheme {
                AppNavigation(
                    passwordResetComplete = passwordResetComplete,
                    pendingResetOobCode   = pendingResetOobCode
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        when {
            uri.scheme == "stationly" && uri.host == "auth"  -> passwordResetComplete.value = true
            uri.scheme == "stationly" && uri.host == "home"  -> { /* just opens the app — no-op */ }
            uri.scheme == "stationly" && uri.host == "reset" -> {
                val code = uri.getQueryParameter("oobCode")
                if (!code.isNullOrBlank()) pendingResetOobCode.value = code
            }
        }
    }
}

/**
 * App Navigation Composable
 * 
 * Handles navigation between screens:
 * 1. SummaryScreen (main dashboard)
 * 2. SelectionScreen (station selection flow)
 */
@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    passwordResetComplete: MutableState<Boolean> = mutableStateOf(false),
    pendingResetOobCode: MutableState<String?> = mutableStateOf(null)
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val authManager = remember { com.stationly.mobile.service.FirebaseAuthManager(context) }
    val isUserLoggedIn = authManager.currentUser != null
    
    // When a stationly://reset deep link arrives, navigate to the confirm screen
    LaunchedEffect(pendingResetOobCode.value) {
        val code = pendingResetOobCode.value ?: return@LaunchedEffect
        navController.navigate("auth/reset-confirm/$code") {
            popUpTo("auth/login") { inclusive = false }
        }
        pendingResetOobCode.value = null
    }

    NavHost(
        navController = navController,
        startDestination = if (isUserLoggedIn) "summary" else "auth/login",
        modifier = modifier
    ) {
        // --- Authentication Flow ---
        
        // Sign In Screen
        composable("auth/login") {
            com.stationly.mobile.ui.login.LoginScreen(
                screenType = "login",
                onNavigateToSummary = {
                    navController.navigate("summary") {
                        popUpTo("auth/login") { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate("auth/register") },
                onNavigateToForgotPassword = { navController.navigate("auth/forgot-password") },
                showPasswordResetSuccess = passwordResetComplete.value,
                onPasswordResetBannerShown = { passwordResetComplete.value = false }
            )
        }
        
        // Sign Up Screen
        composable("auth/register") {
            com.stationly.mobile.ui.login.LoginScreen(
                screenType = "register",
                onNavigateToSummary = {
                    navController.navigate("summary") {
                        popUpTo("auth/login") { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.popBackStack() }, // Standard back
                onNavigateToRegister = {}, // Already here
                onNavigateToForgotPassword = { navController.navigate("auth/forgot-password") }
            )
        }
        
        // Reset Password Confirm (from deep link stationly://reset?oobCode=XXX)
        composable("auth/reset-confirm/{oobCode}") { backStackEntry ->
            val oobCode = backStackEntry.arguments?.getString("oobCode") ?: ""
            com.stationly.mobile.ui.login.LoginScreen(
                screenType          = "reset-confirm",
                resetOobCode        = oobCode,
                onNavigateToSummary = {},
                onNavigateToLogin   = {
                    navController.navigate("auth/login") {
                        popUpTo("auth/reset-confirm/$oobCode") { inclusive = true }
                    }
                }
            )
        }

        // Forgot Password Screen
        composable("auth/forgot-password") {
            com.stationly.mobile.ui.login.LoginScreen(
                screenType = "forgot-password",
                onNavigateToSummary = {}, // Not applicable directly
                onNavigateToLogin = { navController.popBackStack() }, // Standard back
                onNavigateToRegister = { navController.navigate("auth/register") },
                onNavigateToForgotPassword = {} // Already here
            )
        }

        // --- Main App Logic ---
        
        // Profile Screen
        composable("profile") {
            com.stationly.mobile.ui.profile.ProfileScreen(
                onNavigateBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate("auth/login") {
                        popUpTo("summary") { inclusive = true }
                    }
                },
                authManager = authManager
            )
        }

        // Summary Screen - Main Dashboard
        composable("summary") {
            SummaryScreen(
                onNavigateToSelection = {
                    navController.navigate("selection") {
                        launchSingleTop = true
                    }
                },
                onNavigateToProfile = {
                    navController.navigate("profile") {
                        launchSingleTop = true
                    }
                }
            )
        }
        
        // Selection Screen - Station Selection Flow
        composable("selection") {
            SelectionScreen(
                onNavigateToSummary = {
                    navController.navigate("summary") {
                        popUpTo("selection") { inclusive = false }
                    }
                }
            )
        }
    }
}