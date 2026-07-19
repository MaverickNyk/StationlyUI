package com.stationly.app.ui.login

interface PlatformAuthProvider {
    suspend fun signInWithEmail(email: String, password: String): Result<String>
    suspend fun registerWithEmail(email: String, password: String): Result<String>
    suspend fun signInWithGoogle(idToken: String): Result<String>
    suspend fun signInWithGoogleInteractive(): Result<String>
    suspend fun signInWithAppleInteractive(): Result<String>
    suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Result<Unit>
    suspend fun updateDisplayName(name: String): Result<Unit>
    suspend fun signOut(): Result<Unit>
    fun isLoggedIn(): Boolean
    fun currentUserUid(): String?
    fun currentUserEmail(): String?
    fun currentUserDisplayName(): String?
    fun currentUserPhotoUrl(): String?
    fun consumePendingResetCode(): String?
    fun isEmailVerified(): Boolean
    fun isEmailProvider(): Boolean
    suspend fun sendEmailVerification(): Result<Unit>
    suspend fun reloadUser(): Result<Unit>
}
