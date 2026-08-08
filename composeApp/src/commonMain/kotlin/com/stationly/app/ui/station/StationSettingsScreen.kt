package com.stationly.app.ui.station

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.ModeIconStore
import com.stationly.app.platform.performHaptic
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
import com.stationly.app.ui.util.HomeLayout
import com.stationly.app.ui.util.StationPrefsRepository
import com.stationly.core.model.UserSelection
import com.stationly.core.util.BoardDisplayPrefs
import com.stationly.core.util.BoardLabels
import com.stationly.core.util.LineShortNames
import com.stationly.core.util.MultiLineBoardProcessor

/**
 * Everything about ONE station's card, on its own screen.
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
    onEditLines: () -> Unit,
    viewModel: StationSettingsViewModel = viewModel(key = "station-settings-$stationId") {
        StationSettingsViewModel(stationId)
    },
) {
    val boards by viewModel.boards.collectAsStateWithLifecycle()
    val towards by viewModel.towards.collectAsStateWithLifecycle()
    val prefsMap by viewModel.prefs.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    // Deleting a board tears down its stream subscriptions and its widget rows,
    // which is not instant. Every control that could start another one is held
    // shut until it finishes — a second tap would race the `remaining` list the
    // teardown is computed from.
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val homeLayout by StationPrefsRepository.layout.collectAsStateWithLifecycle()
    val platforms by viewModel.platforms.collectAsStateWithLifecycle()
    val prefs = prefsMap[stationId]
    val startExpanded = prefs?.startExpanded == true
    val heroVisible = prefs?.hideHero != true
    val board = prefs?.board ?: BoardDisplayPrefs()
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

    // The station this screen is about no longer exists, so neither should the
    // screen. Covers both routes out: deleting the station outright, and
    // deleting its last remaining board one at a time.
    LaunchedEffect(deleted) { if (deleted) onBack() }

    var confirmDelete by remember { mutableStateOf(false) }
    var boardToDelete by remember { mutableStateOf<UserSelection?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Station settings", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            StationIdentity(stationName = stationName, mode = mode, boards = boards)

            Spacer(Modifier.height(28.dp))

            // ── Layout ──
            SettingsSectionLabel("Card layout")
            LayoutPicker(
                heroVisible = heroVisible,
                onChoose = {
                    if (it != heroVisible) {
                        performHaptic(HapticType.TAP)
                        viewModel.setHeroVisible(it)
                    }
                },
            )
            SettingsCaption("The countdown shows the soonest train. Off, the board gets its space.")

            Spacer(Modifier.height(28.dp))

            // ── How the board itself is arranged ──
            //
            // Directly under the layout picker because the two are one subject:
            // that one decides what the card contains, this decides what the
            // panel inside it does with the departures. Everything below is
            // about lines and deletion, which is a different question.
            //
            // The grouping is not among these settings and must not become one —
            // BoardDisplayPrefs in core carries the argument.
            BoardArrangementSection(
                prefs = board,
                platforms = platforms,
                // In selection order, which is the order the pills and the
                // boards list are already in. The picker is one more place the
                // user's own sequence should survive.
                lines = boards.map { it.line }.distinct(),
                isBus = MultiLineBoardProcessor.isBus(mode),
                onSort = viewModel::setSort,
                onRowsPerPlatform = viewModel::setRowsPerPlatform,
                onPin = viewModel::setPin,
            )

            Spacer(Modifier.height(28.dp))

            // ── Expansion ──
            //
            // Two named states rather than a switch. "Open by default" made the
            // user work out what its off position meant, and the answer
            // (collapsed to a few legs, not hidden) is not obvious enough to
            // leave unsaid. Both words are on screen now.
            //
            // No "Pin to top" here any more. Every station's settings screen
            // offered it, so every station could be pinned, and a rank that
            // everything can claim ranks nothing. Ordering moved to home
            // settings, where the stations are shown as one list and dragged
            // into the sequence the user wants.
            //
            // Hidden in a carousel, where each station has a page to itself and
            // there is nothing to collapse.
            if (homeLayout != HomeLayout.CAROUSEL) {
                // "When the app opens" named the MOMENT and left the setting
                // itself unlabelled, so the two words underneath had to carry
                // both what they were and when they applied. "Default view" names
                // the setting; the caption says when.
                SettingsSectionLabel("Default view")
                ExpansionPicker(
                    startExpanded = startExpanded,
                    onChoose = {
                        if (it != startExpanded) {
                            performHaptic(HapticType.TAP)
                            viewModel.setStartExpanded(it)
                        }
                    },
                )
                SettingsCaption(
                    if (startExpanded) "Opens with the full board every time."
                    else "Opens showing the next departures. Tap it for the board."
                )

                Spacer(Modifier.height(28.dp))
            }

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
                        towards = towards,
                        color = lineColorForTheme(line, isDark),
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
                    subtitle = "Lines, directions and filters",
                    onClick = onEditLines,
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Danger ──
            SettingsSectionLabel("Remove")
            SettingsCard(borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.28f)) {
                SettingsActionRow(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "Delete this station",
                    subtitle = if (boards.size == 1) {
                        "Removes its board and stops updates"
                    } else {
                        "Removes all ${boards.size} boards and stops updates"
                    },
                    tint = MaterialTheme.colorScheme.error,
                    enabled = !isDeleting,
                    onClick = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete $stationName?",
            body = if (boards.size == 1) {
                "Its board leaves your home screen. You can add it back any time."
            } else {
                "All ${boards.size} boards leave your home screen. You can add them back any time."
            },
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; viewModel.deleteStation() },
            onDismiss = { confirmDelete = false },
        )
    }

    boardToDelete?.let { board ->
        ConfirmDialog(
            title = "Remove ${LineShortNames.displayName(board.line)}?",
            body = "Only this board goes. The rest of $stationName stays.",
            confirmLabel = "Remove",
            onConfirm = { boardToDelete = null; viewModel.deleteBoard(board) },
            onDismiss = { boardToDelete = null },
        )
    }
}

/** Roundel, name, and what is actually being tracked here. */
@Composable
private fun StationIdentity(
    stationName: String,
    mode: String,
    boards: List<UserSelection>,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = remember(mode) { ModeIconStore.cachedIconBitmap(mode) }
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp))
        } else {
            Box(
                modifier = Modifier.size(34.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stationName,
                fontFamily = DisplayFamily,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // Lines, not board count: "2 lines" is what the user chose,
                // where "3 boards" is an implementation detail of tracking one
                // of them both ways.
                boards.map { it.line }.distinct()
                    .joinToString(" · ") { LineShortNames.displayName(it) }
                    .ifBlank { "No lines yet" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The two card layouts, drawn rather than described.
 *
 * "Hide next departure" was a sentence the user had to simulate in their head to
 * understand. These are small but honest renderings of the two real layouts —
 * same dark panel, same amber, hero block present or absent — so the choice is
 * made by looking rather than by parsing.
 */
@Composable
private fun LayoutPicker(heroVisible: Boolean, onChoose: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LayoutOption(
            label = "Countdown + board",
            withHero = true,
            selected = heroVisible,
            onClick = { onChoose(true) },
            modifier = Modifier.weight(1f),
        )
        LayoutOption(
            label = "Board only",
            withHero = false,
            selected = !heroVisible,
            onClick = { onChoose(false) },
            modifier = Modifier.weight(1f),
        )
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
 * A miniature of the real card, with plausible departures on it.
 *
 * Drawn with the real thing's ingredients — the surface hero with its line dot
 * and countdown, the black panel, the amber platform header and rows — rather
 * than grey placeholder bars. The choice being made here is "what does my card
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
 * How this station's card starts: Expanded or Collapsed.
 *
 * Two named states rather than a switch labelled with one of them. A switch
 * makes the user infer its off state, and here that inference is wrong as often
 * as not — "not open by default" sounds like the station is hidden, when in fact
 * it still shows its next departure each way.
 *
 * ## It is drawn as the card, not as a control
 * The glyph is the card header's own chevron, turned the way that card's chevron
 * actually points in each state: up for expanded, down for collapsed. One icon
 * rotated rather than two icons chosen — whatever pair could be found, the two
 * halves never read as exact opposites, and these have to.
 *
 * And the selected segment's tinted pill is the same accent, at the same weight,
 * as the disc that appears behind the chevron on the card. Choosing "Expanded"
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
    towards: Map<String, String>,
    color: Color,
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
                if (boards.size == 1) "1 board" else "${boards.size} boards",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
        boards.forEach { board ->
            DirectionRow(
                board = board,
                towards = towards[board.boardKey],
                onDelete = onDeleteBoard?.let { delete -> { delete(board) } },
            )
        }
    }
}

/**
 * One direction of one line: where it goes, what it filters, and a way to
 * remove just this board.
 *
 * The filter is spelled out here because the board itself no longer says so —
 * the "VIA GREEN PARK" caption was removed from the dot-matrix panel to keep it
 * to station signage. This is where a filtered board explains itself.
 */
@Composable
private fun DirectionRow(board: UserSelection, towards: String?, onDelete: (() -> Unit)?) {
    val label = BoardLabels.forBoard(board, towards)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 8.dp, top = 6.dp),
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
            label.detail?.let { line ->
                Spacer(Modifier.height(2.dp))
                Text(
                    line,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
