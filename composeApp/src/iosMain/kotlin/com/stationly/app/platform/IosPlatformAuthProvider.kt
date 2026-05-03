package com.stationly.app.platform

import com.stationly.app.ui.login.PlatformAuthProvider
import kotlinx.coroutines.delay
import platform.Foundation.NSUserDefaults

/**
 * iOS implementation of PlatformAuthProvider.
 *
 * Auth is performed by native Swift (AuthBridge.swift) which stores results in
 * NSUserDefaults.standardUserDefaults. This class reads those values and writes
 * pending commands that Swift picks up on the next main-thread run-loop cycle.
 *
 * Command protocol:
 *   KMP writes:  auth_pending_command = "<verb>|<arg1>|<arg2>"
 *   Swift fires Firebase, then either:
 *     - removes auth_pending_command + writes firebase_auth_token  (success)
 *     - removes auth_pending_command + writes auth_pending_error   (failure)
 */
class IosPlatformAuthProvider : PlatformAuthProvider {

    private val defaults = NSUserDefaults.standardUserDefaults

    override fun isLoggedIn(): Boolean =
        defaults.stringForKey("firebase_auth_token")?.isNotBlank() == true

    override fun currentUserEmail(): String? =
        defaults.stringForKey("firebase_user_email")

    override fun currentUserDisplayName(): String? =
        defaults.stringForKey("firebase_user_display_name")

    override fun currentUserPhotoUrl(): String? =
        defaults.stringForKey("firebase_user_photo_url")

    override suspend fun signInWithEmail(email: String, password: String): Result<String> =
        issueCommand("signIn|$email|$password")

    override suspend fun registerWithEmail(email: String, password: String): Result<String> =
        issueCommand("register|$email|$password")

    override suspend fun signInWithGoogle(idToken: String): Result<String> =
        issueCommand("googleSignIn|$idToken")

    override suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Result<Unit> =
        issueCommand("resetConfirm|$oobCode|$newPassword").map { }

    /**
     * Writes a command, then polls every 250 ms for up to 15 s.
     * Swift AuthBridge clears auth_pending_command once it finishes.
     */
    private suspend fun issueCommand(command: String): Result<String> {
        defaults.setObject(command, forKey = "auth_pending_command")
        defaults.removeObjectForKey("auth_pending_error")
        defaults.synchronize()

        repeat(60) {  // 60 × 250 ms = 15 s timeout
            delay(250L)
            val pending = defaults.stringForKey("auth_pending_command")
            val token   = defaults.stringForKey("firebase_auth_token")
            val error   = defaults.stringForKey("auth_pending_error")

            if (pending == null) {
                defaults.removeObjectForKey("auth_pending_error")
                return when {
                    error != null -> Result.failure(Exception(error))
                    token != null -> Result.success(token)
                    else          -> Result.failure(Exception("Auth failed"))
                }
            }
        }

        defaults.removeObjectForKey("auth_pending_command")
        return Result.failure(Exception("Auth timed out. Please check your connection."))
    }
}

/**
 * Singleton called by Swift AppDelegate / AuthBridge after completing an auth
 * operation so KMP can react without waiting for the poll cycle.
 * Swift calls: AuthCallbackBridgeKt.AuthCallbackBridge.notifySuccess()
 */
object AuthCallbackBridge {
    var onAuthSuccess: (() -> Unit)? = null
    var onAuthFailure: ((String) -> Unit)? = null

    fun notifySuccess() { onAuthSuccess?.invoke() }
    fun notifyFailure(error: String) { onAuthFailure?.invoke(error) }
}
