package com.stationly.core.util

import kotlinx.datetime.LocalTime

/**
 * iOS / Compose-Multiplatform port of `android/.../ui/util/BoardFallbackState.kt`.
 *
 * The "we have no current departures to show" message system. Android's copy
 * lives in the android module (java.time + Android `View`/`RemoteViews`
 * rendering); this is a faithful commonMain port (kotlinx.datetime + Compose
 * rendering in `Board.kt`). One state machine, one copy table — so the iOS home
 * board surfaces the SAME empty-state messages Android does:
 *
 *   Offline · Live updates paused · Service ended for tonight ·
 *   Service starting soon · Nothing departing right now · Service disrupted
 *
 * Before this port, the iOS board fell through to
 * `GlobalBoardProcessor.buildPlaceholderRows` (the older "funny message"
 * placeholder copy) for the empty case, while Android always overrode that with
 * this newer state machine — a visible parity divergence on every empty board.
 *
 * Copy + thresholds are SDUI-driven via the backend `homeConfig`
 * (`board.fallback.*` keys) with baked-in defaults, kept in lockstep with the
 * Android file. Detection is client-side because it depends on real-time signals
 * (connectivity, FCM age, current London time).
 *
 * ## ⚠️ Android has NOT taken the 2026-08-17 ordering fix
 * `android/.../ui/util/BoardFallbackState.kt` still tests SIGNAL_LOST above the
 * closed-network windows, so an Android board whose app has been shut all night
 * still says "Live updates paused · Last refresh 5h ago" at 04:00 where this one
 * now says "Service ended for tonight". See the block comment on that branch
 * below for why the new order is the right one.
 *
 * Left divergent deliberately rather than silently: the fix was found on the iOS
 * widget, this work is iOS-only, and an unannounced edit to the Android board's
 * empty state is not something to slip into it. **The change is a straight port
 * of the branch below** — move the SIGNAL_LOST test underneath the time windows,
 * nothing else moves.
 *
 * ## Why this lives in `core` and not next to the board that draws it
 * It was `composeApp/.../ui/util/BoardFallback.kt`, which was right while the
 * only reader was a Compose surface. The **iOS widget extension** is now a
 * reader too, and it cannot link this module at all — it reads the App Group and
 * nothing else (`docs/IOS_WIDGET_DESIGN.md` §7). So `IosWidgetManager` resolves
 * every kind here and publishes the finished table for the widget to render, and
 * that write path lives in `core`.
 *
 * The alternative was a second copy of these strings in Swift, and the widget
 * had already paid for exactly that: it showed one hardcoded "No departures
 * right now" for every one of these situations, so at 02:00 the home board said
 * "Service ended for tonight" and the widget for the same station disagreed.
 *
 * `TimeWindow.kt` moved here ahead of it for the same reason, and there is now a
 * rule worth stating: **the wording is decided here, once.** A surface may
 * decide WHICH kind applies — that needs a clock, a network state and a render
 * time, which only the surface has — but never what it says.
 */

enum class BoardFallbackKind {
    OFFLINE,
    SIGNAL_LOST,
    DISRUPTED,
    LATE_NIGHT,
    EARLY_MORNING,
    NO_UPCOMING,
    CONNECTING,
}

data class BoardFallbackState(
    val kind: BoardFallbackKind,
    val ageMinutes: Long = 0L,
    /** Real TfL severity (e.g. "Part Closure"), only set for DISRUPTED. */
    val statusSeverity: String? = null,
    /** Real TfL reason (e.g. "Engineering works"), only set for DISRUPTED. */
    val statusReason: String? = null,
)

/**
 * Rows the board always renders in a fallback state (title + details padded to
 * this), so the dot-matrix card doesn't visibly shrink vs a populated board.
 * Matches Android's "1 platform block = header + 3 dep rows" baseline.
 */
const val BOARD_FALLBACK_ROW_COUNT: Int = 4

object BoardFallbackDefaults {
    const val SIGNAL_LOST_MIN: Long = 6
    val LATE_NIGHT_START: LocalTime  = LocalTime(0, 0)
    val LATE_NIGHT_END: LocalTime    = LocalTime(4, 30)
    val EARLY_MORNING_END: LocalTime = LocalTime(6, 0)

    const val OFFLINE_TITLE         = "Offline"
    const val OFFLINE_DETAIL        = "Catching up when you're back"
    const val SIGNAL_LOST_TITLE     = "Live updates paused"
    const val SIGNAL_LOST_DETAIL    = "Last refresh {age} ago"
    const val LATE_NIGHT_TITLE      = "Service ended for tonight"
    const val LATE_NIGHT_DETAIL     = "Back in the morning"
    const val EARLY_MORNING_TITLE   = "Service starting soon"
    const val EARLY_MORNING_DETAIL  = "First departures incoming"
    const val NO_UPCOMING_TITLE     = "Nothing departing right now"
    const val NO_UPCOMING_DETAIL    = "Watching for the next one"
    const val CONNECTING_TITLE      = "Connecting"
    const val CONNECTING_DETAIL     = "Live data starting up"

    // DISRUPTED title is normally the live TfL severity; this default only fills
    // in when severity is blank. Detail splits across two rows via `\n`.
    const val DISRUPTED_TITLE       = "Service disrupted"
    const val DISRUPTED_DETAIL      = "No departures expected here\nWe'll update as things change"
}

/**
 * @param hasPredictions board has at least one upcoming row to render
 * @param isOnline device/backend reachability (iOS uses `!isBackendOffline`)
 * @param lastUpdatedMs wall-clock millis of last FCM/REST sync; 0 = never
 * @param nowMs current wall-clock millis (from [rememberMinuteTick])
 * @param londonTime current time in Europe/London
 * @return the message to surface, or null when predictions exist and the board
 *         should render normally.
 */
fun computeBoardFallbackState(
    hasPredictions: Boolean,
    isOnline: Boolean,
    lastUpdatedMs: Long,
    nowMs: Long,
    londonTime: LocalTime,
    lineStatusSeverity: String? = null,
    lineStatusReason: String? = null,
    signalLostMin: Long = BoardFallbackDefaults.SIGNAL_LOST_MIN,
    lateNightStart: LocalTime = BoardFallbackDefaults.LATE_NIGHT_START,
    lateNightEnd: LocalTime = BoardFallbackDefaults.LATE_NIGHT_END,
    earlyMorningEnd: LocalTime = BoardFallbackDefaults.EARLY_MORNING_END,
): BoardFallbackState? {
    if (hasPredictions) return null
    if (!isOnline) return BoardFallbackState(BoardFallbackKind.OFFLINE)

    // Status-aware empty board: a non-good-service status is almost certainly
    // WHY there are no predictions — surface the real reason. Wins over the
    // time-of-day buckets (an all-day closure is more specific than "ended").
    val severity = lineStatusSeverity?.trim().orEmpty()
    val isDisrupted = severity.isNotEmpty() && !severity.lowercase().startsWith("good service")
    if (isDisrupted) {
        return BoardFallbackState(
            kind = BoardFallbackKind.DISRUPTED,
            statusSeverity = severity,
            statusReason = lineStatusReason?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    // ── ⚠️ The closed-network windows come BEFORE staleness, and that ordering
    //    is a bug fix (2026-08-17), reported off the widget ──
    //
    // SIGNAL_LOST used to be tested here, above these two, and it made the
    // board blame itself for behaving correctly. After the last train there is
    // nothing to fetch, so nothing fetches: the app is closed, the stream has
    // nothing to push, and the widget's own schedule backs off overnight by
    // design. Five hours later the last sync is five hours old — which is TRUE,
    // and reported as "Live updates paused · Last refresh 5h ago", which reads
    // as a broken widget.
    //
    // Owner's report, and it is the clearest statement of the rule:
    //
    //     "when the service ended for night the text also says the widget
    //      hasn't updated since last update ... but the fact is we did check
    //      with the backend so our update is recent but the trains are not
    //      there"
    //
    // The timer itself is honest and stays as it is — it measures the last
    // CHECK, not the last train (`SqlStorage.saveSyncTimestamp` stamps every
    // sync including a 0-row one, and the widget's REST path writes `now`
    // unconditionally in `writeBack`). What was wrong is that a gap with a
    // known, correct cause was being described as a fault.
    //
    // So: while the network is closed, how recently we looked is not the story.
    // Outside these windows nothing changes — a stale board at 14:00 still says
    // "Live updates paused", because then it genuinely is one.
    val closed = when {
        inTimeWindow(londonTime, lateNightStart, lateNightEnd) -> BoardFallbackKind.LATE_NIGHT
        inTimeWindow(londonTime, lateNightEnd, earlyMorningEnd) -> BoardFallbackKind.EARLY_MORNING
        else -> null
    }
    if (closed != null) return BoardFallbackState(closed)

    // SIGNAL_LOST only when we actually KNOW how old the last sync is. When
    // `lastUpdatedMs == 0` (never synced) we can't honestly say "Last refresh
    // N min ago" — fall through to NO_UPCOMING.
    if (lastUpdatedMs > 0L) {
        val ageMin = ((nowMs - lastUpdatedMs) / 60_000L).coerceAtLeast(0L)
        if (ageMin >= signalLostMin) return BoardFallbackState(BoardFallbackKind.SIGNAL_LOST, ageMin)
    }

    return BoardFallbackState(BoardFallbackKind.NO_UPCOMING)
}

/** Resolved fallback copy: a bold title + 0..n normal-weight detail lines. */
data class BoardFallbackCopy(val title: String, val detailLines: List<String>)

/**
 * Resolve the SDUI/default copy for [state]. Mirrors the Android `resolveCopy`:
 * DISRUPTED title = the live TfL severity (the "what"); detail points at the
 * fuller reason carried by the disruption banner + status strip.
 */
fun resolveBoardFallbackCopy(
    state: BoardFallbackState,
    strings: Map<String, String>,
    /**
     * Whether to substitute `{age}` in the detail with [BoardFallbackState.ageMinutes].
     *
     * ## ⚠️ Pass `false` when resolving a TABLE rather than a live state
     * The iOS widget publisher resolves every kind ahead of time, with no age to
     * put in, so `ageMinutes` is 0 and substituting yields `formatAge(0)` —
     * turning "Last refresh {age} ago" into **"Last refresh just now ago"** and
     * destroying the placeholder permanently. The renderer on the far side then
     * finds no `{age}`, substitutes nothing, and shows that sentence at every
     * age forever.
     *
     * Silent, and only visible in a state that needs a stale board to reproduce.
     * Found in review before it shipped; do not remove the parameter without
     * re-reading `IosWidgetManager.publishFallbackCopy`.
     */
    substituteAge: Boolean = true,
): BoardFallbackCopy {
    if (state.kind == BoardFallbackKind.DISRUPTED) {
        val title = state.statusSeverity?.takeIf { it.isNotBlank() }
            ?: strings["board.fallback.disrupted.title"]
            ?: BoardFallbackDefaults.DISRUPTED_TITLE
        val detail = strings["board.fallback.disrupted.detail"] ?: BoardFallbackDefaults.DISRUPTED_DETAIL
        return BoardFallbackCopy(title, detail.splitLines())
    }

    val (titleKey, detailKey, titleDefault, detailDefault) = when (state.kind) {
        BoardFallbackKind.OFFLINE -> Quad(
            "board.fallback.offline.title", "board.fallback.offline.detail",
            BoardFallbackDefaults.OFFLINE_TITLE, BoardFallbackDefaults.OFFLINE_DETAIL,
        )
        BoardFallbackKind.SIGNAL_LOST -> Quad(
            "board.fallback.signal_lost.title", "board.fallback.signal_lost.detail",
            BoardFallbackDefaults.SIGNAL_LOST_TITLE, BoardFallbackDefaults.SIGNAL_LOST_DETAIL,
        )
        BoardFallbackKind.LATE_NIGHT -> Quad(
            "board.fallback.late_night.title", "board.fallback.late_night.detail",
            BoardFallbackDefaults.LATE_NIGHT_TITLE, BoardFallbackDefaults.LATE_NIGHT_DETAIL,
        )
        BoardFallbackKind.EARLY_MORNING -> Quad(
            "board.fallback.early_morning.title", "board.fallback.early_morning.detail",
            BoardFallbackDefaults.EARLY_MORNING_TITLE, BoardFallbackDefaults.EARLY_MORNING_DETAIL,
        )
        BoardFallbackKind.NO_UPCOMING -> Quad(
            "board.fallback.no_upcoming.title", "board.fallback.no_upcoming.detail",
            BoardFallbackDefaults.NO_UPCOMING_TITLE, BoardFallbackDefaults.NO_UPCOMING_DETAIL,
        )
        BoardFallbackKind.CONNECTING -> Quad(
            "board.fallback.connecting.title", "board.fallback.connecting.detail",
            BoardFallbackDefaults.CONNECTING_TITLE, BoardFallbackDefaults.CONNECTING_DETAIL,
        )
        BoardFallbackKind.DISRUPTED -> error("handled above")
    }
    val title = strings[titleKey] ?: titleDefault
    val raw = strings[detailKey] ?: detailDefault
    val detail = if (substituteAge) raw.replace("{age}", formatAge(state.ageMinutes)) else raw
    return BoardFallbackCopy(title, detail.splitLines())
}

private data class Quad(val a: String, val b: String, val c: String, val d: String)

private fun String.splitLines(): List<String> =
    split('\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun formatAge(minutes: Long): String = when {
    minutes < 1L -> "just now"
    minutes < 60L -> "$minutes min"
    else -> {
        val h = minutes / 60L
        val m = minutes % 60L
        if (m == 0L) "${h}h" else "${h}h ${m}m"
    }
}
