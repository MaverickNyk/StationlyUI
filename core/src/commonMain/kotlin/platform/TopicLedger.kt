package com.stationly.core.platform

/**
 * What a subscription reconcile must actually change.
 *
 * The arithmetic of "make the device's topics equal this list", pulled out of
 * the platform class that performs it so the rules can be read and tested
 * without a `Context`, a `SharedPreferences` or a Firebase instance. The rules
 * are small and all three of them have a failure behind them:
 *
 *  1. **An empty desired list changes nothing.** `getAllSelections()` answers
 *     empty during a login restore, and in the gap between a wipe and its
 *     refill, and on a device whose database has not opened yet. Every one of
 *     those means "I do not know", and the only safe reading of "I do not know"
 *     is to leave a working device alone. Deleting the last board goes through
 *     `unsubscribeFromTopics` and a logout through `clearAllTopics`: both say
 *     what they mean, which is the whole difference.
 *
 *  2. **Only board topics are ever unsubscribed.** `stationly_all` is how an
 *     `audience: {type:"all"}` push reaches the app with no Firestore reads. It
 *     is not a board, it is not in the ledger, and if it ever got there by
 *     accident a reconcile must not be what silently ends it — the loss would be
 *     total and there is nothing on the device to see it by.
 *
 *  3. **Nothing to do is the common case and must cost nothing.** This runs on
 *     every app foreground. Two set differences, and on the overwhelmingly usual
 *     path, no network call at all. It is also what makes upgrade day a no-op: a
 *     v1 install arrives with its ledger already written under the same key, so
 *     the diff is empty and no device re-subscribes anything (risk R6).
 */
object TopicLedger {

    /** Topics this device asked for and has not asked to be released from. */
    data class Plan(
        val subscribe: List<String>,
        val unsubscribe: List<String>,
    ) {
        val isEmpty: Boolean get() = subscribe.isEmpty() && unsubscribe.isEmpty()
    }

    private val NOTHING = Plan(emptyList(), emptyList())

    /**
     * @param ledger what this device believes it is subscribed to.
     * @param desired the topics its boards need, right now.
     */
    fun plan(ledger: Set<String>, desired: List<String>): Plan {
        if (desired.isEmpty()) return NOTHING
        val wanted = desired.toSet()
        return Plan(
            subscribe = (wanted - ledger).sorted(),
            unsubscribe = (ledger - wanted).filter(::isBoardTopic).sorted(),
        )
    }

    /**
     * The two topic families a board subscribes to, and the only two a reconcile
     * may take away. Spelled here as prefixes because this is a decision about
     * what is SAFE to unsubscribe; the names themselves are built in exactly one
     * place, `StationLifecycleUseCase.topicsFor`.
     */
    fun isBoardTopic(topic: String): Boolean =
        topic.startsWith("Station_") || topic.startsWith("LineStatus_")
}
