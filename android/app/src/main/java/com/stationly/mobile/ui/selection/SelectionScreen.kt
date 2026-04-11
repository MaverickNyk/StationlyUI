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
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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

/* ═══════════════════════════════════════════════════════════════
   Palette
   ═══════════════════════════════════════════════════════════════ */
private val Amber     = Color(0xFFFFB81C)
private val Surface0  = Color(0xFF0A0A0A)
private val Surface1  = Color(0xFF141414)
private val Surface2  = Color(0xFF1C1C1C)
private val White90   = Color.White.copy(alpha = 0.90f)
private val White55   = Color.White.copy(alpha = 0.55f)
private val White25   = Color.White.copy(alpha = 0.25f)
private val White08   = Color.White.copy(alpha = 0.08f)

/* ═══════════════════════════════════════════════════════════════
   SDUI helpers — look up Text components from the server layout
   ═══════════════════════════════════════════════════════════════ */
private fun SduiAppScreen.sdText(id: String): String? =
    components.filterIsInstance<SduiAppComponent.Text>().find { comp -> comp.id == id }?.text

/* ═══════════════════════════════════════════════════════════════
   Step helpers
   ═══════════════════════════════════════════════════════════════ */
private fun computeStep(s: Map<String, String>, f: String?): Int {
    if ("mode" !in s || f == null) return 0
    return when (f) {
        "manual" -> when { "direction" in s -> 3; "line" in s -> 2; else -> 1 }
        else     -> when { "line" in s -> 3; "station" in s -> 2; else -> 1 }
    }
}
private fun screenIdx(s: Map<String, String>, f: String?): Int =
    if ("mode" !in s) 0 else if (f == null) 1 else computeStep(s, f) + 1

/* ═══════════════════════════════════════════════════════════════
   Root
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun SelectionScreen(
    onNavigateToSummary: () -> Unit,
    viewModel: SelectionViewModel = viewModel()
) {
    val st by viewModel.uiState.collectAsState()
    val primary by remember(st.layout) {
        derivedStateOf {
            st.layout?.theme?.primaryColor?.let {
                runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrDefault(Amber)
            } ?: Amber
        }
    }

    LaunchedEffect(st.showSuccessDialog) {
        if (st.showSuccessDialog) { onNavigateToSummary(); viewModel.dismissSuccessDialog() }
    }

    val done by remember(st.selections) {
        derivedStateOf { listOf("mode","station","line","direction").all { it in st.selections } }
    }

    val step = computeStep(st.selections, st.currentTrack)
    val idx  = screenIdx(st.selections, st.currentTrack)
    val mode = st.modes.find { it.id == st.selections["mode"] }

    androidx.activity.compose.BackHandler {
        if ("mode" in st.selections) viewModel.popLastSelection() else onNavigateToSummary()
    }

    val ctx = LocalContext.current
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            viewModel.onSelectionChanged("tracking_flow", "discovery")
            viewModel.setCurrentTrack("discovery")
            viewModel.fetchNearbyStations(modeId = st.selections["mode"])
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Surface0, Color.Black)))
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── top bar ──
            MinimalTopBar(mode?.label, step, st.currentTrack != null, primary) {
                if ("mode" in st.selections) viewModel.popLastSelection() else onNavigateToSummary()
            }

            // ── content ──
            AnimatedContent(
                targetState = idx to st.currentTrack,
                transitionSpec = {
                    val fwd = targetState.first >= initialState.first
                    if (fwd) (slideInHorizontally { it / 2 } + fadeIn(tween(260)))
                        .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(180)))
                    else (slideInHorizontally { -it / 2 } + fadeIn(tween(260)))
                        .togetherWith(slideOutHorizontally { it / 3 } + fadeOut(tween(180)))
                },
                label = "nav",
                modifier = Modifier.weight(1f)
            ) { (i, flow) ->
                when {
                    i == 0 -> ModeScreen(st.layout, st.modes, "mode" in st.failedFetches, primary,
                        { viewModel.onSelectionChanged("mode", it.id) }, { viewModel.retryLoad() })

                    i == 1 -> FlowScreen(st.layout, mode, primary,
                        onNear = {
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                viewModel.onSelectionChanged("tracking_flow", "discovery"); viewModel.setCurrentTrack("discovery")
                                viewModel.fetchNearbyStations(modeId = st.selections["mode"])
                            } else locLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        onBrowse = { viewModel.onSelectionChanged("tracking_flow", "manual"); viewModel.setCurrentTrack("manual") })

                    // Manual: Line → Direction → Station
                    i == 2 && flow == "manual" -> ListScreen(
                        st.layout?.sdText("screen_line_title") ?: "Which line?",
                        st.layout?.sdText("screen_line_subtitle") ?: "Which line do you cling to daily?",
                        st.dropdownData["line"] ?: emptyList(), st.selections["line"],
                        st.dropdownData["line"] == null && "line" !in st.failedFetches, "line" in st.failedFetches,
                        primary, null, st.selections["mode"], { viewModel.onSelectionChanged("line", it.id) }, { viewModel.retryDropdown("line") })
                    i == 3 && flow == "manual" -> DirScreen(st.layout, st.dropdownData["direction"] ?: emptyList(), st.selections["direction"],
                        st.dropdownData["direction"] == null && "direction" !in st.failedFetches, primary,
                        { viewModel.onSelectionChanged("direction", it.id) })
                    i == 4 && flow == "manual" -> ListScreen(
                        st.layout?.sdText("screen_station_title") ?: "Pick your stop.",
                        st.layout?.sdText("screen_station_subtitle") ?: "Your daily departure point awaits.",
                        st.dropdownData["station"] ?: emptyList(), st.selections["station"],
                        st.dropdownData["station"] == null && "station" !in st.failedFetches, "station" in st.failedFetches,
                        primary, mode?.iconUrl, st.selections["mode"], { viewModel.onSelectionChanged("station", it.id) }, { viewModel.retryDropdown("station") })

                    // Near-me: Station → Line → Direction
                    i == 2 && flow != "manual" -> NearScreen(st.layout, st.dropdownData["station"] ?: emptyList(), st.selections["station"],
                        st.isLocating, st.noNearbyStationsFound, primary, mode?.iconUrl,
                        { viewModel.onSelectionChanged("station", it.id) },
                        { viewModel.onSelectionChanged("tracking_flow", "manual"); viewModel.setCurrentTrack("manual") })
                    i == 3 && flow != "manual" -> ListScreen(
                        st.layout?.sdText("screen_line_title") ?: "Select Line",
                        st.layout?.sdText("screen_line_subtitle") ?: "Lines stopping here.",
                        st.dropdownData["line"] ?: emptyList(), st.selections["line"],
                        st.dropdownData["line"] == null && "line" !in st.failedFetches, "line" in st.failedFetches,
                        primary, null, st.selections["mode"], { viewModel.onSelectionChanged("line", it.id) }, { viewModel.retryDropdown("line") })
                    i == 4 && flow != "manual" -> DirScreen(st.layout, st.dropdownData["direction"] ?: emptyList(), st.selections["direction"],
                        st.dropdownData["direction"] == null && "direction" !in st.failedFetches, primary,
                        { viewModel.onSelectionChanged("direction", it.id) })

                    else -> Box(Modifier.fillMaxSize())
                }
            }

            // ── CTA — label driven by SDUI Button component ──
            val ctaBtn = st.layout?.components?.filterIsInstance<SduiAppComponent.Button>()?.firstOrNull()
            AnimatedVisibility(
                done,
                enter = slideInVertically { it } + fadeIn(tween(300)),
                exit = slideOutVertically { it } + fadeOut(tween(200)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.95f), Color.Black))
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp, bottom = 20.dp)
                ) {
                    ModernCtaButton(
                        label = ctaBtn?.label ?: "Set Up My Board",
                        primary = primary,
                        onClick = { ctaBtn?.let { viewModel.onActionTriggered(it.action) } }
                    )
                }
            }
        }

        // overlays
        AnimatedVisibility(st.isSaving, enter = fadeIn(), exit = fadeOut()) { Saving(primary, st.layout?.loadingMessage ?: "Preparing Your Live Board") }
        AnimatedVisibility(st.layout == null && st.isBackendOffline, enter = fadeIn(tween(400)), exit = fadeOut(tween(300))) {
            com.stationly.mobile.ui.common.ServiceUnavailableScreen("selection", st.error, { viewModel.retryLoad() }, { onNavigateToSummary() })
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Top bar  — thin, elegant, with animated progress dots
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
            Spacer(Modifier.size(40.dp)) // balance the back button
        }

        // animated progress dots
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
            Text(layout?.sdText("screen_mode_title") ?: "Pick your\nchariot.",
                color = White90, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                lineHeight = 30.sp, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(6.dp))
            Text(layout?.sdText("screen_mode_subtitle") ?: "Bus, tube, or DLR — we're not judging.",
                color = White55, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(20.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
            ) {
                items(modes, key = { it.id }) { m ->
                    ModeCard(m, primary) { onSelect(m) }
                }
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
            // icon with amber glow ring
            Box(contentAlignment = Alignment.Center) {
                // glow circle behind
                Box(Modifier.size(72.dp).background(primary.copy(0.08f), CircleShape))
                Box(
                    Modifier.size(60.dp).background(Color.White, CircleShape)
                        .border(2.dp, primary.copy(0.35f), CircleShape).padding(12.dp),
                    Alignment.Center
                ) {
                    if (!mode.iconUrl.isNullOrEmpty())
                        AsyncImage(mode.iconUrl, mode.label, Modifier.fillMaxSize())
                    else
                        Text(mode.label.take(1), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Surface0)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(mode.label, color = White90, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Screen 1 — Flow choice (hero layout)
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun FlowScreen(layout: SduiAppScreen?, mode: SduiDropdownOption?, primary: Color, onNear: () -> Unit, onBrowse: () -> Unit) {
    // Read flow picker options from SDUI layout
    val flowPicker = layout?.components?.filterIsInstance<SduiAppComponent.FlowPicker>()?.firstOrNull()
    val nearOpt   = flowPicker?.options?.find { it.id == "discovery" }
    val browseOpt = flowPicker?.options?.find { it.id == "manual" }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            val pulse by rememberInfiniteTransition("hero").animateFloat(
                0.95f, 1.08f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), "hp")
            Box(Modifier.size(110.dp).graphicsLayer(scaleX = pulse, scaleY = pulse)
                .background(primary.copy(0.07f), CircleShape))
            Box(
                Modifier.size(82.dp).background(primary.copy(0.12f), CircleShape)
                    .border(2.dp, primary.copy(0.3f), CircleShape).padding(16.dp),
                Alignment.Center
            ) {
                if (!mode?.iconUrl.isNullOrEmpty())
                    AsyncImage(mode?.iconUrl, mode?.label, Modifier.fillMaxSize())
                else
                    Text(mode?.label?.take(1) ?: "?", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = primary)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(mode?.label ?: "", color = primary, fontWeight = FontWeight.Bold, fontSize = 20.sp)

        Spacer(Modifier.height(36.dp))
        Text(layout?.sdText("screen_flow_title") ?: "Where's your usual haunt?",
            color = White90, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(layout?.sdText("screen_flow_subtitle") ?: "Pick how you'd like to find your stop.",
            color = White55, fontSize = 13.sp, textAlign = TextAlign.Center)

        Spacer(Modifier.height(28.dp))

        // Near Me — labels from SDUI FlowPicker options
        OutlinedButton(
            onClick = onNear, shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, primary.copy(0.75f)),
            modifier = Modifier.fillMaxWidth().height(68.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = primary.copy(0.08f))
        ) {
            Icon(Icons.Rounded.MyLocation, null, tint = primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(nearOpt?.label ?: "Near Me", color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(nearOpt?.description ?: "GPS'll sort it", color = primary.copy(0.6f), fontSize = 12.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = primary.copy(0.4f), modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.height(10.dp))

        // Browse — labels from SDUI FlowPicker options
        OutlinedButton(
            onClick = onBrowse, shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, White25),
            modifier = Modifier.fillMaxWidth().height(68.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = White08)
        ) {
            Icon(Icons.Rounded.Search, null, tint = White55, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(browseOpt?.label ?: "Browse Network", color = White90, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(browseOpt?.description ?: "I'll find my own way, cheers", color = White55, fontSize = 12.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = White25, modifier = Modifier.size(18.dp))
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Generic list screen (Lines / Stations)
   — search only when > 10 items
   — modeIconUrl shown for station rows
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun ListScreen(
    title: String, sub: String,
    options: List<SduiDropdownOption>, selectedId: String?,
    loading: Boolean, err: Boolean, primary: Color,
    modeIcon: String? = null,
    mode: String? = null,
    onSelect: (SduiDropdownOption) -> Unit, onRetry: () -> Unit
) {
    var q by remember(options) { mutableStateOf("") }
    val showSearch = options.size > 10
    val list = remember(options, q) {
        if (q.isBlank() || !showSearch) options
        else options.filter { it.label.contains(q, true) }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))
        Text(title, color = White90, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(4.dp))
        Text(sub, color = White55, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(14.dp))

        when {
            err -> Err("Couldn't load data", primary, onRetry)
            loading -> Loader(primary)
            else -> {
                if (showSearch) {
                    OutlinedTextField(
                        value = q, onValueChange = { q = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp).height(48.dp),
                        placeholder = { Text("Search…", color = White25, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = primary.copy(0.6f), modifier = Modifier.size(18.dp)) },
                        trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Rounded.Clear, null, tint = White25, modifier = Modifier.size(16.dp)) } },
                        singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primary.copy(0.5f), unfocusedBorderColor = White08,
                            focusedContainerColor = Surface2, unfocusedContainerColor = Surface1,
                            focusedTextColor = White90, unfocusedTextColor = White90
                        )
                    )
                }

                if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(if (q.isBlank()) "Nothing here yet" else "No results", color = White25, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(list, key = { it.id }) { opt ->
                            OptRow(opt, opt.id == selectedId, primary, modeIcon, mode) { onSelect(opt) }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
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
    // Parse hex color from backend — only shown for non-bus lines
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
            // TfL line color stripe (non-bus lines only)
            if (lineColor != null) {
                Box(
                    Modifier
                        .width(5.dp)
                        .height(32.dp)
                        .background(lineColor, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                )
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.width(14.dp))
            }

            // Mode icon or option icon
            if (modeIcon != null) {
                Box(
                    Modifier.size(34.dp).background(Color.White, CircleShape)
                        .border(1.dp, primary.copy(0.25f), CircleShape).padding(5.dp),
                    Alignment.Center
                ) { AsyncImage(modeIcon, null, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(12.dp))
            } else if (opt.iconUrl != null) {
                AsyncImage(opt.iconUrl, null, Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(displayLabel, color = if (sel) primary else White90, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                opt.secondaryLabel?.let { secondary ->
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (secondary.contains("km", true) || secondary.contains("mi", true) || secondary.contains("m ", true)) {
                            Icon(Icons.Rounded.NearMe, null, tint = primary.copy(0.6f), modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(secondary, color = primary.copy(0.6f), fontSize = 12.sp)
                    }
                }
            }

            if (sel) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(22.dp).background(primary, CircleShape), Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Direction step — grouped cards with destination bullets
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun DirScreen(
    layout: SduiAppScreen?,
    options: List<SduiDropdownOption>, selectedId: String?,
    loading: Boolean, primary: Color, onSelect: (SduiDropdownOption) -> Unit
) {
    val funFactTitle = layout?.sdText("screen_direction_funfact_title")
    val funFactText  = layout?.sdText("screen_direction_funfact")
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(layout?.sdText("screen_direction_title") ?: "Which direction?",
            color = White90, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(4.dp))
        Text(layout?.sdText("screen_direction_subtitle") ?: "Which way are you fleeing today?",
            color = White55, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        if (loading) Loader(primary)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(options, key = { it.id }) { opt -> DirCard(opt, opt.id == selectedId, primary) { onSelect(opt) } }
            if (funFactText != null) {
                item {
                    Spacer(Modifier.height(8.dp))
                    DirFunFact(primary, funFactTitle, funFactText)
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun DirCard(opt: SduiDropdownOption, sel: Boolean, primary: Color, onClick: () -> Unit) {
    // Parse "Inbound towards\nKing's Cross\nBrixton" from label
    val lbl = opt.label
    val tIdx = lbl.indexOf(" towards", ignoreCase = true)
    val dirName: String
    val rawDests: List<String>
    if (tIdx > 0) {
        dirName = lbl.substring(0, tIdx).trim()
        rawDests = lbl.substring(tIdx + 8).trim()
            .split("\n", ",", " and ", " & ").map { it.trim() }.filter { it.isNotBlank() }
    } else { dirName = lbl.trim(); rawDests = emptyList() }

    val fromSecondary = opt.secondaryLabel
        ?.let { it.removePrefix("towards ").removePrefix("Towards ")
            .split("\n", ",", " and ", " & ").map { s -> s.trim() }.filter { s -> s.isNotBlank() } }
        ?: emptyList()
    val dests = (rawDests + fromSecondary).distinct().take(6)

    val icon: ImageVector = when {
        opt.id.contains("inbound", true) || lbl.contains("inbound", true) -> Icons.Filled.CallReceived
        opt.id.contains("outbound", true) || lbl.contains("outbound", true) -> Icons.Filled.CallMade
        lbl.contains("north", true) -> Icons.Filled.North
        lbl.contains("south", true) -> Icons.Filled.South
        lbl.contains("east", true)  -> Icons.Filled.East
        lbl.contains("west", true)  -> Icons.Filled.West
        else -> Icons.Filled.Explore
    }

    Surface(
        onClick = onClick, color = if (sel) primary.copy(0.08f) else Surface1,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (sel) 1.5.dp else 1.dp, if (sel) primary.copy(0.6f) else White08),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(18.dp)) {
            // Direction icon
            Box(
                Modifier.size(42.dp).background(if (sel) primary.copy(0.15f) else White08, CircleShape),
                Alignment.Center
            ) { Icon(icon, null, tint = if (sel) primary else White55, modifier = Modifier.size(20.dp)) }

            Spacer(Modifier.width(14.dp))

            // Content
            Column(Modifier.weight(1f)) {
                Text("DIRECTION", color = White25, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dirName, color = if (sel) primary else White90,
                        fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                    if (sel) Box(Modifier.size(22.dp).background(primary, CircleShape), Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                    }
                }
                if (dests.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("towards", color = White55, fontSize = 11.sp, letterSpacing = 0.2.sp)
                    Spacer(Modifier.height(5.dp))
                    dests.forEach { d ->
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(3.5.dp).background(primary.copy(0.4f), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(d, color = White55, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Near-me station step
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun NearScreen(
    layout: SduiAppScreen?,
    stations: List<SduiDropdownOption>, selectedId: String?,
    locating: Boolean, noNearby: Boolean, primary: Color, modeIcon: String?,
    onSelect: (SduiDropdownOption) -> Unit, onManual: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))
        Text(layout?.sdText("screen_station_title") ?: "Nearby Stations",
            color = White90, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(4.dp))
        Text(layout?.sdText("screen_station_subtitle") ?: "Closest escape routes first.",
            color = White55, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(14.dp))

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

            noNearby -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(Icons.Rounded.LocationOff, null, tint = White25, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("No stations nearby", color = White90, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Try browsing the network instead", color = White55, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = onManual, colors = ButtonDefaults.buttonColors(containerColor = primary),
                        shape = RoundedCornerShape(12.dp)) {
                        Text("Browse Network", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            stations.isEmpty() -> Loader(primary)

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()
            ) {
                items(stations, key = { it.id }) { st ->
                    OptRow(st, st.id == selectedId, primary, modeIcon) { onSelect(st) }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Direction fun-fact card — explains Inbound/Outbound to newcomers
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun DirFunFact(primary: Color, title: String?, body: String) {
    Surface(
        color = primary.copy(alpha = 0.07f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, primary.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, null, tint = primary.copy(0.8f),
                modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                if (title != null) {
                    Text(title, color = primary.copy(0.9f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Spacer(Modifier.height(5.dp))
                }
                Text(body, color = White55, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Shared
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
                Icon(Icons.Rounded.Refresh, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Modern CTA — gradient shimmer + pulse glow
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun ModernCtaButton(label: String, primary: Color, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition("cta")
    val shimmerX by inf.animateFloat(
        -1f, 2f,
        infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart), "shim"
    )
    val glowAlpha by inf.animateFloat(
        0.0f, 0.35f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), "glow"
    )
    val darkerPrimary = Color(
        (primary.red * 0.75f).coerceIn(0f, 1f),
        (primary.green * 0.65f).coerceIn(0f, 1f),
        (primary.blue * 0.3f).coerceIn(0f, 1f)
    )
    Box(modifier = Modifier.fillMaxWidth()) {
        // Glow behind the button
        Box(
            Modifier
                .fillMaxWidth(0.85f)
                .height(64.dp)
                .align(Alignment.Center)
                .graphicsLayer { alpha = glowAlpha; scaleX = 1.05f; scaleY = 1.3f }
                .background(primary.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
        )
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .drawWithContent {
                    // Gradient background
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(primary, darkerPrimary, primary),
                            start = Offset(size.width * shimmerX, 0f),
                            end = Offset(size.width * (shimmerX + 0.6f), size.height)
                        )
                    )
                    drawContent()
                },
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 12.dp, pressedElevation = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.RocketLaunch, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    label, color = Color.Black,
                    fontWeight = FontWeight.Black, fontSize = 17.sp, letterSpacing = 0.5.sp
                )
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Rounded.ArrowForward, null, tint = Color.Black.copy(0.5f), modifier = Modifier.size(18.dp))
            }
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
            .background(Color.Black.copy(0.97f)).padding(32.dp),
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
