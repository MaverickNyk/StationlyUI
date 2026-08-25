package com.stationly.core.session

import com.stationly.core.platform.Platform
import com.stationly.core.service.SduiApiService
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A durable queue for the one thing a sign-out cannot afford to lose: telling
 * the backend it happened.
 *
 * ## The hole this closes
 * Sign-out has had four implementations doing measurably different things, and
 * not one of them retried. Read down the failure column:
 *
 *  - **Ordinary sign-out with no network.** `POST /user/logout` fails, the app
 *    signs out locally anyway (correctly — the user asked), and the server is
 *    never told. The account keeps its subscription hold and the device stays in
 *    the push audience of an account nobody is signed into.
 *  - **Forced sign-out on auth expiry.** Worse: it ends the session through
 *    `Platform.signOutFromAuthExpiry` and never calls the backend AT ALL — a
 *    logout the server itself triggered, which the server never learns about.
 *
 * Both leave the same residue, and the nightly sweep only clears it after the
 * 90-day TTL. Queueing the call makes it eventual instead of lost.
 *
 * ## Why the queue is DURABLE storage
 * The op is enqueued during a teardown that WIPES the app's ordinary defaults.
 * A queue written there would be erased by the very sequence it exists to
 * outlive. This is the same reasoning as the account-removed flag.
 *
 * ## Why replay is safe without a conditional
 * `POST /user/logout` addresses `users/{uid}/devices/{deviceId}` — a path that
 * NAMES the account. If somebody else has since signed in on this device, their
 * row lives under a different parent, so a replayed logout cannot see it, let
 * alone delete it; the previous account's transition already ran via the steal
 * on the new sign-in. So this can be replayed as often as you like, years late,
 * with no check to get wrong. Under a query-shaped teardown that was true by
 * construction of the query; under the path it is true by construction, which
 * is stronger and cheaper to review.
 */
object PendingOps {

    /** One queued call. Only logout today; the shape allows more without a migration. */
    @Serializable
    data class Op(
        val kind: String,
        val uid: String,
        val deviceId: String? = null,
        /** Epoch ms. Used only to drop ops older than the server-side TTL. */
        val at: Long,
    )

    const val KIND_LOGOUT = "logout"

    private const val KEY = "pending_ops_v1"

    /**
     * Ops older than this are dropped unreplayed.
     *
     * Matched to the backend's 90-day session TTL: past it the sweep has already
     * released whatever this op would have released, so replaying is at best a
     * no-op and at worst a confusing write against an account that has moved on.
     */
    private const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun load(): List<Op> =
        runCatching {
            Platform.storageManager.loadDurable(KEY)
                ?.takeIf { it.isNotBlank() }
                ?.let { json.decodeFromString(ListSerializer(Op.serializer()), it) }
                ?: emptyList()
        }.getOrElse {
            // A queue we cannot parse is a queue we cannot act on. Returning
            // empty loses at most a subscription hold the nightly sweep will
            // release anyway; throwing would break every launch that reads it.
            emptyList()
        }

    private suspend fun save(ops: List<Op>) {
        runCatching {
            Platform.storageManager.saveDurable(KEY, json.encodeToString(ListSerializer(Op.serializer()), ops))
        }
    }

    /**
     * Queue a logout for replay. Idempotent per (uid, deviceId): a user who taps
     * sign-out three times offline queues one op, not three.
     */
    suspend fun enqueueLogout(uid: String, deviceId: String?) {
        if (uid.isBlank()) return
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val existing = load()
        if (existing.any { it.kind == KIND_LOGOUT && it.uid == uid && it.deviceId == deviceId }) return
        save(existing + Op(KIND_LOGOUT, uid, deviceId, now))
    }

    /**
     * Replay what the signed-in account is allowed to replay.
     *
     * @param currentDeviceId this device, so an op superseded by a fresh
     *   sign-in on it is DISCARDED rather than sent. Without this the queue
     *   signs the user out of the session that just drained it.
     * @param signedInUid the account currently signed in, or null when nobody
     *   is. **Null replays nothing** — see the guard below: every call would be
     *   a guaranteed 401, and the queue survives to try again once somebody
     *   signs in. That makes the useful call sites "just after a successful
     *   login" and "on network regain while signed in", not "at launch".
     *
     * An op is dropped only when the server has ACKNOWLEDGED it or it has aged
     * out. A transport failure leaves it queued — that is the entire point, and
     * it is why this returns the count rather than a boolean: a caller that
     * wants to log "replayed 1 of 2" can.
     */
    suspend fun replay(
        api: SduiApiService,
        signedInUid: String? = null,
        currentDeviceId: String? = null,
    ): Int {
        val ops = load()
        if (ops.isEmpty()) return 0

        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val survivors = mutableListOf<Op>()
        var done = 0

        for (op in ops) {
            if (now - op.at > MAX_AGE_MS) continue // aged out; the sweep has it

            // ⚠️ Only the account itself can retire its own session.
            //
            // `/user/logout` sits behind `validateUserToken`, which rejects a
            // uid that does not match the bearer. So a queued logout for A is
            // replayable ONLY while signed in as A — signed out it is a 401, and
            // signed in as B it is a 403. Both would burn the op's one chance
            // per launch for nothing.
            //
            // Kept rather than dropped when it does not match: A may well sign
            // back in later, which is exactly the forced-auth-expiry case this
            // queue was built for. It ages out at the TTL if they never do, and
            // the sweep releases the hold then anyway.
            if (signedInUid == null) { survivors.add(op); continue }
            if (op.uid != signedInUid) { survivors.add(op); continue }

            // ⚠️ A SIGN-IN SUPERSEDES A QUEUED LOGOUT FOR THE SAME DEVICE.
            //
            // Drop it, do not send it. This account has just signed back in ON
            // THIS DEVICE, so the session the op refers to no longer exists —
            // and the row now at `users/{uid}/devices/{deviceId}` belongs to the
            // NEW session. Replaying would delete it and sign the user out of
            // the session they just created.
            //
            // Observed on a real device: sign out offline (queued), sign back
            // in, and the replay tore the fresh session down a second later.
            // The backend logged `POST /user/logout 200` AFTER the login.
            //
            // The original safety argument was that `/user/logout` addresses a
            // path that NAMES the account, so if somebody ELSE signed in on this
            // device their row is under a different parent and cannot be
            // touched. That is true and still holds. It simply says nothing
            // about the SAME account signing back in — which is precisely the
            // forced-auth-expiry case this queue exists for.
            //
            // Ops for this account's OTHER devices are still replayed: those
            // devices really are signed out, and this account's token is exactly
            // what authorises retiring them.
            if (currentDeviceId != null && op.deviceId == currentDeviceId) {
                done++   // resolved, not sent — counted so the trace is honest
                continue
            }
            // A "sign out everywhere" op (no deviceId) is likewise superseded by
            // this device signing back in: it would delete the row just created.
            if (op.deviceId == null) { done++; continue }

            val ok = when (op.kind) {
                KIND_LOGOUT -> runCatching { api.logOut(op.uid, op.deviceId) }.getOrDefault(false)
                // An op this build does not recognise is KEPT, not dropped: a
                // downgrade must not silently discard work a newer build queued.
                else -> false
            }
            if (ok) done++ else survivors.add(op)
        }

        if (survivors.size != ops.size) save(survivors)
        return done
    }

    /** Everything currently queued — for diagnostics and tests. */
    suspend fun peek(): List<Op> = load()

    /** Drop the queue. For account deletion, where there is nothing left to tell. */
    suspend fun clear() {
        runCatching { Platform.storageManager.removeDurable(KEY) }
    }
}
