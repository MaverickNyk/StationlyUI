package com.stationly.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stationly.mobile.ui.common.StagingBanner
import com.stationly.mobile.ui.common.rememberFirebaseAuthState
import com.stationly.mobile.ui.selection.SelectionScreen
import com.stationly.mobile.ui.summary.SummaryScreen
import com.stationly.mobile.ui.theme.StationlyThemeHost

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
    // oobCode for an inbound stationly://verified deep link — set by handleDeepLink,
    // consumed by AppNavigation which applies the code via Firebase client SDK
    // (LoginViewModel.applyVerificationCode) and routes the user past the verify gate.
    private val pendingVerifyOobCode  = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        handleDeepLink(intent)
        setContent {
            StationlyThemeHost {
                Box(Modifier.fillMaxSize()) {
                    AppNavigation(
                        passwordResetComplete = passwordResetComplete,
                        pendingResetOobCode   = pendingResetOobCode,
                        pendingVerifyOobCode  = pendingVerifyOobCode
                    )
                    StagingBanner(Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        // Foreground re-sync fallback (#5): if the device missed a `user_sync`
        // FCM push (e.g. was offline), reconcile local state with the cloud
        // profile when the app comes back to the foreground. Debounced + a
        // no-op when signed out, inside the coordinator.
        com.stationly.mobile.service.UserSyncCoordinator.reconcile(this)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        when {
            uri.scheme == "stationly" && uri.host == "auth"  -> passwordResetComplete.value = true
            uri.scheme == "stationly" && uri.host == "home"  -> { /* just opens the app — no-op */ }
            uri.scheme == "stationly" && uri.host == "verified" -> {
                val code = uri.getQueryParameter("oobCode")
                if (!code.isNullOrBlank()) pendingVerifyOobCode.value = code
            }
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
    pendingResetOobCode: MutableState<String?> = mutableStateOf(null),
    pendingVerifyOobCode: MutableState<String?> = mutableStateOf(null)
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val authManager = remember { com.stationly.mobile.service.FirebaseAuthManager(context) }

    val firebaseUser by rememberFirebaseAuthState()
    // Initial routing decision — re-evaluated only on cold start (rememberSaveable isn't
    // needed since the NavHost preserves its own state across recompositions).
    //
    // Three buckets:
    //   - No user        → auth/login
    //   - User exists but is email-provider AND not yet verified → auth/verify-email
    //     (closes the cold-start verify-bypass: previously a user who signed up via
    //      email then killed the app could re-open straight into summary)
    //   - Anyone else (Google, Apple, verified email) → summary
    // rememberSaveable (NOT remember): the NavHost saves/restores its back stack
    // across process death, and that restore requires the SAME startDestination it
    // was created with. Plain remember re-evaluates authManager.currentUser on
    // recreation — if Firebase hasn't rehydrated the user yet it would flip to
    // "auth/login", mismatching the saved "summary"-rooted stack and leaving the
    // NavHost unable to restore → blank. Persisting the original value keeps the
    // start destination stable so the back stack always restores cleanly.
    val startDestination = rememberSaveable {
        val u = authManager.currentUser
        when {
            u == null -> "auth/login"
            isUnverifiedEmailUser(u) -> "auth/verify-email"
            else -> "summary"
        }
    }

    // ALSO react to subsequent auth-state changes — e.g. token rotated mid-session
    // and Firebase decided the cached profile is stale. If a user becomes unverified
    // (rare but possible after admin action), bounce them to verify-email.
    //
    // Critically, this only fires when the user is on a NON-auth route (e.g. summary).
    // During signup the LoginViewModel owns navigation via onNeedsEmailVerification
    // — if we navigate here first, we clear the register ViewModel mid-coroutine
    // and cancel the in-flight branded-email backend call, which silently falls
    // back to Firebase's default email.
    LaunchedEffect(firebaseUser) {
        val u = firebaseUser
        if (u != null && isUnverifiedEmailUser(u)) {
            val currentRoute = navController.currentDestination?.route
            val onAuthScreen = currentRoute?.startsWith("auth/") == true
            if (!onAuthScreen) {
                navController.navigate("auth/verify-email") {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    // Reactive eviction: the moment Firebase reports no user (sign-out, token revoked,
    // account deleted, 401 from backend), jump back to login and clear the back stack
    // so the previous user's data can't be revealed by pressing Back. Also runs a
    // backup cleanup pass in case the sign-out bypassed FirebaseAuthManager.logout()
    // (e.g. came from the 401 interceptor). All clears are idempotent.
    LaunchedEffect(firebaseUser) {
        if (firebaseUser == null) {
            val sqlStorage = com.stationly.core.platform.Platform.sqlStorage
            if (sqlStorage.getAllSelections().isNotEmpty()) {
                com.stationly.core.platform.Platform.notificationManager.clearAllTopics()
                sqlStorage.clearAllData()
                com.stationly.core.platform.Platform.storageManager.clearAll()
                com.stationly.core.platform.Platform.widgetManager.clearWidgetData()
            }
            val currentRoute = navController.currentDestination?.route
            if (currentRoute == null || !currentRoute.startsWith("auth/")) {
                navController.navigate("auth/login") {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }

            // If this sign-out was triggered by a remote account deletion
            // (another device deleted the account → user_sync push), show a
            // brief notice so the user understands why they're back at login.
            if (com.stationly.mobile.service.UserSyncCoordinator.consumeAccountRemovedFlag(context)) {
                android.widget.Toast.makeText(
                    context,
                    "Your account was removed.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // When a stationly://reset deep link arrives, navigate to the confirm screen
    LaunchedEffect(pendingResetOobCode.value) {
        val code = pendingResetOobCode.value ?: return@LaunchedEffect
        navController.navigate("auth/reset-confirm/$code") {
            popUpTo("auth/login") { inclusive = false }
        }
        pendingResetOobCode.value = null
    }

    // When a stationly://verified deep link arrives (user tapped the verify link in
    // their email and the OS routed it to us instead of the browser), apply the
    // code via Firebase client SDK and bounce them to the summary. The user might
    // be on the verify-email screen or on a cold-start LoginScreen — either way,
    // applyVerificationCode handles reload + token refresh + sync + nav.
    val verifyVm: com.stationly.mobile.ui.login.LoginViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    LaunchedEffect(pendingVerifyOobCode.value) {
        val code = pendingVerifyOobCode.value ?: return@LaunchedEffect
        pendingVerifyOobCode.value = null
        verifyVm.applyVerificationCode(code) {
            navController.navigate("summary") {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Provide an in-app WebView opener for every screen below — SDUI link
    // rows, profile "Visit Website", announcement CTAs, etc. all route
    // through `LocalOpenUrl` and end up at the in-app WebViewScreen
    // instead of bouncing the user out to Chrome. Falls back to the
    // external opener if the URL is non-HTTP (mailto:, tel:, market:).
    androidx.compose.runtime.CompositionLocalProvider(
        com.stationly.mobile.ui.common.LocalOpenUrl provides { url, title ->
            val isWeb = url.startsWith("http://", ignoreCase = true) ||
                        url.startsWith("https://", ignoreCase = true)
            if (isWeb) {
                val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
                val route = if (title.isNullOrBlank()) {
                    "webview/$encodedUrl"
                } else {
                    val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
                    "webview/$encodedUrl?title=$encodedTitle"
                }
                navController.navigate(route) { launchSingleTop = true }
            } else {
                com.stationly.mobile.ui.common.defaultExternalOpener(context).invoke(url, title)
            }
        }
    ) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
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
                onNeedsEmailVerification = {
                    navController.navigate("auth/verify-email") {
                        launchSingleTop = true
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
                onNeedsEmailVerification = {
                    navController.navigate("auth/verify-email") {
                        // Replace register on the back stack so back goes to landing.
                        popUpTo("auth/login") { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onNavigateToLogin = { navController.popBackStack() }, // Standard back
                onNavigateToRegister = {}, // Already here
                onNavigateToForgotPassword = { navController.navigate("auth/forgot-password") }
            )
        }

        // Verify Email — hard gate after email signup or for an unverified email login.
        composable("auth/verify-email") {
            com.stationly.mobile.ui.login.VerifyEmailScreen(
                onVerified = {
                    navController.navigate("summary") {
                        popUpTo("auth/login") { inclusive = true }
                    }
                },
                onUseDifferentEmail = {
                    navController.navigate("auth/login") {
                        popUpTo("auth/login") { inclusive = true }
                    }
                }
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
        
        // In-app WebView — first-party links open inside Stationly instead of
        // bouncing the user out to Chrome. URL is path-encoded so query
        // strings / fragments survive the round-trip. Title is an optional
        // query-style nav arg; if omitted the screen falls back to the
        // WebView's own resolved <title>.
        composable(
            route = "webview/{url}?title={title}",
            arguments = listOf(
                androidx.navigation.navArgument("url") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("title") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val rawUrl   = entry.arguments?.getString("url").orEmpty()
            val rawTitle = entry.arguments?.getString("title")
            val url   = runCatching { java.net.URLDecoder.decode(rawUrl, "UTF-8") }
                .getOrDefault(rawUrl)
            val title = rawTitle
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                ?.takeIf { it.isNotBlank() }
            com.stationly.mobile.ui.common.WebViewScreen(
                url = url,
                title = title,
                onClose = { navController.popBackStack() },
            )
        }

        // Selection Screen - Station Selection Flow.
        // popUpTo("selection") { inclusive = true } so that after a save, the
        // selection screen is removed from the back stack. Previously it was
        // inclusive=false which left selection lingering — pressing back from
        // summary later (post profile-visit, etc.) would unexpectedly return
        // the user to the selection flow they thought they'd finished.
        // launchSingleTop ensures we don't stack a duplicate summary on top
        // when there's already one underneath.
        composable("selection") {
            SelectionScreen(
                onNavigateToSummary = {
                    navController.navigate("summary") {
                        popUpTo("selection") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
    } // close CompositionLocalProvider for LocalOpenUrl
}

/**
 * True when the user signed up with email/password AND hasn't verified yet. Google
 * and Apple emails are pre-verified by the provider so they always return false
 * even with isEmailVerified == false at the very first instant. Used to gate the
 * summary screen at cold start so a half-completed signup can't sneak past the
 * verify-email screen by killing and reopening the app.
 */
private fun isUnverifiedEmailUser(user: com.google.firebase.auth.FirebaseUser): Boolean {
    if (user.isEmailVerified) return false
    // providerData includes "firebase" pseudo-provider plus the real ones — we only
    // care whether "password" is among them, which means email/password is at least
    // one of the user's sign-in methods.
    return user.providerData.any { it.providerId == "password" }
}