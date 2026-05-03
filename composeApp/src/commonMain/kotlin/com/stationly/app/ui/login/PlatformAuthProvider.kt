package com.stationly.app.ui.login

interface PlatformAuthProvider {
    suspend fun signInWithEmail(email: String, password: String): Result<String>
    suspend fun registerWithEmail(email: String, password: String): Result<String>
    suspend fun signInWithGoogle(idToken: String): Result<String>
    suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Result<Unit>
    fun isLoggedIn(): Boolean
    fun currentUserEmail(): String?
    fun currentUserDisplayName(): String?
    fun currentUserPhotoUrl(): String?
}
