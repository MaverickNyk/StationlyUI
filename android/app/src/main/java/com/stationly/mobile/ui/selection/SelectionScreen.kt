@file:OptIn(ExperimentalMaterial3Api::class)
package com.stationly.mobile.ui.selection

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.model.sdui.SduiDropdownOption
import kotlinx.coroutines.delay

/* ═══════════════════════════════════════════════════════════════
   Palette
   ═══════════════════════════════════════════════════════════════ */
// Theme-aware palette — names preserved so call sites stay unchanged.
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
 * `{mode}` / `{station}` / `{line}` (etc.) placeholders the backend
 * embedded with the live selection. This is what makes the copy fully
 * SDUI-driven: the server can ship "{Mode} stops near you" or "Lines
 * from {station}" and the client fills in the runtime values. Empty
 * substitutions collapse cleanly so a missing var never leaves "  " or a
 * dangling token. Returns null when the key isn't in the layout, so call
 * sites fall back to the local default string.
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
   Context-aware vocabulary. The copy on each step references the
   choice made on the previous step (mode → station → line), so the
   flow reads like a sentence: "Bus stops near you" → "Routes from
   Trafalgar Square" → "Buses from Trafalgar Square". A bus rides on
   "stops"/"routes"/"Buses"; rail modes on "stations"/"lines"/"Trains".
   Backend can still override any of these via the screen_* SDUI keys.
   ─────────────────────────────────────────────────────────────── */
private fun stopNounSingular(modeId: String?): String =
    if (modeId == "bus" || modeId == "tram") "stop" else "station"
private fun lineNounSingular(modeId: String?): String =
    if (modeId == "bus") "route" else "line"
private fun lineNounPluralCap(modeId: String?): String =
    if (modeId == "bus") "Routes" else "Lines"
private fun vehicleNounPlural(modeId: String?): String = when (modeId) {
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
private fun computeStep(s: Map<String, String>): Int = when {
    "direction" in s -> 3
    "line"      in s -> 2
    "station"   in s -> 1
    else             -> 0
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
    viewModel: SelectionViewModel = viewModel()
) {
    val st by viewModel.uiState.collectAsState()
    // App-wide themed primary — flips automatically with light/dark mode and
    // any SDUI ThemeTokens override. Previously this screen parsed
    // `st.layout?.theme?.primaryColor` from the per-layout SDUI theme, which
    // hardcoded a bright TfL-amber that wrecked light-mode contrast (chips,
    // CTA, border were all #FFB81C regardless of theme).
    val primary = MaterialTheme.colorScheme.primary

    LaunchedEffect(st.showSuccessDialog) {
        if (st.showSuccessDialog) { onNavigateToSummary(); viewModel.dismissSuccessDialog() }
    }

    val done by remember(st.selections) {
        derivedStateOf { listOf("mode", "station", "line", "direction").all { it in st.selections } }
    }

    val step = computeStep(st.selections)
    val idx  = screenIdx(st.selections)
    val mode = st.modes.find { it.id == st.selections["mode"] }
    // The chosen station's display name, carried forward into the line /
    // direction copy ("Lines from {station}"). Kept in dropdownData even
    // after selection, so this resolves on the later steps too.
    val stationName = remember(st.dropdownData, st.selections) {
        st.selections["station"]?.let { id ->
            st.dropdownData["station"]?.find { it.id == id }?.label
        }
    }

    androidx.activity.compose.BackHandler {
        if ("mode" in st.selections) viewModel.popLastSelection() else onNavigateToSummary()
    }

    val ctx = LocalContext.current

    // Permission launcher for nearby station discovery
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.fetchNearbyStations(modeId = st.selections["mode"])
        // If denied, the StationScreen will show only the search bar
    }

    // When station screen is shown, auto-load nearby stations (if not already loaded)
    LaunchedEffect(idx) {
        if (idx == 1 && !st.isLocating && st.dropdownData["station"].isNullOrEmpty() && !st.isGpsUnavailable) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                viewModel.fetchNearbyStations(st.userLat, st.userLon, st.selections["mode"])
            } else {
                locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    // If location resolved after station screen was already shown, re-trigger nearby fetch
    LaunchedEffect(st.userLat, st.userLon) {
        if (idx == 1 && !st.isLocating && st.dropdownData["station"].isNullOrEmpty()
            && !st.isGpsUnavailable && st.userLat != null && st.userLon != null) {
            viewModel.fetchNearbyStations(st.userLat, st.userLon, st.selections["mode"])
        }
    }

    Box(
        Modifier.fillMaxSize()
            // Subtle theme-aware fade: surface → background. Originally a
            // hardcoded Surface0 → Black, which would be a near-black band
            // in dark and ALSO a black band in light (jarring). Swapping
            // both ends to theme colours keeps the gentle gradient feel
            // in both modes.
            .background(Brush.verticalGradient(listOf(Surface1, Surface0)))
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── top bar ──
            MinimalTopBar(mode?.label, step, "mode" in st.selections, primary) {
                if ("mode" in st.selections) viewModel.popLastSelection() else onNavigateToSummary()
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
                        st.layout, st.modes, "mode" in st.failedFetches, primary,
                        { viewModel.onSelectionChanged("mode", it.id) }, { viewModel.retryLoad() }
                    )

                    // Screen 1 — Station (nearby + search combined)
                    1 -> StationScreen(
                        layout          = st.layout,
                        stations        = st.dropdownData["station"] ?: emptyList(),
                        recentStations  = run {
                            val currentIds = st.dropdownData["station"]?.map { it.id }?.toSet() ?: emptySet()
                            st.recentStations.filter { it.id in currentIds }
                        },
                        selectedId      = st.selections["station"],
                        locating        = st.isLocating,
                        noNearby        = st.isGpsUnavailable,
                        searchEmpty     = st.isSearchEmpty,
                        primary         = primary,
                        modeIcon        = mode?.iconUrl,
                        mode            = st.selections["mode"],
                        modeLabel       = mode?.label,
                        onSelect        = { viewModel.onSelectionChanged("station", it.id) },
                        onSearch        = { viewModel.searchStations(it) }
                    )

                    // Screen 2 — Line + Direction (merged)
                    2 -> LineDirectionScreen(
                        layout          = st.layout,
                        lines           = st.dropdownData["line"] ?: emptyList(),
                        selectedLineId  = st.selections["line"],
                        directions      = st.dropdownData["direction"] ?: emptyList(),
                        selectedDirId   = st.selections["direction"],
                        loadingLines    = st.dropdownData["line"] == null && "line" !in st.failedFetches,
                        loadingDirs     = st.dropdownData["direction"] == null && "direction" !in st.failedFetches,
                        errLines        = "line" in st.failedFetches,
                        primary         = primary,
                        mode            = st.selections["mode"],
                        modeIcon        = mode?.iconUrl,
                        modeLabel       = mode?.label,
                        stationName     = stationName,
                        onSelectLine    = { viewModel.onSelectionChanged("line", it.id) },
                        onSelectDir     = { viewModel.onSelectionChanged("direction", it.id) },
                        onRetry         = { viewModel.retryDropdown("line") }
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
                        // CTA backdrop fades from transparent up top into the
                        // canvas background at the bottom so the floating CTA
                        // doesn't sit on bare content.
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

        // overlays
        AnimatedVisibility(st.isSaving, enter = fadeIn(), exit = fadeOut()) {
            Saving(primary, st.layout?.loadingMessage ?: "Preparing Your Live Board")
        }
        AnimatedVisibility(st.layout == null && st.isBackendOffline, enter = fadeIn(tween(400)), exit = fadeOut(tween(300))) {
            com.stationly.mobile.ui.common.ServiceUnavailableScreen(
                "selection", st.error, { viewModel.retryLoad() }, { onNavigateToSummary() }
            )
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Top bar — thin, elegant, animated progress dots
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun MinimalTopBar(modeName: String?, step: Int, showProgress: Boolean, primary: Color, onBack: () -> Unit) {
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
                if (modeName != null) "New $modeName Board" else "Set up a Board",
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
            ) { AsyncImage(modeIcon, null, Modifier.fillMaxSize()) }
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
            // Adaptive grid — auto-grows columns as the screen gets wider.
            // Was hardcoded to Fixed(2), which produced phone-sized 2-up
            // tiles even on a tablet (where the tiles ballooned to ~400dp
            // tall with a tiny roundel floating in the middle). 170dp min
            // gives 2 columns on a phone (~360dp wide) and 4-5 columns on
            // a tablet (~800dp short edge).
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
            // White roundel container — always white because TfL line icons
            // are designed to sit on a white field (transport branding
            // convention). The fallback letter inside is forced to black
            // for the same reason: black-on-white roundel typography.
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).background(primary.copy(0.08f), CircleShape))
                Box(
                    Modifier.size(60.dp).background(Color.White, CircleShape)
                        .border(2.dp, primary.copy(0.35f), CircleShape).padding(12.dp),
                    Alignment.Center
                ) {
                    if (!mode.iconUrl.isNullOrEmpty())
                        AsyncImage(mode.iconUrl, mode.label, Modifier.fillMaxSize())
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

    LaunchedEffect(searchQuery) { delay(300); onSearch(searchQuery) }

    // Auto-focus search when GPS is unavailable
    LaunchedEffect(noNearby) { if (noNearby && stations.isEmpty()) focusRequester.requestFocus() }

    // Interpolation vars + functional fallbacks. Backend owns the wording
    // via screen_station_title / screen_station_subtitle; templates may use
    // {mode} and {stop} (the mode-correct "station"/"stop" noun). These local
    // strings only show offline or before the layout loads.
    val noun = stopNounSingular(mode)               // "stop" (bus/tram) | "station"
    val vars = mapOf("mode" to modeLabel, "stop" to noun)
    val titleFallback =
        if (!modeLabel.isNullOrBlank()) "Find a $modeLabel $noun" else "Find your $noun"
    val subtitleFallback =
        if (noNearby) "Location off — search by name."
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
                    Text("Location unavailable — type to find stops", color = White25, fontSize = 12.sp)
                }
            }

            stations.isEmpty() -> Loader(primary)

            else -> {
                val showRecent = searchQuery.isBlank() && recentStations.isNotEmpty()
                val sectionLabel = if (searchQuery.isBlank()) "Nearby" else "Results"
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (showRecent) {
                        item {
                            SectionHeader("Recent")
                        }
                        items(recentStations, key = { "r_${it.id}" }) { s ->
                            OptRow(s, s.id == selectedId, primary, modeIcon, mode) { onSelect(s) }
                        }
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                    item { SectionHeader(sectionLabel) }
                    items(stations, key = { it.id }) { s ->
                        OptRow(s, s.id == selectedId, primary, modeIcon, mode) { onSelect(s) }
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
    selectedLineId: String?,
    directions: List<SduiDropdownOption>,
    selectedDirId: String?,
    loadingLines: Boolean,
    loadingDirs: Boolean,
    errLines: Boolean,
    primary: Color,
    mode: String?,
    modeIcon: String?,
    modeLabel: String?,
    stationName: String?,
    onSelectLine: (SduiDropdownOption) -> Unit,
    onSelectDir: (SduiDropdownOption) -> Unit,
    onRetry: () -> Unit
) {
    val lineSelected = selectedLineId != null
    val lineName = lines.find { it.id == selectedLineId }?.label

    // Interpolation vars shared by both sub-steps. Backend owns the wording
    // via the screen_line_* / screen_direction_* keys. Templates may use
    // {station}, {line}, {mode}, plus the mode-correct nouns {lines}
    // ("Lines"/"Routes"), {line_noun} ("line"/"route") and {vehicle}
    // ("Trains"/"Buses"). These local strings only show offline.
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
            ?: "${lineNounPluralCap(mode)}$fromStation"        // "Lines from Baker Street"
    else
        layout?.sdText("screen_direction_title", vars) ?: "Which direction?"

    val subtitle = if (!lineSelected)
        layout?.sdText("screen_line_subtitle", vars)
            ?: "Which ${lineNounSingular(mode)} are you taking?" // "Which line are you taking?"
    else
        layout?.sdText("screen_direction_subtitle", vars)
            ?: "${vehicleNounPlural(mode)}$fromStation"          // "Trains from Baker Street"

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))
        StepHeader(modeIcon = modeIcon, primary = primary, title = title, subtitle = subtitle)
        Spacer(Modifier.height(14.dp))

        when {
            errLines -> Err("Couldn't load lines", primary, onRetry)
            loadingLines -> Loader(primary)
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item { SectionHeader("Lines") }

                items(lines, key = { it.id }) { line ->
                    OptRow(line, line.id == selectedLineId, primary, null, mode) { onSelectLine(line) }

                    // Inline direction picker expands below the selected line
                    AnimatedVisibility(
                        visible = line.id == selectedLineId,
                        enter = expandVertically(tween(280)) + fadeIn(tween(220)),
                        exit  = shrinkVertically(tween(200)) + fadeOut(tween(150))
                    ) {
                        Column(Modifier.padding(start = 8.dp, top = 10.dp)) {
                            SectionHeader("Direction")
                            Spacer(Modifier.height(8.dp))
                            if (loadingDirs) {
                                Box(Modifier.fillMaxWidth().height(56.dp), Alignment.Center) {
                                    CircularProgressIndicator(color = primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                                }
                            } else {
                                directions.forEach { dir ->
                                    DirCard(dir, dir.id == selectedDirId, primary, layout) { onSelectDir(dir) }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun OptRow(opt: SduiDropdownOption, sel: Boolean, primary: Color, modeIcon: String?, mode: String? = null, onClick: () -> Unit) {
    val displayLabel = remember(opt.label, mode) {
        if (mode == "bus" && opt.label.all { it.isDigit() || it == ' ' } && opt.label.trim().isNotEmpty())
            "Bus ${opt.label.trim()}"
        else opt.label
    }
    val lineColor = remember(opt.color, mode) {
        if (mode != "bus" && opt.color != null)
            runCatching { Color(android.graphics.Color.parseColor(opt.color)) }.getOrNull()
        else null
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
                    AsyncImage(modeIcon, null, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
            } else if (opt.iconUrl != null) {
                AsyncImage(opt.iconUrl, null, Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
            }

            Column(Modifier.weight(1f)) {
                // Station name — always high-contrast onSurface. Selected
                // state is indicated by the card's border + tick, not by
                // recolouring the title (which made it disappear on light).
                Text(displayLabel, color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val hasDistance = opt.secondaryLabel != null
                val hasTags = !opt.tags.isNullOrEmpty()
                if (hasDistance || hasTags) {
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        opt.secondaryLabel?.let { secondary ->
                            // Distance/secondary label: muted onSurface so it
                            // reads as supporting text in both themes. The
                            // previous primary-tinted version disappeared on
                            // light theme (amber on white = poor contrast).
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
                            val dotColor = remember(hex) {
                                runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
                            }
                            if (dotColor != null) {
                                Box(Modifier.size(8.dp).background(dotColor, CircleShape)
                                    .border(0.5.dp, White25.copy(alpha = 0.6f), CircleShape))
                            }
                        }
                    }
                }
            }

            if (sel) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(22.dp).background(primary, CircleShape), Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
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
    val splitHint     = layout?.sdText("dir_split_hint") ?: "This direction splits — tap a destination above to see its full line of stops."
    // Bind to the STRUCTURED route fields the backend now sends
    // (directionName / towards / destinations / upcomingStations). Fall back
    // to parsing the legacy label / secondaryLabel strings so older payloads
    // (or a stale cache) still render.
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

    // Reachable destinations as FULL objects — each carries its own branch
    // stops in `upcomingStations`, so tapping a chip swaps the timeline to that
    // branch. Legacy payloads (no structured destinations) fall back to labels.
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

    // Headline target: a chosen branch's NEXT stop → else the next common stop
    // → else (junction, nothing chosen) the destination list itself
    // ("Wimbledon, Richmond & 2 more"), which is what the platform sign shows.
    val displayedTowards = when {
        selectedDest != null -> selectedDest.upcomingStations?.firstOrNull() ?: selectedDest.label
        commonStops.isNotEmpty() -> towards
        destObjs.isNotEmpty() -> summariseDestinations(destObjs.map { it.label })
        else -> towards
    }

    // Compass badge only for rail/tube/overground (Northbound … / Clockwise).
    // Buses report directionName="Towards" and inbound/outbound is meaningless
    // to passengers, so those get no badge — the "towards X" headline carries it.
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
                // ── Header: a QUIET compass cue (rail only) + selected tick.
                // The compass is a secondary confirmation, not the headline, so
                // it's a small muted label with no solid fill. Skipped entirely
                // when neither is present (unselected bus card) so "towards …"
                // sits flush at the top. ──
                if (compassIcon != null || sel) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (badgeText != null && compassIcon != null) {
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

                // ── "towards {next station}" — the HIGHLIGHTED headline. The
                // station name is the boldest, largest element on the card (the
                // primary recognition cue); "towards" is a quiet muted lead-in,
                // baseline-aligned so they read as one phrase. ──
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
                }

                // ── Destination chips. With >1 destination they're TAPPABLE:
                // tapping one swaps the timeline below to that branch's stops;
                // tapping again returns to the common trunk. ──
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

                // ── Stations sequence. By default we ALWAYS show a sequence:
                // the common trunk if branches share one, otherwise the richest
                // branch (so the user sees stops at a junction too). Tapping a
                // chip swaps to that branch. The label names the branch when we're
                // showing one; otherwise it's the shared-trunk label. ──
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

                // ── "Routes split" note — sits BELOW the stops, shown when the
                // direction branches and no specific destination is chosen yet. ──
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
@Composable private fun Loader(primary: Color) = Box(Modifier.fillMaxSize(), Alignment.Center) {
    CircularProgressIndicator(color = primary, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
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
    val shape    = RoundedCornerShape(20.dp)
    // Solid primary background — the earlier 3-stop gradient had a bright
    // lemon `#FFD96A` mid-stop. On light theme the white onPrimary text
    // disappeared into the pale yellow band; on dark it shimmered but the
    // contrast was inconsistent. Solid primary holds clean onPrimary
    // contrast in both themes.
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
            Icon(Icons.Rounded.RocketLaunch, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
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
