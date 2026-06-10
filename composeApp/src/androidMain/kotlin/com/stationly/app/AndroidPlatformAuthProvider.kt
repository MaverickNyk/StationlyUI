package com.stationly.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.stationly.app.ui.login.PlatformAuthProvider
import kotlinx.coroutines.tasks.await

class AndroidPlatformAuthProvider(private val context: Context) : PlatformAuthProvider {

    private val auth = FirebaseAuth.getInstance()

    override fun isLoggedIn(): Boolean = auth.currentUser != null

    override fun currentUserUid(): String? = auth.currentUser?.uid

    override fun currentUserEmail(): String? = auth.currentUser?.email

    override fun currentUserDisplayName(): String? = auth.currentUser?.displayName

    override fun currentUserPhotoUrl(): String? = auth.currentUser?.photoUrl?.toString()

    override suspend fun signInWithEmail(email: String, password: String): Result<String> = try {
        val result = auth.signInWithEmailAndPassword(email, password).await()
        val token = result.user?.getIdToken(false)?.await()?.token ?: ""
        Result.success(token)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun registerWithEmail(email: String, password: String): Result<String> = try {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        val token = result.user?.getIdToken(false)?.await()?.token ?: ""
        Result.success(token)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun signInWithGoogle(idToken: String): Result<String> = try {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = auth.signInWithCredential(credential).await()
        val token = result.user?.getIdToken(false)?.await()?.token ?: ""
        Result.success(token)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Result<Unit> = try {
        auth.confirmPasswordReset(oobCode, newPassword).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun updateDisplayName(name: String): Result<Unit> = try {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in.")
        user.updateProfile(
            com.google.firebase.auth.UserProfileChangeRequest.Builder()
                .setDisplayName(name)
                .build()
        ).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun signInWithGoogleInteractive(): Result<String> =
        Result.failure(Exception("Use the Google Sign-In button to continue."))

    override suspend fun signOut(): Result<Unit> = try {
        auth.signOut()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun consumePendingResetCode(): String? = null
}
