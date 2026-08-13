package com.stationly.core.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Decides whether the board list is worth sending, and whether it may be empty.
 *
 * Extracted from [UserStateRepository] because it is the part with the sharp
 * edges — a race against an in-flight request, and a permission that gates a
 * destructive write — and the repository itself cannot be unit-tested: it reaches
 * the network and `Platform`, which is an `expect object` with no injection seam.
 * This has neither, so the rules can be tested directly.
 *
 * The caller serialises [pending] and [accepted] against each other (the
 * repository's push mutex). [changed] is called from arbitrary threads and does
 * not need that lock.
 */
internal class BoardPushGate {

    /**
     * How many times the user has changed their boards this session.
     *
     * A counter rather than a boolean so an edit made WHILE a push is in flight
     * cannot be acknowledged away: [accepted] only clears up to the revision the
     * request actually carried. A boolean set true by the edit and false by the
     * response would drop it silently, and the loss surfaces as a board that
     * appears on the user's other device and then vanishes.
     *
     * `update` rather than `value++`: the latter is not atomic, so two taps in
     * one frame would count as one.
     */
    private val revision = MutableStateFlow(0L)

    /** The last [revision] the server accepted. Guarded by the caller's lock. */
    private var pushedRevision = 0L

    /**
     * Whether the user has emptied their board list since the last accepted
     * non-empty push — the only legitimate way for the account to reach zero.
     *
     * Sticky, so it survives the debounce and any number of failed attempts.
     */
    private val emptied = MutableStateFlow(false)

    /** Note a user change. [emptiedByUser] when it leaves no boards at all. */
    fun changed(emptiedByUser: Boolean = false) {
        revision.update { it + 1 }
        if (emptiedByUser) emptied.value = true
    }

    /**
     * The revision a push would cover, or null when there is nothing to say.
     *
     * Null is the overwhelmingly common answer: a flush runs on every
     * backgrounding and once a night, and most of those follow a session in
     * which the user never touched their boards.
     */
    fun pending(): Long? = revision.value.takeIf { it != pushedRevision }

    /** Whether this push may replace a stored list with an empty one. */
    fun allowEmpty(): Boolean = emptied.value

    /**
     * Record that the server took the write.
     *
     * @param revision the value [pending] returned for this attempt — NOT the
     *   current one, which a concurrent edit may have moved past it.
     * @param listWasEmpty whether the list actually sent was empty. The empty
     *   permission is surrendered only once a NON-empty list has been accepted:
     *   while the account legitimately has no boards, every later push is also
     *   empty and needs the same justification, and clearing it here would make
     *   the next one look exactly like the write this exists to refuse.
     */
    fun accepted(revision: Long, listWasEmpty: Boolean) {
        pushedRevision = revision
        if (!listWasEmpty) emptied.value = false
    }

    /**
     * Drop everything for a session that is ending.
     *
     * Both counters go to zero rather than "pushed = current": the incoming
     * session starts with nothing to say, and a leftover count from the outgoing
     * user must not make the next one's first flush send a list they never
     * edited.
     */
    fun reset() {
        revision.value = 0
        pushedRevision = 0
        emptied.value = false
    }
}
