package com.stationly.app.ui.selection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.core.model.FilterMode
import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.core.util.BoardFilterResolver
import com.stationly.core.util.RouteGraph
import com.stationly.core.model.sdui.SduiRouteStop

/**
 * A direction's human headline.
 *
 * Buses have no compass direction — the backend sends `directionName` as the
 * literal word "Towards", so using it alone renders a card titled "Towards" with
 * "towards Gordon Cottages" underneath. Fall back to the full label, which
 * already reads "Towards Romford Station".
 */
internal fun directionHeadline(opt: SduiDropdownOption): String {
    val name = opt.directionName?.trim().orEmpty()
    val meaningless = name.isBlank() || name.equals("Towards", ignoreCase = true)
    return if (meaningless) opt.label.trim().ifBlank { name } else name
}

/**
 * Bottom sheet for one board's departure filter.
 *
 * Deliberately a level below the direction card rather than on it: filtering is
 * a power feature, most people want every train, and the two-up card has no room
 * for it. The card shows only the RESULT ("via Green Park") and opens this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardFilterSheet(
    lineLabel: String,
    originName: String,
    directionOption: SduiDropdownOption,
    filter: BoardFilter,
    /** Transport mode id ("tube", "bus", "tram"…) — drives every noun on this sheet. */
    mode: String?,
    primary: Color,
    lineColor: Color,
    onSetMode: (FilterMode) -> Unit,
    onToggleDestination: (String) -> Unit,
    onToggleVia: (SduiRouteStop) -> Unit,
    onToggleBranch: (List<RouteGraph.Pattern>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val destinations = directionOption.destinations ?: emptyList()
    val vehicles = vehicleNounPlural(mode)          // Trains / Buses / Trams
    // "Buses".dropLast(1) is "Buse", so bus is special-cased rather than
    // de-pluralised.
    val vehicleOne = if (vehicles == "Buses") "bus" else vehicles.dropLast(1).lowercase()
    val stopNoun = stopNounSingular(mode)            // station / stop
    val tree = remember(directionOption) { RouteGraph.from(directionOption) }
    val viaStops = remember(tree) { tree.allStops }

    // Whether id-accurate filtering is possible at all. A cached 24h payload from
    // before the backend served `upcomingStops` has names but no ids, and a
    // filter built from it could never match a prediction — so the option is
    // disabled with a reason rather than offered as something that silently
    // does nothing.
    val viaAvailable = viaStops.isNotEmpty()
    val destinationsAvailable = destinations.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // Scrollable: a four-branch graph (District at Earl's Court) plus the
        // three mode rows and the preview overflows an iPhone 11 sheet, and the
        // preview is the part that must never be cut off — it is the only
        // warning that a filter matches nothing.
        // Horizontal padding lives on the CHILDREN, not here, so the route map
        // can run edge to edge — it scrolls sideways and every pixel of width is
        // one more stop you can see without dragging.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            // NOT "Which buses?" — a bus board is a single route, so asking
            // which buses reads as a choice between vehicles. The real question
            // is which of this direction's departures belong on the board.
            Text(
                "Which departures?",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 19.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // Buses report directionName as the literal word "Towards", which
                // says nothing on its own — fall back to the full label
                // ("Towards Romford Station") whenever there is no real compass.
                "$lineLabel · ${directionHeadline(directionOption)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(16.dp))

            FilterModeRow(
                label = "All $vehicles".trimEnd(),
                detail = "Show everything going this way",
                selected = filter.mode == FilterMode.ALL,
                enabled = true,
                primary = primary,
            ) { onSetMode(FilterMode.ALL) }

            FilterModeRow(
                label = "Only ones finishing at…",
                detail = if (destinationsAvailable)
                    "Pick the final $stopNoun. A $vehicleOne that turns back early is hidden."
                else "No destination list for this direction",
                selected = filter.mode == FilterMode.DESTINATIONS,
                enabled = destinationsAvailable,
                primary = primary,
            ) { onSetMode(FilterMode.DESTINATIONS) }

            AnimatedVisibility(
                visible = filter.mode == FilterMode.DESTINATIONS,
                enter = expandVertically(tween(240)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
            ) {
                // Indented to line up under the mode row's LABEL, not its radio:
                // 20dp sheet margin + 20dp dot + 12dp gap. These options belong to
                // the row above them and should read as nested under it.
                Column(Modifier.padding(start = 52.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)) {
                    destinations.forEach { dest ->
                        CheckRow(
                            label = dest.label,
                            checked = dest.id in filter.destinationIds,
                            primary = primary,
                        ) { onToggleDestination(dest.id) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // The distinction that decides which mode someone wants,
                        // stated where the choice is made rather than in a doc.
                        "Exact match. A $vehicleOne that turns back early won't show.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp, lineHeight = 13.sp
                    )
                }
            }

            FilterModeRow(
                label = "Going through…",
                detail = if (viaAvailable)
                    "Pick a $stopNoun on the map. Anything that reaches it counts, even if it turns back there."
                else "Route stops unavailable. Reopen this line to refresh.",
                selected = filter.mode == FilterMode.VIA,
                enabled = viaAvailable,
                primary = primary,
            ) { onSetMode(FilterMode.VIA) }

            AnimatedVisibility(
                visible = filter.mode == FilterMode.VIA,
                enter = expandVertically(tween(240)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
            ) {
                ViaStopPicker(
                    tree = tree,
                    originName = originName,
                    stopNoun = stopNoun,
                    selectedIds = filter.viaStopIds,
                    selectedPatternIds = filter.patternIds,
                    primary = primary,
                    lineColor = lineColor,
                    vehiclePlural = vehicles,
                    onToggleStop = onToggleVia,
                    onToggleBranch = onToggleBranch,
                )
            }

            Spacer(Modifier.height(12.dp))
            FilterPreview(
                directionOption, filter, primary, vehicles,
                modifier = Modifier.padding(horizontal = 20.dp),
                onConfirm = onDismiss,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * Live readout of what the current filter would actually show.
 *
 * The single most useful thing on this sheet: it is the only way to tell, before
 * saving, that a filter matches nothing — the failure mode that would otherwise
 * surface as a mysteriously empty board.
 */
@Composable
private fun FilterPreview(
    directionOption: SduiDropdownOption,
    filter: BoardFilter,
    primary: Color,
    vehicles: String,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit,
) {
    // MUST mirror what `saveSelection` will resolve, argument for argument.
    //
    // `chosenPatternIds` was missing here: taking a whole branch left this
    // preview resolving an empty via-stop set, so the sheet announced "Nothing
    // matches. All trains will be shown." for a filter that was about to save
    // perfectly well. The preview lying about the save is worse than no preview.
    val resolution = remember(directionOption, filter) {
        if (!filter.isActive) BoardFilterResolver.EMPTY
        else BoardFilterResolver.resolve(
            mode = filter.mode,
            direction = directionOption,
            chosenDestinationIds = filter.destinationIds,
            viaStopIds = filter.viaStopIds,
            chosenPatternIds = filter.patternIds,
        )
    }
    val destTotal = (directionOption.destinations ?: emptyList()).size

    // Remembered: this builds four strings and is read on every recomposition of
    // a sheet the user is actively tapping.
    val (text, warn) = remember(filter, resolution, vehicles, destTotal) {
        when {
            !filter.isActive ->
                "Showing every ${vehicles.dropLast(1).lowercase()} going this way" to false
            resolution.isEmpty ->
                "Nothing matches. All ${vehicles.lowercase()} will be shown." to true
            filter.mode == FilterMode.DESTINATIONS ->
                "Showing ${filter.destinationIds.size} of $destTotal destinations" to false
            // Whole services read as their own name, stops as the place. Both are
            // answers to "what do you want to see".
            else ->
                "Showing ${vehicles.lowercase()} that call at ${filter.viaSummary}" to false
        }
    }

    Surface(
        color = if (warn) primary.copy(0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (warn) primary.copy(0.45f) else Color.Transparent),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                color = if (warn) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp, lineHeight = 15.sp,
                fontWeight = if (warn) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
            )
            Spacer(Modifier.width(8.dp))
            // The confirm lives ON the status bar rather than as a separate
            // button: this line already states what the board will show, so the
            // tick reads as "yes, that" — and swipe-to-dismiss alone gave the
            // user nothing to press.
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(primary)
                    .clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Check, null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun FilterModeRow(
    label: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    primary: Color,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioDot(selected = selected, primary = primary, alpha = alpha)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    fontSize = 11.sp, lineHeight = 14.sp
                )
            }
        }
    }
}

/** Filled tick when chosen, hollow ring when not — the same affordance the line
 *  and direction rows use, so "chosen" reads identically everywhere. */
@Composable
private fun RadioDot(selected: Boolean, primary: Color, alpha: Float) {
    if (selected) {
        Box(
            Modifier.size(20.dp).background(primary.copy(alpha = alpha), CircleShape),
            Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Check, null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(13.dp)
            )
        }
    } else {
        Box(
            Modifier.size(20.dp).border(
                1.5.dp,
                MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f * alpha),
                CircleShape
            )
        )
    }
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    primary: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (checked) {
                Box(Modifier.size(19.dp).background(primary, CircleShape), Alignment.Center) {
                    Icon(
                        Icons.Rounded.Check, null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            } else {
                Box(
                    Modifier.size(19.dp).border(
                        1.5.dp,
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f),
                        CircleShape
                    )
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Route diagram for choosing which trains count as "going my way".
 *
 * Search does NOT switch to a list. It scrolls the map to the match and rings
 * it, so the answer always stays in its route context — the whole reason for
 * drawing the map is that "which branch is this on" is the thing you need to
 * know, and a flat result list throws it away at exactly the wrong moment.
 * Finding a stop and choosing it stay separate: the ring is attention, not
 * selection.
 */
@Composable
private fun ViaStopPicker(
    tree: RouteGraph,
    selectedPatternIds: Set<String>,
    originName: String,
    stopNoun: String,
    selectedIds: Set<String>,
    primary: Color,
    lineColor: Color,
    vehiclePlural: String,
    onToggleStop: (SduiRouteStop) -> Unit,
    onToggleBranch: (List<RouteGraph.Pattern>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    val match = remember(tree, query) {
        if (query.isBlank()) null
        else tree.allStops.firstOrNull { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val noMatch = query.isNotBlank() && match == null

    // Bring the match into view, centred where possible. Recomputing the same
    // geometry the picker uses is the price of laying the map out absolutely;
    // keeping both off the shared STOP_W/ORIGIN_W constants stops them drifting.
    //
    // ROUTE_EDGE_GUTTER is part of that geometry: the gutters live INSIDE the
    // scrolling content, so every column sits one gutter further right than the
    // bare column maths says. Omitting it put every search hit a gutter's width
    // off — the exact drift these shared constants exist to prevent.
    LaunchedEffect(match?.id) {
        val stop = match ?: return@LaunchedEffect
        val idx = tree.columnOfStop(stop.id)
        if (idx < 0) return@LaunchedEffect
        val targetPx = with(density) {
            (ROUTE_EDGE_GUTTER + ROUTE_ORIGIN_W + ROUTE_STOP_W * idx).toPx()
        }
        scrollState.animateScrollTo(
            (targetPx - with(density) { 120.dp.toPx() }).toInt().coerceAtLeast(0)
        )
    }

    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    Column(Modifier.padding(top = 6.dp, bottom = 8.dp)) {

        // Search is COLLAPSED by default.
        //
        // Most people scan the map and tap; a permanent field stole vertical
        // space from the diagram, which is the thing actually being used. It
        // expands in place, takes focus, and folds away once a match is found.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            AnimatedVisibility(
                visible = !searchOpen,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(0.10f))
                        .clickable { searchOpen = true }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Search, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "Find a $stopNoun",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            AnimatedVisibility(
                visible = searchOpen,
                enter = expandHorizontally(tween(220)) + fadeIn(tween(180)),
                exit = shrinkHorizontally(tween(160)) + fadeOut(tween(120)),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("e.g. Green Park", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        IconButton({
                            // One control that always means "put this away".
                            query = ""
                            searchOpen = false
                            dismissKeyboard()
                        }) {
                            Icon(Icons.Rounded.Clear, null, modifier = Modifier.size(16.dp))
                        }
                    },
                    isError = noMatch,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { dismissKeyboard() }),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primary.copy(0.6f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.25f),
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                )
            }
        }

        // Take focus as the field appears, so opening search and typing is one
        // gesture rather than tap-then-tap-again.
        LaunchedEffect(searchOpen) {
            if (searchOpen) { delay(80); runCatching { searchFocus.requestFocus() } }
        }

        Spacer(Modifier.height(5.dp))
        Text(
            when {
                noMatch -> "No $stopNoun on this line matches \"${query.trim()}\""
                match != null -> "Found ${match.name}. Tap it on the map to use it."
                else -> "Tap a $stopNoun on the map, or a terminus to take that whole branch"
            },
            color = if (noMatch) primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.8f),
            fontSize = 10.sp, lineHeight = 13.sp,
            fontWeight = if (match != null || noMatch) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(6.dp))

        if (tree.allStops.isEmpty()) {
            Text(
                "No route stops for this direction yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp)
            )
        } else {
            RouteGraphPicker(
                graph = tree,
                originName = originName,
                selectedIds = selectedIds,
                selectedPatternIds = selectedPatternIds,
                focusedId = match?.id,
                lineColor = lineColor,
                vehiclePlural = vehiclePlural,
                onToggleStop = { dismissKeyboard(); onToggleStop(it) },
                onToggleBranch = { dismissKeyboard(); onToggleBranch(it) },
                onInteract = {
                    // Touching the map means the search has served its purpose.
                    if (searchOpen) { searchOpen = false; query = "" }
                    dismissKeyboard()
                },
                scrollState = scrollState,
            )
        }
    }
}
