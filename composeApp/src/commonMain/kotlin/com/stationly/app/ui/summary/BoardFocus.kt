package com.stationly.app.ui.summary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request to put one station in front of the user, from outside the UI.
 *
 * Raised when a home-screen widget is tapped: three widgets on three stations
 * used to open the app to whatever it was last showing, which makes the widget
 * an app launcher rather than a way into the station it is displaying.
 *
 * ## Why a singleton and not a navigation argument
 * The app is normally already running when this arrives — the user taps a widget
 * and the app RESUMES. On iOS the Compose host reads its constructor parameters
 * exactly once, in `makeUIViewController`, and `updateUIViewController` is a
 * no-op, so anything threaded through `MainViewController` reaches a cold start
 * and nothing else. A flow the running composition is already collecting reaches
 * both.
 *
 * ## The nonce
 * Tapping the same widget twice must focus that station twice — the user may
 * have swiped away in between. Keyed on the station id alone the second request
 * would be equal to the first, and the effect watching it would never re-run.
 *
 * Single-threaded by construction: raised from the platform's URL callback and
 * consumed from a composition effect, both on the main thread.
 */
object BoardFocus {

    /** One request. [nonce] distinguishes repeats of the same station. */
    data class Target(val stationId: String, val nonce: Long)

    private val _target = MutableStateFlow<Target?>(null)

    /**
     * The station waiting to be shown, or null.
     *
     * Held until a collector [consume]s it rather than cleared on a timer,
     * because the request routinely arrives BEFORE there is anywhere to put it:
     * on a cold start the URL is delivered while the board list is still loading
     * out of SQLite. The screen picks it up whenever it becomes able to.
     */
    val target: StateFlow<Target?> = _target.asStateFlow()

    private var issued = 0L

    /** Ask for [stationId] — a grouping id, the same thing one home card is. */
    fun request(stationId: String) {
        val id = stationId.trim()
        if (id.isEmpty()) return
        issued += 1
        _target.value = Target(id, issued)
    }

    /**
     * Mark [target] handled.
     *
     * `compareAndSet` rather than a plain clear: a newer request may have landed
     * between a collector reading one and finishing with it, and dropping that
     * would lose the tap the user made most recently — the one they are watching
     * for a response to.
     */
    fun consume(target: Target) {
        _target.compareAndSet(target, null)
    }
}
