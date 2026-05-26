package com.stationly.mobile.ui.util

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.stationly.mobile.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The "we have no current departures to show" message system, shared by
 * the home Board, the dream Board, and the home-screen widget. One state
 * machine, one copy table, three surfaces — all rendered inside the
 * existing `rows_container` using `widget_departure_row` (the same layout
 * real departures use) so the dot-matrix aesthetic stays consistent. The
 * title row's visual weight comes from a BOLD typeface, not a different
 * layout, to avoid pulling in `widget_platform_header`'s 6dp top margin.
 *
 * Detection happens client-side because it depends on real-time signals
 * (network state, FCM age, current London time). Copy + thresholds come
 * from the backend's `homeConfig` (`board.fallback.*` keys) so we can
 * re-voice or re-tune without a release; baked-in defaults keep the
 * board honest if a fresh fetch never lands.
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
 * How many rows the board's rows_container always renders, even in a
 * fallback state. Matches the "1 platform block = header + 3 dep rows"
 * baseline so the widget / home card / dream don't visibly shrink when
 * we switch from real departures to a message.
 */
const val BOARD_FALLBACK_ROW_COUNT: Int = 4

object BoardFallbackDefaults {
    const val SIGNAL_LOST_MIN: Long = 6
    val LATE_NIGHT_START: LocalTime  = LocalTime.of(0, 0)
    val LATE_NIGHT_END: LocalTime    = LocalTime.of(4, 30)
    val EARLY_MORNING_END: LocalTime = LocalTime.of(6, 0)

    // Copy is kept tight enough to fit one line on a ~260dp widget cell
    // (~30 chars at 15sp). Anything longer would truncate with "..." on
    // the widget's single-line departure rows.
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

    // DISRUPTED title is normally the live TfL severity; this default only
    // fills in when severity is blank. Detail is split across two rows via
    // `\n` so the longer message doesn't have to wrap inside a single row.
    const val DISRUPTED_TITLE       = "Service disrupted"
    const val DISRUPTED_DETAIL      = "No departures expected here\nWe'll update as things change"
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

fun parseHHmm(raw: String?, default: LocalTime): LocalTime =
    raw?.let { runCatching { LocalTime.parse(it, HHMM) }.getOrNull() } ?: default

/**
 * @param hasPredictions board has at least one upcoming row to render
 * @param isOnline device connectivity (read from [NetworkState])
 * @param lastUpdatedMs wall-clock millis of last FCM/REST sync; 0 = never
 * @param nowMs current wall-clock millis (from [rememberMinuteTick] in Compose)
 * @param londonTime current time in Europe/London
 * @return the message to surface, or null when predictions exist and the
 *         board should render normally
 */
fun computeBoardFallbackState(
    hasPredictions: Boolean,
    isOnline: Boolean,
    lastUpdatedMs: Long,
    nowMs: Long,
    londonTime: LocalTime,
    /** Real TfL severity for this line — used to surface DISRUPTED when empty
     *  predictions correlate with a known service problem. */
    lineStatusSeverity: String? = null,
    lineStatusReason: String? = null,
    signalLostMin: Long = BoardFallbackDefaults.SIGNAL_LOST_MIN,
    lateNightStart: LocalTime = BoardFallbackDefaults.LATE_NIGHT_START,
    lateNightEnd: LocalTime = BoardFallbackDefaults.LATE_NIGHT_END,
    earlyMorningEnd: LocalTime = BoardFallbackDefaults.EARLY_MORNING_END,
): BoardFallbackState? {
    if (hasPredictions) return null
    if (!isOnline) return BoardFallbackState(BoardFallbackKind.OFFLINE)

    // SIGNAL_LOST is only meaningful when we actually KNOW how old the
    // last sync is. If `lastUpdatedMs == 0` (SQL empty, never synced)
    // we can't honestly say "Last refresh N min ago" — fall through to
    // disrupted / time-window detection instead. This keeps home / dream
    // / widget consistent: they all reach time-window messaging when SQL
    // is empty, regardless of whether the timestamp source defaulted to
    // `0L` (home) or `System.currentTimeMillis()` (widget, dream).
    if (lastUpdatedMs > 0L) {
        val ageMin = ((nowMs - lastUpdatedMs) / 60_000L).coerceAtLeast(0L)
        if (ageMin >= signalLostMin) return BoardFallbackState(BoardFallbackKind.SIGNAL_LOST, ageMin)
    }

    // Status-aware empty board: if TfL is reporting a non-good-service
    // status, that's almost certainly WHY there are no predictions —
    // surface the real reason instead of a generic "nothing departing
    // right now". Wins over time-of-day buckets because a closure all
    // day is more specific than "ended for tonight".
    val severity = lineStatusSeverity?.trim().orEmpty()
    val isDisrupted = severity.isNotEmpty() && !severity.lowercase().startsWith("good service")
    if (isDisrupted) {
        return BoardFallbackState(
            kind = BoardFallbackKind.DISRUPTED,
            statusSeverity = severity,
            statusReason = lineStatusReason?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    return when {
        inTimeWindow(londonTime, lateNightStart, lateNightEnd) ->
            BoardFallbackState(BoardFallbackKind.LATE_NIGHT)
        inTimeWindow(londonTime, lateNightEnd, earlyMorningEnd) ->
            BoardFallbackState(BoardFallbackKind.EARLY_MORNING)
        else -> BoardFallbackState(BoardFallbackKind.NO_UPCOMING)
    }
}

/** Inclusive start, exclusive end. Supports windows that wrap midnight. */
private fun inTimeWindow(now: LocalTime, start: LocalTime, endExclusive: LocalTime): Boolean =
    if (start <= endExclusive) now >= start && now < endExclusive
    else now >= start || now < endExclusive

private data class Copy(val title: String, val detail: String)

private fun resolveCopy(state: BoardFallbackState, strings: Map<String, String>): Copy {
    // DISRUPTED title = the live TfL severity (the "what"). Detail = a
    // short generic pointer to the full reason elsewhere — on home that's
    // the collapsible disclaimer banner above the board, on the widget
    // it's the status row beneath and the in-app banner once you tap in.
    // We deliberately do NOT pour the verbose TfL reason into the board
    // rows themselves; reasons can be multi-sentence and would balloon
    // the row stack, especially on the widget.
    if (state.kind == BoardFallbackKind.DISRUPTED) {
        val title = state.statusSeverity?.takeIf { it.isNotBlank() }
            ?: strings["board.fallback.disrupted.title"]
            ?: BoardFallbackDefaults.DISRUPTED_TITLE
        val detail = strings["board.fallback.disrupted.detail"]
            ?: BoardFallbackDefaults.DISRUPTED_DETAIL
        return Copy(title, detail)
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
    val detail = (strings[detailKey] ?: detailDefault).replace("{age}", formatAge(state.ageMinutes))
    return Copy(title, detail)
}

private data class Quad(val a: String, val b: String, val c: String, val d: String)

private fun formatAge(minutes: Long): String = when {
    minutes < 1L -> "just now"
    minutes < 60L -> "$minutes min"
    else -> {
        val h = minutes / 60L
        val m = minutes % 60L
        if (m == 0L) "${h}h" else "${h}h ${m}m"
    }
}

/**
 * Fill `rows_container` with the fallback message. Title (bold) + each
 * detail line + filler rows pad to [BOARD_FALLBACK_ROW_COUNT] so the
 * board doesn't visibly shrink. Called from the Compose AndroidView
 * update lambdas on home + dream, AFTER the normal row-rendering
 * branches, so this overrides whatever those branches wrote.
 *
 * No-op when [state] is null — caller's normal rows stay as-is.
 *
 * Surface-agnostic: the dream wraps this call in its own re-baselining
 * + scaleAllText pass to participate in the dream-wide text scale.
 */
fun applyBoardFallbackToRows(
    root: View,
    state: BoardFallbackState?,
    strings: Map<String, String>,
) {
    // Old "waiting_container" path is retired — keep the view hidden
    // unconditionally so any stale visibility from previous renders
    // doesn't leak through.
    root.findViewById<View>(R.id.waiting_container)?.visibility = View.GONE
    if (state == null) return

    val rowsContainer = root.findViewById<LinearLayout>(R.id.rows_container) ?: return
    val copy = resolveCopy(state, strings)
    val inflater = LayoutInflater.from(root.context)

    rowsContainer.removeAllViews()
    rowsContainer.visibility = View.VISIBLE

    // Every fallback row uses widget_departure_row — same row type, same
    // 2dp margin, same 15sp text — so the message reads as a continuous
    // departure block. The title gets its visual weight from BOLD typeface
    // (NOT a different layout) so we don't pull in platform_header's 6dp
    // top margin. Detail rows stay at NORMAL weight so the hierarchy
    // matches what the eye expects (heavier = primary).
    fun addRow(text: String, bold: Boolean) {
        val row = inflater.inflate(R.layout.widget_departure_row, rowsContainer, false)
        val dest = row.findViewById<TextView>(R.id.destination_text)
        dest.text = text
        dest.gravity = android.view.Gravity.CENTER
        dest.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
        row.findViewById<TextView>(R.id.eta_text).text = ""
        rowsContainer.addView(row)
    }

    addRow(copy.title, bold = true)
    // Detail may contain `\n` to spread across multiple rows (used by
    // DISRUPTED to fit a longer message without wrapping).
    for (line in copy.detail.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
        addRow(line, bold = false)
    }
    // Pad with empty rows so the board keeps its size — matches the
    // "1 platform block = 4 rows" baseline.
    while (rowsContainer.childCount < BOARD_FALLBACK_ROW_COUNT) addRow("", bold = false)
}

/**
 * RemoteViews-friendly equivalent: builds the fallback message as a
 * list of `widget_departure_row` RemoteViews so the widget can pass
 * them through its existing `applyRowsToWidget` plumbing (which routes
 * to either `rows_list` on API ≥ 31 or `rows_container` below).
 *
 * Single source of copy — uses the same [resolveCopy] as the View path.
 */
fun buildFallbackRowRemoteViews(
    context: Context,
    state: BoardFallbackState,
    strings: Map<String, String>,
): List<RemoteViews> {
    val copy = resolveCopy(state, strings)
    val packageName = context.packageName
    val rows = mutableListOf<RemoteViews>()

    // Every fallback row uses widget_departure_row — same row type, same
    // 2dp margin, same 15sp text — so the message reads as a continuous
    // departure block. Title visual weight comes from a SpannableString
    // with a BOLD StyleSpan (RemoteViews can't call setTypeface directly).
    // See applyBoardFallbackToRows for the View path equivalent.
    fun addRow(text: String, bold: Boolean) {
        val row = RemoteViews(packageName, R.layout.widget_departure_row)
        val displayText: CharSequence = if (bold && text.isNotEmpty()) {
            SpannableString(text).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else text
        row.setTextViewText(R.id.destination_text, displayText)
        row.setInt(R.id.destination_text, "setGravity", android.view.Gravity.CENTER)
        row.setTextViewText(R.id.eta_text, "")
        rows.add(row)
    }

    addRow(copy.title, bold = true)
    for (line in copy.detail.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
        addRow(line, bold = false)
    }
    while (rows.size < BOARD_FALLBACK_ROW_COUNT) addRow("", bold = false)
    return rows
}
