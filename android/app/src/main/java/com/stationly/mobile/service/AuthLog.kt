package com.stationly.mobile.service

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase

/**
 * Single funnel for auth-related observability. Every login/signup/logout/delete
 * code path should call into this instead of scattering Log.d calls or directly
 * touching FirebaseAnalytics.
 *
 * Two layers:
 *   - Log.i to "StationlyAuth" so we can grep adb logcat on staging
 *   - Firebase Analytics events so we get a real funnel in the console
 *
 * Identifiers are masked before logging — uid and email never leave the device
 * in plaintext. See [maskPii].
 */
object AuthLog {

    private const val TAG = "StationlyAuth"
    private var analytics: FirebaseAnalytics? = null

    /** Lazily initialise FirebaseAnalytics. Safe to call from anywhere with a context. */
    fun init(context: Context) {
        if (analytics == null) {
            analytics = Firebase.analytics
        }
    }

    /**
     * Hash any potentially-PII string (email, uid) down to a stable 6-char token
     * before it's written to logs or analytics. Same input → same token, but
     * the original value can't be recovered.
     */
    fun maskPii(value: String?): String {
        if (value.isNullOrBlank()) return "none"
        val h = value.hashCode().toLong() and 0xFFFFFFL
        return h.toString(16).padStart(6, '0')
    }

    // ── Success events ─────────────────────────────────────────────────────────

    fun registerSuccess(uid: String, provider: String) {
        Log.i(TAG, "register_success provider=$provider uid=${maskPii(uid)}")
        send("auth_register_success", "provider" to provider)
    }

    fun loginSuccess(uid: String, provider: String) {
        Log.i(TAG, "login_success provider=$provider uid=${maskPii(uid)}")
        send("auth_login_success", "provider" to provider)
    }

    fun googleLoginStarted() {
        Log.i(TAG, "google_login_started")
        send("auth_google_started")
    }

    fun googleLoginCancelled() {
        Log.i(TAG, "google_login_cancelled")
        send("auth_google_cancelled")
    }

    fun passwordResetRequested(emailHash: String) {
        Log.i(TAG, "password_reset_requested email=$emailHash")
        send("auth_password_reset_requested")
    }

    fun passwordResetConfirmed() {
        Log.i(TAG, "password_reset_confirmed")
        send("auth_password_reset_confirmed")
    }

    fun logoutSuccess(uid: String) {
        Log.i(TAG, "logout_success uid=${maskPii(uid)}")
        send("auth_logout_success")
    }

    fun accountDeleted(uid: String) {
        Log.i(TAG, "account_deleted uid=${maskPii(uid)}")
        send("auth_account_deleted")
    }

    // ── Failure / soft-warning events ──────────────────────────────────────────

    fun registerFailure(reason: String, provider: String) {
        Log.w(TAG, "register_failure provider=$provider reason=$reason")
        send("auth_register_failure", "provider" to provider, "reason" to reason)
    }

    fun loginFailure(reason: String, provider: String) {
        Log.w(TAG, "login_failure provider=$provider reason=$reason")
        send("auth_login_failure", "provider" to provider, "reason" to reason)
    }

    fun backendSyncFailed(stage: String, reason: String) {
        Log.e(TAG, "backend_sync_failed stage=$stage reason=$reason")
        send("auth_backend_sync_failed", "stage" to stage, "reason" to reason)
    }

    fun logoutBackendFailed(reason: String) {
        Log.w(TAG, "logout_backend_failed reason=$reason")
        send("auth_logout_backend_failed", "reason" to reason)
    }

    fun accountDeleteFailed(reason: String) {
        Log.e(TAG, "account_delete_failed reason=$reason")
        send("auth_account_delete_failed", "reason" to reason)
    }

    fun sessionExpired401(path: String) {
        Log.w(TAG, "session_expired_401 path=$path")
        send("auth_session_expired_401", "path" to path)
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun send(event: String, vararg params: Pair<String, String>) {
        val a = analytics ?: return
        val bundle = Bundle().apply { params.forEach { (k, v) -> putString(k, v) } }
        a.logEvent(event, bundle)
    }
}
