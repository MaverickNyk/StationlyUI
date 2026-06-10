package com.stationly.app.platform

import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.core.platform.AppGroupKeys
import kotlinx.coroutines.delay
import platform.Foundation.NSUserDefaults

/**
 * iOS PlatformAuthProvider — bridges KMP ViewModel layer to Swift AuthBridge.
 *
 * Protocol (both sides use NSUserDefaults.standard):
 *
 * 1. KMP writes:  auth_pending_command = "<verb>|<arg1>|<arg2>"
 * 2. Swift AuthBridge observes via UserDefaults.didChangeNotification, clears
 *    the command key immediately (dedupe), processes the Firebase call, then:
 *      - success → writes firebase_auth_token (or auth_operation_success = "1"
 *                  for non-token operations like resetConfirm)
 *      - failure → writes auth_pending_error = <message>
 *    and ALWAYS finishes by writing auth_command_done = "1".
 * 3. KMP polls every 250 ms for auth_command_done. The command key vanishing
 *    means nothing — Swift clears it before the async work starts, which is
 *    why waiting on it broke interactive Google sign-in (the user was still
 *    in the Google account sheet when the old 15 s poll gave up).
 *
 * Supported commands:
 *   signIn|<email>|<password>
 *   register|<email>|<password>
 *   googleSignIn|<idToken>
 *   googleSignInInteractive
 *   resetConfirm|<oobCode>|<newPassword>
 *   updateDisplayName|<name>
 *   signOut
 */
class IosPlatformAuthProvider : PlatformAuthProvider {

    private val defaults = NSUserDefaults.standardUserDefaults

    override fun isLoggedIn(): Boolean =
        defaults.stringForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN)?.isNotBlank() == true

    override fun currentUserEmail(): String? =
        defaults.stringForKey(AppGroupKeys.FIREBASE_USER_EMAIL)

    override fun currentUserDisplayName(): String? =
        defaults.stringForKey(AppGroupKeys.FIREBASE_USER_NAME)

    override fun currentUserPhotoUrl(): String? =
        defaults.stringForKey(AppGroupKeys.FIREBASE_USER_PHOTO)

    override fun currentUserUid(): String? =
        defaults.stringForKey(AppGroupKeys.FIREBASE_USER_UID)

    override suspend fun signInWithEmail(email: String, password: String): Result<String> =
        issueCommand("signIn|$email|$password")

    override suspend fun registerWithEmail(email: String, password: String): Result<String> =
        issueCommand("register|$email|$password")

    override suspend fun signInWithGoogle(idToken: String): Result<String> =
        issueCommand("googleSignIn|$idToken")

    // The user can sit in the Google account sheet for as long as they like —
    // give the interactive flow 3 minutes, not the regular network timeout.
    override suspend fun signInWithGoogleInteractive(): Result<String> =
        issueCommand("googleSignInInteractive", timeoutMillis = 180_000L)

    override suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Result<Unit> =
        issueCommand("resetConfirm|$oobCode|$newPassword").map { }

    override suspend fun updateDisplayName(name: String): Result<Unit> =
        issueCommand("updateDisplayName|$name").map { }

    override suspend fun signOut(): Result<Unit> =
        issueCommand("signOut").map { }

    override fun consumePendingResetCode(): String? {
        val code = defaults.stringForKey(AppGroupKeys.PENDING_RESET_OOB_CODE) ?: return null
        defaults.removeObjectForKey(AppGroupKeys.PENDING_RESET_OOB_CODE)
        defaults.synchronize()
        return code.ifBlank { null }
    }

    /**
     * Writes a command and polls every 250 ms until Swift writes
     * auth_command_done (its very last write for every command). Completion
     * states, checked in order:
     *   • auth_pending_error present   → failure with message
     *   • firebase_auth_token present  → success (sign-in / register)
     *   • auth_operation_success = "1" → success (non-token ops)
     */
    private suspend fun issueCommand(
        command: String,
        timeoutMillis: Long = 30_000L
    ): Result<String> {
        defaults.removeObjectForKey(AppGroupKeys.AUTH_PENDING_ERROR)
        defaults.removeObjectForKey(AppGroupKeys.AUTH_OPERATION_SUCCESS)
        defaults.removeObjectForKey(AppGroupKeys.AUTH_COMMAND_DONE)
        defaults.setObject(command, forKey = AppGroupKeys.AUTH_PENDING_COMMAND)
        defaults.synchronize()

        repeat((timeoutMillis / 250L).toInt()) {
            delay(250L)
            if (defaults.stringForKey(AppGroupKeys.AUTH_COMMAND_DONE) != null) {
                val error   = defaults.stringForKey(AppGroupKeys.AUTH_PENDING_ERROR)
                val token   = defaults.stringForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN)
                val success = defaults.stringForKey(AppGroupKeys.AUTH_OPERATION_SUCCESS)
                defaults.removeObjectForKey(AppGroupKeys.AUTH_PENDING_ERROR)
                defaults.removeObjectForKey(AppGroupKeys.AUTH_OPERATION_SUCCESS)
                defaults.removeObjectForKey(AppGroupKeys.AUTH_COMMAND_DONE)
                return when {
                    error   != null -> Result.failure(Exception(error))
                    token   != null -> Result.success(token)
                    success != null -> Result.success("")
                    else            -> Result.failure(Exception("Auth failed. Please try again."))
                }
            }
        }

        defaults.removeObjectForKey(AppGroupKeys.AUTH_PENDING_COMMAND)
        return Result.failure(Exception("Request timed out. Please check your connection."))
    }
}
