@file:OptIn(
    ExperimentalMaterial3Api::class,
    // BringIntoViewRequester, for scrolling a station into view when its widget
    // is tapped. Same API LoginScreen already uses to lift a field above the
    // keyboard.
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
package com.stationly.app.ui.summary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.app.ui.common.AnnouncementBanner
import com.stationly.app.ui.common.AppBusy
import com.stationly.app.ui.common.NotificationPermissionEffect
import com.stationly.app.ui.common.OfflineBanner
import com.stationly.app.ui.support.LocalSupport
import com.stationly.app.ui.support.SupportBanner
import com.stationly.app.ui.support.SupportMoment
import com.stationly.app.ui.support.SupporterAvatarBadge
import com.stationly.app.ui.support.formatMoney
import com.stationly.app.ui.summary.components.BoardSection
import com.stationly.app.ui.summary.components.StationBoard
import com.stationly.app.ui.summary.components.EmptyStationsState
import com.stationly.app.ui.summary.components.StationExploreSection
import com.stationly.app.ui.summary.components.StationLimitSheet
import com.stationly.app.ui.theme.DisplayFamily
import com.stationly.core.config.BoardQuota
import com.stationly.core.util.MultiLineBoardProcessor
import com.stationly.app.ui.theme.TflAmber
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.HomeLayout
import com.stationly.core.repository.UserSettings
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.Platform
import kotlin.math.floor

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
    val showNotificationDeniedBanner by viewModel.showNotificationDeniedBanner.collectAsStateWithLifecycle()
    val pendingExpansion by BoardExpansion.pending.collectAsStateWithLifecycle()
    val boardConfigs by viewModel.boardConfigs.collectAsStateWithLifecycle()
    // Tells "the user has no preferences" apart from "we have not read them
    // yet" — the two are the same empty map, and they must not render the same.
    val prefsLoaded by viewModel.prefsLoaded.collectAsStateWithLifecycle()
    val homeLayout by viewModel.homeLayout.collectAsStateWithLifecycle()

    // Arrived. Whatever covered the journey here — today only the station
    // delete — comes down on this screen's FIRST FRAME rather than after a
    // guessed duration, so a slow teardown stays covered instead of tearing.
    //
    // Keyed on entry, which is the whole contract: every path that raises
    // [AppBusy] ends on this screen, so the one screen that clears it is the one
    // screen they all reach, and it cannot be left standing.
    LaunchedEffect(Unit) { AppBusy.clear() }

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
                onAddStation = { viewModel.onAddBoardClicked(onNavigateToSelection) },
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
                // Was a 500ms crossfade each way. Half a second is a long time to
                // watch a board fade after adding a station, and the two halves
                // overlapped for all of it — the incoming board spent most of its
                // entrance semi-transparent over the outgoing one. The new board
                // now arrives faster than the old one leaves, which is the order
                // that reads as a replacement rather than a dissolve.
                transitionSpec = {
                    fadeIn(tween(220, delayMillis = 60, easing = EaseOutCubic)) togetherWith
                        fadeOut(tween(160, easing = EaseInCubic))
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
                      // Ordered by each board's own `position`, which is why
                      // `boardConfigs` is the only thing this has to watch: a
                      // drag in home settings and a rows-per-platform change
                      // arrive on the same emission.
                      val stationIds = remember(currentSelections, boardConfigs) {
                          UserSettings.ordered(
                              currentSelections.map { it.groupingId }.distinct()
                          )
                      }

                      // The selections behind each card, grouped on the HUB —
                      // one card is one station, and a bus hub's poles must not
                      // become several cards with the same name.
                      //
                      // Hoisted because two things need it and they must agree:
                      // the height budget below counts a collapsed card's legs
                      // from it, and the cards themselves are built from it. It
                      // was grouped twice, which is two chances for the budget to
                      // be reserving space for a different set of stations than
                      // the one being drawn.
                      val selectionsByStation = remember(currentSelections) {
                          currentSelections.groupBy { it.groupingId }
                      }

                      // ── Which stations are open ──
                      //
                      // Only asked in [HomeLayout.LIST]. A carousel gives every
                      // station a page of its own, so there is nothing to
                      // collapse FOR — collapsing one page would just be a page
                      // with a hole in it.
                      //
                      // A SET, because the user can open several by hand and can
                      // mark several to open themselves (`startExpanded`).
                      //
                      // `null` is not the same as an empty set. Empty means the
                      // user closed everything during THIS session, which must
                      // survive; null means they have not touched anything yet,
                      // and resolves to exactly the stations marked
                      // open-by-default.
                      //
                      // Session state, deliberately. What you had open is not a
                      // setting you configured; the setting is `startExpanded`,
                      // and it is the only thing that survives a cold start.
                      var expandedIdsState by rememberSaveable {
                          mutableStateOf<List<String>?>(null)
                      }
                      val expandedIds: Set<String> = when {
                          // No station is open before we know which ones should
                          // be. Nothing renders in this window either (see the
                          // `prefsLoaded` gate around the cards), so this is not
                          // what the user sees — it keeps the height budget and
                          // `primaryStationId`, which ARE computed here, from
                          // being derived from a guess.
                          //
                          // Above the carousel branch because it is the more
                          // fundamental question: `homeLayout` comes from the
                          // same read and is itself a default until it lands.
                          !prefsLoaded -> emptySet()
                          // Every page is open in a carousel.
                          homeLayout == HomeLayout.CAROUSEL -> stationIds.toSet()
                          // ⚠️ NO exception for a single station.
                          //
                          // There used to be one — a lone station was forced
                          // open, on the reasoning that there is nothing to
                          // collapse FOR when no other card is competing for the
                          // room. True about the room, and it made the setting a
                          // lie: the settings screen offered Expanded/Collapsed,
                          // stored the choice, said "Applies when you switch it
                          // to List", and the home screen then ignored it and
                          // drew the card open. The user with one station is
                          // exactly the user most likely to go looking for why.
                          // The chevron is offered here too, for the same reason
                          // — see `collapsible` at the call site.
                          // Whatever the settings say, and NOTHING else. There
                          // used to be an `else` here force-opening the first
                          // station whenever no station was marked, so that the
                          // page "never opens on a list of shut drawers" — but
                          // that overrode a user who had deliberately collapsed
                          // every one of them, using a rule no screen stated and
                          // no setting could switch off. `startExpanded` now
                          // defaults to true, so a fresh install and every newly
                          // added station open by themselves; a page of shut
                          // drawers is now only ever something the user asked
                          // for.
                          //
                          // Read through the defaults, never off the map: rows
                          // equal to the defaults are pruned on write, so a
                          // board with default configuration is ABSENT from it,
                          // and `boardConfigs[it]?.expanded == true` would read
                          // the new default as false for exactly the boards that
                          // have never been configured.
                          expandedIdsState == null ->
                              stationIds.filter {
                                  (boardConfigs[it] ?: BoardConfig()).expanded
                              }.toSet()
                          // A station can be deleted while it is open, leaving an
                          // id that matches no card — which would then be counted
                          // in the height budget.
                          else -> expandedIdsState!!.filter { it in stationIds }.toSet()
                      }

                      // ── Changing "Default view" applies NOW, not next launch ──
                      //
                      // The stored flag is only the DEFAULT; `expandedIdsState`
                      // above is what is actually open, and it outranks the
                      // default on purpose once the user has touched a chevron.
                      // So a change made on the settings screen has to arrive as
                      // an instruction rather than be inferred from the value
                      // moving — see [BoardExpansion], which carries the full
                      // argument and the bug that produced it.
                      //
                      // Applied in every layout, including the carousel. There it
                      // changes nothing on screen (every page is open), but it
                      // leaves the session state correct for the moment the user
                      // switches back to a list.
                      LaunchedEffect(pendingExpansion, prefsLoaded, stationIds) {
                          if (!prefsLoaded || pendingExpansion.isEmpty()) return@LaunchedEffect
                          val applicable = pendingExpansion.filterKeys { it in stationIds }
                          // Nothing to act on yet. Left PENDING rather than
                          // consumed: the station may simply not have loaded, and
                          // dropping the request would lose a choice the user made.
                          if (applicable.isEmpty()) return@LaunchedEffect
                          val base = expandedIdsState?.toSet()
                              ?: stationIds.filter { (boardConfigs[it] ?: BoardConfig()).expanded }.toSet()
                          expandedIdsState = applicable.entries
                              .fold(base) { open, (id, wanted) -> if (wanted) open + id else open - id }
                              .toList()
                          BoardExpansion.consume(applicable.keys)
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
                      //
                      // A carousel shows exactly one card at a time, so it asks
                      // the same question with the counts it actually has: one
                      // open board, nothing collapsed, plus the dots.
                      //
                      // One station is not a carousel. There is nothing to swipe
                      // to, so the page renders as a plain card and must not be
                      // charged for a row of dots it will not draw.
                      val isCarousel = homeLayout == HomeLayout.CAROUSEL && stationIds.size > 1

                      // ── A widget was tapped: go to that station ──
                      //
                      // [BoardFocus] holds the request until somewhere can act on
                      // it, which matters on a COLD START: the URL is delivered
                      // while the boards are still coming out of SQLite, so the
                      // first pass through here has an empty [stationIds] and
                      // must leave the request alone rather than drop it. Keyed
                      // on the station list as well as the request for exactly
                      // that reason — the retry is the list arriving.
                      //
                      // Mirrored into local state before being consumed. The
                      // global has to be cleared promptly (a request left
                      // standing would re-fire on the next recomposition that
                      // changes the list), but the carousel needs to still see it
                      // on the frame after — so the local copy is what drives the
                      // layouts and it is never cleared, only replaced.
                      val requested by BoardFocus.target.collectAsStateWithLifecycle()
                      var focus by remember { mutableStateOf<BoardFocus.Target?>(null) }
                      val focusRequester = remember { BringIntoViewRequester() }
                      // Resolution only — this half knows nothing about which
                      // layout is on screen, so it cannot go stale when that
                      // changes.
                      LaunchedEffect(requested, stationIds) {
                          val wanted = requested ?: return@LaunchedEffect
                          if (stationIds.isEmpty()) return@LaunchedEffect
                          // The list has loaded, so this request has had its
                          // answer whether or not the station is in it. A widget
                          // pinned to a station since deleted lands here, and
                          // dropping it silently is right: the app simply opens.
                          BoardFocus.consume(wanted)
                          if (wanted.stationId in stationIds) focus = wanted
                      }

                      // The LIST's response: open the station and scroll to it.
                      // The carousel's is a page turn and lives in
                      // [StationCarousel], which is handed `focus` directly.
                      //
                      // A SEPARATE effect from the resolution above, for two
                      // reasons. `isCarousel` is a KEY here rather than something
                      // captured, so switching layout re-evaluates instead of
                      // running against a stale answer. And the requester is
                      // attached by the composition that setting `focus`
                      // triggers, so a `bringIntoView` called from the effect
                      // that sets it would run against a requester bound to no
                      // node at all and do nothing.
                      //
                      // Expanding before scrolling is safe even though the
                      // expansion animates: a card grows DOWNWARDS, so the top
                      // edge being scrolled to is already where it will end up.
                      LaunchedEffect(focus, isCarousel) {
                          val wanted = focus ?: return@LaunchedEffect
                          if (isCarousel) return@LaunchedEffect
                          // Open it ONLY when the user asked to see this board —
                          // a widget tap, where a collapsed card is not what they
                          // tapped. Everything else they had open is left alone,
                          // because rearranging the home screen is a bigger edit
                          // than the one that was asked for.
                          //
                          // ⚠️ A RESTORE must not open anything. It runs when the
                          // user comes BACK from this station's own settings, and
                          // this line used to reopen the card they had just set
                          // to Collapsed — every time, because this effect is
                          // declared after the one that applies the setting and
                          // therefore wins. Two correct features cancelling out.
                          if (wanted.kind == BoardFocus.Kind.REVEAL) {
                              expandedIdsState = (expandedIds + wanted.stationId).toList()
                          }
                          runCatching { focusRequester.bringIntoView() }
                      }

                      // How many leg rows the collapsed cards will draw between
                      // them — one per platform (rail) or pole (bus), since the
                      // collapsed card no longer stops at two.
                      //
                      // Counted from the CACHED rows via `blockCount`, never from
                      // the live legs: a platform that runs dry keeps its block
                      // for a few minutes, so this figure holds still while
                      // trains depart instead of re-flowing every open board
                      // underneath whatever the user is reading. It is therefore
                      // a stable upper bound — it can over-reserve one row
                      // briefly, and can never under-reserve and push the board
                      // off screen.
                      val collapsedLegCount = remember(
                          stationIds, expandedIds, selectionsByStation, predictions, isCarousel,
                      ) {
                          if (isCarousel) 0 else {
                              stationIds.filterNot { it in expandedIds }.sumOf { id ->
                                  val group = selectionsByStation[id].orEmpty()
                                  if (group.isEmpty()) 0 else MultiLineBoardProcessor.blockCount(
                                      feeds = group.map { selection ->
                                          MultiLineBoardProcessor.Feed(
                                              stationId = selection.station,
                                              line = selection.line,
                                              direction = selection.direction,
                                              predictions = predictions[selection.boardKey].orEmpty(),
                                          )
                                      },
                                      isBus = MultiLineBoardProcessor.isBus(group.first().mode),
                                  )
                              }
                          }
                      }

                      val primaryBoardMaxHeight = boardMaxHeight(
                          viewportHeight = maxHeight,
                          expandedCount = if (isCarousel) 1 else expandedIds.size,
                          collapsedCount = if (isCarousel) 0 else stationIds.size - expandedIds.size,
                          collapsedLegCount = collapsedLegCount,
                          chromeHeight = with(density) { chromePx.toDp() },
                          exploreHeight = with(density) { explorePx.toDp() },
                          bottomInset = bottomInset,
                          extraChrome = if (isCarousel) PAGER_DOTS_BLOCK else 0.dp,
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
                            // Banners are grouped so their COMBINED height can be
                            // measured in one place — the board's cap is whatever is
                            // left after them, and they come and go as they are
                            // dismissed. Both survivors are things the user can ACT
                            // on: an admin announcement, and a permission they can
                            // grant. Nothing here advertises a feature.
                            val notifBannerEnabled = (homeConfig["home.notif_denied.show"] ?: "true").equals("true", ignoreCase = true)

                            val hasChrome = announcement != null ||
                                (showNotificationDeniedBanner && notifBannerEnabled)

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
                            val stationGroups = stationIds.mapNotNull { id ->
                                selectionsByStation[id]?.let { id to it }
                            }

                            // One card, wherever it is being placed. Written once
                            // as a composable lambda because the two layouts differ
                            // only in how much height a card gets and whether it can
                            // be collapsed — everything else about a station card is
                            // the same in a list and in a carousel, and two copies of
                            // this call is two places for them to drift apart.
                            val stationCard: @Composable (String, List<UserSelection>, Boolean, Dp) -> Unit =
                                { stationId, groupSelections, collapsible, cardMaxHeight ->
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
                                    val config = boardConfigs[stationId] ?: BoardConfig()

                                    StationBoard(
                                        // Hung on the ONE card a widget tap is
                                        // asking for, and only in the list — the
                                        // carousel turns a page instead. Identity
                                        // everywhere else, so nothing about the
                                        // layout changes for the other cards.
                                        modifier = if (!isCarousel && focus?.stationId == stationId) {
                                            Modifier.bringIntoViewRequester(focusRequester)
                                        } else {
                                            Modifier
                                        },
                                        expanded = stationId in expandedIds,
                                        onToggleExpanded = if (collapsible) {
                                            {
                                                expandedIdsState =
                                                    if (stationId in expandedIds) (expandedIds - stationId).toList()
                                                    else (expandedIds + stationId).toList()
                                            }
                                        } else null,
                                        startsExpanded = config.expanded && !isCarousel,
                                        showHero = config.view.showsHero,
                                        boardPrefs = config,
                                        onOpenSettings = {
                                            onOpenStationSettings(stationId, primary.mode, primary.stationName)
                                        },
                                        stationName = primary.stationName,
                                        mode = primary.mode,
                                        sections = sections,
                                        homeConfig = homeConfig,
                                        isOnline = uiState.isOnline,
                                        maxHeight = cardMaxHeight,
                                        maxWidth = MAX_BOARD_WIDTH,
                                    )
                                }

                            // ── Nothing is drawn until it can be drawn RIGHT ──
                            //
                            // The stations arrive from one read and their
                            // arrangement from another, and the two races. Draw
                            // on whichever lands first and the home screen paints
                            // a state nobody configured and then corrects itself:
                            // expanded stations snapping shut, or collapsed ones
                            // springing open, a beat after launch. Either way the
                            // first thing the app does is contradict itself.
                            //
                            // Waiting costs a frame or two — this is one
                            // NSUserDefaults string, and it resolves long before
                            // the departures it would be shown alongside. What it
                            // buys is that the first painted frame IS the
                            // configured one: no correction, and no board built
                            // in a state it is about to leave.
                            //
                            // Deliberately renders NOTHING rather than a
                            // placeholder. A skeleton here would be a second
                            // wrong state on the way to the right one, and the
                            // screen around it — the header, the banners — is
                            // already up and already says the app is running.
                            if (prefsLoaded) {
                                if (isCarousel) {
                                    StationCarousel(
                                        stationGroups = stationGroups,
                                        pageHeight = primaryBoardMaxHeight,
                                        focus = focus,
                                    ) { stationId, groupSelections ->
                                        stationCard(stationId, groupSelections, false, primaryBoardMaxHeight)
                                    }
                                } else {
                                    stationGroups.forEach { (stationId, groupSelections) ->
                                        stationCard(
                                            stationId,
                                            groupSelections,
                                            // Collapsible whenever the card is in
                                            // a LIST, however many stations are
                                            // in it. It used to need a SECOND
                                            // station, which left the one-station
                                            // user with a stored setting, no
                                            // chevron to express it with, and a
                                            // card that ignored it anyway.
                                            !isCarousel,
                                            // The top open station gets the leftover
                                            // room; the rest get the floor, which is
                                            // three departures (MIN_BOARD_HEIGHT).
                                            if (stationId == primaryStationId) primaryBoardMaxHeight
                                            else MIN_BOARD_HEIGHT,
                                        )
                                    }
                                }
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

            OfflineBanner(
                visible = uiState.isBackendOffline,
                onRetry = { viewModel.retryLoad() },
                onDismiss = { viewModel.clearError() }
            )

            // ── Support, after a board lands ─────────────────────────────
            //
            // Bottom-aligned and floating, so it never enters the measured
            // chrome group above and never resizes the board. See the note on
            // `SupportBanner` for why that matters here specifically.
            val support = LocalSupport.current
            val supportState = support?.uiState?.collectAsStateWithLifecycle()?.value
            LaunchedEffect(homeConfig) { support?.onHomeConfig(homeConfig) }

            // Derived once, and used by both the threshold check and the
            // banner's own copy. One card is one STATION, so someone tracking
            // three lines at one stop has one board, not three.
            val boardCount = remember(selections) {
                BoardQuota.stationCount(selections.map { it.groupingId })
            }

            val boardAdded by SupportMoment.boardAdded.collectAsStateWithLifecycle()
            LaunchedEffect(boardAdded?.nonce) {
                if (boardAdded != null) {
                    support?.onBoardAdded(boardCount)
                    SupportMoment.consumeBoardAdded()
                }
            }

            if (support != null && supportState != null) {
                SupportBanner(
                    visible = supportState.bannerVisible,
                    icon = supportState.config.icon,
                    strings = supportState.strings,
                    boardCount = boardCount,
                    amountLabel = supportState.config.defaultTier
                        ?.let { formatMoney(it.amountMinor, supportState.config.currency) }
                        ?: "",
                    onSupport = { support.openSheet() },
                    onDismiss = { support.onBannerDismissed() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

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

            StationLimitSheet(
                visible = uiState.showStationLimitDialog,
                onDismiss = { viewModel.dismissStationLimitDialog() },
            )
            } // end padded content box

            // NO floating theme toggle. It sat at the bottom-right corner of
            // every home screen, above the home indicator, cycling
            // light → dark → system on each tap. Appearance now has one home, in
            // home settings, where all three options are visible at once and the
            // one that is set can be seen without tapping to find out. A control
            // permanently overlapping the board is a high price for a setting
            // changed twice a year.
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
            // The supporter mark rides the avatar the user already has, rather
            // than adding a permanent element to a top bar with three controls
            // in it. Read from the composition local here instead of threaded
            // down as a parameter: the top bar is composed near the top of the
            // screen and the support state is collected near the bottom, and
            // hoisting the collection that far up would recompose the whole
            // screen on every change to a badge that lives in one corner.
            //
            // `show_on_home` is the server's switch, so the mark can be turned
            // off without a release if it ever reads as bragging.
            val supportVm = LocalSupport.current
            val supportState = supportVm?.uiState?.collectAsStateWithLifecycle()?.value
            val showBadge = supportState?.isSupporter == true &&
                supportState.config.badge.showOnHome

            IconButton(onClick = onNavigateToProfile, modifier = Modifier.padding(start = 8.dp)) {
                Box(contentAlignment = Alignment.Center) {
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
                    // The ring is the top bar's own background, so the disc
                    // reads as sitting ON the avatar rather than as part of
                    // whatever a Google profile picture has in that corner.
                    //
                    // NO OFFSET. `IconButton` clips its content to a circle for
                    // the ripple, and the bottom-end corner is already the
                    // furthest point from that circle's centre that any content
                    // reaches. The old `offset(2.dp, 2.dp)` pushed the badge's
                    // outer edge past the clip, which sheared the side off the
                    // heart. See `SupporterAvatarBadge`.
                    SupporterAvatarBadge(
                        visible = showBadge,
                        size = 15.dp,
                        ringColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
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
            ?: "Get told when your line has delays or closures.",
        cta = strings["home.notif_denied.cta"] ?: "Enable",
        onCta = {
            com.stationly.app.platform.openAppNotificationSettings()
            onEnable()
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

// ── The update nudge used to live here ───────────────────────────────────
//
// Moved to `ui/update/UpdateSurfaces.kt`, rendered from `App`. It could only
// ever fire on this screen, its "Maybe Later" was a `remember` that reset on
// the next navigation back to home, and its store URL came from `app.storeUrl`
// — a Play Store link, served to both platforms, which sent iPhone users to
// Google Play.

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
