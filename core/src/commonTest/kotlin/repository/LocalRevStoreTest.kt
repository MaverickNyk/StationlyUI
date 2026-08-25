package repository

import com.stationly.core.model.UserSelection
import com.stationly.core.platform.StorageManager
import com.stationly.core.repository.LocalRevStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The client half of the rev gate — the two integers that decide whether an app
 * open costs a Firestore read.
 *
 * The rule that most needs pinning is the one the design document gets wrong:
 * an OBSERVED REV OF ZERO MUST FETCH. Read literally, "fetch iff observed >
 * localRev" leaves both sides at zero and never fetches — and every account
 * that predates `stateRev` reads zero, so a literal implementation silently
 * switches off cross-device sync for all of them. That is the failure this file
 * exists to stop coming back, because nothing else would notice: the symptom is
 * boards not appearing on the other device, which looks nothing like an integer
 * comparison.
 *
 * Test names avoid commas: Kotlin/Native rejects them inside backticks, and
 * `core:iosSimulatorArm64Test` will not compile if one creeps back in.
 */
class LocalRevStoreTest {

    /** The only two methods [LocalRevStore] uses; everything else throws. */
    private class FakeStorage : StorageManager {
        val strings = mutableMapOf<String, String>()
        override suspend fun saveString(key: String, value: String) { strings[key] = value }
        override suspend fun loadString(key: String): String? = strings[key]

        override suspend fun saveSelections(selections: List<UserSelection>) = unused()
        override suspend fun loadSelections(): List<UserSelection> = unused()
        override suspend fun saveLineStatus(lineId: String, statusJson: String) = unused()
        override suspend fun loadLineStatus(lineId: String): String? = unused()
        override suspend fun clearCache() = unused()
        override suspend fun clearAll() = unused()
        override suspend fun saveDurable(key: String, value: String) = unused()
        override suspend fun loadDurable(key: String): String? = unused()
        override suspend fun removeDurable(key: String) = unused()
        private fun unused(): Nothing = throw UnsupportedOperationException("not used by LocalRevStore")
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    @Test
    fun `an account with no stored revision reads as zero`() = runTest {
        assertEquals(0L, LocalRevStore.load(FakeStorage(), "u"))
    }

    @Test
    fun `a blank uid reads as zero rather than reaching storage`() = runTest {
        assertEquals(0L, LocalRevStore.load(FakeStorage(), ""))
    }

    @Test
    fun `a corrupt stored value reads as zero instead of throwing`() = runTest {
        val storage = FakeStorage()
        storage.strings["user_state_rev:u"] = "not a number"
        // Storage is shared with everything else the app writes. A bad value has
        // to degrade to "I have applied nothing" — which fetches — rather than
        // take down the foreground path.
        assertEquals(0L, LocalRevStore.load(storage, "u"))
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    @Test
    fun `store then load round-trips`() = runTest {
        val storage = FakeStorage()
        LocalRevStore.store(storage, "u", 7)
        assertEquals(7L, LocalRevStore.load(storage, "u"))
    }

    @Test
    fun `store never moves the revision backwards`() = runTest {
        val storage = FakeStorage()
        LocalRevStore.store(storage, "u", 9)
        // A reconcile that started earlier but finished later must not undo a
        // newer one. Rolling back would make the gate re-fetch state already
        // applied — on every foreground until something moved it forward again.
        LocalRevStore.store(storage, "u", 4)
        assertEquals(9L, LocalRevStore.load(storage, "u"))
    }

    @Test
    fun `store ignores zero and negatives`() = runTest {
        val storage = FakeStorage()
        LocalRevStore.store(storage, "u", 5)
        LocalRevStore.store(storage, "u", 0)
        LocalRevStore.store(storage, "u", -3)
        assertEquals(5L, LocalRevStore.load(storage, "u"))
    }

    @Test
    fun `revisions are namespaced per account`() = runTest {
        val storage = FakeStorage()
        LocalRevStore.store(storage, "alice", 5)
        LocalRevStore.store(storage, "bob", 2)
        // An unscoped key would let the previous account's revision make the new
        // one look up to date, so the first reconcile after a switch would be
        // suppressed — exactly when it is most needed.
        assertEquals(5L, LocalRevStore.load(storage, "alice"))
        assertEquals(2L, LocalRevStore.load(storage, "bob"))
    }

    // ── The gate ────────────────────────────────────────────────────────────

    @Test
    fun `a newer observed revision fetches`() = runTest {
        val storage = FakeStorage()
        LocalRevStore.store(storage, "u", 3)
        assertTrue(LocalRevStore.shouldFetch(storage, "u", 4))
    }

    @Test
    fun `an equal observed revision does not fetch`() = runTest {
        val storage = FakeStorage()
        LocalRevStore.store(storage, "u", 3)
        // THE point of P1: this is the common case, and it is the Firestore read
        // that used to happen on every app open.
        assertFalse(LocalRevStore.shouldFetch(storage, "u", 3))
    }

    @Test
    fun `an older observed revision does not fetch`() = runTest {
        val storage = FakeStorage()
        LocalRevStore.store(storage, "u", 8)
        // A late push carrying a superseded rev. Nothing to do.
        assertFalse(LocalRevStore.shouldFetch(storage, "u", 5))
    }

    @Test
    fun `an observed revision of zero always fetches`() = runTest {
        val storage = FakeStorage()
        // Zero means "I cannot tell you" — an account that predates `stateRev`
        // (which is EVERY account that already existed when it shipped), a
        // failed rev call, an older backend, a push with no rev. Reading the
        // design's rule literally gives `0 > 0` = false and never fetches, which
        // would have switched off cross-device reconcile on every existing
        // account. See docs/HANDOVER_SESSION_SYNC.md section 3.1.
        assertTrue(LocalRevStore.shouldFetch(storage, "u", 0))

        // And still true once this device HAS applied revisions: a signal that
        // cannot name a revision is never evidence that nothing changed.
        LocalRevStore.store(storage, "u", 12)
        assertTrue(LocalRevStore.shouldFetch(storage, "u", 0))
    }

    @Test
    fun `a device that has applied nothing fetches on any real revision`() = runTest {
        val storage = FakeStorage()
        assertTrue(LocalRevStore.shouldFetch(storage, "u", 1))
    }
}
