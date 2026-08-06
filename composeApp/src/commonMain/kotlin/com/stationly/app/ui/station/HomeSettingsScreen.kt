package com.stationly.app.ui.station

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.openAppNotificationSettings
import com.stationly.app.platform.performHaptic
import com.stationly.app.ui.common.PickerTile
import com.stationly.app.ui.common.SegmentedRow
import com.stationly.app.ui.common.SettingsActionRow
import com.stationly.app.ui.common.SettingsCaption
import com.stationly.app.ui.common.SettingsCard
import com.stationly.app.ui.common.SettingsDivider
import com.stationly.app.ui.common.SettingsSectionLabel
import com.stationly.app.ui.theme.AppTheme
import com.stationly.app.ui.theme.LocalAppTheme
import com.stationly.app.ui.util.HomeLayout
import com.stationly.app.ui.util.StationPrefsRepository
import com.stationly.core.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Settings for the home screen as a whole, reached from the gear in the top bar.
 *
 * Everything here applies across stations. Anything that is a property of ONE
 * station — its layout, its lines — belongs on that station's own
 * settings screen, which is one tap away from its card; splitting the two is
 * what keeps either list short enough to read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSettingsScreen(
    onBack: () -> Unit,
    onOpenScreensaver: () -> Unit,
    /** Straight through to that station's own settings — see [StationOrderCard]. */
    onOpenStationSettings: (stationId: String, mode: String, stationName: String) -> Unit = { _, _, _ -> },
) {
    val themeState = LocalAppTheme.current
    val savedOrder by StationPrefsRepository.order.collectAsStateWithLifecycle()
    val homeLayout by StationPrefsRepository.layout.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Every tracked station, in the user's own order. Names and modes as well as
    // ids: this list is something the user READS and rearranges, not just a set
    // of keys.
    var stations by remember { mutableStateOf<List<HomeStation>>(emptyList()) }
    LaunchedEffect(Unit) {
        StationPrefsRepository.ensureLoaded()
        stations = withContext(Dispatchers.Default) {
            val byId = Platform.sqlStorage.getAllSelections()
                .groupBy { it.groupingId }
            StationPrefsRepository.orderedIds(byId.keys.toList()).mapNotNull { id ->
                byId[id]?.firstOrNull()?.let { first ->
                    HomeStation(
                        id = id,
                        name = first.stationName,
                        mode = first.mode,
                        lines = byId.getValue(id).map { it.line }.distinct(),
                    )
                }
            }
        }
    }
    // A reorder made HERE writes the saved order, and this pulls the list back
    // into line with it. Cheap insurance rather than a second source of truth:
    // the drag already updates `stations` optimistically, and this only matters
    // if the write is rejected or something else reorders while the screen is up.
    LaunchedEffect(savedOrder) {
        if (stations.isNotEmpty()) {
            val ordered = StationPrefsRepository.orderedIds(stations.map { it.id })
            if (ordered != stations.map { it.id }) {
                stations = ordered.mapNotNull { id -> stations.firstOrNull { it.id == id } }
            }
        }
    }

    // A row being dragged owns the whole gesture, and the page must hold still
    // under it. The drag detector already claims its events before the scroller
    // can see them (see [StationOrderCard]); this is the second lock, and the
    // one that also stops a fling started elsewhere from carrying on mid-drag.
    var reordering by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Home settings", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
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
                .verticalScroll(rememberScrollState(), enabled = !reordering)
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // ── Layout ──
            //
            // First, because it decides what the rest of this screen means: the
            // order below is a top-to-bottom order in a list and a left-to-right
            // one in a carousel, and the per-station Expanded setting only
            // applies to the list.
            SettingsSectionLabel("Layout")
            LayoutPicker(
                selected = homeLayout,
                onSelect = { option ->
                    if (option != homeLayout) {
                        performHaptic(HapticType.TAP)
                        scope.launch { StationPrefsRepository.setLayout(option) }
                    }
                },
            )
            SettingsCaption(
                if (homeLayout == HomeLayout.CAROUSEL) "Swipe left and right between stations."
                else "All your stations on one scrolling page."
            )

            Spacer(Modifier.height(28.dp))

            // ── Order ──
            //
            // Where "Pin to top" ended up, and the reason it could not stay a
            // per-station switch: pinning is a claim about POSITION, and a
            // position only exists relative to the other stations. Offered once
            // per station, every station could hold it, and four pinned stations
            // are just four stations. Here the whole list is present at once, so
            // the user states the order directly.
            //
            // Only past one station. A drag handle on a list of one is an
            // affordance for something that cannot happen.
            if (stations.size > 1) {
                SettingsSectionLabel("Your stations")
                // The control matches the layout it arranges: a stack for the
                // list, a strip for the carousel. Ordering a set of side-by-side
                // pages in a top-to-bottom list would make the user map one
                // picture onto the other every time.
                val reorder: (List<HomeStation>) -> Unit = { reordered ->
                    stations = reordered
                    scope.launch { StationPrefsRepository.setOrder(reordered.map { it.id }) }
                }
                val openStation: (HomeStation) -> Unit = { s ->
                    performHaptic(HapticType.TAP)
                    onOpenStationSettings(s.id, s.mode, s.name)
                }
                if (homeLayout == HomeLayout.CAROUSEL) {
                    StationOrderStrip(
                        stations = stations,
                        onReorder = reorder,
                        onOpen = openStation,
                        onDraggingChange = { reordering = it },
                    )
                    SettingsCaption("Hold and drag a page left or right. Tap for settings.")
                } else {
                    StationOrderCard(
                        stations = stations,
                        onReorder = reorder,
                        onOpen = openStation,
                        onDraggingChange = { reordering = it },
                    )
                    SettingsCaption("Hold and drag to reorder. Tap for settings.")
                }

                Spacer(Modifier.height(28.dp))
            }

            // ── Elsewhere ──
            //
            // No station-wide board section. It held bulk versions of the
            // per-station switches, and each was a second way to set something
            // that already has one home on the station's own settings screen.
            SettingsSectionLabel("More")
            SettingsCard {
                SettingsActionRow(
                    icon = Icons.Rounded.Bedtime,
                    title = "Screensaver",
                    subtitle = "Live departures while charging",
                    onClick = onOpenScreensaver,
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Rounded.Notifications,
                    title = "Notifications",
                    subtitle = "Open iOS notification settings",
                    onClick = { openAppNotificationSettings() },
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Appearance, last ──
            //
            // A theme is set once and then never thought about again, while the
            // station list is the thing someone came here to change. One
            // segmented row rather than three full rows, for the same reason.
            SettingsSectionLabel("Appearance")
            ThemeSegmentedRow(
                selected = themeState.theme,
                onSelect = { option ->
                    if (themeState.theme != option) {
                        performHaptic(HapticType.TAP)
                        themeState.onChange(option)
                    }
                },
            )
            SettingsCaption("The departure board stays dark in every theme.")
        }
    }
}


/* ── Layout picker ──────────────────────────────────────────────────────── */

/**
 * List or carousel, drawn rather than described.
 *
 * The same argument as the station card's layout picker: the choice is about
 * what the home screen will LOOK like, and a sentence makes the user build the
 * picture themselves. Two tiny mocks answer it at a glance — three stacked cards
 * against one card with pages either side.
 */
@Composable
private fun LayoutPicker(selected: HomeLayout, onSelect: (HomeLayout) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeLayout.entries.forEach { option ->
            LayoutTile(
                layout = option,
                selected = option == selected,
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LayoutTile(
    layout: HomeLayout,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    PickerTile(
        label = layout.label,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) {
            when (layout) {
                HomeLayout.LIST -> ListMock(accent, selected)
                HomeLayout.CAROUSEL -> CarouselMock(accent, selected)
            }
        }
    }
}

/** Three stacked cards, the top one open. */
@Composable
private fun ListMock(accent: Color, selected: Boolean) {
    val on = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = Modifier.fillMaxWidth(0.66f).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    if (selected) accent.copy(alpha = 0.35f) else on.copy(alpha = 0.16f),
                    RoundedCornerShape(4.dp),
                )
        )
        repeat(2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .background(on.copy(alpha = 0.14f), RoundedCornerShape(3.dp))
            )
        }
    }
}

/** One card centre stage, its neighbours peeking, dots underneath. */
@Composable
private fun CarouselMock(accent: Color, selected: Boolean) {
    val on = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight(0.72f)
                    .background(on.copy(alpha = 0.14f), RoundedCornerShape(3.dp))
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (selected) accent.copy(alpha = 0.35f) else on.copy(alpha = 0.16f),
                        RoundedCornerShape(4.dp),
                    )
            )
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight(0.72f)
                    .background(on.copy(alpha = 0.14f), RoundedCornerShape(3.dp))
            )
        }
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(
                            if (i == 1) {
                                if (selected) accent else on.copy(alpha = 0.5f)
                            } else on.copy(alpha = 0.18f),
                            CircleShape,
                        )
                )
            }
        }
    }
}

/**
 * The three themes as one row of equal segments.
 *
 * A segmented control rather than a list of rows because the options are three
 * words with three icons and are mutually exclusive — the thing a user wants from
 * this is to see all three and tap one, which a list makes them scroll to do.
 *
 * Icon and label sit on ONE line. Stacked, the row was as tall as a card for a
 * choice that is made once. The control itself is [SegmentedRow], shared with the
 * station screen's expanded/collapsed picker so the two cannot drift apart.
 */
@Composable
private fun ThemeSegmentedRow(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    SegmentedRow(
        options = AppTheme.entries,
        selected = selected,
        onSelect = onSelect,
        label = { it.displayName },
        icon = {
            when (it) {
                AppTheme.LIGHT -> Icons.Rounded.LightMode
                AppTheme.DARK -> Icons.Rounded.DarkMode
                AppTheme.SYSTEM -> Icons.Rounded.BrightnessAuto
            }
        },
    )
}
