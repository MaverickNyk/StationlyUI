@file:OptIn(ExperimentalMaterial3Api::class)
package com.stationly.app.ui.selection

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.South
import androidx.compose.material.icons.filled.West
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.ArrowRightAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.app.ui.common.ServiceUnavailableScreen
import com.stationly.app.ui.common.StationlySpinner
import com.stationly.app.ui.summary.components.lineColorForTheme
import com.stationly.app.ui.theme.isDarkTheme
import com.stationly.core.model.FilterMode
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.model.sdui.SduiDropdownOption
import kotlinx.coroutines.delay

/* ═══════════════════════════════════════════════════════════════
   Palette — theme-aware (names preserved so call sites stay unchanged).
   Each reads from MaterialTheme.colorScheme so the screen flips with the
   app theme. The dot-matrix board keeps its own locked dark signage palette.
   ═══════════════════════════════════════════════════════════════ */
private val Amber    @Composable get() = MaterialTheme.colorScheme.primary
private val Surface0 @Composable get() = MaterialTheme.colorScheme.background
private val Surface1 @Composable get() = MaterialTheme.colorScheme.surface
private val Surface2 @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val White90  @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.90f)
private val White55  @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
private val White25  @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
private val White08  @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)

/* ═══════════════════════════════════════════════════════════════
   SDUI helpers
   ═══════════════════════════════════════════════════════════════ */
/**
 * Resolve a server-driven string by component id, interpolating any
 * `{mode}` / `{station}` / `{line}` (etc.) placeholders the backend embedded
 * with the live selection. The server can ship "{Mode} stops near you" or
 * "Lines from {station}" and the client fills in the runtime values. Empty
 * substitutions collapse cleanly so a missing var never leaves a dangling
 * token. Returns null when the key isn't in the layout, so call sites fall
 * back to the local default string.
 */
private fun SduiAppScreen.sdText(id: String, vars: Map<String, String?> = emptyMap()): String? =
    components.filterIsInstance<SduiAppComponent.Text>().find { it.id == id }?.text
        ?.let { interpolate(it, vars) }

private fun interpolate(template: String, vars: Map<String, String?>): String {
    if (!template.contains('{')) return template
    var out = template
    vars.forEach { (k, v) -> out = out.replace("{$k}", v ?: "") }
    return out.replace(Regex("\\s{2,}"), " ").trim()
}

/* ───────────────────────────────────────────────────────────────
   Context-aware vocabulary. The copy on each step references the choice
   made on the previous step (mode → station → line), so the flow reads like
   a sentence: "Bus stops near you" → "Routes from Trafalgar Square" →
   "Buses from Trafalgar Square". A bus rides on "stops"/"routes"/"Buses";
   rail modes on "stations"/"lines"/"Trains". Backend can still override any
   of these via the screen_* SDUI keys.
   ─────────────────────────────────────────────────────────────── */
internal fun stopNounSingular(modeId: String?): String =
    if (modeId == "bus" || modeId == "tram") "stop" else "station"
internal fun lineNounSingular(modeId: String?): String =
    if (modeId == "bus") "route" else "line"
internal fun lineNounPluralCap(modeId: String?): String =
    if (modeId == "bus") "Routes" else "Lines"
internal fun vehicleNounPlural(modeId: String?): String = when (modeId) {
    "bus"       -> "Buses"
    "tram"      -> "Trams"
    "river-bus" -> "Boats"
    else        -> "Trains"
}

/** Compact destination summary for a junction headline, e.g.
 *  ["Wimbledon","Richmond","Ealing Broadway","Kensington (Olympia)"] →
 *  "Wimbledon, Richmond & 2 more". */
private fun summariseDestinations(labels: List<String>): String = when (labels.size) {
    0 -> ""
    1 -> labels[0]
    2 -> "${labels[0]} & ${labels[1]}"
    3 -> "${labels[0]}, ${labels[1]} & ${labels[2]}"
    else -> "${labels[0]}, ${labels[1]} & ${labels.size - 2} more"
}

/* ═══════════════════════════════════════════════════════════════
   Step helpers  (unified flow: Mode → Station → Line → Direction)
   ═══════════════════════════════════════════════════════════════ */
/**
 * Progress dots. Line and direction are no longer keys in the flat selection
 * map — they live in the multi-line picks — so the last two steps are derived
 * from those instead: any line checked reaches step 2, and every checked line
 * having a direction completes step 3.
 */
private fun computeStep(s: Map<String, String>, picks: Map<String, Set<String>>): Int = when {
    picks.isNotEmpty() && picks.values.all { it.isNotEmpty() } -> 3
    picks.isNotEmpty()                                        -> 2
    "station" in s                                            -> 1
    else                                                      -> 0
}

private fun screenIdx(s: Map<String, String>): Int = when {
    "mode"    !in s -> 0
    "station" !in s -> 1
    else            -> 2  // merged line + direction screen
}

/* ═══════════════════════════════════════════════════════════════
   Root
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun SelectionScreen(
    onNavigateToSummary: () -> Unit,
    onNavigateBack: () -> Unit = onNavigateToSummary,
    onRequestLocationPermission: () -> Unit = {},
    /**
     * Station to open the flow ON, as (grouping id, mode, name) — the home
     * screen's "Edit station". Null starts the flow at the mode step as usual.
     */
    editStation: Triple<String, String, String>? = null,
    /** A line to open expanded — see [SelectionViewModel.openForStation]. */
    focusLine: String? = null,
    viewModel: SelectionViewModel = viewModel { SelectionViewModel() }
) {
    // Keyed on the station so re-entering for a DIFFERENT one re-runs, and
    // recomposition alone never does — replaying it would fight the user by
    // dragging them back to the line step every time they pressed back.
    LaunchedEffect(editStation, focusLine) {
        editStation?.let { (id, mode, name) ->
            viewModel.openForStation(mode, id, name, focusLine)
        }
    }

    val st by viewModel.uiState.collectAsStateWithLifecycle()
    val selMap by viewModel.selections.collectAsStateWithLifecycle()
    val dropdownData by viewModel.dropdownData.collectAsStateWithLifecycle()
    val modes by viewModel.modes.collectAsStateWithLifecycle()
    val recentStations by viewModel.recentStations.collectAsStateWithLifecycle()
    val linePicks by viewModel.linePicks.collectAsStateWithLifecycle()
    val existingPicks by viewModel.existingPicks.collectAsStateWithLifecycle()
    val directionsByLine by viewModel.directionsByLine.collectAsStateWithLifecycle()
    val loadingDirections by viewModel.loadingDirections.collectAsStateWithLifecycle()
    val failedDirections by viewModel.failedDirections.collectAsStateWithLifecycle()
    val pickedRowCount by viewModel.pickedRowCount.collectAsStateWithLifecycle()
    val atCap by viewModel.isAtCap.collectAsStateWithLifecycle()
    val selectionComplete by viewModel.isSelectionComplete.collectAsStateWithLifecycle()
    val boardFilters by viewModel.boardFilters.collectAsStateWithLifecycle()
    val expandedLine by viewModel.expandedLine.collectAsStateWithLifecycle()

    // (line, direction) whose filter sheet is open; null = closed.
    var filterTarget by remember {
        mutableStateOf<Pair<SduiDropdownOption, SduiDropdownOption>?>(null)
    }

    // App-wide themed primary — flips automatically with light/dark mode and
    // any SDUI ThemeTokens override. Previously this screen parsed
    // `st.layout?.theme?.primaryColor`, which hardcoded a bright TfL-amber that
    // wrecked light-mode contrast (chips, CTA, border all #FFB81C regardless).
    val primary = MaterialTheme.colorScheme.primary

    LaunchedEffect(st.showSuccessDialog) {
        if (st.showSuccessDialog) { onNavigateToSummary(); viewModel.dismissSuccessDialog() }
    }

    // The CTA now waits on the multi-line picks rather than on flat map keys:
    // every checked line must have a direction before the board can be built.
    val done by remember(selMap, selectionComplete) {
        derivedStateOf { "mode" in selMap && "station" in selMap && selectionComplete }
    }

    val step = computeStep(selMap, linePicks)
    val idx  = screenIdx(selMap)
    val mode = modes.find { it.id == selMap["mode"] }
    // The chosen station's display name, carried forward into the line /
    // direction copy ("Lines from {station}"). Kept in dropdownData even after
    // selection, so this resolves on the later steps too.
    val stationName = remember(dropdownData, selMap) {
        selMap["station"]?.let { id ->
            dropdownData["station"]?.find { it.id == id }?.label
        }
    }

    // When station screen is shown, auto-load nearby stations (if not already
    // loaded). On iOS there is no Activity permission launcher — we hand off to
    // the host via onRequestLocationPermission and let the VM resolve location.
    LaunchedEffect(idx) {
        if (idx == 1 && !st.isLocating && dropdownData["station"].isNullOrEmpty() && !st.isGpsUnavailable) {
            if (st.userLat != null && st.userLon != null) {
                viewModel.fetchNearbyStations(st.userLat, st.userLon, selMap["mode"])
            } else {
                onRequestLocationPermission()
                viewModel.fetchNearbyStations(null, null, selMap["mode"])
            }
        }
    }

    // If location resolved after the station screen was already shown, re-trigger
    LaunchedEffect(st.userLat, st.userLon) {
        if (idx == 1 && !st.isLocating && dropdownData["station"].isNullOrEmpty()
            && !st.isGpsUnavailable && st.userLat != null && st.userLon != null) {
            viewModel.fetchNearbyStations(st.userLat, st.userLon, selMap["mode"])
        }
    }

    Box(
        Modifier.fillMaxSize()
            // Subtle theme-aware fade: surface → background. (Originally a
            // hardcoded Surface0 → Black band — jarring in light mode.)
            .background(Brush.verticalGradient(listOf(Surface1, Surface0)))
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── top bar ──
            MinimalTopBar(mode?.label, step, "mode" in selMap, primary, existingPicks.isNotEmpty()) {
                // Editing an existing station ENTERED this flow at the line
                // step — the mode and the station were never picked here, they
                // came from the card the user tapped. Stepping back through them
                // walks the user forwards through screens they did not choose
                // and strands them at the mode list; back has to mean "leave",
                // which returns to the station settings screen it came from.
                if (editStation == null && "mode" in selMap) viewModel.popLastSelection()
                else onNavigateBack()
            }

            // ── content ──
            AnimatedContent(
                targetState = idx,
                transitionSpec = {
                    val fwd = targetState >= initialState
                    if (fwd) (slideInHorizontally { it / 2 } + fadeIn(tween(260)))
                        .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(180)))
                    else (slideInHorizontally { -it / 2 } + fadeIn(tween(260)))
                        .togetherWith(slideOutHorizontally { it / 3 } + fadeOut(tween(180)))
                },
                label = "nav",
                modifier = Modifier.weight(1f)
            ) { i ->
                when (i) {
                    // Screen 0 — Mode
                    0 -> ModeScreen(
                        st.layout, modes, "mode" in st.failedFetches, primary,
                        { viewModel.onDropdownSelected("mode", it.id) }, { viewModel.retryLoad() }
                    )

                    // Screen 1 — Station (nearby + search combined)
                    1 -> StationScreen(
                        layout          = st.layout,
                        stations        = dropdownData["station"] ?: emptyList(),
                        recentStations  = run {
                            val currentIds = dropdownData["station"]?.map { it.id }?.toSet() ?: emptySet()
                            recentStations.filter { it.id in currentIds }
                        },
                        selectedId      = selMap["station"],
                        locating        = st.isLocating,
                        noNearby        = st.isGpsUnavailable,
                        searchEmpty     = st.isSearchEmpty,
                        primary         = primary,
                        modeIcon        = mode?.iconUrl,
                        mode            = selMap["mode"],
                        modeLabel       = mode?.label,
                        onSelect        = { viewModel.onDropdownSelected("station", it.id) },
                        onSearch        = { viewModel.searchStations(it) }
                    )

                    // Screen 2 — Lines (multi-select) + a direction per line
                    2 -> LineDirectionScreen(
                        layout            = st.layout,
                        lines             = dropdownData["line"] ?: emptyList(),
                        linePicks         = linePicks,
                        existingPicks     = existingPicks,
                        directionsByLine  = directionsByLine,
                        loadingDirections = loadingDirections,
                        failedDirections  = failedDirections,
                        rowCount          = pickedRowCount,
                        atCap             = atCap,
                        loadingLines      = dropdownData["line"] == null && "line" !in st.failedFetches,
                        errLines          = "line" in st.failedFetches,
                        primary           = primary,
                        mode              = selMap["mode"],
                        modeIcon          = mode?.iconUrl,
                        modeLabel         = mode?.label,
                        stationName       = stationName,
                        boardFilters      = boardFilters,
                        expandedLine      = expandedLine,
                        onExpandLine      = { viewModel.toggleExpandedLine(it) },
                        onToggleLine      = { viewModel.toggleLine(it.id) },
                        onToggleDir       = { lineId, dir -> viewModel.toggleDirection(lineId, dir.id) },
                        onToggleAllDirs   = { viewModel.toggleAllDirections(it) },
                        onToggleAllLines  = { viewModel.toggleAllLines(it) },
                        onOpenFilter      = { line, dir -> filterTarget = line to dir },
                        onRetryDirections = { viewModel.retryDirections(it) },
                        onRetry           = { viewModel.retryDropdown("line") }
                    )

                    else -> Box(Modifier.fillMaxSize())
                }
            }

            // ── CTA ──
            val ctaBtn = st.layout?.components?.filterIsInstance<SduiAppComponent.Button>()?.firstOrNull()
            AnimatedVisibility(
                done,
                enter = slideInVertically { it } + fadeIn(tween(300)),
                exit  = slideOutVertically { it } + fadeOut(tween(200)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // CTA backdrop fades from transparent into the canvas
                        // background so the floating CTA doesn't sit on bare content.
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Surface0.copy(0.95f), Surface0)))
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp, bottom = 20.dp)
                ) {
                    ModernCtaButton(
                        label   = ctaBtn?.label ?: "Set Up My Board",
                        primary = primary,
                        onClick = { ctaBtn?.let { viewModel.onActionTriggered(it.action) } }
                    )
                }
            }
        }

        // Per-board departure filter. Hosted at the screen root rather than
        // inside the LazyColumn item so it survives the row scrolling out of
        // view and isn't clipped by the list.
        filterTarget?.let { (line, dir) ->
            BoardFilterSheet(
                lineLabel = line.label,
                originName = stationName ?: "your station",
                directionOption = dir,
                filter = boardFilters[SelectionViewModel.boardFilterKey(line.id, dir.id)]
                    ?: BoardFilter(),
                mode = selMap["mode"],
                primary = primary,
                // The line's own TfL colour, so the route map reads as THAT
                // line rather than as generic app chrome.
                lineColor = lineColorForTheme(line.id, isDarkTheme()),
                onSetMode = { viewModel.setFilterMode(line.id, dir.id, it) },
                onToggleDestination = { viewModel.toggleFilterDestination(line.id, dir.id, it) },
                onToggleVia = { stop -> viewModel.toggleFilterVia(line.id, dir.id, stop.id, stop.name) },
                // Selecting a whole branch = selecting its FIRST unique stop.
                // Everything past a divergence is only reachable through it, so
                // that one id already implies the entire branch downstream.
                // A chip means "this whole service", stored as the pattern it
                // names. It no longer ticks a stop, so nothing in the middle of
                // the branch lights up as though the user had chosen it.
                onToggleBranch = { patterns ->
                    viewModel.toggleFilterBranch(
                        line.id, dir.id,
                        patterns.map { it.id to it.label },
                    )
                },
                onDismiss = { filterTarget = null },
            )
        }

        // overlays
        AnimatedVisibility(st.isSaving, enter = fadeIn(), exit = fadeOut()) {
            Saving(primary, st.layout?.loadingMessage ?: "Preparing Your Live Board")
        }
        AnimatedVisibility(
            st.layout == null && st.isBackendOffline,
            enter = fadeIn(tween(400)), exit = fadeOut(tween(300))
        ) {
            ServiceUnavailableScreen(
                context                = "selection",
                overridingErrorMessage = st.error,
                onRetry                = { viewModel.retryLoad() },
                onDismiss              = onNavigateBack
            )
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Top bar — thin, elegant, animated progress dots
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun MinimalTopBar(
    modeName: String?,
    step: Int,
    showProgress: Boolean,
    primary: Color,
    isEditing: Boolean = false,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.ArrowBackIosNew, "Back", tint = White55, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    // Landing on a station that already has a card is an edit,
                    // not a new board — saying "New" while the list opens
                    // prefilled reads as though the app is about to duplicate it.
                    isEditing && modeName != null -> "Edit $modeName Board"
                    isEditing                     -> "Edit Board"
                    modeName != null              -> "New $modeName Board"
                    else                          -> "Set up a Board"
                },
                color = White90, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.3.sp
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(40.dp))
        }

        if (showProgress) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 6.dp), Arrangement.spacedBy(5.dp)) {
                repeat(3) { i ->
                    val filled by animateFloatAsState(if (step > i) 1f else 0f, tween(400), label = "bar$i")
                    Box(Modifier.weight(1f).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(White08)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(filled).background(
                            Brush.horizontalGradient(listOf(primary, primary.copy(0.7f)))
                        ))
                    }
                }
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Step header — title + subtitle, with the chosen mode's icon carried
   forward as a roundel so the user always sees what they picked.
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun StepHeader(modeIcon: String?, primary: Color, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!modeIcon.isNullOrEmpty()) {
            // White roundel — TfL mode glyphs are drawn for a white field.
            Box(
                Modifier.size(40.dp).background(Color.White, CircleShape)
                    .border(1.5.dp, primary.copy(0.3f), CircleShape).padding(7.dp),
                Alignment.Center
            ) {
                coil3.compose.AsyncImage(
                    model = modeIcon, contentDescription = null, modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = White90, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                lineHeight = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = White55, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Screen 0 — Mode picker
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun ModeScreen(
    layout: SduiAppScreen?, modes: List<SduiDropdownOption>, err: Boolean, primary: Color,
    onSelect: (SduiDropdownOption) -> Unit, onRetry: () -> Unit
) {
    when {
        err -> Err("Couldn't load modes", primary, onRetry)
        modes.isEmpty() -> Loader(primary)
        else -> Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(24.dp))
            // Copy is backend-owned (screen_mode_*); these are the offline
            // fallbacks. Functional/clear tone, not the old "Pick your chariot".
            Text(layout?.sdText("screen_mode_title") ?: "How are you travelling?",
                color = White90, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                lineHeight = 30.sp, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(6.dp))
            Text(layout?.sdText("screen_mode_subtitle") ?: "Pick a transport mode to track.",
                color = White55, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(20.dp))
            // Adaptive grid — auto-grows columns as the screen gets wider. 170dp
            // min gives 2 columns on a phone and 4-5 on a tablet.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 170.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
            ) {
                items(modes, key = { it.id }) { m -> ModeCard(m, primary) { onSelect(m) } }
            }
        }
    }
}

@Composable
private fun ModeCard(mode: SduiDropdownOption, primary: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick, color = Surface1, shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, White08),
        modifier = Modifier.fillMaxWidth().aspectRatio(0.92f)
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            // White roundel container — always white because TfL line icons are
            // designed to sit on a white field. The fallback letter is forced to
            // black for the same reason: black-on-white roundel typography.
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).background(primary.copy(0.08f), CircleShape))
                Box(
                    Modifier.size(60.dp).background(Color.White, CircleShape)
                        .border(2.dp, primary.copy(0.35f), CircleShape).padding(12.dp),
                    Alignment.Center
                ) {
                    if (!mode.iconUrl.isNullOrEmpty())
                        coil3.compose.AsyncImage(
                            model = mode.iconUrl, contentDescription = mode.label,
                            modifier = Modifier.fillMaxSize()
                        )
                    else
                        Text(mode.label.take(1), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(mode.label, color = White90, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Screen 1 — Station picker (nearby + search in one screen)
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun StationScreen(
    layout: SduiAppScreen?,
    stations: List<SduiDropdownOption>,
    recentStations: List<SduiDropdownOption>,
    selectedId: String?,
    locating: Boolean, noNearby: Boolean, searchEmpty: Boolean,
    primary: Color, modeIcon: String?, mode: String?, modeLabel: String?,
    onSelect: (SduiDropdownOption) -> Unit,
    onSearch: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    /**
     * Put the keyboard away and drop focus.
     *
     * Both are needed: hiding the keyboard alone leaves the field focused, so
     * the caret keeps blinking and the next tap re-opens the keyboard.
     */
    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    // Dismiss on scroll, the way every native iOS list behaves. Guarded on
    // `available.y` so a settling fling or a horizontal gesture doesn't trigger
    // it, and it is cheap to call repeatedly once already hidden.
    val dismissOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) dismissKeyboard()
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(searchQuery) { delay(300); onSearch(searchQuery) }

    // Auto-focus search when GPS is unavailable
    LaunchedEffect(noNearby) { if (noNearby && stations.isEmpty()) focusRequester.requestFocus() }

    // Interpolation vars + functional fallbacks. Backend owns the wording via
    // screen_station_title / screen_station_subtitle; templates may use {mode}
    // and {stop} (the mode-correct "station"/"stop" noun).
    val noun = stopNounSingular(mode)
    val vars = mapOf("mode" to modeLabel, "stop" to noun)
    val titleFallback =
        if (!modeLabel.isNullOrBlank()) "Find a $modeLabel $noun" else "Find your $noun"
    val subtitleFallback =
        if (noNearby) "Location off. Search by name."
        else "Nearby ${noun}s first, or search for another."

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))
        StepHeader(
            modeIcon = modeIcon,
            primary  = primary,
            title    = layout?.sdText("screen_station_title", vars) ?: titleFallback,
            subtitle = layout?.sdText("screen_station_subtitle", vars) ?: subtitleFallback,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 10.dp)
                .focusRequester(focusRequester),
            placeholder = { Text(layout?.sdText("station_search_placeholder", vars) ?: "Search ${noun}s…", color = White25, fontSize = 15.sp) },
            leadingIcon  = { Icon(Icons.Rounded.Search, null, tint = primary.copy(0.6f), modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) IconButton({ searchQuery = "" }) {
                    Icon(Icons.Rounded.Clear, null, tint = White25, modifier = Modifier.size(18.dp))
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The Search key previously did nothing at all — results are already
            // live-filtered as you type, so the only thing left for it to do is
            // get out of the way and reveal them.
            keyboardActions = KeyboardActions(onSearch = { dismissKeyboard() }),
            shape = RoundedCornerShape(12.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = primary.copy(0.5f), unfocusedBorderColor = White08,
                focusedContainerColor = Surface2, unfocusedContainerColor = Surface1,
                focusedTextColor = White90, unfocusedTextColor = White90
            )
        )

        when {
            locating -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                val p by rememberInfiniteTransition("loc").animateFloat(
                    1f, 1.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), "lp")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(64.dp).graphicsLayer(scaleX = p, scaleY = p, alpha = (2f - p) * 0.25f)
                            .background(primary, CircleShape))
                        Icon(Icons.Rounded.LocationOn, null, tint = primary, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Searching nearby…", color = White90, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Letting GPS do the legwork", color = White25, fontSize = 12.sp)
                }
            }

            // Search returned zero results — don't spin
            searchEmpty -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Rounded.SearchOff, null, tint = White25, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No stations found", color = White55, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Try a different search term", color = White25, fontSize = 12.sp)
                }
            }

            // GPS unavailable but no search active — prompt to search
            stations.isEmpty() && noNearby -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Rounded.Search, null, tint = White25, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Search for a station", color = White55, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Location off. Type to find stops", color = White25, fontSize = 12.sp)
                }
            }

            stations.isEmpty() -> Loader(primary)

            else -> {
                val showRecent = searchQuery.isBlank() && recentStations.isNotEmpty()
                val sectionLabel = if (searchQuery.isBlank()) "Nearby" else "Results"
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().nestedScroll(dismissOnScroll)
                ) {
                    // Picking a station ADVANCES to the line step, so the
                    // keyboard has to go with it — otherwise it stays up over
                    // the line list, hiding most of it and the CTA below.
                    if (showRecent) {
                        item { SectionHeader("Recent") }
                        items(recentStations, key = { "r_${it.id}" }) { s ->
                            OptRow(s, s.id == selectedId, primary, modeIcon, mode) {
                                dismissKeyboard(); onSelect(s)
                            }
                        }
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                    item { SectionHeader(sectionLabel) }
                    items(stations, key = { it.id }) { s ->
                        OptRow(s, s.id == selectedId, primary, modeIcon, mode) {
                            dismissKeyboard(); onSelect(s)
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Screen 2 — Line + Direction (merged)
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun LineDirectionScreen(
    layout: SduiAppScreen?,
    lines: List<SduiDropdownOption>,
    linePicks: Map<String, Set<String>>,
    existingPicks: Map<String, Set<String>>,
    directionsByLine: Map<String, List<SduiDropdownOption>>,
    loadingDirections: Set<String>,
    failedDirections: Set<String>,
    rowCount: Int,
    atCap: Boolean,
    loadingLines: Boolean,
    errLines: Boolean,
    primary: Color,
    mode: String?,
    modeIcon: String?,
    modeLabel: String?,
    stationName: String?,
    boardFilters: Map<String, BoardFilter>,
    expandedLine: String?,
    onExpandLine: (String) -> Unit,
    onToggleLine: (SduiDropdownOption) -> Unit,
    onToggleDir: (String, SduiDropdownOption) -> Unit,
    onToggleAllDirs: (String) -> Unit,
    onToggleAllLines: (List<SduiDropdownOption>) -> Unit,
    onOpenFilter: (SduiDropdownOption, SduiDropdownOption) -> Unit,
    onRetryDirections: (String) -> Unit,
    onRetry: () -> Unit
) {
    val lineSelected = linePicks.isNotEmpty()
    // Only meaningful for the single-pick copy templates; with several lines
    // checked the backend's "{line}" wording no longer has one answer, so the
    // header falls back to the count-based copy below.
    val lineName = linePicks.keys.singleOrNull()?.let { id -> lines.find { it.id == id }?.label }

    // Interpolation vars shared by both sub-steps. Backend owns the wording via
    // the screen_line_* / screen_direction_* keys. Templates may use {station},
    // {line}, {mode}, plus the mode-correct nouns {lines}, {line_noun} and
    // {vehicle}. These local strings only show offline.
    val vars = mapOf(
        "mode"      to modeLabel,
        "station"   to stationName,
        "line"      to lineName,
        "lines"     to lineNounPluralCap(mode),
        "line_noun" to lineNounSingular(mode),
        "vehicle"   to vehicleNounPlural(mode),
    )
    val fromStation = if (!stationName.isNullOrBlank()) " from $stationName" else ""

    val title = if (!lineSelected)
        layout?.sdText("screen_line_title", vars)
            ?: "${lineNounPluralCap(mode)}$fromStation"
    else
        layout?.sdText("screen_direction_title", vars) ?: "Which direction?"

    // The line step is now multi-select, so the subtitle has to say so — the
    // old copy ("Which line are you taking?") reads as a single choice and
    // would leave the checkbox affordance looking like a rendering bug.
    val pendingDirections = linePicks.count { it.value.isEmpty() }
    val subtitle = when {
        !lineSelected ->
            layout?.sdText("screen_line_multi_subtitle", vars)
                ?: "Pick every ${lineNounSingular(mode)} you want on this board"
        pendingDirections > 0 ->
            layout?.sdText("screen_direction_subtitle", vars)
                ?: "Choose a direction for each ${lineNounSingular(mode)}"
        else -> {
            val n = linePicks.size
            "$n ${if (n == 1) lineNounSingular(mode) else lineNounPluralCap(mode).lowercase()} selected"
        }
    }

    // Bring a newly ticked line into view. Its direction cards expand BELOW the
    // row, so ticking a line near the bottom of the list would otherwise open
    // them off-screen — the user taps, apparently nothing happens, and the CTA
    // stays disabled with no visible reason.
    val lineListState = rememberLazyListState()
    LaunchedEffect(expandedLine) {
        val target = expandedLine ?: return@LaunchedEffect
        val idx = lines.indexOfFirst { it.id == target }
        if (idx < 0) return@LaunchedEffect
        // Let the expand animation start first, so the scroll lands on the row's
        // settled position rather than fighting the growing item.
        delay(120)
        // +1 for the "Lines" header item above the list.
        runCatching { lineListState.animateScrollToItem(idx + 1) }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))
        StepHeader(modeIcon = modeIcon, primary = primary, title = title, subtitle = subtitle)
        Spacer(Modifier.height(14.dp))

        when {
            errLines -> Err("Couldn't load lines", primary, onRetry)
            loadingLines -> Loader(primary)
            else -> LazyColumn(
                state = lineListState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    // "Select all" turns an interchange from one tap per line
                    // into one tap total — King's Cross alone serves six.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader("Lines")
                        Spacer(Modifier.weight(1f))
                        if (lines.size > 1) {
                            val allPicked = lines.all { it.id in linePicks }
                            TextButton(
                                onClick = { onToggleAllLines(lines) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    if (allPicked) "Clear all" else "Select all",
                                    color = primary, fontSize = 12.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                items(lines, key = { it.id }) { line ->
                    val isChecked = line.id in linePicks
                    val isExpanded = expandedLine == line.id
                    OptRow(
                        line, isChecked, primary, null, mode,
                        multiSelect = true,
                        // Rows already saved on this station's card are marked
                        // so a returning user can tell at a glance what the board
                        // holds, instead of reading a prefilled tick as
                        // something they just did.
                        onBoard = line.id in existingPicks
                    ) {
                        // A chosen-but-collapsed line EXPANDS on tap rather than
                        // unticking. With the accordion the row is the only large
                        // target on screen, and having it silently discard a line
                        // the user had already configured (directions + filter)
                        // is a far worse mistake than an extra tap to remove one.
                        if (isChecked && !isExpanded) onExpandLine(line.id) else onToggleLine(line)
                    }

                    // Inline direction picker expands below EACH checked line.
                    // Each line owns its own direction list — Circle's
                    // inner/outer rail and Jubilee's north/south are different
                    // vocabularies, so there is no shared "Direction" step.
                    // Collapsed summary for a line that is chosen but not being
                    // worked on. It has to state WHAT is chosen — a bare collapsed
                    // row would leave the user unable to see their own answers
                    // without reopening each line one at a time.
                    AnimatedVisibility(
                        visible = isChecked && !isExpanded,
                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                        exit  = shrinkVertically(tween(160)) + fadeOut(tween(120))
                    ) {
                        CollapsedLineSummary(
                            picks = linePicks[line.id] ?: emptySet(),
                            directions = directionsByLine[line.id],
                            filters = boardFilters,
                            lineId = line.id,
                            modeId = mode,
                            primary = primary,
                            onClick = { onExpandLine(line.id) },
                        )
                    }

                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically(tween(280)) + fadeIn(tween(220)),
                        exit  = shrinkVertically(tween(200)) + fadeOut(tween(150))
                    ) {
                        Column(Modifier.padding(start = 8.dp, top = 10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SectionHeader("Direction")
                                Spacer(Modifier.weight(1f))
                                // "Both ways" is the single most common
                                // multi-pick — trains each way at one platform —
                                // and is otherwise a tap per direction.
                                val opts = directionsByLine[line.id]
                                if (opts != null && opts.size > 1) {
                                    val picked = linePicks[line.id] ?: emptySet()
                                    val allPicked = opts.all { it.id in picked }
                                    TextButton(
                                        onClick = { onToggleAllDirs(line.id) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            if (allPicked) "Clear" else "Both ways",
                                            color = primary, fontSize = 12.sp, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            when {
                                line.id in failedDirections ->
                                    InlineErr("Couldn't load directions", primary) {
                                        onRetryDirections(line.id)
                                    }

                                line.id in loadingDirections ||
                                    directionsByLine[line.id] == null ->
                                    Box(Modifier.fillMaxWidth().height(56.dp), Alignment.Center) {
                                        StationlySpinner(size = 22.dp, color = primary)
                                    }

                                // Directions side by side, two per row, each
                                // independently checkable — both directions of a
                                // line is a valid choice and yields two boards.
                                else -> directionsByLine[line.id]!!.chunked(2).forEach { pair ->
                                    // IntrinsicSize.Max + fillMaxHeight makes both
                                    // cards in a row match the taller one. Without
                                    // it a direction with more destinations grows
                                    // and its neighbour sits short, which reads as
                                    // a rendering fault rather than a difference in
                                    // content.
                                    Row(
                                        Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        pair.forEach { dir ->
                                            DirChoiceCard(
                                                opt = dir,
                                                sel = dir.id in (linePicks[line.id] ?: emptySet()),
                                                primary = primary,
                                                layout = layout,
                                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                                filter = boardFilters[
                                                    SelectionViewModel.boardFilterKey(line.id, dir.id)
                                                ] ?: BoardFilter(),
                                                modeId = mode,
                                                onOpenFilter = { onOpenFilter(line, dir) },
                                            ) { onToggleDir(line.id, dir) }
                                        }
                                        // Keep a lone card at half width instead
                                        // of letting it stretch across the row.
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(20.dp)) }
            }
        }

        // Pinned summary. With several lines expanded the picks scroll well off
        // screen, so without this the only way to check what you have built is
        // to scroll back up through every expanded direction block.
        if (rowCount > 0) {
            PickSummaryBar(
                rowCount = rowCount,
                atCap = atCap,
                linePicks = linePicks,
                lines = lines,
                primary = primary
            )
        }
    }
}

/**
 * One-line readout of the board being assembled: which lines, and how many
 * (line, direction) rows against the cap.
 *
 * The count is the honest unit here — "2 lines" hides that both-ways doubles
 * the sections on the card, and the cap is counted in rows, not lines.
 */
@Composable
private fun PickSummaryBar(
    rowCount: Int,
    atCap: Boolean,
    linePicks: Map<String, Set<String>>,
    lines: List<SduiDropdownOption>,
    primary: Color,
) {
    val names = remember(linePicks, lines) {
        linePicks.keys.mapNotNull { id -> lines.find { it.id == id }?.label }
    }
    Surface(
        color = Surface1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summariseDestinations(names),
                    color = White90, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "$rowCount / ${SelectionViewModel.MAX_ROWS_PER_STATION}",
                    color = if (atCap) primary else White55,
                    fontSize = 12.sp,
                    fontWeight = if (atCap) FontWeight.Bold else FontWeight.Medium
                )
            }
            // A tap that hits the cap is silently rejected, so the reason has to
            // be on screen — this is the only feedback besides the error haptic.
            if (atCap) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "Board full. Untick one to add another",
                    color = White55, fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun OptRow(
    opt: SduiDropdownOption,
    sel: Boolean,
    primary: Color,
    modeIcon: String?,
    mode: String? = null,
    multiSelect: Boolean = false,
    onBoard: Boolean = false,
    onClick: () -> Unit
) {
    val displayLabel = remember(opt.label, mode) {
        if (mode == "bus" && opt.label.all { it.isDigit() || it == ' ' } && opt.label.trim().isNotEmpty())
            "Bus ${opt.label.trim()}"
        else opt.label
    }
    val lineColor = remember(opt.color, mode) {
        val c = opt.color
        if (mode != "bus" && c != null) parseColorSafe(c) else null
    }

    Surface(
        onClick = onClick,
        color = if (sel) primary.copy(0.08f) else Surface1,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (sel) primary.copy(0.5f) else White08),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lineColor != null) {
                Box(Modifier.width(5.dp).height(32.dp).background(lineColor, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
                Spacer(Modifier.width(12.dp))
            } else Spacer(Modifier.width(14.dp))

            if (modeIcon != null) {
                Box(Modifier.size(34.dp).background(Color.White, CircleShape)
                    .border(1.dp, primary.copy(0.25f), CircleShape).padding(5.dp), Alignment.Center) {
                    coil3.compose.AsyncImage(model = modeIcon, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
            } else if (opt.iconUrl != null) {
                coil3.compose.AsyncImage(model = opt.iconUrl, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
            }

            Column(Modifier.weight(1f)) {
                // Station name — always high-contrast onSurface. Selected state is
                // indicated by the card's border + tick, not by recolouring the
                // title (which made it disappear on light theme).
                Text(displayLabel, color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val hasDistance = opt.secondaryLabel != null
                val hasTags = !opt.tags.isNullOrEmpty()
                if (hasDistance || hasTags) {
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        opt.secondaryLabel?.let { secondary ->
                            // Muted onSurfaceVariant so it reads as supporting text
                            // in both themes (the old primary tint vanished on light).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.NearMe, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(11.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(secondary,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp)
                            }
                        }
                        opt.tags?.forEach { hex ->
                            val dotColor = remember(hex) { parseColorSafe(hex) }
                            if (dotColor != null) {
                                Box(Modifier.size(8.dp).background(dotColor, CircleShape)
                                    .border(0.5.dp, White25.copy(alpha = 0.6f), CircleShape))
                            }
                        }
                    }
                }
            }

            // On single-pick steps (mode, station) the tick only appears once
            // something is chosen. On the multi-select line step an EMPTY
            // affordance is drawn on every row as well, because a list where
            // unselected rows show nothing reads as "pick one" — the user has
            // no way to discover they can tick several.
            if (onBoard) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "On board",
                    color = White55, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp
                )
            }

            if (sel) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(22.dp).background(primary, CircleShape), Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            } else if (multiSelect) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(22.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f), CircleShape)
                )
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Small section header
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun SectionHeader(label: String) {
    Text(label.uppercase(), color = White25, fontSize = 11.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun DirCard(opt: SduiDropdownOption, sel: Boolean, primary: Color, layout: SduiAppScreen?, onClick: () -> Unit) {
    // Static chrome labels are backend-overridable via SDUI (dir_* keys); the
    // strings below are only offline fallbacks.
    val towardsLabel  = layout?.sdText("dir_towards_label") ?: "towards"
    val stationsLabel = layout?.sdText("dir_stations_label") ?: "STATIONS THIS WAY"
    val stationsToTpl = layout?.sdText("dir_stations_to_label") ?: "STATIONS TO {dest}"
    val splitHint     = layout?.sdText("dir_split_hint") ?: "This direction splits. Tap a destination above for its full line of stops."
    // Bind to the STRUCTURED route fields the backend sends (directionName /
    // towards / destinations / upcomingStations). Fall back to parsing the
    // legacy label / secondaryLabel strings so older payloads still render.
    val lbl  = opt.label
    val tIdx = lbl.indexOf(" towards", ignoreCase = true)
    val parsedDir     = if (tIdx > 0) lbl.substring(0, tIdx).trim() else lbl.trim()
    val parsedTowards = if (tIdx > 0) lbl.substring(tIdx + 8).trim().substringBefore('\n').trim() else ""

    // "towards X" — the most relevant NEXT station in this direction (what the
    // platform signage shows), not the terminus.
    val towards = opt.towards?.takeIf { it.isNotBlank() }
        ?: opt.upcomingStations?.firstOrNull()
        ?: parsedTowards.takeIf { it.isNotBlank() }
        ?: parsedDir

    // Reachable destinations as FULL objects — each carries its own branch stops
    // in `upcomingStations`, so tapping a chip swaps the timeline to that branch.
    val destObjs = opt.destinations ?: emptyList()
    val fallbackDestLabels = if (destObjs.isEmpty() && tIdx > 0)
        lbl.substring(tIdx + 8).trim().split('\n').map { it.trim() }.filter { it.isNotBlank() }.drop(1)
    else emptyList()

    // Default timeline = the common trunk shared by all branches
    // (direction-level upcomingStations). Empty at a hard junction.
    val commonStops = opt.upcomingStations
        ?: opt.secondaryLabel?.split(" · ")?.map { it.trim() }?.filter { it.isNotBlank() }
        ?: emptyList()

    // Tapping a destination chip selects that branch; null = the default view.
    var selectedDestId by remember(opt.id) { mutableStateOf<String?>(null) }
    val selectedDest = destObjs.firstOrNull { it.id == selectedDestId }
    val routeSplits = destObjs.size > 1

    // Timeline stops: a selected branch → else the common trunk. At a hard
    // junction (no common trunk) we deliberately DON'T preview an arbitrary
    // branch; the headline lists the destinations and a note invites a tap.
    val activeStops = selectedDest?.upcomingStations?.takeIf { it.isNotEmpty() } ?: commonStops

    // Headline target: a chosen branch's NEXT stop → else the next common stop →
    // else (junction, nothing chosen) the destination list itself.
    val displayedTowards = when {
        selectedDest != null -> selectedDest.upcomingStations?.firstOrNull() ?: selectedDest.label
        commonStops.isNotEmpty() -> towards
        destObjs.isNotEmpty() -> summariseDestinations(destObjs.map { it.label })
        else -> towards
    }

    // Compass badge only for rail/tube/overground (Northbound … / Clockwise).
    // Buses report directionName="Towards" and inbound/outbound is meaningless to
    // passengers, so those get no badge — the "towards X" headline carries it.
    val dir = (opt.directionName ?: parsedDir).lowercase()
    val compassIcon: ImageVector? = when {
        dir.contains("north")        -> Icons.Filled.North
        dir.contains("south")        -> Icons.Filled.South
        dir.contains("east")         -> Icons.Filled.East
        dir.contains("west")         -> Icons.Filled.West
        dir.contains("clockwise")    -> Icons.Rounded.Loop   // covers anticlockwise too
        else                         -> null
    }
    val badgeText = (opt.directionName ?: parsedDir).takeIf { compassIcon != null }

    Surface(
        onClick = onClick,
        color = if (sel) primary.copy(0.07f) else Surface1,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (sel) 1.5.dp else 1.dp, if (sel) primary.copy(0.55f) else White08),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        if (sel) primary else primary.copy(0.25f),
                        RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
                    )
            )
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
                // Compass cue + tick header — RAIL ONLY (compassIcon != null).
                // Gated purely on compass presence, NEVER on `sel`, and pinned to
                // the tick's height so selecting a card only fades the tick in/out
                // *inside* an already-reserved row — the card never changes height.
                if (compassIcon != null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 22.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (badgeText != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(compassIcon, null, tint = White55, modifier = Modifier.size(13.dp))
                                Text(badgeText.uppercase(), color = White55,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (sel) {
                            Box(Modifier.size(22.dp).background(primary, CircleShape), Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // "towards {next station}" — the HIGHLIGHTED headline. The station
                // name is the boldest element on the card; "towards" is a quiet
                // muted lead-in, baseline-aligned so they read as one phrase.
                Row(Modifier.fillMaxWidth()) {
                    Text("$towardsLabel ", color = White55, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.alignByBaseline())
                    Text(
                        displayedTowards,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).alignByBaseline()
                    )
                    // Bus cards have no compass header, so the selected tick rides
                    // at the end of the headline row instead. The 18sp headline is
                    // already ~tick-tall, so toggling it doesn't grow the row.
                    if (sel && compassIcon == null) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.align(Alignment.CenterVertically)
                                .size(22.dp).background(primary, CircleShape),
                            Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
                        }
                    }
                }

                // Destination chips. With >1 destination they're TAPPABLE: tapping
                // one swaps the timeline below to that branch's stops; tapping
                // again returns to the common trunk.
                if (destObjs.isNotEmpty() || fallbackDestLabels.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        Icon(Icons.Rounded.Place, null, tint = primary.copy(0.7f), modifier = Modifier.size(13.dp))
                        destObjs.forEach { d ->
                            val isSel = d.id == selectedDestId
                            // Only worth tapping if this branch has its own stops AND
                            // there's more than one destination to disambiguate.
                            val tappable = destObjs.size > 1 && !d.upcomingStations.isNullOrEmpty()
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) primary.copy(0.22f) else primary.copy(0.10f))
                                    .border(0.5.dp, if (isSel) primary.copy(0.6f) else primary.copy(0.30f), RoundedCornerShape(8.dp))
                                    .then(if (tappable) Modifier.clickable { selectedDestId = if (isSel) null else d.id } else Modifier)
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Text(d.label, color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        fallbackDestLabels.forEach { dest ->
                            Box(
                                Modifier
                                    .background(primary.copy(0.10f), RoundedCornerShape(8.dp))
                                    .border(0.5.dp, primary.copy(0.30f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Text(dest, color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // Stations sequence. By default we ALWAYS show a sequence: the
                // common trunk if branches share one, otherwise the selected
                // branch. Tapping a chip swaps to that branch.
                if (activeStops.isNotEmpty()) {
                    val timelineLabel = if (selectedDest != null)
                        interpolate(stationsToTpl, mapOf("dest" to selectedDest.label.uppercase()))
                    else stationsLabel
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(White08, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Column {
                            Text(
                                timelineLabel,
                                color = White25,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                activeStops.forEachIndexed { i, stop ->
                                    if (i > 0) {
                                        Text("  →  ", color = primary.copy(0.55f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(Modifier.size(5.dp).background(primary.copy(0.6f), CircleShape))
                                        Text(stop, color = White90, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                // "Routes split" note — sits BELOW the stops, shown when the
                // direction branches and no specific destination is chosen yet.
                if (routeSplits && selectedDest == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(splitHint, color = White55, fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium, lineHeight = 15.sp)
                }
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Shared utilities
   ═══════════════════════════════════════════════════════════════ */
/**
 * The step's own content is still loading. Inline rather than an overlay: there
 * is nothing behind it yet to protect, and the back button must stay live.
 */
@Composable private fun Loader(primary: Color) = Box(Modifier.fillMaxSize(), Alignment.Center) {
    StationlySpinner(size = 28.dp, color = primary)
}

@Composable
private fun Err(msg: String, primary: Color, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Rounded.WifiOff, null, tint = White25, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(msg, color = White55, textAlign = TextAlign.Center, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = primary),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.height(38.dp)) {
                Icon(Icons.Rounded.Refresh, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Modern CTA
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun ModernCtaButton(label: String, primary: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    // Solid primary background — holds clean onPrimary contrast in both themes.
    // (The earlier 3-stop gradient had a lemon mid-stop that swallowed the text
    // on light theme.)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(100), label = "cta_scale")

    Box(
        modifier = Modifier
            .fillMaxWidth().height(60.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(primary)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        pressed = event.changes.any { it.pressed }
                    }
                }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            // A tick, not a rocket. This button only appears once the selection
            // is complete, so its job is to read as "accept what I've chosen" —
            // a confirmation glyph says that; a launch glyph says "something
            // else is about to happen".
            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 0.3.sp)
        }
    }
}

@Composable
private fun Saving(primary: Color, text: String) {
    val t = rememberInfiniteTransition("sav")
    val slide by t.animateFloat(-50f, 50f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse), "sl")
    val glow  by t.animateFloat(1f, 1.5f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), "gl")
    val dots  by t.animateFloat(0f, 4f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), "dt")

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent() } } }
            .background(MaterialTheme.colorScheme.background.copy(0.97f)).padding(32.dp),
        Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(120.dp), Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(1.5.dp).background(
                    Brush.horizontalGradient(listOf(Color.Transparent, White08, White08, Color.Transparent))))
                Box(Modifier.size(60.dp).graphicsLayer { scaleX = glow; scaleY = glow; alpha = (2f - glow) * 0.2f }
                    .background(primary, CircleShape))
                Icon(Icons.Rounded.Train, null, tint = primary, modifier = Modifier.size(36.dp).offset(x = slide.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(text, color = White90, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Connecting${".".repeat(dots.toInt().coerceIn(0, 3))}", color = White25, fontSize = 13.sp)
            Spacer(Modifier.height(32.dp))
            LinearProgressIndicator(
                Modifier.fillMaxWidth(0.5f).height(1.5.dp).clip(RoundedCornerShape(1.dp)),
                color = primary, trackColor = primary.copy(0.1f))
        }
    }
}

private fun parseColorSafe(hex: String): Color? {
    return try {
        val clean = hex.trim().removePrefix("#")
        val argb = when (clean.length) {
            6 -> "FF$clean".toLong(16)
            8 -> clean.toLong(16)
            else -> return null
        }
        Color(argb.toInt())
    } catch (_: Exception) { null }
}

/**
 * Compact direction card, sized to sit two-per-row.
 *
 * The full [DirCard] carries a route timeline, destination branch chips and a
 * split hint — none of which survives being squeezed into half an iPhone 11's
 * width. This variant keeps only what you need to answer "which way am I
 * going": the compass badge, the direction name, and the next station towards.
 * It is a checkbox, not a radio: both directions of a line can be selected, and
 * each one becomes its own board section.
 */
@Composable
private fun DirChoiceCard(
    opt: SduiDropdownOption,
    sel: Boolean,
    primary: Color,
    layout: SduiAppScreen?,
    modifier: Modifier = Modifier,
    filter: BoardFilter = BoardFilter(),
    modeId: String? = null,
    onOpenFilter: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val towardsLabel = layout?.sdText("dir_towards_label") ?: "towards"

    // Same structured-first, parse-as-fallback binding the full DirCard uses, so
    // both render identically off one payload.
    val lbl = opt.label
    val tIdx = lbl.indexOf(" towards", ignoreCase = true)
    val parsedDir = if (tIdx > 0) lbl.substring(0, tIdx).trim() else lbl.trim()
    val parsedTowards =
        if (tIdx > 0) lbl.substring(tIdx + 8).trim().substringBefore('\n').trim() else ""

    // Buses report directionName as the literal word "Towards" and some modes
    // send nothing at all, which rendered a card titled "Towards" above
    // "towards Gordon Cottages". Fall back to the full label in that case, and
    // suppress the now-duplicate second line.
    val rawDirName = opt.directionName?.trim().orEmpty()
    val hasCompass = rawDirName.isNotBlank() && !rawDirName.equals("Towards", ignoreCase = true)
    val dirName = when {
        hasCompass -> rawDirName
        opt.label.isNotBlank() -> opt.label.trim()
        else -> parsedDir
    }
    val towards = opt.towards?.takeIf { it.isNotBlank() }
        ?: opt.upcomingStations?.firstOrNull()
        ?: parsedTowards.takeIf { it.isNotBlank() }

    val dir = dirName.lowercase()
    val compassIcon: ImageVector? = when {
        dir.contains("north")     -> Icons.Filled.North
        dir.contains("south")     -> Icons.Filled.South
        dir.contains("east")      -> Icons.Filled.East
        dir.contains("west")      -> Icons.Filled.West
        dir.contains("clockwise") -> Icons.Rounded.Loop   // covers anticlockwise too
        else                      -> null
    }

    Surface(
        onClick = onClick,
        color = if (sel) primary.copy(0.10f) else Surface1,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (sel) 1.5.dp else 1.dp, if (sel) primary.copy(0.6f) else White08),
        modifier = modifier
    ) {
        Column(Modifier.padding(12.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (compassIcon != null) {
                    Icon(compassIcon, null, tint = primary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Spacer(Modifier.weight(1f))
                // Tick box on every card, filled when checked — the affordance
                // has to read as multi-select even before anything is picked.
                if (sel) {
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
            }
            Spacer(Modifier.height(6.dp))
            Text(
                dirName.replaceFirstChar { it.uppercase() },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // Only when the title is a compass bearing — otherwise the title
            // ALREADY says "Towards X" and this repeats it.
            if (hasCompass && !towards.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "$towardsLabel $towards",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, lineHeight = 14.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }

            // Where this direction ENDS UP.
            //
            // `towards` is the next stop — correct platform signage, but it
            // answers the wrong question when you are choosing a board. "Towards
            // Russell Square" gives no hint that this is the Heathrow direction,
            // which is the thing people actually decide on. The termini were
            // already in the payload and simply weren't shown.
            val destLabels = opt.destinations?.map { it.label }.orEmpty()
            if (destLabels.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                // One per line rather than a run-on summary: on a junction these
                // are genuinely different journeys and a comma-joined list made
                // them read as one place.
                destLabels.forEach { dest ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Rounded.ArrowRightAlt, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f),
                            modifier = Modifier.size(12.dp).padding(top = 1.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            dest,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.85f),
                            fontSize = 10.sp, lineHeight = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
                // No split warning here: listing several destinations already
                // says the direction divides, so the extra sentence was saying
                // the same thing twice on a card that has little room.
            }

            // Filter row — only once the direction is actually chosen. Showing it
            // on unpicked cards would put a second tap target on something the
            // user hasn't committed to, and read as clutter on a half-width card.
            if (sel && onOpenFilter != null) {
                // Push the filter row to the card's BOTTOM edge. Both cards in a
                // row are the same height now, so without this the control sits
                // at a different vertical position on each — and it reads as the
                // card's footer, which it should look like.
                Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(0.15f))
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenFilter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.FilterList, null,
                        tint = if (filter.isActive) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        filter.summary(modeId),
                        color = if (filter.isActive) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp, lineHeight = 13.sp,
                        fontWeight = if (filter.isActive) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}


/**
 * A chosen line, collapsed while the user works on another.
 *
 * States the answers rather than merely showing the line is closed: which
 * directions were picked and any filter on each. Without that the user has to
 * reopen lines one at a time to remember what they already decided, which is
 * exactly the cost the accordion was meant to remove.
 */
@Composable
private fun CollapsedLineSummary(
    picks: Set<String>,
    directions: List<SduiDropdownOption>?,
    filters: Map<String, BoardFilter>,
    lineId: String,
    modeId: String?,
    primary: Color,
    onClick: () -> Unit,
) {
    // Resolve ids to labels where the direction list is loaded; fall back to the
    // raw id so a collapsed line is never blank while its fetch is in flight.
    val parts = picks.map { dirId ->
        val label = directions?.find { it.id == dirId }
            ?.let { it.directionName ?: it.label }
            ?: dirId.replaceFirstChar { c -> c.uppercase() }
        val filter = filters[SelectionViewModel.boardFilterKey(lineId, dirId)] ?: BoardFilter()
        if (filter.isActive) "$label (${filter.summary(modeId)})" else label
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(primary.copy(0.07f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Check, null,
            tint = primary, modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (parts.isEmpty()) "No direction chosen yet" else parts.joinToString(" · "),
            color = if (parts.isEmpty()) MaterialTheme.colorScheme.error else White90,
            fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Edit",
            color = primary, fontSize = 10.sp, fontWeight = FontWeight.Bold
        )
    }
}

/**
 * One-line description of a filter, for the direction card.
 *
 * Takes the transport mode because "All trains" is wrong on a bus, tram or river
 * board — it was hardcoded and shipped that way.
 */
private fun BoardFilter.summary(modeId: String? = null): String = when {
    !isActive -> "All ${vehicleNounPlural(modeId).lowercase()}"
    mode == FilterMode.VIA -> "via $viaSummary"
    destinationIds.size == 1 -> "1 destination"
    else -> "${destinationIds.size} destinations"
}


/**
 * Compact inline error + retry, sized for a row inside a LazyColumn item.
 *
 * The full-screen [Err] centres itself with `fillMaxSize()`, which inside a lazy
 * item has an unbounded height constraint — it degrades to wrap-content and
 * renders a large icon block in the middle of the line list. This is the same
 * affordance at row scale.
 */
@Composable
private fun InlineErr(msg: String, primary: Color, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.WifiOff, null, tint = White25, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(msg, color = White55, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) {
            Text("Retry", color = primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}
