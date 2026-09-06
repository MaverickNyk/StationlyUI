package com.stationly.app.ui.login

interface PlatformAuthProvider {
    suspend fun signInWithEmail(email: String, password: String): Result<String>
    suspend fun registerWithEmail(email: String, password: String): Result<String>
    suspend fun signInWithGoogle(idToken: String): Result<String>
    suspend fun signInWithGoogleInteractive(): Result<String>
    suspend fun signInWithAppleInteractive(): Result<String>

    /**
     * Whether this platform can actually complete [signInWithAppleInteractive],
     * asked BEFORE the landing screen offers it.
     *
     * The landing screen used to render "Continue with Apple" unconditionally,
     * so on Android it was a button whose only outcome was an apology. Sign in
     * with Apple needs an Apple-issued `ASAuthorization` flow that exists only
     * on Apple platforms; there is no Android implementation to write.
     *
     * Deliberately abstract rather than `= false` with a default: a default
     * would let a third platform inherit "no Apple" silently, which is the same
     * shape of mistake in the other direction. A new platform has to answer.
     */
    val supportsAppleSignIn: Boolean
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
