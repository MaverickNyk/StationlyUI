package com.stationly.core.repository

import com.stationly.core.platform.StorageManager

/**
 * The account revision this device has already applied.
 *
 * ## What it buys
 * Paired with the server's `stateRev`, this is the whole of the client's "do I
 * need to refetch?" decision. The rule is one sentence: **fetch the profile iff
 * an observed rev exceeds the stored one; after applying, store the rev that
 * came back.** Duplicate pushes, late pushes, a push racing the foreground
 * check, and (after P4) the socket tier arriving first all collapse into
 * comparing two integers and doing nothing.
 *
 * Before it existed, every app open past the two-minute foreground debounce
 * read `users/{uid}` from Firestore to discover that nothing had changed —
 * about twenty reads a day per user for an answer that was almost always "no".
 *
 * ## Keyed by uid, on purpose
 * A single unscoped key would let one account's revision suppress another's
 * first fetch after a switch — the new user would look up to date because the
 * previous one was. The uid in the key makes that structurally impossible
 * rather than a thing to remember.
 *
 * ## Ordinary storage, not durable
 * Logout wipes the app's defaults domain and takes this with it, which is
 * correct: the first reconcile of a fresh session should always fetch. The
 * login path performs a full guarded restore anyway, and a revision that
 * outlived a session could only ever suppress the one fetch a new session most
 * needs. Compare [UserSettings], which is durable precisely because appearance
 * SHOULD survive a sign-out.
 *
 * ## Only ever moves forward
 * [store] ignores a value lower than the one held. Two reconciles can overlap —
 * a push and a foreground check are serialised by a mutex today, but the socket
 * tier in P4 adds a third source — and an older one completing last must not
 * roll the watermark back and cause an endless refetch of state already applied.
 *
 * The check is a read-then-write and is therefore **not atomic**, and it
 * deliberately is not: the two writers that can genuinely overlap hold
 * DIFFERENT locks (`UserSyncBridge`'s mutex guards the reconcile,
 * `UserStateRepository`'s guards the push), so making this safe would mean
 * introducing a third lock shared between them. The whole cost of losing that
 * race is one redundant profile fetch on the next foreground — strictly less
 * than the pre-P1 behaviour of fetching on every foreground — which is not
 * worth a lock ordering to eliminate.
 */
object LocalRevStore {

    private fun key(uid: String) = "user_state_rev:$uid"

    /**
     * The revision this device has applied for [uid], or 0 if it has none.
     *
     * Zero rather than null: "I have never applied a revision" and "I am at
     * revision zero" want the same behaviour — any observed rev above zero is
     * newer, so fetch. A nullable return would make every call site restate that.
     */
    suspend fun load(storage: StorageManager, uid: String): Long {
        if (uid.isBlank()) return 0L
        return storage.loadString(key(uid))?.toLongOrNull() ?: 0L
    }

    /** Record an applied revision. Ignored if it is not newer than what is held. */
    suspend fun store(storage: StorageManager, uid: String, rev: Long) {
        if (uid.isBlank() || rev <= 0L) return
        if (rev <= load(storage, uid)) return
        storage.saveString(key(uid), rev.toString())
    }

    /**
     * Whether a fetch is warranted, given a revision observed from any source —
     * a push payload, the rev endpoint, or (P4) a socket frame.
     *
     * ## An observed 0 means "I cannot tell you", and must FETCH
     * This is the one place the gate's obvious reading is wrong, and getting it
     * backwards disables cross-device sync outright.
     *
     * `DESIGN_SESSIONS_AND_SYNC.md` §6.1 states the rule as "fetch iff an
     * observed rev exceeds `localRev`". Taken literally with both sides at zero,
     * `0 > 0` is false and the client never fetches. And zero is not a rare
     * edge: **every account that existed before `stateRev` shipped has no such
     * field**, so the server answers 0 for all of them until their first content
     * write. A literal implementation would have silently switched off every
     * reconcile on every existing account, and the symptom — boards not
     * appearing across devices — looks nothing like a gate.
     *
     * Zero is also what every "could not answer" path reports: an older backend,
     * a failed rev call, a push that carries no rev. All of them mean the same
     * thing, and the only safe response to not knowing is to go and look.
     *
     * The cost is that a rev-0 account behaves exactly as it did before P1 — one
     * profile read per foreground past the debounce. That is not a regression,
     * and it heals by itself the first time anything on the account changes,
     * because that write takes the rev to 1.
     */
    suspend fun shouldFetch(storage: StorageManager, uid: String, observedRev: Long): Boolean {
        if (observedRev <= 0L) return true
        return observedRev > load(storage, uid)
    }
}
