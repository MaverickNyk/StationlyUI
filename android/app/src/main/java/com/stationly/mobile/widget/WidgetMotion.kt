package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.stationly.mobile.R

/**
 * Which animation a widget redraw should play, and who is allowed to ask for one.
 *
 * ## Motion means "you did that"
 * A departure board on a home screen is read at a glance, in passing, usually
 * while walking. Anything that moves on it pulls the eye, so movement has to be
 * worth the interruption — and the only thing that is, is the user's own press.
 *
 * Everything else redraws this widget without moving a pixel: an FCM push
 * landing, the minute tick that keeps the ETAs honest, another widget being
 * refreshed, the board list syncing from another device. There are a lot of
 * those. One refresh tap measured **132 redraws** across four placed widgets,
 * and when every redraw animated, the result was what the owner described as
 * "multiple flashes ... like zoo zoo zoo". The fix is not fewer redraws (though
 * they were scoped too); it is that a redraw is silent unless somebody asked
 * for it.
 *
 * iOS draws the same line with three timestamps in `WidgetViews.swift`
 * (`boardTransition`, `refreshFlip`). Android gets it from which update path
 * the redraw arrived on, plus the one-shot flag below for the case that path
 * cannot carry.
 *
 * ## Why a motion is a FLIPPER and not a parameter
 * `ViewFlipper.setInAnimation` is not a `@RemotableViewMethod`, so a flipper's
 * animation pair is fixed at inflate time and an update cannot choose one. The
 * layout therefore stacks three flippers in one slot, each with its own pair,
 * and exactly one is ever VISIBLE. Choosing the motion IS choosing the flipper,
 * which is why [flipperId] is the enum's whole payload.
 */
enum class WidgetMotion(val flipperId: Int) {

    /**
     * Redraw in place. The resting flipper, shown with a single child and never
     * told to change child, so nothing animates.
     *
     * It shares [NEXT]'s flipper deliberately: a board at rest has to live
     * somewhere, and putting it in the one a forward step uses means the common
     * step needs no visibility change at all.
     */
    NONE(R.id.platform_flipper),

    /** The right chevron. Slides in from the right. */
    NEXT(R.id.platform_flipper),

    /** The left chevron. Slides in from the left. */
    PREV(R.id.platform_flipper_prev),

    /**
     * The refresh button, and only ever a press of it. Rises from the bottom.
     *
     * ## Why the animations are short, measured rather than chosen
     * A redraw REBUILDS the stage, which destroys the views an animation is
     * playing on. So any redraw landing mid-flight cuts the motion off.
     *
     * The gap is measured: a refresh produces two redraws of the tapped widget,
     * the armed one and an ambient one about **1.4 seconds** later (the config
     * broadcast and `notifyPredictions` are the same signal arriving twice). At
     * 230ms the flip has been over for more than a second by then, with six
     * times the margin it needs.
     *
     * It is a real constraint and not a theoretical one: stretched to six
     * seconds to photograph it on the device, the second redraw visibly cut the
     * flip short. Anyone lengthening these animations has to close that gap
     * first.
     */
    REFRESH(R.id.platform_flipper_refresh),
    ;

    /** Whether this redraw should move anything. */
    val animates: Boolean get() = this != NONE

    companion object {

        /** A chevron's delta to the direction it means. */
        fun step(delta: Int): WidgetMotion = if (delta < 0) PREV else NEXT

        /** Every flipper in the stack, so an update can hide the ones it is not using. */
        val flippers: List<Int> = listOf(
            R.id.platform_flipper,
            R.id.platform_flipper_prev,
            R.id.platform_flipper_refresh,
        ).distinct()

        /**
         * How long an armed refresh stays armed.
         *
         * A tap arms the flip and the redraw that follows the fetch spends it.
         * If that redraw never comes — no network, the fetch threw, the process
         * was killed — the flag would otherwise sit there and spend itself on
         * whatever ambient push happened to land next, which is a board
         * flipping for no reason minutes after the button was pressed.
         *
         * Matched to [DepartureWidgetProvider.REFRESH_IN_FLIGHT_CEILING_MS]: the
         * window in which a tap is still considered to be "in flight" is exactly
         * the window in which its animation is still owed.
         */
        const val ARMED_FOR_MS: Long = DepartureWidgetProvider.REFRESH_IN_FLIGHT_CEILING_MS

        /** `refresh_motion_<appWidgetId>` -> the millis the button was pressed. */
        internal const val KEY_PREFIX = "refresh_motion_"

        internal fun keyFor(appWidgetId: Int) = "$KEY_PREFIX$appWidgetId"

        /**
         * Whether a refresh armed at [armedAt] is still owed an animation at [now].
         *
         * A stamp in the FUTURE is a clock that moved (a timezone change, an NTP
         * correction, the user setting the date) and is treated as expired
         * rather than as arming the flip forever. Same rule, same reason, as
         * [DepartureWidgetProvider.mayRefresh].
         */
        fun isArmed(armedAt: Long, now: Long): Boolean =
            armedAt in 1..now && now - armedAt <= ARMED_FOR_MS

        private fun prefs(context: Context) =
            context.getSharedPreferences(WidgetBindingStore.PREFS, Context.MODE_PRIVATE)

        /**
         * The refresh button was pressed on this widget: the next redraw of it
         * may flip.
         *
         * An INVALID id means a programmatic refresh with no widget behind it,
         * which is nobody's press and arms nothing.
         */
        fun armRefresh(context: Context, appWidgetId: Int, now: Long = System.currentTimeMillis()) {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            prefs(context).edit().putLong(keyFor(appWidgetId), now).apply()
        }

        /**
         * Spend the flag if it is there: [REFRESH] once, [NONE] every time after.
         *
         * One-shot, because a refresh fans out several redraws — one per stop the
         * board covers, plus the pushes the backend answers with — and all but
         * the first of those are the same press arriving again. Flipping on each
         * of them is the flashing this exists to stop.
         */
        fun consumeRefresh(
            context: Context,
            appWidgetId: Int,
            now: Long = System.currentTimeMillis(),
        ): WidgetMotion {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return NONE
            val p = prefs(context)
            val armedAt = p.getLong(keyFor(appWidgetId), 0L)
            if (armedAt == 0L) return NONE
            p.edit().remove(keyFor(appWidgetId)).apply()
            return if (isArmed(armedAt, now)) REFRESH else NONE
        }

        /** Called from `onDeleted`, beside the page and the binding. */
        fun forget(context: Context, appWidgetIds: IntArray) {
            if (appWidgetIds.isEmpty()) return
            prefs(context).edit().apply {
                appWidgetIds.forEach { remove(keyFor(it)) }
            }.apply()
        }
    }
}
