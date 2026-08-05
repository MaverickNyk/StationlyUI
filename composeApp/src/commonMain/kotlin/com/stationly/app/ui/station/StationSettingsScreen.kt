package com.stationly.app.ui.station

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
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
import com.stationly.app.ui.summary.components.lineColorForTheme
import com.stationly.app.ui.theme.DisplayFamily
import com.stationly.app.ui.theme.isDarkTheme
import com.stationly.core.model.UserSelection
import com.stationly.core.util.BoardLabels
import com.stationly.core.util.LineShortNames

/**
 * The dot-matrix palette, mirrored from `Board.kt` for the layout previews.
 *
 * Fixed regardless of app theme, exactly as the real board is — a preview whose
 * panel went white in light mode would be showing the user a board that does not
 * exist.
 */
private val PreviewPanelBg = Color(0xFF0C0C0C)
private val PreviewAmber = Color(0xFFFFC819)
private val PreviewActiveRow = Color(0xFF161616)

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
    val prefs = prefsMap[stationId]
    val openByDefault = prefs?.openByDefault == true
    val heroVisible = prefs?.hideHero != true
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
            SectionLabel("Card layout")
            LayoutPicker(
                heroVisible = heroVisible,
                onChoose = {
                    if (it != heroVisible) {
                        performHaptic(HapticType.TAP)
                        viewModel.setHeroVisible(it)
                    }
                },
            )
            SectionCaption(
                "The countdown is the single soonest departure across every line " +
                    "you track here, in large type. Turning it off gives the board " +
                    "its space instead — the same departures, more of them on screen."
            )

            Spacer(Modifier.height(28.dp))

            // ── Expansion ──
            //
            // No "Pin to top" here any more. Every station's settings screen
            // offered it, so every station could be pinned, and a rank that
            // everything can claim ranks nothing. Ordering moved to home
            // settings, where the stations are shown as one list and dragged
            // into the sequence the user wants — the only place a ranking can
            // actually be expressed.
            SectionLabel("On your home screen")
            SettingsCard {
                ToggleRow(
                    icon = Icons.Rounded.OpenInFull,
                    title = "Open by default",
                    subtitle = if (openByDefault) {
                        "Its board is already open every time you launch the app."
                    } else {
                        "Starts collapsed to its next departures. Tap the header to open it."
                    },
                    checked = openByDefault,
                    onCheckedChange = {
                        performHaptic(HapticType.TAP)
                        viewModel.setOpenByDefault(it)
                    },
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Boards ──
            SectionLabel("Lines and directions")
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
                    HairlineDivider()
                }
                ActionRow(
                    icon = Icons.Rounded.Tune,
                    title = "Add or edit lines",
                    subtitle = "Pick lines, directions and destination filters",
                    onClick = onEditLines,
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Danger ──
            SectionLabel("Remove")
            SettingsCard(borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.28f)) {
                ActionRow(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "Delete this station",
                    subtitle = if (boards.size == 1) {
                        "Removes this board and stops its live updates"
                    } else {
                        "Removes all ${boards.size} boards and stops their live updates"
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
                "Its board disappears from your home screen and stops updating. " +
                    "You can add the station again at any time."
            } else {
                "All ${boards.size} boards here disappear from your home screen and " +
                    "stop updating. You can add the station again at any time."
            },
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; viewModel.deleteStation() },
            onDismiss = { confirmDelete = false },
        )
    }

    boardToDelete?.let { board ->
        ConfirmDialog(
            title = "Remove ${LineShortNames.displayName(board.line)}?",
            body = "Only this board is removed. The rest of $stationName stays as it is.",
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
    val accent = MaterialTheme.colorScheme.primary
    val borderColor by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
        label = "layout_border",
    )
    val borderWidth by animateDpAsState(if (selected) 2.dp else 1.dp, label = "layout_border_w")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        BoardPreview(withHero = withHero, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (selected) 1f else 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Box(
                    modifier = Modifier.size(16.dp).background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
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
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(7.dp),
            color = PreviewPanelBg,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                PreviewHeaderRow("PLATFORM 3")
                PreviewDepartureRow("Brixton", "2 min")
                PreviewDepartureRow("Walthamstow", "5 min")
                if (!withHero) {
                    // The rows the hero was costing. This is the whole point of
                    // the choice, so the preview has to actually show them.
                    PreviewHeaderRow("PLATFORM 4")
                    PreviewDepartureRow("Seven Sisters", "7 min")
                    PreviewDepartureRow("Brixton", "9 min")
                }
            }
        }
    }
}

@Composable
private fun PreviewHeaderRow(title: String) {
    Box(
        modifier = Modifier.fillMaxWidth().background(PreviewActiveRow).padding(vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            color = PreviewAmber,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PreviewDepartureRow(destination: String, eta: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(PreviewActiveRow).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            destination,
            color = PreviewAmber,
            fontSize = 7.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        Text(eta, color = PreviewAmber, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
    )
}

@Composable
private fun SettingsCard(
    borderColor: Color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column { content() }
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
    )
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = tint)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp),
        )
    }
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
