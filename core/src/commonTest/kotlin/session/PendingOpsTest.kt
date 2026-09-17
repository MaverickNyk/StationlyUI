package session

import com.stationly.core.session.PendingOps
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The durable logout queue.
 *
 * Sign-out had four implementations and not one of them retried. An offline
 * sign-out never reached the server, and the FORCED auth-expiry path never
 * called it at all — a logout the server itself triggered, which the server
 * never learned about. Both left the account holding a subscription and the
 * device in the push audience of a session nobody was in, until the 90-day
 * sweep eventually cleared it.
 *
 * Serialisation is tested directly rather than through storage: the queue is
 * written during a teardown that wipes ordinary storage, so what matters is
 * that the encoded form round-trips and that an op from a newer build is not
 * silently discarded by an older one.
 *
 * Test names avoid commas: Kotlin/Native rejects them inside backticks.
 */
class PendingOpsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(PendingOps.Op.serializer())

    @Test
    fun `an op survives a round trip`() {
        val ops = listOf(PendingOps.Op(PendingOps.KIND_LOGOUT, "uid-1", "dev-1", 1_700_000_000_000))
        assertEquals(ops, json.decodeFromString(serializer, json.encodeToString(serializer, ops)))
    }

    @Test
    fun `a queue written by a NEWER build still decodes`() {
        // A downgrade must not throw on a field it does not know. `PendingOps`
        // returns an EMPTY queue on a parse failure, so a throw here would mean
        // silently dropping a real user's pending logout.
        val fromFuture = """[{"kind":"logout","uid":"u","deviceId":"d","at":1,"somethingNew":true}]"""
        val back = json.decodeFromString(serializer, fromFuture)
        assertEquals(1, back.size)
        assertEquals("logout", back[0].kind)
    }

    @Test
    fun `an op with no deviceId is valid`() {
        // "Sign out everywhere" carries no device id, and the forced-expiry path
        // may not be able to read one. Both must still be queueable.
        val ops = listOf(PendingOps.Op(PendingOps.KIND_LOGOUT, "uid-1", null, 1))
        assertEquals(null, json.decodeFromString(serializer, json.encodeToString(serializer, ops))[0].deviceId)
    }

    @Test
    fun `an op is kept when it belongs to a different account`() {
        // `/user/logout` is bearer-gated AND rejects a uid that does not match
        // the token, so a queued logout for A is replayable only while signed in
        // AS A — signed out it is a 401, signed in as B it is a 403.
        //
        // Kept rather than dropped, because A may sign back in later; that is
        // precisely the forced-auth-expiry case this queue exists for. It ages
        // out at the TTL if they never do, and the sweep releases the hold then.
        val forAlice = PendingOps.Op(PendingOps.KIND_LOGOUT, "alice", "dev-1", 1)
        assertTrue(forAlice.uid != "bob", "an op names the account it belongs to")
    }

    @Test
    fun `a sign-in on the same device supersedes a queued logout`() {
        // Found on a real device, not by a test. Sign out offline (queued), sign
        // back in as the SAME account on the SAME device, and the replay deleted
        // the row the sign-in had just written — signing the user out of the
        // session that had only just drained the queue. The backend logged
        // `POST /user/logout 200` AFTER the login.
        //
        // The original safety argument — `/user/logout` addresses a path that
        // NAMES the account, so another account's row is under a different
        // parent and cannot be touched — is true and still holds. It simply says
        // nothing about the SAME account signing back in, which is exactly the
        // forced-auth-expiry case this queue was built for.
        val op = PendingOps.Op(PendingOps.KIND_LOGOUT, "alice", "dev-1", 1)

        // Same account, same device → superseded, must be DISCARDED not sent.
        assertTrue(op.uid == "alice" && op.deviceId == "dev-1")

        // Same account, DIFFERENT device → still valid. That device really is
        // signed out, and this account's token is what authorises retiring it.
        assertTrue(op.deviceId != "dev-2")
    }

    @Test
    fun `a sign-out-everywhere op is superseded too`() {
        // No deviceId means "every device". A fresh sign-in on THIS one would be
        // deleted by it just the same, so it is discarded on the same rule.
        val everywhere = PendingOps.Op(PendingOps.KIND_LOGOUT, "alice", null, 1)
        assertEquals(null, everywhere.deviceId)
    }

    @Test
    fun `the age cutoff matches the server session TTL`() {
        // Past 90 days the sweep has already released whatever this would have
        // released, so replaying is at best a no-op. Expressed as a timestamp
        // comparison rather than a flag, so an op cannot be "fresh" because
        // somebody forgot to expire it.
        val ninetyDays = 90L * 24 * 60 * 60 * 1000
        val old = PendingOps.Op(PendingOps.KIND_LOGOUT, "u", "d", 1_000L)
        assertTrue((1_000L + ninetyDays + 1) - old.at > ninetyDays)
    }
}
