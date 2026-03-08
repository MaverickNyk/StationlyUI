package com.stationly.mobile.service

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

sealed class AuthResult {
    data class Success(val user: FirebaseUser) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class FirebaseAuthManager(private val context: Context) {
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val TAG = "FirebaseAuthManager"
    
    private val CLIENT_ID = "48865967804-daogo1om8e92inob2lr2481akgvm6a4a.apps.googleusercontent.com"

    // Set up Google Sign In
    fun getGoogleSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(CLIENT_ID)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    suspend fun signInWithGoogle(task: Task<GoogleSignInAccount>): AuthResult {
        return try {
            val account = task.getResult(ApiException::class.java)!!
            Log.d(TAG, "firebaseAuthWithGoogle:" + account.id)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
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
    
    suspend fun logout() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            try {
                // Call backend to update lastLogoutTime and loggedIn status
                com.stationly.core.service.SduiApiServiceFactory.create().logOut(currentUser.uid)
                Log.d(TAG, "Successfully notified backend of logout for ${currentUser.uid}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify backend of logout", e)
            }
        }

        // Unsubscribe from all current station topics before clearing
        try {
            val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
            selections.forEach { selection ->
                val topics = listOf(
                    "Station_${selection.station}",
                    "LineStatus_${selection.mode}_${selection.line}"
                )
                Log.d(TAG, "Unsubscribing from topics on logout: $topics")
                com.stationly.core.platform.Platform.notificationManager.unsubscribeFromTopics(topics)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unsubscribe during logout", e)
        }

        auth.signOut()
        getGoogleSignInClient().signOut()
        
        // Clear local storage on logout
        com.stationly.core.platform.Platform.sqlStorage.clearAllData()
        com.stationly.core.platform.Platform.storageManager.clearCache()
        
        // Force the widget to update and reflect the cleared state
        com.stationly.mobile.widget.DepartureWidgetProvider.updateFromStorage(context)
    }
    
    val currentUser: FirebaseUser?
        get() = auth.currentUser
}
