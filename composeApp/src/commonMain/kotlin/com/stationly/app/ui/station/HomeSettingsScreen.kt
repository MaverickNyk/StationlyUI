package com.stationly.app.ui.station

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.ModeIconStore
import com.stationly.app.platform.openAppNotificationSettings
import com.stationly.app.platform.performHaptic
import com.stationly.app.ui.summary.components.lineColorForTheme
import com.stationly.app.ui.theme.AppTheme
import com.stationly.app.ui.theme.isDarkTheme
import com.stationly.app.ui.theme.LocalAppTheme
import com.stationly.app.ui.util.StationPrefsRepository
import com.stationly.core.platform.Platform
import com.stationly.core.util.ListReorder.moved
import com.stationly.core.util.LineShortNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val scope = rememberCoroutineScope()

    // Every tracked station, in the user's own order. Names and modes as well as
    // ids: this list is now something the user READS and rearranges, not just a
    // set of keys for the bulk actions to iterate.
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // ── Order ──
            //
            // Where "Pin to top" ended up, and the reason it could not stay a
            // per-station switch: pinning is a claim about POSITION, and a
            // position only exists relative to the other stations. Offered once
            // per station, every station could hold it, and four pinned stations
            // are just four stations. Here the whole list is present at once, so
            // the user states the order directly — which is what they were
            // reaching for.
            //
            // Only past one station. A drag handle on a list of one is an
            // affordance for something that cannot happen.
            if (stations.size > 1) {
                HomeSectionLabel("Your stations")
                StationOrderCard(
                    stations = stations,
                    onReorder = { reordered ->
                        stations = reordered
                        scope.launch { StationPrefsRepository.setOrder(reordered.map { it.id }) }
                    },
                    onOpen = { s ->
                        performHaptic(HapticType.TAP)
                        onOpenStationSettings(s.id, s.mode, s.name)
                    },
                )
                HomeCaption("Use the arrows to reorder. Tap a station for its own settings.")

                Spacer(Modifier.height(28.dp))
            }

            // NO station-wide board section here.
            //
            // It held bulk versions of the per-station switches — open every
            // station on launch, collapse every station, show every countdown —
            // and all of them are gone. Each was a second way to set something
            // that already has one home on the station's own settings screen, and
            // a settings screen that offers to change four stations at once for a
            // choice the user makes per station is offering a shortcut nobody
            // asked for. What belongs here is what is genuinely about the home
            // screen AS A WHOLE, which is the order above and the rows below.

            // ── Elsewhere ──
            HomeSectionLabel("More")
            HomeCard {
                ActionRowSimple(
                    icon = Icons.Rounded.Bedtime,
                    title = "Screensaver",
                    subtitle = "Turn your phone into a departure board while it charges",
                    onClick = onOpenScreensaver,
                )
                HomeDivider()
                ActionRowSimple(
                    icon = Icons.Rounded.Notifications,
                    title = "Notifications",
                    subtitle = "Opens Stationly's notification settings in iOS",
                    onClick = { openAppNotificationSettings() },
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Appearance, last ──
            //
            // It used to open this screen, above the stations, which had it
            // backwards: a theme is set once and then never thought about again,
            // while the station list is the thing someone came here to change.
            // Three full rows for a choice with three options was also more space
            // than the decision deserves — one segmented row says the same thing
            // in a quarter of the height and shows all three at once.
            HomeSectionLabel("Appearance")
            ThemeSegmentedRow(
                selected = themeState.theme,
                onSelect = { option ->
                    if (themeState.theme != option) {
                        performHaptic(HapticType.TAP)
                        themeState.onChange(option)
                    }
                },
            )
            HomeCaption(
                "The departure board itself stays dark amber in every theme — that " +
                    "is signage, not app chrome."
            )
        }
    }
}


/** One tracked station, as the order list needs it. */
private data class HomeStation(
    val id: String,
    val name: String,
    val mode: String,
    /** Line ids, in the order the user picked them — displayed, so keep it stable. */
    val lines: List<String>,
)


/** Height of one row in the order list. The slot animation depends on it being fixed. */
private val ORDER_ROW_HEIGHT = 62.dp

/**
 * The station list, reordered with the arrows on each row.
 *
 * ## Why arrows and not drag-and-drop
 * Drag-to-reorder was built three times for this list and never worked; the
 * attempts and the reason each failed are written up in
 * `docs/IOS_HANDOVER.md` §6d, because the next person to try deserves them.
 * Ordering is the only way to arrange the home screen since "Pin to top" was
 * removed, so shipping a gesture that does not respond was not an option: these
 * arrows are plain `clickable`s with no gesture arbitration anywhere near them,
 * and they cannot fail the way the drag did.
 *
 * They are not a downgrade in every respect, either. A drag needs a steady hand
 * on a moving target; two taps do not, and the result is easier to undo.
 *
 * ## The rows still animate
 * The list is a fixed-height [Box] with each row placed at `index * ROW_HEIGHT`
 * and that position animated, rather than a [Column] of stacked rows. Tapping an
 * arrow changes the committed order, the two affected rows' indices swap, and they
 * glide past each other — so the change is something the user watches happen
 * rather than something that has already happened by the time they look. `key`
 * ties each row's animation to its station, so the right one moves.
 *
 * Every position read comes from the caller's list, which is the single source of
 * truth; this composable holds no order state of its own to drift out of sync.
 */
@Composable
private fun StationOrderCard(
    stations: List<HomeStation>,
    onReorder: (List<HomeStation>) -> Unit,
    onOpen: (HomeStation) -> Unit,
) {
    val rowPx = with(LocalDensity.current) { ORDER_ROW_HEIGHT.toPx() }

    fun move(from: Int, to: Int) {
        if (to !in stations.indices) return
        performHaptic(HapticType.TAP)
        onReorder(stations.moved(from, to))
    }

    HomeCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ORDER_ROW_HEIGHT * stations.size)
        ) {
            stations.forEachIndexed { index, station ->
                key(station.id) {
                    // Read inside `offset {}` below, so a row settling into its
                    // new place re-places itself without recomposing its contents.
                    val slot = animateFloatAsState(
                        targetValue = index * rowPx,
                        animationSpec = spring(
                            dampingRatio = 0.9f,
                            stiffness = Spring.StiffnessMedium,
                            visibilityThreshold = 0.5f,
                        ),
                        label = "row_slot",
                    )
                    StationOrderRow(
                        station = station,
                        position = index + 1,
                        canMoveUp = index > 0,
                        canMoveDown = index < stations.lastIndex,
                        onMoveUp = { move(index, index - 1) },
                        onMoveDown = { move(index, index + 1) },
                        onOpen = { onOpen(station) },
                        modifier = Modifier.offset {
                            IntOffset(x = 0, y = slot.value.roundToInt())
                        },
                    )
                }
            }
        }
    }
}

/** One row of the order list: position, mode roundel, station name, its lines. */
@Composable
private fun StationOrderRow(
    station: HomeStation,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ORDER_ROW_HEIGHT)
            // The row opens the station; the arrows are their own targets and take
            // their own taps. Nested `clickable`s, no gesture arbitration.
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Position, stated plainly. The order is the entire point of this list,
        // and a number says where a row sits without the user counting.
        Text(
            "$position",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.width(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        // The real backend roundel — the same cached bitmap the card header and
        // the widget draw, so a station looks like itself everywhere. The tinted
        // disc is the pre-first-sync fallback, not a second design.
        val icon = remember(station.mode) { ModeIconStore.cachedIconBitmap(station.mode) }
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(26.dp))
        } else {
            Box(
                modifier = Modifier.size(26.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                station.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A dot per line in its own colour, then the names. The dots
                // survive the truncation the names may not, so a station with
                // four lines still shows that it has four.
                station.lines.take(4).forEach { line ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(lineColorForTheme(line, isDarkTheme()), CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    station.lines.joinToString(" · ") { LineShortNames.displayName(it) }
                        .ifBlank { "No lines yet" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Disabled rather than hidden at the ends of the list: a control that
        // disappears makes the row jump and makes the user re-find the other one.
        MoveButton(Icons.Rounded.KeyboardArrowUp, "Move ${station.name} up", canMoveUp, onMoveUp)
        MoveButton(Icons.Rounded.KeyboardArrowDown, "Move ${station.name} down", canMoveDown, onMoveDown)
    }
}

@Composable
private fun MoveButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            description,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.7f else 0.15f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * The three themes as one row of equal segments.
 *
 * A segmented control rather than a list of rows because the options are three
 * words with three icons and are mutually exclusive — the thing a user wants from
 * this is to see all three and tap one, which a list makes them scroll to do. The
 * selected segment carries fill AND colour AND weight: one of those alone is too
 * quiet at this size, and on a light theme a tinted fill nearly vanishes.
 */
@Composable
private fun ThemeSegmentedRow(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)),
    ) {
        Row(modifier = Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppTheme.entries.forEach { option ->
                val isSelected = option == selected
                val tint by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    label = "theme_tint",
                )
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSelect(option) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else Color.Transparent,
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            when (option) {
                                AppTheme.LIGHT -> Icons.Rounded.LightMode
                                AppTheme.DARK -> Icons.Rounded.DarkMode
                                AppTheme.SYSTEM -> Icons.Rounded.BrightnessAuto
                            },
                            null,
                            tint = tint,
                            modifier = Modifier.size(21.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            option.displayName,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionLabel(text: String) {
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
private fun HomeCaption(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
    )
}

@Composable
private fun HomeCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)),
    ) {
        Column { content() }
    }
}

@Composable
private fun HomeDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
    )
}

@Composable
private fun ActionRowSimple(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
