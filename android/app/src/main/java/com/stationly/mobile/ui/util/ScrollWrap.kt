package com.stationly.mobile.ui.util

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.stationly.mobile.R

/**
 * Wrap a `widget_departure_board.xml` `rows_container` `LinearLayout`
 * in a `ScrollView` so the platform headers + departure rows scroll
 * independently of the station strip / status row / footer that sit
 * around them in the parent `LinearLayout`.
 *
 * Used by every Stationly surface that renders the dot-matrix board:
 *
 *   - Home Board (`ui/summary/components/Board.kt`) — non-scrollable
 *     parent (LazyColumn item) → wrap is the only way to bound the
 *     rows
 *   - Cluster + fullscreen dream (`dream/DreamBoard.kt`) — fullscreen
 *     mode also wants `fillViewport=true` + vertical-centre gravity
 *     so a short list doesn't pin to the top of a tall card; that's
 *     applied separately by the caller after this returns
 *
 * Centralised here so all three surfaces use exactly the same
 * scroll-behaviour, scrollbar styling, and layout-param surgery —
 * previously the home + dream had two near-identical copies.
 *
 * **Idempotent**: safe to call on every update lambda fire. If
 * `rowsContainer` is already inside a `ScrollView` from a previous
 * invocation, returns the existing one without re-parenting.
 *
 * Scrollbar styling (3dp amber thumb that fades after 600ms idle over
 * 1500ms) matches the widget's API ≥ 31 `rows_list` ListView — same
 * affordance the rider sees on every Stationly surface.
 */
fun ensureRowsScrollWrapped(rowsContainer: LinearLayout): ScrollView {
    val existingParent = rowsContainer.parent
    if (existingParent is ScrollView) return existingParent

    val parent = existingParent as ViewGroup
    val idx = parent.indexOfChild(rowsContainer)
    val originalParams = rowsContainer.layoutParams as LinearLayout.LayoutParams
    parent.removeView(rowsContainer)

    val scroll = ScrollView(rowsContainer.context).apply {
        // 0dp + weight=1 — the ScrollView claims the leftover vertical
        // space the rows_container used to claim, between the station
        // strip above and the status row + footer below.
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
        ).apply {
            weight = originalParams.weight.takeIf { it > 0f } ?: 1f
        }
        // Scrollbar — thin (3dp) amber tapered thumb, fades after
        // 600ms idle then over 1500ms. Matches the widget XML's
        // rows_list (API ≥ 31) styling so every surface looks the
        // same when overflowing.
        isVerticalScrollBarEnabled = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        isScrollbarFadingEnabled = true
        scrollBarFadeDuration = 1500
        scrollBarDefaultDelayBeforeFade = 600
        scrollBarSize = (3f * resources.displayMetrics.density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            verticalScrollbarThumbDrawable =
                ContextCompat.getDrawable(context, R.drawable.scrollbar_thumb)
        }
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    parent.addView(scroll, idx)
    scroll.addView(
        rowsContainer,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
    // Inside the scroll the LinearLayout must be wrap_content, not the
    // 0dp + weight pattern that fragile-measured in a wrap-content
    // grandparent (see the old `widget_departure_board.xml` issue).
    (rowsContainer.layoutParams as ViewGroup.LayoutParams).height =
        ViewGroup.LayoutParams.WRAP_CONTENT
    return scroll
}
