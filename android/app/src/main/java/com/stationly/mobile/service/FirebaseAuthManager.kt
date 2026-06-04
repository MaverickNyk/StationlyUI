package com.stationly.mobile.service

import android.app.Activity
import android.content.Context
import android.util.Log
import com.stationly.mobile.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed class AuthResult {
    data class Success(val user: FirebaseUser) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class FirebaseAuthManager(private val context: Context) {
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val TAG = "FirebaseAuthManager"

    // Fire-and-forget scope for non-blocking side effects (backend logout notify, etc.).
    // SupervisorJob so one failure can't cancel siblings.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Set up Google Sign In
    fun getGoogleSignInClient(): GoogleSignInClient {
        // Web client ID is read from the active flavor's google-services.json
        // (R.string.default_web_client_id), so prod builds use the prod OAuth
        // client and staging builds use the staging one. Never hardcode this:
        // a hardcoded staging ID makes prod sign-in fail with "id_token audience
        // not authorized for this project".
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    suspend fun signInWithGoogle(task: Task<GoogleSignInAccount>): AuthResult {
        return try {
            val account = task.getResult(ApiException::class.java)
                ?: return AuthResult.Error("Google sign-in returned no account. Please try again.")
            val idToken = account.idToken
                ?: return AuthResult.Error("Google sign-in didn't return an ID token. Please try again.")
            Log.d(TAG, "firebaseAuthWithGoogle uid=${com.stationly.mobile.service.AuthLog.maskPii(account.id)}")
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val user = authResult.user
            if (user != null) {
                AuthResult.Success(user)
            } else {
                AuthResult.Error("Google authentication failed. No user found.")
            }
        } catch (e: Exception) {
            val errorMessage = if (e is ApiException) {
                "Google Sign-In failed (Code: ${e.statusCode})"
            } else {
                e.localizedMessage ?: "Google sign in failed"
            }
            Log.e(TAG, "Google sign in failed: $errorMessage", e)
            AuthResult.Error(errorMessage)
        }
    }

    suspend fun signInWithEmail(email: String, pass: String): AuthResult {
        return try {
            val authResult = auth.signInWithEmailAndPassword(email, pass).await()
            val user = authResult.user
            if (user != null) {
                AuthResult.Success(user)
            } else {
                AuthResult.Error("Sign in failed. No user found.")
            }
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
            Log.w(TAG, "Email login failed: User not found", e)
            AuthResult.Error("No account found with this email. Please sign up first!")
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
            Log.w(TAG, "Email login failed: Invalid credentials", e)
            AuthResult.Error("Incorrect password. Please try again.")
        } catch (e: Exception) {
            Log.w(TAG, "Email login failed", e)
            AuthResult.Error(e.localizedMessage ?: "Invalid email or password.")
        }
    }

    suspend fun createEmailAccount(email: String, pass: String): AuthResult {
        return try {
            val authResult = auth.createUserWithEmailAndPassword(email, pass).await()
            val user = authResult.user
            if (user != null) {
                AuthResult.Success(user)
            } else {
                AuthResult.Error("Registration failed. No user created.")
            }
        } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
            Log.w(TAG, "Email account creation failed: User collision", e)
            AuthResult.Error("An account already exists with this email. Try signing in!")
        } catch (e: com.google.firebase.auth.FirebaseAuthWeakPasswordException) {
            Log.w(TAG, "Email account creation failed: Weak password", e)
            AuthResult.Error("Your password is too weak. Please use at least 6 characters.")
        } catch (e: Exception) {
            Log.w(TAG, "Email account creation failed", e)
            AuthResult.Error(e.localizedMessage ?: "Registration failed. Check your data.")
        }
    }
    
    /**
     * Sign the user out. Local sign-out + cleanup is the durable path — backend notify
     * is fire-and-forget so a broken `/user/logout` endpoint can't strand the user
     * "halfway logged in". Runs cleanup under [NonCancellable] so even if the calling
     * coroutine is cancelled mid-logout (rare, but happens during navigation), the
     * local state is consistent.
     */
    suspend fun logout() = withContext(NonCancellable) {
        val uid = auth.currentUser?.uid

        // 1. Notify backend BEFORE the local sign-out — and await it (capped).
        //    /user/logout is gated by validateUserToken, and the request's
        //    bearer token is read from FirebaseAuth.currentUser, which
        //    signOut() nulls. Firing this fire-and-forget (racing signOut, as
        //    it did before) meant the request usually went out tokenless → 401,
        //    so the server-side station-count decrement AND loggedIn=false
        //    never ran. We still cap it with a timeout so a slow/broken backend
        //    can't strand the user "half logged in".
        if (uid != null) {
            val deviceId = DeviceIdProvider.get(context)
            // Run BOTH auth-gated backend calls CONCURRENTLY before signOut (each
            // needs the still-valid token, which signOut() nulls). They're
            // independent, so racing them keeps worst-case sign-out latency at
            // ~4s instead of 4s+3s sequential. Both are best-effort / capped so a
            // slow or unreachable backend can't strand the user "half logged in".
            kotlinx.coroutines.coroutineScope {
                // a) /user/logout — ends this device's session server-side so the
                //    station-count decrement (last device) + loggedIn flip run.
                launch {
                    try {
                        val ok = kotlinx.coroutines.withTimeoutOrNull(4000) {
                            com.stationly.core.service.SduiApiServiceFactory.create().logOut(uid, deviceId)
                        }
                        if (ok == null) AuthLog.logoutBackendFailed("timeout")
                        else Log.d(TAG, "Backend logout notify ok uid=${AuthLog.maskPii(uid)}")
                    } catch (e: Exception) {
                        AuthLog.logoutBackendFailed(e.message ?: e::class.simpleName.orEmpty())
                    }
                }
                // b) Unregister this device's FCM token so a push for the (now
                //    signed-out) user — incl. a `user_sync` deleted/force-logout —
                //    can't reach this device after another account signs in here.
                launch {
                    try {
                        val fcmToken = FirebaseMessaging.getInstance().token.await()
                        if (!fcmToken.isNullOrBlank()) {
                            kotlinx.coroutines.withTimeoutOrNull(3000) {
                                com.stationly.core.service.SduiApiServiceFactory.create().unregisterFcmToken(fcmToken)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "FCM token unregister during logout failed (continuing): ${e.message}")
                    }
                }
            }
        }

        // 2. Unsubscribe FCM topics for any active selections — must happen BEFORE we
        //    wipe SQL since the topic names are derived from selections.
        try {
            val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
            selections.forEach { selection ->
                val topics = listOf(
                    "Station_${selection.station}",
                    "LineStatus_${selection.mode}_${selection.line}"
                )
                com.stationly.core.platform.Platform.notificationManager.unsubscribeFromTopics(topics)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Topic unsubscribe during logout failed (continuing)", e)
        }

        // 3. Sign out Firebase + Google locally. From this point on the user is
        //    definitively logged out regardless of what happened above.
        auth.signOut()
        getGoogleSignInClient().signOut()

        // 4. Full wipe of local data — every SharedPrefs key including firebase_user_*,
        //    signin_provider, member_since, sdui_layout_*, fcm_topics, etc. Previously
        //    used clearCache() which left identity fields behind and surfaced as a
        //    "Stationly User" placeholder lingering after sign-out.
        com.stationly.core.platform.Platform.sqlStorage.clearAllData()
        com.stationly.core.platform.Platform.storageManager.clearAll()

        // 5. Push the empty state to the widget.
        com.stationly.mobile.widget.DepartureWidgetProvider.updateFromStorage(context)

        if (uid != null) AuthLog.logoutSuccess(uid)
    }
    
    val currentUser: FirebaseUser?
        get() = auth.currentUser
}
