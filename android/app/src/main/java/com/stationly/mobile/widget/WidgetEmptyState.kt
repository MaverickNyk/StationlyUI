package com.stationly.mobile.widget

/**
 * Why a widget has no board to draw, and what a tap on it should do about it.
 *
 * ## Same four states as iOS, not the same words
 * The STATES match iOS's `WidgetStateCopy` and the server keys are shared
 * (`widget.state.<state>.title|detail`), so one served document describes both.
 * The WORDS do not have to match. On iOS a widget cannot act, so it has to
 * describe a gesture ("Touch and hold, then tap Edit Widget"). On Android the
 * widget itself can take the user where the fix is, so every state is a short
 * "Tap to ..." and [tapTarget] is the place that tap lands. The backend serves
 * the Android wording when the client says `android` (`sduiService.ts`).
 *
 * The defaults here are the compiled fallbacks for a first launch with no
 * cached config. Each detail fits the widget's 30-character budget.
 *
 * ## The copy and the tap cannot disagree
 * A state owns its tap target. "Tap to sign in" that opened a station picker,
 * or "Tap to choose one" that opened a home screen with nothing to choose,
 * would be an instruction the destination cannot carry out.
 */
enum class WidgetEmptyState(
    private val key: String,
    val defaultTitle: String,
    val defaultDetail: String,
    val tapTarget: WidgetTapTarget,
) {
    /** Nobody is signed in. The app is where you sign in. */
    SIGNED_OUT("signed_out", "Signed out", "Tap to sign in", WidgetTapTarget.APP),

    /** Signed in with no stations. The app is where you add one. */
    NO_STATIONS("no_stations", "No stations yet", "Tap to add your first station", WidgetTapTarget.APP),

    /** The account has stations; this widget has not been told which. */
    NEEDS_STATION("needs_station", "Choose a station", "Tap to choose one", WidgetTapTarget.CONFIGURE),

    /** This widget was bound to a station the user has since removed. */
    REMOVED("removed", "Station removed", "Tap to choose another", WidgetTapTarget.CONFIGURE);

    fun title(strings: Map<String, String>): String =
        strings["widget.state.$key.title"]?.takeIf { it.isNotBlank() } ?: defaultTitle

    fun detail(strings: Map<String, String>): String =
        strings["widget.state.$key.detail"]?.takeIf { it.isNotBlank() } ?: defaultDetail

    companion object {
        /**
         * The one state that applies, most fundamental first: you cannot choose
         * a station while signed out, and cannot have removed one you never had.
         */
        fun resolve(signedIn: Boolean, hasAnyBoard: Boolean, wasBound: Boolean): WidgetEmptyState = when {
            !signedIn -> SIGNED_OUT
            !hasAnyBoard -> NO_STATIONS
            wasBound -> REMOVED
            else -> NEEDS_STATION
        }
    }
}
