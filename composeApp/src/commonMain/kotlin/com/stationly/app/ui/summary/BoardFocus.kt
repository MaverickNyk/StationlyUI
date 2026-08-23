package com.stationly.app.ui.summary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request to put one station in front of the user, from outside the UI.
 *
 * Two callers, and the difference between them is the whole reason [Target]
 * carries [Target.kind]:
 *
 *  - **A widget tap** ([request]). The user tapped a board on their home screen
 *    and is watching the app arrive; the page SLIDES, because that says the app
 *    went somewhere. Three widgets on three stations used to open the app to
 *    whatever it was last showing, which makes the widget an app launcher rather
 *    than a way into the station it is displaying.
 *  - **Coming back from a screen about one station** ([restore]) — its settings,
 *    or the line picker. The user never left that station as far as they are
 *    concerned, so there is nothing to travel: a slide here would animate a
 *    journey the user did not take, and on a four-station carousel it reads as
 *    the app scrolling away from where they were and back. It must also leave
 *    the board's own state ALONE — see [Kind.RESTORE].
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

    /** Why a station is being brought forward, which decides what may move. */
    enum class Kind {
        /**
         * The user asked to SEE this board — a widget tap. Turn the page with an
         * animation, and open the card if it is closed: they tapped a board and
         * a collapsed card is not one.
         */
        REVEAL,

        /**
         * Put the user back where they were, having been on a screen about this
         * station. Snap, and **change nothing else**.
         *
         * The "change nothing else" is load-bearing. REVEAL's force-expand
         * silently undid the Expanded/Collapsed setting for a whole session: the
         * user chose Collapsed, tapped back, this restored their place, and the
         * expand ran afterwards and reopened the card. Two features added on the
         * same day, each correct alone.
         */
        RESTORE,
    }

    /** One request. [nonce] distinguishes repeats of the same station. */
    data class Target(
        val stationId: String,
        val nonce: Long,
        val kind: Kind = Kind.REVEAL,
    )

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
    fun request(stationId: String) = raise(stationId, Kind.REVEAL)

    /**
     * Put [stationId] back in front without moving anything visibly.
     *
     * Raised on the way OUT of a screen about one station, so returning lands
     * where the user left rather than on the first card. It is not enough to
     * rely on the pager and scroll state surviving: both clamp to zero on any
     * frame where the station list is momentarily empty, which is exactly what a
     * repository re-read on resume produces.
     *
     * Harmless for a station that no longer exists — deleting one is a route out
     * of that screen too. Nothing matches the id, and both layouts already
     * ignore a target they cannot find.
     */
    fun restore(stationId: String) = raise(stationId, Kind.RESTORE)

    private fun raise(stationId: String, kind: Kind) {
        val id = stationId.trim()
        if (id.isEmpty()) return
        issued += 1
        _target.value = Target(id, issued, kind)
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

    /**
     * Forget anything pending.
     *
     * For sign-out. A request names a station id, and ids are TfL naptans shared
     * across accounts — so one left behind would scroll the next account to a
     * station they never asked for, and a [Kind.REVEAL] would open its card too.
     * Symmetric with [BoardExpansion.clear].
     */
    fun clear() {
        _target.value = null
    }
}
