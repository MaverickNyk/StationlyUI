package com.stationly.mobile.dream

import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.LegacyRow
import com.stationly.core.util.StationlyFormatters
import com.stationly.mobile.util.HomeConfigStore
import com.stationly.mobile.util.ModeColors
import com.stationly.mobile.util.ModeIconCache
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
     * Show the station-name header strip. The widget shows it; in the
     * clock-and-board dream we sometimes draw a Compose station header above
     * the board instead, so it's hidden there.
     */
    showHeader: Boolean = false,
    /**
     * Show the widget's built-in ticking TextClock at the bottom. Hidden by
     * default because the clock-and-board dream has its own larger clock; the
     * fullscreen-board dream turns this on so the board has a built-in clock.
     */
    showClock: Boolean = false,
    /**
     * Effective dp width the dot-matrix card occupies on this surface.
     * Used to size the station strip's `line_name` `maxEms` so the name
     * uses the room available (full screen on fullscreen dream; 70%
     * of the right column on cluster landscape; full width on cluster
     * portrait). Defaults to 320dp — a reasonable mid-size estimate for
     * surfaces that haven't measured their slot.
     */
    slotWidthDp: Int = 320,
    /**
     * Fullscreen-board styling:
     *  - rows centred vertically in their ScrollView viewport (no top/bottom
     *    gap when few rows; scrolls when many)
     *  - stronger amber border + larger corner radius — this IS the centerpiece,
     *    not a card sitting next to a clock
     *  - small inter-row margin so portrait doesn't read crammed
     */
    fullscreen: Boolean = false,
) {
    val sel = snapshot.selection
    // Self-tick row ETAs once per minute so a row labelled "5 min" visibly
    // drops to "4 min" without waiting for the next FCM push. Rows whose
    // targetEpochMs is null (FCM ISO parse fallback) fall through unchanged.
    val predictions = com.stationly.mobile.ui.util.rememberTickedPredictions(snapshot.predictions)
    val lineStatus = snapshot.lineStatus
    val lastUpdated = snapshot.lastUpdatedMs
    // Subscribe to the same wall-clock minute tick that drives the rows so
    // the chronometer's stale-colour (amber → grey → red) recomputes in
    // lockstep with the home Board and the widget. Adding it as a `remember`
    // key on the updater below guarantees the lambda re-runs at xx:00
    // boundaries even if the prediction list happens to be unchanged that
    // minute (e.g. board has fallen back to the empty-state).
    val nowMs by com.stationly.mobile.ui.util.rememberMinuteTick()

    // Stable lambda — only recreated when data changes, so AndroidView.update()
    // doesn't fire on every recomposition (which would reset Chronometer + marquee).
    val updater: (View) -> Unit = remember(sel, predictions, lineStatus, lastUpdated, textScale, showHeader, showClock, fullscreen, nowMs, slotWidthDp) {
        update@{ view ->
            val context = view.context
            // SDUI string map — used by the mode-icon contentDescription
            // and the platform-header line prefix below. Read once per
            // updater fire so we don't hit disk on every label substitution.
            val sduiStrings = HomeConfigStore.read(context)

            // Hide buttons — dream is non-interactive.
            view.findViewById<View>(R.id.btn_settings).visibility = View.GONE
            view.findViewById<View>(R.id.btn_refresh).visibility = View.GONE

            // Widget's own TextClock — clock-and-board hides it (there's a bigger
            // Compose clock alongside); fullscreen-board shows it so the board
            // has a built-in ticking time at the bottom, widget-style.
            view.findViewById<View>(R.id.clock)?.visibility =
                if (showClock) View.VISIBLE else View.GONE

            // Either show the widget's own station-name header strip, or hide it
            // and rely on the Compose layer drawn above the board.
            view.findViewById<View>(R.id.header_row)?.visibility =
                if (showHeader) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.line_name).apply {
                text = sel?.stationName ?: "Stationly"
                // Size to the actual slot the caller reported, not the
                // widget's XML default of 18 ems. On fullscreen dream
                // that's the full card width; on cluster landscape it
                // shrinks to ~70% of the screen.
                maxEms = com.stationly.mobile.ui.util.StationStripFitter
                    .maxEmsForWidthDp(slotWidthDp)
            }

            // Mode roundel on the station strip — same source-of-truth as
            // the widget: prefer the backend icon cached by ModeIconCache
            // during board setup; fall back to the tinted generic roundel
            // when nothing's cached yet.
            view.findViewById<ImageView>(R.id.mode_icon)?.apply {
                if (showHeader && sel != null) {
                    visibility = View.VISIBLE
                    // TalkBack — announce mode for the dream's signage strip.
                    contentDescription = StationlyFormatters.formatModeName(sel.mode, sduiStrings)
                    val cached = ModeIconCache.cachedBitmap(context, sel.mode)
                    if (cached != null) {
                        clearColorFilter()
                        setImageBitmap(cached)
                    } else {
                        // Tint precedence: backend-shipped tintHex (via /modes)
                        // > hardcoded ModeColors fallback for offline safety.
                        val tint = ModeIconCache.tintFor(context, sel.mode)
                            ?: ModeColors.forMode(sel.mode)
                        setImageResource(R.drawable.mode_roundel)
                        setColorFilter(tint)
                    }
                } else {
                    visibility = View.GONE
                }
            }

            // Line prefix applied to every platform header below in BOTH
            // dream layouts now. Previously cluster mode skipped it on
            // the theory that the Compose line-pill above the board
            // carried the context — but that diverged from the home Board
            // + widget + fullscreen-dream, which all show "Piccadilly:
            // Platform 1 (Eastbound)". Cross-surface consistency wins.
            val linePrefix = if (sel != null) {
                StationlyFormatters.formatLinePrefix(sel.mode, sel.line, sduiStrings)
            } else ""

            // "X ago" timer. Compute base from real last-updated wall-time so the
            // counter is honest even on the first paint of the dream. Only
            // reset when fresh data actually landed — the per-minute self-tick
            // re-fires this lambda but must NOT make the counter jump back to
            // zero.
            val chrono = view.findViewById<Chronometer>(R.id.last_updated_timer)
            chrono.visibility = View.VISIBLE
            chrono.format = "%s ago"
            val previousLastUpdated = chrono.tag as? Long
            if (previousLastUpdated != lastUpdated) {
                chrono.stop()
                chrono.base = SystemClock.elapsedRealtime() -
                    (System.currentTimeMillis() - lastUpdated).coerceAtLeast(0L)
                chrono.start()
                chrono.tag = lastUpdated
            }

            // Chronometer colour — shared `StaleColor` palette + thresholds
            // with home + widget. Recomputed every minute via `nowMs` in
            // the updater's `remember` key. Anchored to lastUpdated, so the
            // amber → grey at 60s and grey → red at 180s transitions
            // reflect the true age of the SQL row regardless of when the
            // dream first composed. Sentinel `lastUpdated == 0` stays amber
            // (no real data to age).
            if (lastUpdated > 0L) {
                val ageMs = (nowMs - lastUpdated).coerceAtLeast(0L)
                chrono.setTextColor(com.stationly.core.util.StaleColor.colorForAge(ageMs))
            } else {
                chrono.setTextColor(com.stationly.core.util.StaleColor.AMBER)
            }

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
            // Status row sits a notch BELOW the departure rows (it's secondary
            // info), consistent with home + widget where the XML now puts the
            // status at 12sp vs the 15sp departure rows (~0.8 ratio).
            severityText.setBaselineSp(ROW_BASE_SP * 0.9f)
            reasonText.setBaselineSp(ROW_BASE_SP * 0.9f)
            view.findViewById<Chronometer>(R.id.last_updated_timer)
                ?.setBaselineSp(ROW_BASE_SP * 0.8f)

            // The waiting_container is now the shared fallback panel —
            // applyBoardFallback at the end of this lambda decides its
            // visibility based on the computed BoardFallbackState. No more
            // unconditional GONE here.

            // Same fallback rules as home + widget so the three surfaces
            // never disagree about why the board is empty. Skipped when
            // `sel == null` (DreamHost normally shows EmptyStatePanel in
            // that case; the defensive "Add a board" header below should
            // win if we ever get here).
            val fallbackState = if (sel == null) null else {
                val nowMs = System.currentTimeMillis()
                val londonTime = java.time.Instant.ofEpochMilli(nowMs)
                    .atZone(java.time.ZoneId.of("Europe/London"))
                    .toLocalTime()
                com.stationly.mobile.ui.util.computeBoardFallbackState(
                    hasPredictions = predictions.isNotEmpty(),
                    isOnline = com.stationly.mobile.ui.util.NetworkState.isOnline.value,
                    lastUpdatedMs = lastUpdated,
                    nowMs = nowMs,
                    londonTime = londonTime,
                    lineStatusSeverity = lineStatus?.statusSeverityDescription,
                    lineStatusReason = lineStatus?.reason,
                )
            }

            // Rows. The widget XML declares the container as `0dp + weight=1`
            // — that only works when the parent has a fixed height. We wrap it
            // in a ScrollView so the user can scroll when their station has
            // more rows than the dream's viewport can show (multi-platform).
            // The vertical scrollbar is visible; the dream service has
            // isInteractive=true so the user can actually drag it.
            val rowsContainer = view.findViewById<LinearLayout>(R.id.rows_container)
            val rowsScroll = ensureScrollWrapped(rowsContainer, fullscreen)

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
                        is LegacyRow.Header  -> {
                            (child as TextView).text =
                                StationlyFormatters.platformHeaderText(linePrefix, row.title)
                        }
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
                // Extra spacing between rows when running fullscreen — at
                // signage scale rows otherwise look too close together,
                // especially in portrait. Dp picked to be ~half a row line
                // height at default scale.
                val rowGapPx = if (fullscreen)
                    (6f * context.resources.displayMetrics.density).toInt()
                else 0
                legacyRows.forEachIndexed { index, row ->
                    val rowView: View = when (row) {
                        is LegacyRow.Header -> inflater.inflate(
                            R.layout.widget_platform_header, rowsContainer, false
                        ).also {
                            it.findViewById<TextView>(R.id.platform_name).apply {
                                text = StationlyFormatters.platformHeaderText(linePrefix, row.title)
                                setBaselineSp(ROW_BASE_SP)
                            }
                        }
                        is LegacyRow.Departure -> inflater.inflate(
                            R.layout.widget_departure_row, rowsContainer, false
                        ).also {
                            it.findViewById<TextView>(R.id.destination_text).apply {
                                text = row.destination
                                setBaselineSp(ROW_BASE_SP)
                            }
                            it.findViewById<TextView>(R.id.eta_text).apply {
                                text = row.eta
                                setBaselineSp(ROW_BASE_SP)
                            }
                        }
                        is LegacyRow.Message -> inflater.inflate(
                            R.layout.widget_platform_header, rowsContainer, false
                        ).also {
                            it.findViewById<TextView>(R.id.platform_name).apply {
                                text = row.text
                                setBaselineSp(ROW_BASE_SP)
                            }
                        }
                    }
                    // Apply the extra dream rowGap to every row (incl. the
                    // first) so the vertical rhythm is steady — earlier the
                    // first row kept the XML's 2dp marginTop while subsequent
                    // rows jumped to 6dp, which read as a small visual hitch
                    // after the station strip.
                    if (rowGapPx > 0) {
                        (rowView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = rowGapPx
                    }
                    rowsContainer.addView(rowView)
                }
            }

            // Shared fallback rows — applied BEFORE scaleAllText so the
            // new TextViews participate in dream's text-scale multiplier
            // (otherwise they'd render at XML 15sp while real rows are at
            // 14sp × textScale ≈ 24sp+ — visibly tiny). The apply call's
            // rows_container reset wins over the diff-update / re-inflate
            // branches above; if no fallback is active it's a no-op.
            com.stationly.mobile.ui.util.applyBoardFallbackToRows(view, fallbackState, emptyMap())

            // Re-baseline any fallback rows we just added so they match
            // real-row sizing (real rows set ROW_BASE_SP inline during
            // inflation; fallback rows come from the surface-agnostic
            // helper which doesn't know about dream's baseline). Idempotent
            // on real rows that were already baselined.
            if (fallbackState != null) {
                for (i in 0 until rowsContainer.childCount) {
                    val child = rowsContainer.getChildAt(i)
                    child.findViewById<TextView>(R.id.destination_text)?.setBaselineSp(ROW_BASE_SP)
                    child.findViewById<TextView>(R.id.eta_text)?.setBaselineSp(ROW_BASE_SP)
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
    val androidView: @Composable (Modifier) -> Unit = { innerModifier ->
        AndroidView(
            modifier = innerModifier,
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

    // Rounded Compose surface around the inflated widget — the widget's own
    // backgrounds are square-cornered (designed for a home-screen tile), and
    // a soft radius + amber border reads more elegantly. Fullscreen mode
    // beefs everything up: larger radius and a brighter border because the
    // board IS the centerpiece, not a card sitting next to a clock. Inner
    // padding is slightly larger so content doesn't kiss the border.
    val cornerRadius = if (fullscreen) 28.dp else 20.dp
    val borderAlpha  = if (fullscreen) 0.32f else 0.18f
    val borderWidth  = if (fullscreen) 2.dp  else 1.dp
    val innerPad     = if (fullscreen) 14.dp else 6.dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        color = Color(0xFF050505),
        border = BorderStroke(borderWidth, amber.copy(alpha = borderAlpha)),
    ) {
        androidView(Modifier.fillMaxWidth().padding(innerPad))
    }
}

/**
 * Dream-specific scroll wrap. Delegates to the shared
 * [com.stationly.mobile.ui.util.ensureRowsScrollWrapped] for the
 * actual re-parenting + scrollbar styling, then layers on the
 * fullscreen-mode tweaks (centre-vertical gravity + `fillViewport`
 * so a short list doesn't pin to the top of a tall card).
 *
 * Safe to call on every update — the inner helper is idempotent.
 */
private fun ensureScrollWrapped(rowsContainer: LinearLayout, fullscreen: Boolean): ScrollView {
    val scroll = com.stationly.mobile.ui.util.ensureRowsScrollWrapped(rowsContainer)

    // Apply orientation-dependent behaviour every time — on rotation or
    // layout-change we re-enter this with the same ScrollView and need to
    // refresh flags.
    if (fullscreen) {
        // Fullscreen-board: centre the rows in the ScrollView viewport so a
        // short list doesn't pin to the top with a giant gap before the
        // status row. `fillViewport=true` stretches the inner LinearLayout
        // to viewport height; gravity inside the (vertical) LinearLayout
        // then vertically centres its children. When rows overflow,
        // gravity is ignored and the ScrollView scrolls naturally.
        scroll.isFillViewport = true
        rowsContainer.gravity = android.view.Gravity.CENTER_VERTICAL
    } else {
        scroll.isFillViewport = false
        rowsContainer.gravity = android.view.Gravity.TOP
    }
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
