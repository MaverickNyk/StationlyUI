package com.stationly.app

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.stationly.app.platform.awaitActivityResult
import com.stationly.app.platform.currentActivity
import com.stationly.app.ui.login.PlatformAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Android's half of the shared UI's auth contract.
 *
 * @param context the host Activity where there is one. Interactive Google
 *   sign-in launches an Activity for a result, so a `Context` alone will not
 *   do; [currentActivity] is the fallback for any other construction site.
 * @param googleWebClientId the OAuth **web** client id, `R.string.default_web_client_id`.
 *
 * ## Why the web client id is passed in rather than read here
 * It is generated into `:android:app` by the google-services plugin, from that
 * flavour's `google-services.json` — prod's project and staging's are different
 * OAuth clients, and hardcoding either one makes the other fail with
 * "id_token audience not authorized for this project". `:composeApp` cannot see
 * that generated `R` (the dependency runs the other way), so the two candidate
 * workarounds were `resources.getIdentifier("default_web_client_id", …)` or a
 * constructor parameter.
 *
 * `getIdentifier` was rejected for a reason that only bites later: the release
 * build runs `shrinkResources`, and once AV2-3.5 deletes v1's
 * `FirebaseAuthManager` — today the only `R.string.default_web_client_id`
 * reference in the app — a string reached solely by name would become an unused
 * resource and be stripped. Google sign-in would then work in every debug build
 * and fail only in release. A constructor parameter is a compile error instead.
 */
class AndroidPlatformAuthProvider(
    private val context: Context,
    private val googleWebClientId: String,
) : PlatformAuthProvider {

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

    /**
     * Rename the signed-in user, and re-publish the identity.
     *
     * ## Why the re-publish is not optional
     * `AuthIdentityPublisher` mirrors `FirebaseAuth` into the shared session
     * store from an `AuthStateListener`, and that listener fires on sign-in,
     * sign-out and token change — **not** on a profile update. So a rename
     * changed the account and left the stored display name at the old value
     * until the next cold start: the home avatar's monogram, and the name the
     * device session is registered under, would both go on saying what the user
     * had just changed.
     *
     * `updateProfile` mutates `auth.currentUser` in place, so re-reading it
     * after the await gives the new name.
     */
    override suspend fun updateDisplayName(name: String): Result<Unit> = try {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in.")
        user.updateProfile(
            com.google.firebase.auth.UserProfileChangeRequest.Builder()
                .setDisplayName(name)
                .build()
        ).await()
        auth.currentUser?.let { fresh ->
            com.stationly.core.session.AuthIdentity.publish(
                uid = fresh.uid,
                email = fresh.email,
                displayName = fresh.displayName,
                photoUrl = fresh.photoUrl?.toString(),
                provider = com.stationly.core.session.AuthIdentity.providerLabelFor(
                    fresh.providerData.map { it.providerId }
                ),
            )
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * The whole interactive flow: chooser → Google ID token → Firebase.
     *
     * The shared `LoginScreen` calls this from its "Continue with Google"
     * button. Until now it returned a failure carrying *"Use the Google Sign-In
     * button to continue."* — v1's flow talking about a button the shared screen
     * does not have, so sign-in from the shared UI could not complete at all.
     *
     * ## Why the legacy `GoogleSignIn` API and not Credential Manager
     * v1 signs in with exactly this client, against exactly this web client id,
     * and the two have to coexist until AV2-3.5 deletes v1's login. Same
     * library means a tester comparing the two doors is comparing the doors and
     * not two Google SDKs; it also adds no dependency, since
     * `play-services-auth` is already here. The migration is worth doing on its
     * own, once there is one login screen left to migrate.
     *
     * ## Cancellation surfaces a message, unlike v1
     * v1 swallowed status 12501 and left the screen silent. The shared
     * `LoginViewModel` has no channel for "failed, but say nothing" — every
     * failure becomes `uiState.error` — and giving it one means changing
     * `commonMain`, which is iOS's shipped code. So this matches what iOS
     * already does with an abandoned Apple sheet: a plain "Sign-in was
     * cancelled." Logged as a finding on AV2-3.4 rather than fixed by widening
     * a shared contract for one platform's habit.
     */
    override suspend fun signInWithGoogleInteractive(): Result<String> {
        val activity = hostActivity()
            ?: return Result.failure(IllegalStateException(NO_ACTIVITY))

        val result = try {
            awaitActivityResult(
                activity,
                ActivityResultContracts.StartActivityForResult(),
                googleClient().signInIntent,
            )
        } catch (e: Exception) {
            return Result.failure(e)
        }

        // A dismissed chooser can come back either way: RESULT_CANCELED with no
        // data at all, or data carrying status 12501. Both are the same event.
        if (result.resultCode == Activity.RESULT_CANCELED && result.data == null) {
            return Result.failure(Exception(CANCELLED))
        }

        val account = try {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
        } catch (e: ApiException) {
            return Result.failure(Exception(messageFor(e)))
        }

        val idToken = account?.idToken
            ?: return Result.failure(Exception("Google sign-in did not return an ID token. Please try again."))

        // The credential exchange is the same one the non-interactive entry
        // point does, so it is that one.
        return signInWithGoogle(idToken)
    }

    /**
     * No, and the landing screen no longer offers it.
     *
     * Sign in with Apple needs an Apple-issued `ASAuthorization` flow that
     * exists only on Apple platforms — there is no Android implementation to
     * write, so this is a permanent answer rather than a stub.
     *
     * Until AV2-3.5 the shared `LandingContent` rendered "Continue with Apple"
     * unconditionally, so Android showed a button whose only outcome was the
     * apology below. It now asks this first.
     */
    override val supportsAppleSignIn: Boolean = false

    /**
     * Unreachable from the landing screen, and kept anyway.
     *
     * [supportsAppleSignIn] is what hides the button, and it is one boolean in
     * one composable — a future screen that forgets to ask would otherwise get
     * a `TODO()` or a silent no-op. A failure carrying a sentence a user can
     * read is the safe thing to find at the bottom of that path.
     */
    override suspend fun signInWithAppleInteractive(): Result<String> =
        Result.failure(Exception("Sign in with Apple is not available on Android."))

    override suspend fun signOut(): Result<Unit> = try {
        auth.signOut()
        // v1 signs the Google client out too (`FirebaseAuthManager.signOut`).
        // Without this the client keeps the last account cached, and the next
        // "Continue with Google" silently re-signs the SAME account with no
        // chooser — so "sign out, then sign in as someone else" fails on a
        // shared phone in a way that looks like the sign-out did not work.
        // Deliberately not `revokeAccess()`: that also withdraws the app's
        // consent, which is a different thing than signing out and is not what
        // v1 does. Swallowed on failure — Firebase is the sign-out that counts.
        runCatching { googleClient().signOut().await() }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ── interactive sign-in plumbing ─────────────────────────────────────────

    private fun googleClient(): GoogleSignInClient = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(googleWebClientId)
            .requestEmail()
            .build(),
    )

    /**
     * The Activity to launch the chooser from: the one this provider was built
     * with when the host passed itself, otherwise whatever is on screen.
     */
    private fun hostActivity(): ComponentActivity? =
        context as? ComponentActivity ?: currentActivity()

    /**
     * Google's status codes, in the words the user gets.
     *
     * `DEVELOPER_ERROR` keeps v1's exact wording. Worth knowing what it
     * actually means, because it does not say: this build's (package name,
     * signing certificate SHA-1) pair is not registered as an Android OAuth
     * client in the Firebase project the flavour points at. Staging signs in
     * fine today without one — verified on device — so if it ever appears,
     * the console changed, not this code.
     */
    private fun messageFor(e: ApiException): String = when (e.statusCode) {
        GoogleSignInStatusCodes.SIGN_IN_CANCELLED, CommonStatusCodes.CANCELED -> CANCELLED
        CommonStatusCodes.DEVELOPER_ERROR -> "Google sign-in misconfigured. Please contact support."
        // Worded to contain "network" on purpose: the shared LoginViewModel's
        // `friendlyAuthError` matches that word and swaps in its own copy.
        CommonStatusCodes.NETWORK_ERROR -> "A network error stopped Google sign-in."
        else -> "Google sign-in failed (code ${e.statusCode})."
    }

    private companion object {
        const val CANCELLED = "Sign-in was cancelled."
        const val NO_ACTIVITY =
            "Google sign-in needs an Activity to launch from, and none is on screen."
    }

    override fun consumePendingResetCode(): String? = null

    override fun isEmailVerified(): Boolean = auth.currentUser?.isEmailVerified == true

    override fun isEmailProvider(): Boolean =
        auth.currentUser?.providerData?.any { it.providerId == "password" } == true

    override suspend fun sendEmailVerification(): Result<Unit> = try {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in.")
        user.sendEmailVerification().await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun reloadUser(): Result<Unit> = try {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in.")
        user.reload().await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Apply an `oobCode` from a `…://verified` deep link, then make the local
     * user agree with the server about it.
     *
     * ## Why this is not on [PlatformAuthProvider]
     * Only Android registers that link. iOS's verification mail opens Firebase's
     * hosted page in Safari and the app finds out by polling on resume, so there
     * is no iOS half to write — and widening the shared interface for one
     * platform's link would mean a new Swift bridge command that nothing calls.
     * The host holds this provider already, so an Android-only method on the
     * Android class is the whole of the plumbing.
     *
     * ## The token refresh is not optional
     * `applyActionCode` changes the account on Firebase's side; the cached user
     * and the cached ID token both still say unverified. `reload()` fixes the
     * first. `getIdToken(true)` fixes the second, and it is the one that matters
     * to the backend: the next sync carries the claim, which is what releases
     * the welcome email. v1 did both, in `LoginViewModel.applyVerificationCode`.
     *
     * A null user is not a failure. The app can be killed between signing up and
     * tapping the link, in which case the apply still succeeded — the account is
     * verified — and there is simply no session here to refresh. Reporting that
     * as an error would put "this link is no longer valid" in front of someone
     * whose link worked.
     */
    suspend fun applyEmailVerificationCode(oobCode: String): Result<Unit> = try {
        auth.applyActionCode(oobCode).await()
        auth.currentUser?.let { user ->
            user.reload().await()
            user.getIdToken(true).await()
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
