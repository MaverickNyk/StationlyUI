package user

import com.stationly.core.repository.BoardPushGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules that decide whether a board list reaches the backend at all.
 *
 * Two failures are being pinned here, and both were live:
 *  - a flush pushed unconditionally, so every app backgrounding spent a write
 *    plus a cross-device notification on a list nobody had touched;
 *  - an empty local list was indistinguishable from "delete all my boards", and
 *    the login path empties the local list before restoring it.
 *
 * Test names avoid commas: Kotlin/Native rejects them inside backticks, and
 * `core:iosSimulatorArm64Test` will not compile if one creeps back in.
 */
class BoardPushGateTest {

    // ── Nothing to say ──────────────────────────────────────────────────────

    @Test
    fun `a fresh gate has nothing pending`() {
        assertNull(BoardPushGate().pending())
    }

    @Test
    fun `a flush after an accepted push has nothing pending`() {
        val gate = BoardPushGate()
        gate.changed()
        val target = gate.pending()!!
        gate.accepted(target, listWasEmpty = false)

        // This is the app being backgrounded with no edits since the last sync,
        // which is what most backgroundings are.
        assertNull(gate.pending())
    }

    @Test
    fun `repeated flushes without an edit stay quiet`() {
        val gate = BoardPushGate()
        gate.changed()
        gate.accepted(gate.pending()!!, listWasEmpty = false)

        repeat(20) { assertNull(gate.pending()) }
    }

    // ── Something to say ────────────────────────────────────────────────────

    @Test
    fun `a change makes a push pending`() {
        val gate = BoardPushGate()
        gate.changed()
        assertEquals(1L, gate.pending())
    }

    @Test
    fun `a burst of changes collapses into one pending push`() {
        val gate = BoardPushGate()
        repeat(4) { gate.changed() }

        // Four ticked boxes, one request — the count moved but there is still
        // only one thing pending, and one acknowledgement clears all of it.
        gate.accepted(gate.pending()!!, listWasEmpty = false)
        assertNull(gate.pending())
    }

    @Test
    fun `a failed push stays pending so the nightly flush retries it`() {
        val gate = BoardPushGate()
        gate.changed()
        val target = gate.pending()

        // No `accepted` call: the request threw, or came back non-200.
        assertEquals(target, gate.pending())
    }

    // ── The in-flight race ──────────────────────────────────────────────────

    @Test
    fun `an edit during a push is not acknowledged away`() {
        val gate = BoardPushGate()
        gate.changed()
        val inFlight = gate.pending()!!

        // The user edits while the request is on the wire.
        gate.changed()

        gate.accepted(inFlight, listWasEmpty = false)

        // The second edit must still go. Acknowledging the CURRENT revision
        // rather than the one sent would drop it, and the user would see the
        // change vanish on their other device.
        assertEquals(inFlight + 1, gate.pending())
    }

    // ── The empty-list permission ───────────────────────────────────────────

    @Test
    fun `an ordinary change does not permit an empty list`() {
        val gate = BoardPushGate()
        gate.changed()
        assertFalse(gate.allowEmpty())
    }

    @Test
    fun `a gate with no changes at all does not permit an empty list`() {
        // The login race: local SQL is wiped and nothing has marked a change.
        // Even if something forced a push it must not be allowed to clear the
        // account.
        assertFalse(BoardPushGate().allowEmpty())
    }

    @Test
    fun `the user emptying their boards permits an empty list`() {
        val gate = BoardPushGate()
        gate.changed(emptiedByUser = true)
        assertTrue(gate.allowEmpty())
    }

    @Test
    fun `the permission survives a failed push`() {
        val gate = BoardPushGate()
        gate.changed(emptiedByUser = true)

        // Offline. Nothing is acknowledged, and the retry must still be allowed
        // to say the account is empty.
        assertTrue(gate.allowEmpty())
    }

    @Test
    fun `the permission survives an accepted EMPTY push`() {
        val gate = BoardPushGate()
        gate.changed(emptiedByUser = true)
        gate.accepted(gate.pending()!!, listWasEmpty = true)

        // The account legitimately has no boards, so the next push is empty too
        // and needs the same justification. Clearing it here would make that
        // push look exactly like the login-race write the guard exists to
        // refuse.
        assertTrue(gate.allowEmpty())
    }

    @Test
    fun `the permission is surrendered once a non-empty list is accepted`() {
        val gate = BoardPushGate()
        gate.changed(emptiedByUser = true)
        gate.accepted(gate.pending()!!, listWasEmpty = true)

        gate.changed()
        gate.accepted(gate.pending()!!, listWasEmpty = false)

        assertFalse(gate.allowEmpty())
    }

    @Test
    fun `deleting one of several boards does not permit an empty list`() {
        val gate = BoardPushGate()
        // The call site passes the LIVE answer, and boards remain.
        gate.changed(emptiedByUser = false)
        assertFalse(gate.allowEmpty())
    }

    @Test
    fun `a re-emptying during a push keeps the permission`() {
        val gate = BoardPushGate()
        gate.changed(emptiedByUser = true)
        val inFlight = gate.pending()!!

        // The user re-adds a board and deletes it again while the first request
        // is still out.
        gate.changed()
        gate.changed(emptiedByUser = true)

        gate.accepted(inFlight, listWasEmpty = true)

        assertTrue(gate.allowEmpty())
        assertTrue(gate.pending() != null)
    }

    // ── Session boundaries ──────────────────────────────────────────────────

    @Test
    fun `reset leaves nothing pending for the incoming user`() {
        val gate = BoardPushGate()
        repeat(3) { gate.changed(emptiedByUser = true) }

        gate.reset()

        // The outgoing user's unsent edits must not be posted under the next
        // user's credentials, and their permission to empty an account must not
        // carry over either.
        assertNull(gate.pending())
        assertFalse(gate.allowEmpty())
    }

    @Test
    fun `the gate still works after a reset`() {
        val gate = BoardPushGate()
        gate.changed()
        gate.accepted(gate.pending()!!, listWasEmpty = false)
        gate.reset()

        gate.changed()
        assertEquals(1L, gate.pending())
    }
}
