package com.stationly.app.ui.summary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "The user just chose Expanded/Collapsed for this station — apply it now."
 *
 * ## Why this is an explicit signal and not a diff
 * `BoardConfig.expanded` is the DEFAULT: which stations open when the app
 * starts. Which stations are open *right now* is session state, held in
 * `SummaryScreen` as `expandedIdsState`, and once the user has touched a chevron
 * the session deliberately outranks the default — otherwise every card would
 * spring back open on the next recomposition.
 *
 * So changing the setting has to say so out loud. The first attempt inferred it
 * instead, by remembering the previous value of every station's `expanded` flag
 * and reacting when one moved. That could not work, and the reason is worth
 * keeping: **`SummaryScreen` leaves composition while the settings screen is
 * open.** A plain `remember` holding the previous values is gone by the time the
 * user comes back, the reconciler sees its own first read, and the change is
 * recorded as history rather than applied. The one path the feature existed for
 * was the one path it could never fire on.
 *
 * A signal raised by the ViewModel survives that, because it lives outside the
 * composition entirely — the same reason [BoardFocus] is a singleton.
 *
 * ## A map, not one slot
 * Two stations can be waiting: settings for A, back, settings for B, back, with
 * the home screen never getting a frame in between if the user is quick. A
 * single slot would silently drop the first.
 *
 * ## Consumed only when applied
 * The screen removes exactly the keys it acted on. A change for a station that
 * is not in the list yet — a sync still landing — stays pending and applies when
 * it arrives, rather than being dropped for being early.
 *
 * Single-threaded by construction: raised from a ViewModel on the main thread,
 * consumed from a composition effect on the main thread.
 */
object BoardExpansion {

    private val _pending = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Station id → the state the user just asked for. Empty when nothing waits. */
    val pending: StateFlow<Map<String, Boolean>> = _pending.asStateFlow()

    /** The user chose [expanded] for [stationId] on its settings screen. */
    fun request(stationId: String, expanded: Boolean) {
        val id = stationId.trim()
        if (id.isEmpty()) return
        _pending.value = _pending.value + (id to expanded)
    }

    /** Drop the requests that have been applied to the live session state. */
    fun consume(stationIds: Collection<String>) {
        if (stationIds.isEmpty()) return
        _pending.value = _pending.value - stationIds.toSet()
    }

    /**
     * Forget everything pending.
     *
     * For sign-out, where the board list is about to be replaced wholesale and a
     * request naming the previous account's station would apply to whatever
     * happens to share its id.
     */
    fun clear() {
        _pending.value = emptyMap()
    }
}
