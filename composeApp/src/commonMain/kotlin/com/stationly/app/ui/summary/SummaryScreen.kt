@file:OptIn(ExperimentalMaterial3Api::class)
package com.stationly.app.ui.summary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.app.ui.common.AnnouncementBanner
import com.stationly.app.ui.common.NotificationPermissionEffect
import com.stationly.app.ui.common.OfflineBanner
import com.stationly.app.ui.common.ThemeToggleButton
import com.stationly.app.ui.summary.components.BoardSection
import com.stationly.app.ui.summary.components.StationBoard
import com.stationly.app.ui.summary.components.EmptyStationsState
import com.stationly.app.ui.summary.components.StationExploreSection
import com.stationly.app.ui.theme.DisplayFamily
import com.stationly.app.ui.theme.TflAmber
import com.stationly.app.ui.util.StationPrefs
import com.stationly.app.ui.util.StationPrefsRepository
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.core.platform.Platform
import com.stationly.core.util.MultiLineBoardProcessor
import kotlin.math.floor

/** Top/bottom padding inside the home scroll, applied at each end. */
private val HOME_PADDING_V = 16.dp

/** Vertical gap between the home screen's blocks (chrome, boards, Network). */
private val HOME_GAP = 20.dp

/**
 * Heights of a collapsed card's parts, mirroring `HEADER_HEIGHT` and
 * `LEG_HEIGHT` in Board.kt.
 *
 * Duplicated deliberately rather than exposed: these are the height budget's
 * ASSUMPTIONS about a collapsed card, and the layout must not silently re-flow
 * if the header's internals change — a mismatch shows up as the open board being
 * a few dp off, not as a crash.
 */
private val STATION_HEADER_HEIGHT = 44.dp
private val LEG_HEIGHT = 22.dp

/**
 * What the card CONTAINER costs before any of its content: the colour rail, the
 * inner padding above and below, and the hairline border top and bottom.
 *
 * Mirrors `CARD_RAIL_HEIGHT` plus the container padding in Board.kt. A station
 * card became a real bounded surface rather than loose content on the canvas, and
 * every dp of that surround comes off the panel's share — left out of the budget,
 * the floor below silently stops being three departures.
 */
private val CARD_CHROME_HEIGHT = 20.dp

/** One departure row inside the panel, including the 2dp gap under it. */
private val BOARD_ROW_HEIGHT = 26.dp

/** What "a usable departure board" means, in rows. Feeds [MIN_BOARD_HEIGHT]. */
private const val MIN_VISIBLE_ROWS = 3

/**
 * Floor for a card, sized so its panel can always show [MIN_VISIBLE_ROWS]
 * departures.
 *
 * A CARD is not a board: by the time the panel gets its share, the card has
 * already spent its height on the station header, the line pills, the hero, the
 * status strip and the footer. The old 280dp floor was picked against the card
 * and left the panel with a single row once several stations were open —
 * technically "fitting one viewport", but a departure board showing one
 * departure is not a departure board.
 *
 * Written as the sum of the parts rather than as one number, so a change to any
 * of them can be reflected here without re-deriving the total by hand.
 *
 * Budgeted WITH the hero even though a station can hide it: the floor has to
 * cover the worst case, and a hero-hidden card simply spends the same height on
 * more rows.
 *
 * Overflowing this is deliberate. Past it the PAGE scrolls, which is the honest
 * outcome when the user has opened more boards than a screen holds — the
 * alternative is several boards none of which can be read.
 */
private val MIN_BOARD_HEIGHT =
    CARD_CHROME_HEIGHT +         // rail + container padding + border
    STATION_HEADER_HEIGHT +      // the card's own nameplate
    34.dp +                      // line pills row (2 top + 22 pill + 10 bottom)
    104.dp +                     // hero (HERO_HEIGHT) + its 10dp spacer
    16.dp +                      // panel padding, 8 top and 8 bottom
    26.dp +                      // one platform header
    (BOARD_ROW_HEIGHT * MIN_VISIBLE_ROWS) +
    22.dp +                      // status strip
    34.dp                        // footer: clock + maker mark

/**
 * The tallest a station card may be: exactly the room left after everything else
 * on the home screen has taken what it needs.
 *
 * This is DERIVED, not a fraction of the screen. Earlier versions guessed —
 * "viewport minus padding", then 82% of the viewport — and both were wrong in
 * both directions at once: too tall when a promo card was showing, too short when
 * none were, and never right on a device whose proportions differed from the one
 * being tested on. The board is the last thing to be given space, so it can just
 * be told what is genuinely left.
 *
 * [chromeHeight] and [exploreHeight] are measured (`onSizeChanged`) rather than
 * assumed, because both change at runtime: promos are dismissible, and the
 * Network section grows with the number of live disruptions.
 *
 * Collapsed stations cost a known [STATION_HEADER_HEIGHT] (plus legs) each, so
 * with one board open the whole home screen still fits one viewport however many
 * stations are tracked. This returns the cap for the TOP open board — every
 * other open board is held to [MIN_BOARD_HEIGHT] by the caller.
 */
private fun boardMaxHeight(
    viewportHeight: Dp,
    expandedCount: Int,
    collapsedCount: Int,
    chromeHeight: Dp,
    exploreHeight: Dp,
    bottomInset: Dp,
): Dp {
    // Blocks in the scroll: [chrome?] + boards + Network. Each pair costs one gap.
    val gapCount = if (chromeHeight > 0.dp) 2 else 1
    // Collapsed stations cost a known amount, so they come out of the budget
    // before it is shared — this is what replaced the old viewport-fraction cap.
    //
    // Budgeted at the MAXIMUM leg count rather than the actual one. The real
    // number depends on live predictions, so budgeting on it would re-flow every
    // open board each time a train departs — the height churn this whole layout
    // exists to prevent. Over-reserving costs the open board a few dp and is
    // stable; under-reserving pushes it off screen.
    val collapsedCost =
        (CARD_CHROME_HEIGHT + STATION_HEADER_HEIGHT +
            LEG_HEIGHT * MultiLineBoardProcessor.MAX_COLLAPSED_LEGS + HOME_GAP) *
            collapsedCount.coerceAtLeast(0)
    val budget = viewportHeight -
        (HOME_PADDING_V * 2) - bottomInset -
        chromeHeight - exploreHeight - collapsedCost -
        (HOME_GAP * gapCount)
    // Everything else that is open is held to the floor, so this is what the TOP
    // open board may take.
    //
    // Not an equal share. Dividing the viewport between three open boards gives
    // three boards nobody can read, and the user has already said which station
    // matters by pinning it to the top. One board gets the room; the others get
    // three departures each and the page scrolls, which is the honest outcome of
    // opening more than a screen holds.
    val others = (expandedCount - 1).coerceAtLeast(0)
    val forOthers = (MIN_BOARD_HEIGHT + HOME_GAP) * others
    return (budget - forOthers).coerceAtLeast(MIN_BOARD_HEIGHT)
}

/**
 * Widest a board is allowed to get, regardless of the window.
 *
 * Everything sized from the viewport scales up happily EXCEPT line length. A
 * departure row is destination-left / ETA-right, so on a full-width iPad the two
 * end up a hand's width apart with nothing between them, and the eye loses the
 * pairing — the one thing the row exists to convey.
 *
 * Roughly the width of a large phone, which is what the board's type sizes were
 * drawn against. Wider windows centre the board and leave margin rather than
 * stretching it. Height still uses the whole window (see [boardMaxHeight]) —
 * more vertical space means more departures, which IS useful.
 */
private val MAX_BOARD_WIDTH = 480.dp

@Composable
fun SummaryScreen(
    onNavigateToSelection: () -> Unit,
    /**
     * Open one station's settings screen — (grouping id, mode, name).
     *
     * The three values the settings screen cannot derive on its own: the
     * grouping id keys the preferences and the boards, and the mode and name are
     * what it puts at the top before its own data loads.
     */
    onOpenStationSettings: (String, String, String) -> Unit = { _, _, _ -> },
    onNavigateToProfile: () -> Unit,
    onOpenScreensaver: () -> Unit = {},
    /** Home-wide settings (theme, boards, screensaver) — the gear in the top bar. */
    onOpenHomeSettings: () -> Unit = {},
    viewModel: SummaryViewModel = viewModel { SummaryViewModel() }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selections by viewModel.selections.collectAsStateWithLifecycle()
    val predictions by viewModel.predictions.collectAsStateWithLifecycle()
    val lineStatuses by viewModel.lineStatuses.collectAsStateWithLifecycle()
    val failedLineStatusKeys by viewModel.failedLineStatusKeys.collectAsStateWithLifecycle()
    val stationUpdates by viewModel.stationUpdates.collectAsStateWithLifecycle()
    val announcement by viewModel.announcement.collectAsStateWithLifecycle()
    val homeConfig by viewModel.homeConfig.collectAsStateWithLifecycle()
    val forceUpdate by viewModel.forceUpdate.collectAsStateWithLifecycle()
    val showWidgetPromo by viewModel.showWidgetPromo.collectAsStateWithLifecycle()
    val showDreamPromo by viewModel.showDreamPromo.collectAsStateWithLifecycle()
    val showNotificationDeniedBanner by viewModel.showNotificationDeniedBanner.collectAsStateWithLifecycle()
    val stationPrefs by viewModel.stationPrefs.collectAsStateWithLifecycle()
    val stationOrder by viewModel.stationOrder.collectAsStateWithLifecycle()

    // First authenticated screen — the one place Android asks too. Re-check the
    // denied banner on the user's answer so a denial surfaces it immediately
    // rather than only after the next foreground.
    NotificationPermissionEffect(onDecision = { viewModel.checkNotificationDeniedBanner() })

    // Reload selections from SQLite whenever this screen resumes (app foreground
    // or returning from Profile). Mirrors Android's ON_RESUME hook: handles a
    // station deleted elsewhere while the in-memory cache was stale, and pulls
    // any board rows an FCM push wrote to SQLite while we were backgrounded —
    // so the in-app board is fresh the instant the user comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadSelectionsFromDb()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SummaryTopBar(
                onNavigateToProfile = onNavigateToProfile,
                onAddStation = onNavigateToSelection,
                onOpenHomeSettings = onOpenHomeSettings,
                userInitial = uiState.userInitial,
                photoUrl = uiState.photoUrl,
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // TOP padding only — the top bar's height, which content genuinely
            // must start below.
            //
            // The BOTTOM inset is deliberately NOT consumed here. Taking it would
            // end the scrolling list ~34dp above the screen edge, leaving a dead
            // band along the bottom that no content can ever occupy: the board
            // gets cut short and the space below it stays permanently empty.
            //
            // Instead the list runs edge-to-edge and carries the inset as
            // `contentPadding` (below), which is the standard scroll-view
            // treatment: rows scroll UNDER the home indicator as you drag, and
            // the last one can still come to rest clear of it.
            //
            // The theme toggle is outside this box entirely so it can pin to the
            // true screen bottom.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {

            // NO delete overlay here any more. Deleting a board is done from
            // the station settings screen, which owns the whole interaction —
            // confirmation, progress and dismissal — and stays on screen for
            // it. The card here simply disappears when the selection does.

            // Measured heights of everything on the home screen that ISN'T a
            // board. The board's cap is whatever is left over, so these have to
            // be real measurements: promos are dismissible, and the Network
            // section's height depends on how many disruptions are live. A
            // hardcoded allowance would be wrong on every device and session.
            //
            // Hoisted ABOVE the AnimatedContent below, which is keyed on
            // `selections` — inside it, adding or removing a board would reset
            // both to zero and flash a full-height card for a frame before the
            // next measurement landed.
            var chromePx by remember { mutableStateOf(0) }
            var explorePx by remember { mutableStateOf(0) }
            val bottomInset = WindowInsets.navigationBars.asPaddingValues()
                .calculateBottomPadding()

            AnimatedContent(
                targetState = selections,
                transitionSpec = {
                    fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
                },
                label = "selections_content"
            ) { currentSelections ->
                if (currentSelections.isEmpty()) {
                    EmptyStationsState(onNavigateToSelection, strings = homeConfig)
                } else {
                    val pullState = rememberPullToRefreshState()
                    val isIos = remember { Platform.getPlatformName() == "iOS" }

                    // iOS only. Light impact the instant the pull crosses the
                    // trigger point — the clearest signal that letting go will
                    // do something, and its absence is most of why a pull feels
                    // "not quite native". Android's haptic stays where it was,
                    // on release inside `refreshAll()`.
                    //
                    // Latched with hysteresis: fires once when the pull passes
                    // the threshold and only re-arms once it has relaxed back
                    // below 0.85, so a finger resting exactly on the boundary
                    // can't machine-gun the taptic engine.
                    if (isIos) {
                        LaunchedEffect(pullState) {
                            var armed = true
                            snapshotFlow { pullState.distanceFraction }.collect { f ->
                                when {
                                    f >= 1f && armed -> {
                                        performHaptic(HapticType.TAP)
                                        armed = false
                                    }
                                    f < 0.85f -> armed = true
                                }
                            }
                        }
                    }

                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                      val density = LocalDensity.current
                      // Station ids in CARD ORDER: the sequence the user dragged
                      // them into in home settings, then anything added since.
                      //
                      // This one list is the whole ordering feature — the cards
                      // below iterate it, and the height budget counts it. It
                      // replaced a pinned-first sort, which could not express an
                      // order at all once every station's settings screen offered
                      // the pin: four pinned stations are four stations in
                      // insertion order wearing a badge.
                      val stationIds = remember(currentSelections, stationOrder) {
                          StationPrefsRepository.orderedIds(
                              currentSelections.map { it.groupingId }.distinct()
                          )
                      }

                      // ── Which stations are open ──
                      //
                      // A SET, because the user can open several by hand and can
                      // mark several to open themselves (`openByDefault`).
                      //
                      // `null` is not the same as an empty set. Empty means the
                      // user closed everything during THIS session, which must
                      // survive; null means they have not touched anything yet,
                      // and resolves to the stations marked open-by-default — or
                      // the first station when none are, so the page never opens
                      // on a list of shut drawers.
                      //
                      // Session state, deliberately. What you had open is not a
                      // setting you configured; the setting is `openByDefault`,
                      // and it is the only thing that survives a cold start.
                      var expandedIdsState by rememberSaveable {
                          mutableStateOf<List<String>?>(null)
                      }
                      val expandedIds: Set<String> = when {
                          // One station has nothing to collapse for.
                          stationIds.size == 1 -> stationIds.toSet()
                          expandedIdsState == null -> {
                              val defaults = stationIds.filter { stationPrefs[it]?.openByDefault == true }
                              if (defaults.isNotEmpty()) defaults.toSet()
                              else setOfNotNull(stationIds.firstOrNull())
                          }
                          // A station can be deleted while it is open, leaving an
                          // id that matches no card — which would then be counted
                          // in the height budget.
                          else -> expandedIdsState!!.filter { it in stationIds }.toSet()
                      }

                      // The board that gets the room left over. Everything else
                      // open is held to the floor, because dividing the viewport
                      // equally between three boards gives three boards nobody
                      // can read — and the user has already told us which
                      // station matters by pinning it to the top.
                      val primaryStationId = stationIds.firstOrNull { it in expandedIds }

                      // `maxHeight` is measured INSIDE the Scaffold's content
                      // padding, so the bottom safe-area inset is already gone
                      // from it — but the list adds it back as its own bottom
                      // padding, so it does come out of the budget here.
                      val primaryBoardMaxHeight = boardMaxHeight(
                          viewportHeight = maxHeight,
                          expandedCount = expandedIds.size,
                          collapsedCount = stationIds.size - expandedIds.size,
                          chromeHeight = with(density) { chromePx.toDp() },
                          exploreHeight = with(density) { explorePx.toDp() },
                          bottomInset = bottomInset,
                      )

                      PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refreshAll() },
                        state = pullState,
                        modifier = Modifier.fillMaxSize(),
                        indicator = {
                            // iOS gets a faithful UIActivityIndicator; Android
                            // keeps the amber ring it already shipped with.
                            // Both replace Material's spinner-in-a-pill.
                            if (isIos) {
                                IosActivityRefreshIndicator(
                                    state = pullState,
                                    isRefreshing = uiState.isRefreshing,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                CupertinoRefreshIndicator(
                                    state = pullState,
                                    isRefreshing = uiState.isRefreshing,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    ) {
                        // A plain scrolling Column, NOT a LazyColumn.
                        //
                        // The board's height has to be derived from how much room
                        // the rest of the screen needs, and a LazyColumn cannot
                        // tell it: off-screen items are never composed, so the
                        // Network section's height is unknown precisely when the
                        // board is too tall — and sizing the board off that
                        // unknown would push Network further off screen, which
                        // keeps it unmeasured. A circular dependency that never
                        // settles.
                        //
                        // Eager composition costs little here (a handful of
                        // boards, and the per-row draw cost is gone), and it
                        // keeps `verticalScroll` — so the pull-to-refresh nested
                        // scroll and the iOS rubber-band both still work. When
                        // the content fits, scrolling has nowhere to go and all
                        // that is left is the bounce.
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                // iOS rubber-band: the WHOLE screen follows the pull,
                                // with increasing friction past the trigger point
                                // (f/(1+0.5f) asymptotes instead of tracking 1:1 —
                                // things in the real world slow down, they don't hit
                                // walls). graphicsLayer = transform-only, no relayout.
                                .graphicsLayer {
                                    val f = pullState.distanceFraction
                                    translationY = (f / (1f + 0.5f * f)) * 72.dp.toPx()
                                }
                                .verticalScroll(rememberScrollState())
                                // The bottom safe-area inset lives HERE, not on the
                                // box above — see the comment there. Inside the
                                // scroll it costs the list no height: content moves
                                // through it, and it only guarantees the last item
                                // can rest clear of the home indicator.
                                .padding(
                                    start = 20.dp, end = 20.dp, top = HOME_PADDING_V,
                                    bottom = HOME_PADDING_V + bottomInset,
                                ),
                            verticalArrangement = Arrangement.spacedBy(HOME_GAP)
                        ) {
                            // SummaryHeader (greeting + "Live · N boards") intentionally
                            // removed to match the redesigned Android home — the top-bar
                            // brand lockup already sets context; boards are the focus.
                            //
                            // Banners and promos are grouped so their COMBINED height
                            // can be measured in one place — the board's cap is
                            // whatever is left after them, and they come and go as
                            // they are dismissed.
                            val widgetPromoEnabled = (homeConfig["home.promo.widget.show"] ?: "true").equals("true", ignoreCase = true)
                            val dreamPromoEnabled = (homeConfig["home.promo.dream.show"] ?: "true").equals("true", ignoreCase = true)
                            val notifBannerEnabled = (homeConfig["home.notif_denied.show"] ?: "true").equals("true", ignoreCase = true)

                            val hasChrome = announcement != null ||
                                (showNotificationDeniedBanner && notifBannerEnabled) ||
                                (showWidgetPromo && widgetPromoEnabled) ||
                                (showDreamPromo && dreamPromoEnabled)

                            if (hasChrome) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(HOME_GAP),
                                    modifier = Modifier.onSizeChanged { chromePx = it.height },
                                ) {
                                    announcement?.let { banner ->
                                        AnnouncementBanner(
                                            announcement = banner,
                                            onDismiss = { viewModel.dismissAnnouncement() }
                                        )
                                    }
                                    if (showNotificationDeniedBanner && notifBannerEnabled) {
                                        NotificationDeniedBanner(
                                            strings = homeConfig,
                                            onDismiss = { viewModel.dismissNotificationDeniedBanner() },
                                            onEnable = { viewModel.dismissNotificationDeniedBanner() },
                                        )
                                    }
                                    if (showWidgetPromo && widgetPromoEnabled) {
                                        WidgetPromoCard(
                                            strings = homeConfig,
                                            onDismiss = { viewModel.dismissWidgetPromo() },
                                        )
                                    }
                                    if (showDreamPromo && dreamPromoEnabled) {
                                        DreamPromoCard(
                                            strings = homeConfig,
                                            onDismiss = { viewModel.dismissDreamPromo() },
                                            onSetUp = {
                                                viewModel.hideDreamPromoForSession()
                                                onOpenScreensaver()
                                            },
                                        )
                                    }
                                }
                            }
                            // Reclaim the chrome's height for the board once the
                            // last banner is dismissed. In an effect, not inline:
                            // the block above is not composed when there is no
                            // chrome, so nothing would otherwise clear the last
                            // measurement — and writing state during composition
                            // (which the inline version did) is how recomposition
                            // loops start.
                            LaunchedEffect(hasChrome) { if (!hasChrome) chromePx = 0 }

                            // One card per STATION, not per selection: a user can
                            // now track several lines at the same station and
                            // expects them stacked in one board, not scattered
                            // across identically-titled cards. `groupBy`
                            // preserves first-encounter order, so cards stay in
                            // the order the stations were added and the lines
                            // within a card stay in the order they were picked.
                            // Group on the HUB, not the fetch key. On bus each direction resolves
                            // to its own pole naptan, so grouping by `station` would split one
                            // stop into a card per pole, all with the same name.
                            // Grouped by station, then walked in `stationIds`
                            // order so the user's own sequence wins — the map's
                            // order is insertion order and knows nothing of it.
                            val byStation = currentSelections.groupBy { it.groupingId }
                            val stationGroups = stationIds.mapNotNull { id ->
                                byStation[id]?.let { id to it }
                            }

                            stationGroups.forEach { (stationId, groupSelections) ->
                                val sections = groupSelections.map { selection ->
                                    val boardKey = selection.boardKey
                                    val statusKey = "${selection.mode}_${selection.line}".lowercase()
                                    BoardSection(
                                        selection = selection,
                                        predictions = predictions[boardKey] ?: emptyList(),
                                        lineStatus = lineStatuses[statusKey],
                                        lineStatusFailed = failedLineStatusKeys.contains(statusKey),
                                        lastUpdated = stationUpdates[boardKey] ?: 0L,
                                    )
                                }
                                val primary = groupSelections.first()

                                val prefs = stationPrefs[stationId] ?: StationPrefs()

                                StationBoard(
                                    expanded = stationId in expandedIds,
                                    onToggleExpanded = if (stationGroups.size > 1) {
                                        {
                                            expandedIdsState =
                                                if (stationId in expandedIds) (expandedIds - stationId).toList()
                                                else (expandedIds + stationId).toList()
                                        }
                                    } else null,
                                    opensByDefault = prefs.openByDefault,
                                    showHero = !prefs.hideHero,
                                    onOpenSettings = {
                                        onOpenStationSettings(stationId, primary.mode, primary.stationName)
                                    },
                                    stationName = primary.stationName,
                                    mode = primary.mode,
                                    sections = sections,
                                    homeConfig = homeConfig,
                                    isOnline = uiState.isOnline,
                                    // The top open station gets the leftover
                                    // room; the rest get the floor, which is
                                    // three departures (MIN_BOARD_HEIGHT).
                                    maxHeight = if (stationId == primaryStationId) {
                                        primaryBoardMaxHeight
                                    } else MIN_BOARD_HEIGHT,
                                    maxWidth = MAX_BOARD_WIDTH,
                                )
                            }

                            Box(modifier = Modifier.onSizeChanged { explorePx = it.height }) {
                                StationExploreSection(
                                    lineStatuses = lineStatuses,
                                    strings = homeConfig
                                )
                            }
                        }
                    }
                    }
                }
            }

            var updateDismissed by remember { mutableStateOf(false) }
            if (forceUpdate && !updateDismissed) {
                UpdateNudgeDialog(
                    title = homeConfig["app.update.title"] ?: "New update available",
                    message = homeConfig["app.update.message"] ?: "Update Stationly for the latest features and improvements.",
                    cta = homeConfig["app.update.cta"] ?: "Update Now",
                    dismiss = homeConfig["app.update.dismiss"] ?: "Maybe Later",
                    storeUrl = homeConfig["app.storeUrl"] ?: "https://apps.apple.com/app/stationly",
                    onDismiss = { updateDismissed = true }
                )
            }

            OfflineBanner(
                visible = uiState.isBackendOffline,
                onRetry = { viewModel.retryLoad() },
                onDismiss = { viewModel.clearError() }
            )

            // Bottom decorative glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, TflAmber.copy(alpha = 0.03f))
                        )
                    )
            )
            } // end padded content box

            // Discreet theme toggle pinned to the TRUE screen bottom-right, just
            // above the home indicator — the daily-use light/dark/system shortcut
            // (the full picker lives in Profile → Appearance). Matches Android's
            // BottomEnd + WindowInsets.navigationBars placement. Sits OUTSIDE the
            // padded content box, so the bottom safe-area inset is applied exactly
            // once (here) instead of stacking on the Scaffold's content padding.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(end = 6.dp, bottom = 2.dp)
                    .zIndex(2f),
            ) {
                ThemeToggleButton(compact = true)
            }
        }
    }
}

@Composable
private fun SummaryTopBar(
    onNavigateToProfile: () -> Unit,
    onAddStation: () -> Unit,
    onOpenHomeSettings: () -> Unit,
    userInitial: String = "?",
    photoUrl: String? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onBackground = MaterialTheme.colorScheme.onBackground
    CenterAlignedTopAppBar(
        title = {
            // Single-line brand lockup matching the redesigned Android home.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                com.stationly.app.ui.common.StationlyLogo(size = 32.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Stationly",
                    color = onBackground,
                    fontFamily = DisplayFamily,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    letterSpacing = (-0.5).sp
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigateToProfile, modifier = Modifier.padding(start = 8.dp)) {
                if (photoUrl != null) {
                    coil3.compose.AsyncImage(
                        model = photoUrl,
                        contentDescription = "Profile",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(onBackground.copy(alpha = 0.05f), CircleShape)
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = onBackground.copy(alpha = 0.05f),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(userInitial, color = primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        actions = {
            // ADD first, then the app's own settings.
            //
            // This button used to flip between + and a pencil depending on
            // whether any stations existed, and the pencil was a lie: it opened
            // the same "pick a mode, pick a station" flow the plus did, which
            // ADDS. Editing is per station now, behind that station's own
            // settings, so the top bar has exactly one job here.
            //
            // The gear sits to the RIGHT of it, on the very edge, because it is
            // the rarer of the two and the primary action should not be the one
            // pushed inboard. It is a GEAR, deliberately not the sliders glyph a
            // station card uses (`Tune`): two settings surfaces exist now, and
            // the same icon on both would say they lead to the same place. A
            // gear is the heavier, app-wide one.
            Surface(
                onClick = onAddStation,
                shape = CircleShape,
                color = primary.copy(alpha = 0.10f),
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add station",
                        tint = primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = onOpenHomeSettings, modifier = Modifier.padding(end = 6.dp)) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "Home settings",
                    tint = onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/**
 * Dismissible one-line nudge above the boards. Port of Android's
 * `PromoBanner` — icon tile, title + subtitle, optional text CTA, X. Every
 * string is SDUI-driven by the caller with a hardcoded fallback, so the
 * banner never renders blank.
 */
@Composable
private fun PromoBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    cta: String?,
    onCta: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }

            if (!cta.isNullOrBlank()) {
                TextButton(
                    onClick = onCta,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(cta, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * "Add a home screen widget" — shown until the WidgetKit probe says one is
 * installed.
 *
 * **No CTA button, by design.** Android offers "Add" only when
 * `isRequestPinAppWidgetSupported`, and passes `cta = null` otherwise; iOS is
 * permanently in that second case, because there is no API to add a widget on
 * the user's behalf — the gesture is theirs to make. So the subtitle carries
 * the instruction instead. Backends that want iOS-specific wording can set
 * `home.promo.widget.subtitle.ios`; otherwise the shared key is used.
 */
@Composable
private fun WidgetPromoCard(
    strings: Map<String, String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PromoBanner(
        icon = Icons.Default.Add,
        title = strings["home.promo.widget.title"] ?: "Add a home screen widget",
        subtitle = strings["home.promo.widget.subtitle.ios"]
            ?: strings["home.promo.widget.subtitle"]
            ?: "Touch and hold the Home Screen, tap ＋, then search Stationly for live departures at a glance",
        cta = null,
        onCta = {},
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/**
 * Surfaces when the OS reports notification permission as denied. Without it,
 * line-status auto-alerts and admin pushes silently no-op and the user has no
 * way to know they're missing them.
 *
 * "Enable" opens the app's Settings page (iOS has no direct per-app
 * notifications deep link — that page is one tap away from it). The banner
 * re-evaluates on the next foreground, so flipping the switch back on makes
 * it disappear without another nudge.
 */
@Composable
private fun NotificationDeniedBanner(
    strings: Map<String, String>,
    onDismiss: () -> Unit,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PromoBanner(
        icon = Icons.Default.Notifications,
        title = strings["home.notif_denied.title"] ?: "Turn on notifications",
        subtitle = strings["home.notif_denied.subtitle"]
            ?: "Stationly can alert you when your line has delays, closures, or recovers.",
        cta = strings["home.notif_denied.cta"] ?: "Enable",
        onCta = {
            com.stationly.app.platform.openAppNotificationSettings()
            onEnable()
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/**
 * "Set as Screensaver" — shown until the user has actually run the dream.
 *
 * Android's CTA opens system Settings → Display → Screen saver, because the
 * dream is a system-owned surface there. On iOS the screensaver is an in-app
 * route, so "Set up" goes straight to the dream settings screen — strictly
 * fewer taps, same destination in spirit.
 */
@Composable
private fun DreamPromoCard(
    strings: Map<String, String>,
    onDismiss: () -> Unit,
    onSetUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PromoBanner(
        icon = Icons.Default.Bedtime,
        title = strings["home.promo.dream.title"] ?: "Set as Screensaver",
        subtitle = strings["home.promo.dream.subtitle"] ?: "Live departures when docked or charging",
        cta = strings["home.promo.dream.cta"] ?: "Set up",
        onCta = onSetUp,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
private fun UpdateNudgeDialog(
    title: String,
    message: String,
    cta: String,
    dismiss: String,
    storeUrl: String,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.stationly.app.ui.common.StationlyLogo(size = 52.dp)
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { uriHandler.openUri(storeUrl); onDismiss() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(cta, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(dismiss, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f), fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * ANDROID ONLY — the pre-existing indicator, kept verbatim so Android's pull is
 * untouched by the iOS work. A thin amber ring that fills as you pull, then
 * becomes an indeterminate spinner while refreshing. iOS now uses
 * [IosActivityRefreshIndicator] instead; do not "unify" these two without
 * deciding to change Android on purpose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.CupertinoRefreshIndicator(
    state: androidx.compose.material3.pulltorefresh.PullToRefreshState,
    isRefreshing: Boolean,
    color: Color,
) {
    // Hook is always called (never conditionally) — spin only matters while refreshing.
    val spin by rememberInfiniteTransition(label = "ptr").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "ptr_spin"
    )
    val fraction = state.distanceFraction
    if (!isRefreshing && fraction <= 0.01f) return  // hidden at rest

    val appear = fraction.coerceIn(0f, 1f)
    val shownAlpha = if (isRefreshing) 1f else appear
    val shownScale = 0.7f + 0.3f * (if (isRefreshing) 1f else appear)

    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .graphicsLayer {
                translationY = if (isRefreshing) 22.dp.toPx()
                else (fraction / (1f + 0.5f * fraction)) * 60.dp.toPx()
                alpha = shownAlpha
                scaleX = shownScale
                scaleY = shownScale
                rotationZ = if (isRefreshing) spin else 0f
            }
            .size(26.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            if (isRefreshing) {
                drawArc(
                    color, startAngle = -90f, sweepAngle = 285f, useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round), topLeft = topLeft, size = arcSize
                )
            } else {
                // faint full-ring track + amber progress arc filling with the pull
                drawArc(color.copy(alpha = 0.22f), 0f, 360f, false, style = Stroke(stroke), topLeft = topLeft, size = arcSize)
                drawArc(
                    color, startAngle = -90f, sweepAngle = 360f * appear, useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round), topLeft = topLeft, size = arcSize
                )
            }
        }
    }
}

/** Spoke count of a real `UIActivityIndicatorView`. Do not change casually — the
 *  tick rate below assumes one spoke per 1/12s so the chase lands on 1 rev/sec. */
private const val SPOKE_COUNT = 12

/**
 * Pull-to-refresh indicator drawn as a faithful `UIActivityIndicatorView`:
 * [SPOKE_COUNT] rounded bars radiating from the centre.
 *
 * While pulling, the spokes light up one at a time clockwise from twelve
 * o'clock, so the ring reaches exactly "full" at the trigger point — the
 * indicator *is* the progress readout, no separate arc needed. While
 * refreshing, brightness chases around the ring in discrete ticks.
 *
 * The thing that makes this read as iOS rather than as an Android spinner:
 * **the geometry never rotates.** A `UIActivityIndicatorView` holds its spokes
 * perfectly still and moves only the opacity around them, which is why it looks
 * mechanical and precise instead of smoothly swirling. Applying `rotationZ`
 * here would undo the whole effect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.IosActivityRefreshIndicator(
    state: androidx.compose.material3.pulltorefresh.PullToRefreshState,
    isRefreshing: Boolean,
    color: Color,
) {
    // Hook is always called (never behind the early return) so the composable
    // keeps a stable slot table. Animates across the spoke count and is floored
    // below, quantising a continuous animation into 12 discrete steps/second.
    val tick by rememberInfiniteTransition(label = "ptr").animateFloat(
        initialValue = 0f,
        targetValue = SPOKE_COUNT.toFloat(),
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "ptr_tick",
    )

    val fraction = state.distanceFraction
    if (!isRefreshing && fraction <= 0.01f) return  // hidden at rest

    val appear = fraction.coerceIn(0f, 1f)
    val shownAlpha = if (isRefreshing) 1f else appear
    // iOS grows the indicator in rather than popping it at full size.
    val shownScale = 0.6f + 0.4f * (if (isRefreshing) 1f else appear)
    val head = floor(tick).toInt() % SPOKE_COUNT

    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .graphicsLayer {
                // Same rubber-band curve the list body uses, so the indicator
                // and the content decelerate together instead of drifting apart.
                translationY = if (isRefreshing) 22.dp.toPx()
                else (fraction / (1f + 0.5f * fraction)) * 60.dp.toPx()
                alpha = shownAlpha
                scaleX = shownScale
                scaleY = shownScale
            }
            .size(22.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val outer = radius * 0.98f
            val inner = radius * 0.52f
            val stroke = radius * 0.21f
            val centre = Offset(size.width / 2f, size.height / 2f)

            repeat(SPOKE_COUNT) { i ->
                val spokeAlpha = if (isRefreshing) {
                    // Comet tail: the head spoke is solid and brightness decays
                    // around the ring behind it, bottoming out at 0.18 rather
                    // than 0 — iOS keeps the full ring faintly visible so the
                    // shape stays legible at every frame.
                    val behind = (head - i + SPOKE_COUNT) % SPOKE_COUNT
                    0.18f + 0.82f * (1f - behind.toFloat() / SPOKE_COUNT)
                } else {
                    // Each spoke gets 1/12th of the pull and fades across its
                    // own slice, so the reveal is stepped but not jerky.
                    (appear * SPOKE_COUNT - i).coerceIn(0f, 1f)
                }
                if (spokeAlpha <= 0.01f) return@repeat

                rotate(degrees = i * (360f / SPOKE_COUNT), pivot = centre) {
                    drawLine(
                        color = color.copy(alpha = spokeAlpha),
                        start = Offset(centre.x, centre.y - inner),
                        end = Offset(centre.x, centre.y - outer),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
