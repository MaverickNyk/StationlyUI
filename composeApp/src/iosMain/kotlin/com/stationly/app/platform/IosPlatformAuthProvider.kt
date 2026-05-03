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
 * 2. Swift AuthBridge observes via UserDefaults.didChangeNotification, processes
 *    the Firebase call, then:
 *      - success → clears auth_pending_command + writes firebase_auth_token
 *                  (or auth_operation_success = "1" for resetConfirm)
 *      - failure → clears auth_pending_command + writes auth_pending_error = <message>
 * 3. KMP polls every 250 ms (up to 15 s) until auth_pending_command disappears.
 *
 * Supported commands:
 *   signIn|<email>|<password>
 *   register|<email>|<password>
 *   googleSignIn|<idToken>
 *   resetConfirm|<oobCode>|<newPassword>
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

    override suspend fun signInWithEmail(email: String, password: String): Result<String> =
        issueCommand("signIn|$email|$password")

    override suspend fun registerWithEmail(email: String, password: String): Result<String> =
        issueCommand("register|$email|$password")

    override suspend fun signInWithGoogle(idToken: String): Result<String> =
        issueCommand("googleSignIn|$idToken")

    override suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Result<Unit> =
        issueCommand("resetConfirm|$oobCode|$newPassword").map { }

    /**
     * Writes a command and polls every 250 ms for up to 15 s.
     * Handles three completion states:
     *   • firebase_auth_token present  → success (sign-in / register)
     *   • auth_operation_success = "1" → success (resetConfirm, no token)
     *   • auth_pending_error present   → failure with message
     */
    private suspend fun issueCommand(command: String): Result<String> {
        defaults.setObject(command, forKey = AppGroupKeys.AUTH_PENDING_COMMAND)
        defaults.removeObjectForKey(AppGroupKeys.AUTH_PENDING_ERROR)
        defaults.removeObjectForKey(AppGroupKeys.AUTH_OPERATION_SUCCESS)
        defaults.synchronize()

        repeat(60) {                      // 60 × 250 ms = 15 s
            delay(250L)
            val pending  = defaults.stringForKey(AppGroupKeys.AUTH_PENDING_COMMAND)
            val token    = defaults.stringForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN)
            val error    = defaults.stringForKey(AppGroupKeys.AUTH_PENDING_ERROR)
            val success  = defaults.stringForKey(AppGroupKeys.AUTH_OPERATION_SUCCESS)

            if (pending == null) {
                defaults.removeObjectForKey(AppGroupKeys.AUTH_PENDING_ERROR)
                defaults.removeObjectForKey(AppGroupKeys.AUTH_OPERATION_SUCCESS)
                return when {
                    error   != null -> Result.failure(Exception(error))
                    token   != null -> Result.success(token)
                    success != null -> Result.success("")   // resetConfirm success
                    else            -> Result.failure(Exception("Auth failed. Please try again."))
                }
            }
        }

        defaults.removeObjectForKey(AppGroupKeys.AUTH_PENDING_COMMAND)
        return Result.failure(Exception("Request timed out. Please check your connection."))
    }
}
