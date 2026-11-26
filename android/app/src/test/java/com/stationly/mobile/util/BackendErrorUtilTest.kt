package com.stationly.mobile.util

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first unit test in `:android:app`, which had none.
 *
 * `BackendErrorUtil` is worth being the first: it decides which apology a user
 * reads, it is reached only from a failure path (so nobody exercises it by
 * hand), and it classifies partly on exception TYPE and partly on message TEXT
 * — which is exactly the kind of rule that rots silently when a library changes
 * its wording.
 */
class BackendErrorUtilTest {

    @Test
    fun `transport exceptions are backend connection errors`() {
        assertTrue(BackendErrorUtil.isBackendConnectionError(UnknownHostException()))
        assertTrue(BackendErrorUtil.isBackendConnectionError(ConnectException()))
        assertTrue(BackendErrorUtil.isBackendConnectionError(SocketTimeoutException()))
    }

    @Test
    fun `message matching is case insensitive and covers the three known phrasings`() {
        listOf(
            "Unable to resolve host \"api.stationly.app\"",
            "UNABLE TO RESOLVE HOST",
            "Read timeout",
            "TIMEOUT",
            "Failed to connect to /10.0.2.2",
        ).forEach {
            assertTrue("expected a connection error for: $it",
                BackendErrorUtil.isBackendConnectionError(RuntimeException(it)))
        }
    }

    @Test
    fun `an unrelated failure is not a connection error`() {
        assertFalse(BackendErrorUtil.isBackendConnectionError(IllegalStateException("boom")))
        // A null message must not throw. The implementation reads `e.message?`,
        // and an exception with no message is the common case for the ones
        // matched on type above.
        assertFalse(BackendErrorUtil.isBackendConnectionError(RuntimeException()))
    }

    @Test
    fun `each branch produces its own message`() {
        val connection = BackendErrorUtil.getFriendlyMessage(UnknownHostException())
        val parsing = BackendErrorUtil.getFriendlyMessage(IllegalArgumentException("bad json"))
        val unknown = BackendErrorUtil.getFriendlyMessage(IllegalStateException("boom"))

        assertEquals(3, setOf(connection, parsing, unknown).size)
        // No apology may leak the exception's own text at the user.
        assertFalse(unknown.contains("boom"))
        assertFalse(parsing.contains("bad json"))
    }
}
