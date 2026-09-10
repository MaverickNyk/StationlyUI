package com.stationly.app.ui.station

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.ModeIconStore
import com.stationly.app.platform.performHaptic
import com.stationly.app.ui.common.AppBusy
import com.stationly.app.ui.common.LoadingOverlay
import com.stationly.app.ui.common.MINI_DEPARTURES
import com.stationly.app.ui.common.MiniBoard
import com.stationly.app.ui.common.MiniBoardDeparture
import com.stationly.app.ui.common.MiniBoardHeader
import com.stationly.app.ui.common.MiniBoardPalette
import com.stationly.app.ui.common.PickerTile
import com.stationly.app.ui.common.SegmentedRow
import com.stationly.app.ui.common.SettingsActionRow
import com.stationly.app.ui.common.SettingsCaption
import com.stationly.app.ui.common.SettingsCard
import com.stationly.app.ui.common.SettingsDivider
import com.stationly.app.ui.common.SettingsSectionLabel
import com.stationly.app.ui.summary.components.lineColorForTheme
import com.stationly.app.ui.theme.DisplayFamily
import com.stationly.app.ui.theme.isDarkTheme
import com.stationly.core.model.user.HomeLayout
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.BoardView
import com.stationly.core.repository.UserSettings
import com.stationly.core.model.UserSelection
import com.stationly.core.util.BoardLabels
import com.stationly.core.util.LineShortNames
import com.stationly.core.util.MultiLineBoardProcessor

/**
 * Scroll distance past which the station header collapses.
 *
 * Small on purpose: the header should shrink as soon as the page moves at all,
 * because the reason it shrinks is to give the settings the screen. A larger
 * value spends the first section of the page on a header nobody is reading any
 * more.
 *
 * In **dp**, converted at the call site. It was a raw pixel constant, which is a
 * different distance on every device — roughly 3x tighter on a 3x screen than on
 * the 1x one the number was chosen against.
 */
private val HEADER_COLLAPSE_DISTANCE = 6.dp

/**
 * Everything about ONE station, on its own screen.
 *
 * A screen rather than the popover this replaced. Three of the four things here
 * need more room than a menu row gives: the layout choice is best made by
 * SEEING the two layouts, the boards list is a list, and deleting a station is
 * destructive enough to deserve a page it cannot be tapped into by accident.
 *
 * @param stationId the GROUPING id (hub) — see [StationSettingsViewModel].
 * @param stationName carried in rather than looked up so the screen has a title
 *   on its first frame, before its own repository read returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSettingsScreen(
    stationId: String,
    stationName: String,
    mode: String,
    onBack: () -> Unit,
    /**
     * Open the line picker. The line id is non-null when the user tapped ONE
     * board's row, so the picker can open already expanded on it instead of
     * making them find it again — the filter lives two taps inside that line.
     */
    onEditLines: (String?) -> Unit,
    viewModel: StationSettingsViewModel = viewModel(key = "station-settings-$stationId") {
        StationSettingsViewModel(stationId)
    },
) {
    val boards by viewModel.boards.collectAsStateWithLifecycle()
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    // Deleting a board tears down its stream subscriptions and its widget rows,
    // which is not instant. Every control that could start another one is held
    // shut until it finishes — a second tap would race the `remaining` list the
    // teardown is computed from.
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val homeLayout by UserSettings.layout.collectAsStateWithLifecycle()
    // Whether a home-screen widget is showing this station, so the delete
    // confirmation can say what deleting it does to that widget. Absent means
    // not placed: the probe replaces this map wholesale and is never allowed to
    // report a FAILED look as an empty one, so a missing entry is an answer
    // rather than an unknown. See [WidgetPlacement].
    val widgets by UserSettings.widgets.collectAsStateWithLifecycle()
    val onHomeScreen = widgets[stationId]?.placed == true
    val platforms by viewModel.platforms.collectAsStateWithLifecycle()
    val stops by viewModel.stops.collectAsStateWithLifecycle()
    // Defaulted ONCE, here, so every control below reads the same thing the home
    // screen does. Rows equal to the defaults are dropped on write, so a board
    // nobody has configured is ABSENT from this map — and reading a field off it
    // with `?.` resolves every default to false. That cost real behaviour once:
    // the screen showed "Collapsed" selected while the home screen opened the
    // station expanded, and because the picker ignores a tap on the
    // already-selected segment, the user could not collapse it without first
    // tapping Expanded.
    val config = configs[stationId] ?: BoardConfig()
    val startExpanded = config.expanded
    val isDark = isDarkTheme()

    // Re-read the boards whenever this screen comes back to the front — the
    // line picker edits them through its OWN repository instance, so returning
    // from it would otherwise show the list as it was before the edit. Same
    // ON_RESUME hook the home screen uses, for the same reason.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var confirmDelete by remember { mutableStateOf(false) }
    var boardToDelete by remember { mutableStateOf<UserSelection?>(null) }
    // One `isDeleting` flag covers both kinds of delete, so the words are chosen
    // when the user confirms rather than inferred afterwards from state that has
    // already been cleared.
    var deleteLabel by remember { mutableStateOf("Removing…") }

    // The station this screen is about no longer exists, so neither should the
    // screen. Covers both routes out: deleting the station outright, and
    // deleting its last remaining board one at a time.
    //
    // The overlay is HANDED OVER before the pop, not dropped with it. This
    // screen's own `LoadingOverlay` below dies with the screen, so the user was
    // covered for the teardown and then uncovered for the arrival — the home
    // screen re-reading its repository, the remaining cards re-flowing into the
    // deleted one's space, the pager clamping to page zero. `AppBusy` is drawn
    // above the whole NavHost and is cleared by the home screen on entry, so the
    // two halves are one cover. See [AppBusy].
    LaunchedEffect(deleted) {
        if (!deleted) return@LaunchedEffect
        AppBusy.begin(deleteLabel)
        onBack()
    }

    // ── Which station am I editing? ──
    //
    // This screen is a long scroll — how it opens, what the card shows, board
    // arrangement, every line, delete — and the station used to be named only at
    // the very top of it, gone by the second section. Every heading below reads
    // the same whichever station you are on, so a user who scrolled had nothing
    // on screen telling them which of their stations they were about to change,
    // or delete.
    //
    // ## The header does not change into something else
    // It is the SAME block throughout — roundel, name, line tags — and scrolling
    // only makes it smaller and moves it to the centre. An earlier attempt faded
    // a plain text title into the app bar instead, which meant the thing you
    // scrolled away from and the thing you ended up with were two different
    // objects that happened to share a word.
    //
    // ## Two states, not a per-frame transform
    // The size is animated between an expanded and a collapsed state rather than
    // driven continuously from the scroll offset. Continuous would recompose the
    // header on every frame of every scroll, for a header that is only ever read
    // at one end or the other. Type and icon sizes are INTERPOLATED rather than
    // scaled with `graphicsLayer`, because a scaled font is a soft font and this
    // one comes to rest small.
    //
    // One threshold and a pure predicate. A two-threshold latch would need state
    // written from inside `derivedStateOf`, which is allowed to re-evaluate
    // whenever it likes — and the 220ms tween already absorbs the jitter a latch
    // would have been for.
    val scrollState = rememberScrollState()
    val collapseAfterPx = with(LocalDensity.current) { HEADER_COLLAPSE_DISTANCE.toPx() }
    val collapsedHeader by remember(collapseAfterPx) {
        derivedStateOf { scrollState.value > collapseAfterPx }
    }
    val headerT by animateFloatAsState(
        targetValue = if (collapsedHeader) 1f else 0f,
        animationSpec = tween(220),
        label = "station_header_collapse",
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // The bar carries the back button and nothing else. The station is
            // named by the header BELOW it, which is the same block whether the
            // page is scrolled or not — see the note at [collapsedHeader].
            Column {
                CenterAlignedTopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
                StationIdentity(
                    stationName = stationName,
                    mode = mode,
                    boards = boards,
                    isDark = isDark,
                    collapse = headerT,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        },
    ) { padding ->
      Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // No station identity here: it is pinned in the top bar, so it stays
            // legible for the whole scroll rather than being the thing that
            // scrolls away first.
            Spacer(Modifier.height(8.dp))

            // ── How this station appears on the home screen ──
            //
            // These two first and together, because they are one subject: does
            // it open, and what is in it when it does. They used to sit at
            // opposite ends of this screen with the board settings between them,
            // which put the two halves of one question two scrolls apart.
            //
            // ⚠️ Shown on BOTH home layouts, including the carousel.
            //
            // It used to be hidden whenever the home screen was a carousel, on
            // the reasoning that a station with a page to itself has nothing to
            // collapse. True, and still the wrong call: a user who went looking
            // for a setting they had seen before found the section simply gone,
            // with nothing to say why or where it went. A setting that is not in
            // force is a setting to explain, not one to hide — and switching the
            // home screen back to a list is one tap away, at which point the
            // choice made here applies immediately.
            run {
                // Two named states rather than a switch. "Open by default" made
                // the user work out what its off position meant, and the answer
                // (collapsed to its next departures, not hidden) is not obvious
                // enough to leave unsaid. Both words are on screen now.
                //
                // "When the app opens" named the MOMENT and left the setting
                // itself unlabelled, so the two words underneath had to carry
                // both what they were and when they applied. "Default view" then
                // named the setting but in the app's vocabulary rather than the
                // user's. This heading is a plain description of what the choice
                // below decides, and the captions say what each one does.
                //
                // No "Pin to top" here any more. Every station's settings screen
                // offered it, so every station could be pinned, and a rank that
                // everything can claim ranks nothing. Ordering moved to home
                // settings, where the stations are shown as one list and dragged
                // into the sequence the user wants.
                SettingsSectionLabel("How this station opens")
                ExpansionPicker(
                    startExpanded = startExpanded,
                    // ⚠️ NO "only if it changed" guard here, and that is the
                    // whole fix.
                    //
                    // This picker shows the stored DEFAULT. The card on the home
                    // screen shows the live SESSION, which outranks the default
                    // the moment the user touches a chevron. The two therefore
                    // disagree routinely — a station stored as Collapsed can be
                    // sitting open right now — and the guard swallowed exactly
                    // the tap that mattered: the user sees "Collapsed" already
                    // selected, taps it to collapse the card they can picture,
                    // and nothing at all is dispatched.
                    //
                    // A tap is an instruction about the board, not a diff against
                    // a stored value. The ViewModel still skips the WRITE when
                    // nothing changed.
                    onChoose = { wanted ->
                        performHaptic(HapticType.TAP)
                        viewModel.setExpanded(wanted)
                    },
                )
                // Both sentences are measured, so the page does not move when
                // the choice changes — see the list overload of SettingsCaption.
                SettingsCaption(
                    variants = listOf(
                        "Shows the next departure each way. Tap the card for the full board.",
                        "Shows the full board as soon as the app opens.",
                    ),
                    selected = if (startExpanded) 1 else 0,
                )
                if (homeLayout == HomeLayout.CAROUSEL) {
                    // Says what is true rather than removing the control: on a
                    // carousel every station already fills its own page, so this
                    // choice is stored and waiting rather than ignored.
                    SettingsCaption(
                        "Your home screen is set to Carousel, where every station " +
                            "fills its own page. This applies when you switch it to List."
                    )
                }

                Spacer(Modifier.height(28.dp))
            }

            SettingsSectionLabel("What the card shows")
            LayoutPicker(
                view = config.view,
                onChoose = {
                    if (it != config.view) {
                        performHaptic(HapticType.TAP)
                        viewModel.setView(it)
                    }
                },
            )
            // Says what each choice GIVES you, in the order you are choosing
            // between them. The old copy ("the countdown shows the soonest
            // train") described the thing rather than the decision, and its
            // second half — "the board gets its space" — never said that the
            // space buys real departure rows.
            //
            // ⚠️ One variant per [BoardView], in enum order — `selected` is an
            // index into `BoardView.entries`. There used to be a third variant
            // here, left behind when a third view was removed, and nothing could
            // ever select it: `indexOf` only ever returns 0 or 1. A list longer
            // than the enum is dead copy that reads as live copy.
            SettingsCaption(
                variants = listOf(
                    "The soonest departure is called out above the board, counting down.",
                    "No countdown above the board. That space becomes more departures.",
                ),
                selected = BoardView.entries.indexOf(config.view),
            )

            Spacer(Modifier.height(28.dp))

            // ── How the board itself is arranged ──
            //
            // Below the two above because it is a narrower question: those decide
            // where this station appears and what it contains, this decides what
            // the panel inside it does with the departures. Everything after is
            // about lines and deletion, which is different again.
            //
            // The grouping is not among these settings and must not become one —
            // BoardConfig in core carries the argument.
            BoardArrangementSection(
                prefs = config,
                platforms = platforms,
                stops = stops,
                // In selection order, which is the order the pills and the
                // boards list are already in. The picker is one more place the
                // user's own sequence should survive.
                lines = boards.map { it.line }.distinct(),
                isBus = MultiLineBoardProcessor.isBus(mode),
                onRowsPerPlatform = viewModel::setRowsPerPlatform,
                onPin = viewModel::setPin,
            )

            Spacer(Modifier.height(28.dp))

            // ── Boards ──
            SettingsSectionLabel("Lines and directions")
            SettingsCard {
                // Grouped by LINE, with a row per direction underneath.
                //
                // A flat list repeated the line name once per direction —
                // "Victoria, Victoria" — which reads as a duplicate rather than
                // as two boards, and gave the user two identical-looking rows to
                // choose between when they wanted to delete one of them. The
                // direction is the entire difference, so the direction is what
                // the deletable row is labelled with.
                //
                // `groupBy` keeps first-encounter order, so the lines stay in
                // the order they were picked.
                boards.groupBy { it.line }.forEach { (line, lineBoards) ->
                    LineGroup(
                        line = line,
                        boards = lineBoards,
                        color = lineColorForTheme(line, isDark),
                        onEditLine = { onEditLines(line) },
                        // The last board IS the station: removing it here would
                        // silently delete the station from a row that does not
                        // say so. Delete Station below is where that decision
                        // belongs, and it asks first.
                        onDeleteBoard = if (boards.size > 1 && !isDeleting) {
                            { boardToDelete = it }
                        } else null,
                    )
                    SettingsDivider()
                }
                SettingsActionRow(
                    icon = Icons.Rounded.Tune,
                    title = "Add or edit lines",
                    subtitle = "Add a line, change a direction, or set a filter",
                    onClick = { onEditLines(null) },
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Danger ──
            // "Delete", matching the row inside it and the dialog it opens. It
            // read "Remove" while everything under it said delete, which is two
            // words for one irreversible action.
            SettingsSectionLabel("Delete")
            SettingsCard(borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.28f)) {
                SettingsActionRow(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "Delete this station",
                    subtitle = if (boards.size == 1) {
                        "Its board and its live departures both go"
                    } else {
                        "All ${boards.size} boards and their live departures go"
                    },
                    tint = MaterialTheme.colorScheme.error,
                    enabled = !isDeleting,
                    onClick = { confirmDelete = true },
                )
            }
        }

        // ── Cover the delete ──
        //
        // Deleting tears down stream subscriptions, unsubscribes push topics,
        // clears the board's widget rows and rewrites the selection table, then
        // pops this screen. Uncovered, that read as the page juddering: rows
        // vanishing one at a time under the finger, the list reflowing, the
        // header resizing as the line tags go, and only then the navigation.
        //
        // Held up through `deleted` as well as `isDeleting`. The work finishes
        // slightly before the pop lands, and dropping the overlay in that gap
        // shows the half-emptied screen for a frame or two — which is the exact
        // thing it is here to hide.
        //
        // `LoadingOverlay` swallows every gesture, so a second tap during the
        // teardown cannot race the `remaining` list the teardown is computed
        // from.
        LoadingOverlay(
            visible = isDeleting || deleted,
            label = deleteLabel,
        )
      }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete $stationName?",
            // ── Warned BEFORE, not reported after ──
            //
            // A widget pinned to this station cannot be cleared: its station
            // lives in an AppIntent configuration that nothing on this side can
            // rewrite. So it stops showing departures and says the station was
            // removed, and only the user's own tap can point it somewhere else.
            // See `StationResolver` in the widget target.
            //
            // It used to switch to another station by itself, and this text used
            // to say so. That substitution is gone: a board is glanced at, not
            // read, so "wrong station, right-looking times" was the worst
            // failure available.
            //
            // Nothing is lost by deleting. The configured id survives, so
            // re-adding this station brings its widget back with no action from
            // the user, which is why the text below promises exactly that.
            body = buildString {
                append(
                    if (boards.size == 1) {
                        "Its board leaves your home screen. You can add it back any time."
                    } else {
                        "All ${boards.size} boards leave your home screen. You can add them back any time."
                    },
                )
                if (onHomeScreen) {
                    append("\n\nA widget is showing this station. It will stop showing departures. ")
                    // The same gesture, in the same words the widget itself uses
                    // in that state (`EmptyWidgetView`). Two surfaces describing
                    // one tap differently is how a user ends up hunting for a
                    // control that was named twice.
                    append("Touch and hold it, then tap Edit Widget, to choose another station.")
                }
            },
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                deleteLabel = "Deleting $stationName"
                viewModel.deleteStation()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    boardToDelete?.let { board ->
        ConfirmDialog(
            // Named by LINE AND DIRECTION. The row this opens from is one
            // direction of one line, and a line tracked both ways gives two rows
            // — so "Remove Victoria?" asked about a board the user could not
            // identify, on the one dialog where getting it wrong deletes the
            // other one.
            title = "Remove ${LineShortNames.displayName(board.line)} " +
                "${BoardLabels.directionPhrase(board)}?",
            body = "Only this board goes. The rest of $stationName stays.",
            confirmLabel = "Remove",
            onConfirm = {
                boardToDelete = null
                deleteLabel = "Removing ${LineShortNames.displayName(board.line)} " +
                    BoardLabels.directionPhrase(board)
                viewModel.deleteBoard(board)
            },
            onDismiss = { boardToDelete = null },
        )
    }
}

/**
 * Roundel, name, and what is actually being tracked here.
 *
 * The lines are named in their OWN colours rather than as one grey run-on. A
 * user reads "Victoria · Piccadilly" as a list of words; they recognise the
 * navy bar and the dark blue one without reading either. It is also the only
 * thing on this screen that says which lines are involved before the user has
 * scrolled to the boards list, and colour is what makes that legible at a
 * glance rather than a sentence to parse.
 *
 * Same colour source as the board, the line group below and the pin chips
 * ([lineColorForTheme]), so one line is one colour everywhere in the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationIdentity(
    stationName: String,
    mode: String,
    boards: List<UserSelection>,
    isDark: Boolean,
    /** 0 = full size, left-aligned. 1 = small, centred. */
    collapse: Float,
    modifier: Modifier = Modifier,
) {
    // Real sizes interpolated, not a `graphicsLayer` scale. This header comes to
    // REST at the small end, and a scaled font is a soft font — fine for a
    // transition, wrong for something you then sit and read.
    val iconSize = lerp(34.dp, 22.dp, collapse)
    val nameSize = lerp(26.sp, 17.sp, collapse)
    val gap = lerp(12.dp, 8.dp, collapse)
    val verticalPad = lerp(4.dp, 2.dp, collapse)

    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = verticalPad),
        // Start when open, centre when collapsed. The bias is what moves it;
        // nothing is re-laid-out to a different width.
        contentAlignment = BiasAlignment(horizontalBias = lerp(-1f, 0f, collapse), verticalBias = 0f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = remember(mode) { ModeIconStore.cachedIconBitmap(mode) }
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(iconSize))
            } else {
                Box(
                    modifier = Modifier.size(iconSize)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                )
            }
            Spacer(Modifier.width(gap))
            Column(
                // `fill = false` is what lets the alignment above work: the
                // Column takes only the width it needs, so the Row wraps its
                // content and there is slack for the bias to move it into.
                //
                // NOT conditional on [collapse]. Swapping the modifier at
                // exactly 1f re-measures the row on the final frame of the
                // animation, which lands as a visible hop right where the eye
                // has followed the movement to.
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    stationName,
                    fontFamily = DisplayFamily,
                    fontSize = nameSize,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    // One line once collapsed: the header is a label there, and
                    // a name that wraps to two lines while shrinking makes the
                    // page below it jump.
                    maxLines = if (collapse > 0.5f) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Lines, not board count: "2 lines" is what the user chose, where
                // "3 boards" is an implementation detail of tracking one of them
                // both ways.
                //
                // They stay through the collapse rather than fading out. They are
                // part of what identifies the station — the whole point of
                // keeping ONE header — and at 10sp they cost a few points of
                // height.
                val lines = boards.map { it.line }.distinct()
                if (lines.isNotEmpty()) {
                    Spacer(Modifier.height(lerp(6.dp, 3.dp, collapse)))
                    // Wraps rather than truncating when open. A station with four
                    // lines is exactly the one where knowing which four matters
                    // most, and an ellipsis would always hide the same ones —
                    // the end of the user's own ordering.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(lerp(6.dp, 4.dp, collapse)),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        lines.forEach { line ->
                            LineTag(line = line, isDark = isDark, collapse = collapse)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One line, named in its own colour.
 *
 * A tinted capsule rather than coloured text. Line colours are chosen to be
 * told apart from each other on a map, not to be legible as small type on a
 * dark or light background — Bakerloo brown and Waterloo & City turquoise both
 * fail as 13sp text. The colour goes in the fill and the rail, and the name
 * stays in the foreground ink, so the identity is carried by the swatch and the
 * legibility by the type.
 */
@Composable
private fun LineTag(line: String, isDark: Boolean, collapse: Float) {
    val color = lineColorForTheme(line, isDark)
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = lerp(7.dp, 6.dp, collapse), vertical = lerp(3.dp, 2.dp, collapse)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp)
                .height(lerp(11.dp, 9.dp, collapse))
                .background(color, RoundedCornerShape(1.5.dp))
        )
        Spacer(Modifier.width(lerp(6.dp, 5.dp, collapse)))
        Text(
            LineShortNames.displayName(line),
            fontSize = lerp(12.sp, 10.sp, collapse),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The two board layouts, drawn rather than described.
 *
 * "Hide next departure" was a sentence the user had to simulate in their head to
 * understand. These are small but honest renderings of the two real layouts —
 * same dark panel, same amber, hero block present or absent — so the choice is
 * made by looking rather than by parsing.
 */
@Composable
private fun LayoutPicker(view: BoardView, onChoose: (BoardView) -> Unit) {
    // Driven off the enum rather than hand-written tiles, so a view added there
    // cannot be missing here — and the label IS the enum's own, which is what
    // stops this screen and the rest of the app naming the same thing two ways.
    //
    // It said so before and did not do it: the tiles carried a local `when` with
    // "Next dept. + board" in it, an abbreviation of a label that was already
    // sitting unused on the enum. Two names for one view, and the shorter one
    // was the one people read.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BoardView.entries.forEach { option ->
            LayoutOption(
                label = option.label,
                withHero = option.showsHero,
                selected = option == view,
                onClick = { onChoose(option) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LayoutOption(
    label: String,
    withHero: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PickerTile(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        contentPadding = 10.dp,
        // The artwork here is a near-black board. A 2dp accent border around it
        // is easy to miss, so selection gets a second, redundant cue.
        showTick = true,
    ) {
        BoardPreview(withHero = withHero, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * A miniature of the real station, with plausible departures on it.
 *
 * Drawn with the real thing's ingredients — the surface hero with its line dot
 * and countdown, the black panel, the amber platform header and rows — rather
 * than grey placeholder bars. The choice being made here is "what does my station
 * look like", and bars cannot answer that: they show a shape where the user
 * needs to see a board.
 *
 * The data is fixed and fictional on purpose. It has to render identically for
 * every station, including one whose last train has gone, and a preview that
 * said "No departures" would be describing tonight rather than the layout.
 */
@Composable
private fun BoardPreview(withHero: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.aspectRatio(0.98f)) {
        if (withHero) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
            ) {
                Row(
                    modifier = Modifier
                        .background(accent.copy(alpha = 0.05f))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(3.dp).background(accent, CircleShape))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "NEXT DEPARTURE",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 6.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "\u2192 Brixton",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "2",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "min",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 2.dp, bottom = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        MiniBoard(
            modifier = Modifier.fillMaxWidth().weight(1f),
            background = MiniBoardPalette.Panel,
            borderColor = null,
            corner = 7.dp,
            padding = 5.dp,
            textSize = 7.sp,
            departures = emptyList(),
        ) {
            MiniBoardHeader("PLATFORM 3")
            MINI_DEPARTURES.take(2).forEach { MiniBoardDeparture(it, textSize = 7.sp) }
            if (!withHero) {
                // The rows the hero was costing. This is the whole point of the
                // choice, so the preview has to actually show them.
                MiniBoardHeader("PLATFORM 4")
                MINI_DEPARTURES.drop(2).forEach { MiniBoardDeparture(it, textSize = 7.sp) }
            }
        }
    }
}

/**
 * How this station starts on the home screen: Expanded or Collapsed.
 *
 * Two named states rather than a switch labelled with one of them. A switch
 * makes the user infer its off state, and here that inference is wrong as often
 * as not — "not open by default" sounds like the station is hidden, when in fact
 * it still shows its next departure each way.
 *
 * ## It is drawn as the station itself, not as a control
 * The glyph is the station header's own chevron, turned the way that chevron
 * actually points in each state: up for expanded, down for collapsed. One icon
 * rotated rather than two icons chosen — whatever pair could be found, the two
 * halves never read as exact opposites, and these have to.
 *
 * And the selected segment's tinted pill is the same accent, at the same weight,
 * as the disc that appears behind the chevron on the station. Choosing "Expanded"
 * here and going back shows the same shape in the same colour doing the same
 * job, which is what makes the two surfaces one setting rather than two.
 */
@Composable
private fun ExpansionPicker(startExpanded: Boolean, onChoose: (Boolean) -> Unit) {
    SegmentedRow(
        options = listOf(true, false),
        selected = startExpanded,
        onSelect = onChoose,
        label = { if (it) "Expanded" else "Collapsed" },
        icon = { Icons.Rounded.ExpandMore },
        iconRotation = { if (it) 180f else 0f },
    )
}

/**
 * One tracked LINE, with a row per direction beneath it.
 *
 * The line names itself once. Its directions are what the user acts on — each
 * is a board that can be deleted on its own — so each gets its own row, its own
 * filter caption and its own control.
 */
@Composable
private fun LineGroup(
    line: String,
    boards: List<UserSelection>,
    color: Color,
    onEditLine: () -> Unit,
    onDeleteBoard: ((UserSelection) -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(4.dp).height(18.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
            Text(
                LineShortNames.displayName(line),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                // Directions, not "boards". A board is what the code calls one
                // of these; a direction is what the user chose, and it is what
                // the rows underneath are.
                if (boards.size == 1) "1 direction" else "${boards.size} directions",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
        boards.forEach { board ->
            DirectionRow(
                board = board,
                onEdit = onEditLine,
                onDelete = onDeleteBoard?.let { delete -> { delete(board) } },
            )
        }
    }
}

/**
 * One direction of one line: which way it runs, what it shows, and a way to
 * remove just this board.
 *
 * Both lines come from the SAVED SELECTION and nothing else — see [BoardLabels],
 * which carries the argument for why. The second line is always present: a board
 * the user never narrowed says "All destinations" rather than saying nothing,
 * because silence there reads as missing information rather than as an answer.
 *
 * The filter is spelled out here because the board itself no longer says so —
 * the "VIA GREEN PARK" caption was removed from the dot-matrix panel to keep it
 * to station signage. This is where a filtered board explains itself.
 */
@Composable
private fun DirectionRow(board: UserSelection, onEdit: () -> Unit, onDelete: (() -> Unit)?) {
    val label = BoardLabels.forBoard(board)
    Row(
        modifier = Modifier.fillMaxWidth()
            // The row IS the way into this board's filter. It was inert, and the
            // only route to the thing it describes was the generic "Add or edit
            // lines" below, which reopens the whole picker on a collapsed list.
            .clickable(onClick = onEdit)
            .padding(start = 32.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label.detail,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                // Two lines, because this now names real destinations rather
                // than a single word. "To Ealing Broadway and Richmond" does not
                // fit beside a delete button at 11sp, and truncating it hides
                // the half the user came here to read.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    "Remove ${label.title} ${LineShortNames.displayName(board.line)}",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body, fontSize = 14.sp, lineHeight = 19.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
