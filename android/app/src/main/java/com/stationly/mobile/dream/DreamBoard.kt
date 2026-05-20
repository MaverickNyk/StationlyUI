package com.stationly.mobile.dream

import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.LegacyRow
import com.stationly.mobile.R

/**
 * The Dream's departure board. We inflate the SAME `widget_departure_board.xml`
 * the home-screen widget uses and feed it via [GlobalBoardProcessor.prepareLegacyRows],
 * the same path the in-app Board uses. Result: identical dot-matrix background,
 * identical TfL-amber font, identical marquee status row — pixel-perfect parity
 * with what the user sees on their home screen.
 *
 * Differences from the in-app Board:
 *   - The widget's header row (with station name) is SHOWN — the dream has no
 *     Compose station-name strip above it.
 *   - The refresh/settings buttons are HIDDEN — dreams are non-interactive
 *     (touch exits the dream).
 *   - The status row is always visible with marquee.
 */
@Composable
fun DreamBoard(
    snapshot: DreamSnapshot,
    modifier: Modifier = Modifier,
    /**
     * Multiplier applied to every TextView's pixel size after the widget XML
     * is inflated. The widget is sized for a tiny home-screen tile (15sp rows);
     * on a fullscreen dream we scale up so the text reads from across the room.
     */
    textScale: Float = 1.75f,
    /**
     * Show the station-name header strip. The widget shows it; in the dream we
     * sometimes draw a Compose station header above the board instead.
     */
    showHeader: Boolean = false,
) {
    val sel = snapshot.selection
    val predictions = snapshot.predictions
    val lineStatus = snapshot.lineStatus
    val lastUpdated = snapshot.lastUpdatedMs

    // Stable lambda — only recreated when data changes, so AndroidView.update()
    // doesn't fire on every recomposition (which would reset Chronometer + marquee).
    val updater: (View) -> Unit = remember(sel, predictions, lineStatus, lastUpdated, textScale, showHeader) {
        update@{ view ->
            val context = view.context

            // Hide buttons — dream is non-interactive.
            view.findViewById<View>(R.id.btn_settings).visibility = View.GONE
            view.findViewById<View>(R.id.btn_refresh).visibility = View.GONE

            // Hide the widget's own clock — the dream already shows a big clock
            // on the side, so this would be a redundant tiny duplicate.
            view.findViewById<View>(R.id.clock)?.visibility = View.GONE

            // Either show the widget's own station-name header strip, or hide it
            // and rely on the Compose layer drawn above the board.
            view.findViewById<View>(R.id.header_row)?.visibility =
                if (showHeader) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.line_name).text =
                sel?.stationName ?: "Stationly"

            // "X ago" timer. Compute base from real last-updated wall-time so the
            // counter is honest even on the first paint of the dream.
            val chrono = view.findViewById<Chronometer>(R.id.last_updated_timer)
            chrono.visibility = View.VISIBLE
            chrono.format = "%s ago"
            chrono.stop()
            chrono.base = SystemClock.elapsedRealtime() -
                (System.currentTimeMillis() - lastUpdated).coerceAtLeast(0L)
            chrono.start()

            // Status row + marquee.
            val statusContainer = view.findViewById<View>(R.id.status_container)
            val severityText = view.findViewById<TextView>(R.id.status_severity)
            val reasonText = view.findViewById<TextView>(R.id.status_reason)
            statusContainer.visibility = View.VISIBLE
            val severity = lineStatus?.statusSeverityDescription?.takeIf { it.isNotBlank() }
                ?: "Good Service"
            val reason = lineStatus?.reason?.takeIf { it.isNotBlank() } ?: ""
            severityText.text = severity
            // Only reset marquee scroll when the text changes — otherwise a refresh
            // tick during a marquee cycle would yank it back to the start.
            if (reasonText.text.toString() != reason) {
                reasonText.text = reason
                reasonText.isSelected = false
                reasonText.post { reasonText.isSelected = true }
            }
            // Force the status row to the uniform baseline so it doesn't read
            // smaller than the departure rows. (Widget XML puts it at 13sp,
            // departure rows at 15sp — looks visually inconsistent at dream
            // sizes.)
            severityText.setBaselineSp(ROW_BASE_SP)
            reasonText.setBaselineSp(ROW_BASE_SP)
            view.findViewById<Chronometer>(R.id.last_updated_timer)
                ?.setBaselineSp(ROW_BASE_SP * 0.8f)

            // Waiting / "wrangling pigeons" container — we never use it on the dream.
            view.findViewById<View>(R.id.waiting_container).visibility = View.GONE

            // Rows. The widget XML declares the container as `0dp + weight=1`
            // — that only works when the parent has a fixed height. We wrap it
            // in a ScrollView so the user can scroll when their station has
            // more rows than the dream's viewport can show (multi-platform).
            // The vertical scrollbar is visible; the dream service has
            // isInteractive=true so the user can actually drag it.
            val rowsContainer = view.findViewById<LinearLayout>(R.id.rows_container)
            val rowsScroll = ensureScrollWrapped(rowsContainer)

            val legacyRows = if (sel == null) {
                listOf<LegacyRow>(LegacyRow.Header("Add a board on the home screen"))
            } else {
                GlobalBoardProcessor.prepareLegacyRows(
                    predictions = predictions,
                    lineName = sel.line,
                    hasSelection = true,
                    lineStatusSeverity = lineStatus?.statusSeverityDescription,
                    lineStatusReason = lineStatus?.reason?.takeIf { it.isNotBlank() },
                    currentHour = java.time.LocalTime.now().hour,
                )
            }

            // Diff-update strategy: when the row count and per-index type
            // matches what's already on screen, mutate text in place rather
            // than tearing down and re-inflating. Tearing down on every FCM
            // tick resets the ScrollView's scroll position and breaks the
            // user's drag mid-gesture — what felt like "have to pull pull
            // pull to scroll one row" was the scroll snapping back to 0 every
            // time fresh data landed.
            val canDiffUpdate = rowsContainer.childCount == legacyRows.size &&
                legacyRows.withIndex().all { (i, row) ->
                    val child = rowsContainer.getChildAt(i)
                    when (row) {
                        is LegacyRow.Header, is LegacyRow.Message -> child is TextView
                        is LegacyRow.Departure -> child is LinearLayout
                    }
                }

            if (canDiffUpdate) {
                legacyRows.forEachIndexed { i, row ->
                    val child = rowsContainer.getChildAt(i)
                    when (row) {
                        is LegacyRow.Header  -> (child as TextView).text = row.title
                        is LegacyRow.Message -> (child as TextView).text = row.text
                        is LegacyRow.Departure -> {
                            child.findViewById<TextView>(R.id.destination_text).text = row.destination
                            child.findViewById<TextView>(R.id.eta_text).text = row.eta
                        }
                    }
                }
            } else {
                rowsContainer.removeAllViews()
                val inflater = LayoutInflater.from(context)
                legacyRows.forEach { row ->
                    when (row) {
                        is LegacyRow.Header -> {
                            val header = inflater.inflate(
                                R.layout.widget_platform_header, rowsContainer, false
                            )
                            header.findViewById<TextView>(R.id.platform_name).apply {
                                text = row.title
                                setBaselineSp(ROW_BASE_SP)
                            }
                            rowsContainer.addView(header)
                        }
                        is LegacyRow.Departure -> {
                            val dep = inflater.inflate(
                                R.layout.widget_departure_row, rowsContainer, false
                            )
                            dep.findViewById<TextView>(R.id.destination_text).apply {
                                text = row.destination
                                setBaselineSp(ROW_BASE_SP)
                            }
                            dep.findViewById<TextView>(R.id.eta_text).apply {
                                text = row.eta
                                setBaselineSp(ROW_BASE_SP)
                            }
                            rowsContainer.addView(dep)
                        }
                        is LegacyRow.Message -> {
                            val header = inflater.inflate(
                                R.layout.widget_platform_header, rowsContainer, false
                            )
                            header.findViewById<TextView>(R.id.platform_name).apply {
                                text = row.text
                                setBaselineSp(ROW_BASE_SP)
                            }
                            rowsContainer.addView(header)
                        }
                    }
                }
            }

            // Scale every text view to dream-sized fonts. The widget XML is
            // calibrated for a tiny home-screen tile, so without this the dream
            // looks like a postage stamp on a 14" panel. (Idempotent thanks to
            // the cached baseline on each TextView's tag.)
            scaleAllText(view, textScale)

            // Tell the system the ScrollView's surface is reserved for app
            // touch — not for status-bar pull / back-edge swipes. Re-applied
            // on every update so a relayout (rotation, font change) refreshes
            // the rect. API 29+; older devices silently no-op.
            applyGestureExclusion(rowsScroll)
        }
    }

    val amber = colorResource(R.color.tfl_amber)
    // Wrap the inflated widget in a soft rounded Compose surface — the widget's
    // own backgrounds are square-cornered (designed for the home-screen tile).
    // Inside the dream a gentle 18dp radius + faint amber border reads much
    // more elegantly than hard pixel edges.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF050505),
        border = BorderStroke(1.dp, amber.copy(alpha = 0.18f)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().padding(6.dp),
            factory = { context ->
                LayoutInflater.from(context).inflate(
                    R.layout.widget_departure_board, null, false
                ) as LinearLayout
            },
            update = updater,
            onRelease = { view ->
                view.findViewById<Chronometer>(R.id.last_updated_timer)?.stop()
            }
        )
    }
}

/**
 * Drop-in wrap: replaces the widget's plain rows_container LinearLayout with a
 * ScrollView containing the same LinearLayout. The user drags it directly —
 * isInteractive=true on the dream service makes that work. The scrollbar is
 * visible (only when content overflows, per Android's default) so people know
 * there's more.
 *
 * Safe to call repeatedly — only wraps the first time.
 */
private fun ensureScrollWrapped(rowsContainer: LinearLayout): ScrollView {
    val existingParent = rowsContainer.parent
    if (existingParent is ScrollView) return existingParent

    val parent = existingParent as ViewGroup
    val idx = parent.indexOfChild(rowsContainer)
    val originalParams = rowsContainer.layoutParams as LinearLayout.LayoutParams
    parent.removeView(rowsContainer)

    val scroll = ScrollView(rowsContainer.context).apply {
        // 0dp + weight=1 is the idiomatic LinearLayout pattern for weighted
        // height — the same pattern the widget XML's `rows_list` uses to
        // claim the leftover vertical space inside the board card.
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
        ).apply {
            weight = originalParams.weight.takeIf { it > 0f } ?: 1f
        }
        // Match the widget's rows_list scrollbar styling — thin (3dp), amber
        // tapered gradient thumb (drawable/scrollbar_thumb.xml), fades after
        // 600ms idle then over 1500ms. Same affordance the user sees on the
        // home-screen widget.
        isVerticalScrollBarEnabled = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        isScrollbarFadingEnabled = true
        scrollBarFadeDuration = 1500
        scrollBarDefaultDelayBeforeFade = 600
        scrollBarSize = (3f * resources.displayMetrics.density).toInt()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            verticalScrollbarThumbDrawable =
                androidx.core.content.ContextCompat.getDrawable(context, R.drawable.scrollbar_thumb)
        }
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        isFillViewport = false
    }
    parent.addView(scroll, idx)
    scroll.addView(
        rowsContainer,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
    // Inside the scroll the LinearLayout must be wrap_content, not 0+weight.
    (rowsContainer.layoutParams as ViewGroup.LayoutParams).height =
        ViewGroup.LayoutParams.WRAP_CONTENT
    return scroll
}

/**
 * Claim the ScrollView's surface as an app-gesture zone so the system stops
 * intercepting scrolls that start near the top edge (where the status-bar
 * pull-down normally lives). API 29+ caps total exclusion area at 200dp per
 * edge but that's enough to cover the typical board height. We post the
 * call so we measure with the final laid-out bounds.
 */
private fun applyGestureExclusion(scroll: android.widget.ScrollView) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
    scroll.post {
        if (scroll.width <= 0 || scroll.height <= 0) return@post
        scroll.systemGestureExclusionRects =
            listOf(android.graphics.Rect(0, 0, scroll.width, scroll.height))
    }
}

/**
 * Uniform baseline for every row inside the inflated widget board. Departure
 * destinations, ETAs, the platform header, and the status row are all forced
 * to this same SP value before [scaleAllText] applies the dream-wide
 * multiplier — so the user sees one consistent row size instead of the
 * widget's tiered 13sp/15sp hierarchy (which looked uneven at dream scale).
 */
private const val ROW_BASE_SP = 14f

/**
 * Set a fresh baseline text size in SP, *clearing* the cached-original tag so
 * [scaleAllText] re-baselines on the next call. Without clearing the tag,
 * scaleAllText would keep applying the multiplier to whatever value it cached
 * the first time — which might predate our baseline override.
 */
private fun TextView.setBaselineSp(sp: Float) {
    tag = null
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
}

/**
 * Recursively scale every TextView's text size to `original * scale`.
 *
 * Crucially this is **idempotent**: we cache each view's original (XML-declared)
 * pixel size on the view's tag the first time we touch it, and always scale from
 * that cached baseline. Without this the AndroidView's `update` lambda fires on
 * every refresh tick, scaling the text again on top of an already-scaled size —
 * status text doubles, triples, then explodes. (Bug spotted in the dream:
 * status row size grew on every FCM tick.)
 */
private fun scaleAllText(root: View, scale: Float) {
    if (root is TextView) {
        val original = (root.tag as? Float) ?: root.textSize.also { root.tag = it }
        root.setTextSize(TypedValue.COMPLEX_UNIT_PX, original * scale)
    }
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            scaleAllText(root.getChildAt(i), scale)
        }
    }
}
