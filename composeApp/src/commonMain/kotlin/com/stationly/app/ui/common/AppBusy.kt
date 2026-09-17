package com.stationly.app.ui.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Blocking work that OUTLIVES the screen that started it.
 *
 * ## The gap this exists for
 * A [LoadingOverlay] is a composable inside the screen that raises it, so it dies
 * with that screen. That is right for work which finishes where it started —
 * saving a name, signing out — and wrong for work that ENDS IN A NAVIGATION.
 *
 * Deleting a station is the case that showed it. The settings screen covered its
 * own teardown (subscriptions torn down, widget rows cleared, the selection table
 * rewritten) and then popped itself, at which point the overlay went with it and
 * the user watched the home screen assemble: an empty board list for a frame, the
 * remaining cards re-flowing as the deleted one's height came back, the pager
 * clamping to page zero and then scrolling. The work was covered; the arrival was
 * not, and the arrival is the part with the movement in it.
 *
 * A signal held outside the composition covers both halves with one overlay, for
 * the same reason [com.stationly.app.ui.summary.BoardFocus] is a singleton: the
 * screen that raises it is not the screen that resolves it.
 *
 * ## It is cleared by ARRIVING, not by a timer
 * `SummaryScreen` calls [clear] on entry. So the overlay lifts on the first frame
 * of the destination rather than after a guessed duration, and a slow teardown
 * simply stays covered for longer instead of tearing.
 *
 * That also makes it self-limiting. Every caller of [begin] is on its way to the
 * home screen — that is what the signal is FOR — so the one screen that clears it
 * is the one screen every path ends on. There is no state in which it can be left
 * standing.
 *
 * ## Not a general-purpose spinner
 * This is a full-screen modal that eats every gesture. Raise it only for work the
 * user must not be able to interrupt or double-fire, and only when that work ends
 * somewhere else. Anything that resolves on the screen it started on wants a plain
 * [LoadingOverlay] there, and anything non-blocking wants a [StationlySpinner]
 * inline.
 *
 * Single-threaded by construction: raised from a ViewModel or a composition
 * callback on the main thread, cleared from a composition effect on the main
 * thread.
 */
object AppBusy {

    private val _label = MutableStateFlow<String?>(null)

    /** What the app is doing, or null when it is not busy in this sense. */
    val label: StateFlow<String?> = _label.asStateFlow()

    /**
     * Cover the screen and say [label] until the destination is reached.
     *
     * The label is shown to the user, so it names the thing ("Deleting Victoria")
     * rather than the mechanism. A blank one is ignored: an overlay with no
     * explanation is worse than the movement it is hiding.
     */
    fun begin(label: String) {
        val text = label.trim()
        if (text.isEmpty()) return
        _label.value = text
    }

    /** Arrived. Called by the destination, not by whoever raised it. */
    fun clear() {
        _label.value = null
    }
}
