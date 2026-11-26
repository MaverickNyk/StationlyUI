package com.stationly.app.sync

/**
 * Whether a session that ends was ended by the person using the app.
 *
 * Profile's sign-out and delete tear down and leave by themselves. A host that sees the
 * account go must not act during them: leaving the screen would cancel the teardown.
 */
object SessionEnd {
    @kotlin.concurrent.Volatile
    var deliberate: Boolean = false
        private set

    suspend fun <T> deliberately(block: suspend () -> T): T {
        deliberate = true
        try {
            return block()
        } finally {
            deliberate = false
        }
    }
}
