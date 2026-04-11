package com.stationly.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Install splash screen (same as MindTheTimeAndroid)
        installSplashScreen()
        
        // Enable edge-to-edge
        enableEdgeToEdge()
        
        setContent {
            StationlyTheme {
                AppNavigation()
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
fun AppNavigation(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    // Simplified initial auth check
    val context = androidx.compose.ui.platform.LocalContext.current
    val authManager = remember { com.stationly.mobile.service.FirebaseAuthManager(context) }
    val isUserLoggedIn = authManager.currentUser != null
    
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
                onNavigateToForgotPassword = { navController.navigate("auth/forgot-password") }
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