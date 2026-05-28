package com.stationly.mobile.dream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Configuration screen for the Daydream. Reachable via the gear icon next to
 * "Stationly" in Settings → Display → Screen saver — wired through
 * `stationly_dream_info.xml`'s settingsActivity attribute.
 *
 * Layout direction (inspired by Google's Pixel screensaver picker):
 *   - Big single preview at the top showing the currently-selected layout.
 *   - Chip selector below for the two layout options.
 *   - Theme picker (mini board mockups, hidden for fullscreen since that
 *     layout is hard-pinned to dark in DreamHost).
 *   - Clock style (hidden for fullscreen — that layout uses its own ticking
 *     widget clock).
 *   - Station picker (only when the user has more than one board on home).
 *
 * Content is centred with a max-width on tablets so the picker reads as a
 * focused card instead of stretching corner-to-corner.
 */
class DreamSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Wrap in StationlyThemeHost so this activity (launched by
            // system Settings, not by MainActivity) picks up the user's
            // AppTheme preference.
            com.stationly.mobile.ui.theme.StationlyThemeHost {
                DreamSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DreamSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Brand accent — flows from the app theme so picker tints flip with
    // light / dark instead of staying TfL amber on a cream canvas.
    val accent = MaterialTheme.colorScheme.primary

    // Read the SDUI string map cached by SummaryViewModel. Activity is
    // launched cold from system Settings (no ViewModel reference) so we
    // pull from the on-disk HomeConfigStore. Hardcoded fallbacks below
    // keep the screen functional on first launch when the cache is empty.
    val strings = remember { com.stationly.mobile.util.HomeConfigStore.read(context) }

    var layout     by remember { mutableStateOf(DreamSettings.getLayout(context)) }
    var theme      by remember { mutableStateOf(DreamSettings.getTheme(context)) }
    var clockStyle by remember { mutableStateOf(DreamSettings.getClockStyle(context)) }
    var stationId  by remember { mutableStateOf(DreamSettings.getStationId(context)) }

    var stations by remember { mutableStateOf<List<UserSelection>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        stations = withContext(Dispatchers.IO) { Platform.sqlStorage.getAllSelections() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = strings["dream.settings.title"] ?: "Screensaver",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        // Outer column centres children horizontally; inner column caps
        // width on tablets so the picker doesn't stretch corner-to-corner.
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // ── Big preview of the active layout ────────────────────
                BigLayoutPreview(
                    layout = layout,
                    theme = theme,
                    clockStyle = clockStyle,
                    accent = accent,
                )

                // ── Layout chips: pick between the two layouts ──────────
                LayoutChipRow(
                    selected = layout,
                    accent = accent,
                    strings = strings,
                    onPick = {
                        layout = it
                        scope.launch { DreamSettings.setLayout(context, it) }
                    },
                )

                // ── Theme picker (hidden for fullscreen — pinned dark) ──
                if (layout != DreamLayout.FULLSCREEN_BOARD) {
                    Section(label = strings["dream.settings.section.theme"] ?: "Theme") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            DreamTheme.entries.forEach { t ->
                                ThemeTile(
                                    theme = t,
                                    selected = t == theme,
                                    accent = accent,
                                    strings = strings,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        theme = t
                                        scope.launch { DreamSettings.setTheme(context, t) }
                                    },
                                )
                            }
                        }
                    }
                }

                // ── Clock style (only for clock-and-board) ──────────────
                if (layout == DreamLayout.CLOCK_AND_BOARD) {
                    Section(label = strings["dream.settings.section.clock"] ?: "Clock style") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ClockStyle.entries.forEach { style ->
                                ClockStyleTile(
                                    style = style,
                                    selected = style == clockStyle,
                                    accent = accent,
                                    strings = strings,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        clockStyle = style
                                        scope.launch { DreamSettings.setClockStyle(context, style) }
                                    },
                                )
                            }
                        }
                    }
                }

                // ── Station picker (only when more than one is set) ─────
                if (stations.size > 1) {
                    Section(label = strings["dream.settings.section.station"] ?: "Station to display") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StationCard(
                                title    = strings["dream.settings.station.auto.title"]    ?: "Auto",
                                subtitle = strings["dream.settings.station.auto.subtitle"] ?: "Match the top board on your home screen",
                                lineColor = accent.copy(alpha = 0.55f),
                                selected = stationId == null,
                                accent = accent,
                                onClick = {
                                    stationId = null
                                    scope.launch { DreamSettings.setStationId(context, null) }
                                },
                            )
                            stations.forEach { sel ->
                                StationCard(
                                    title = sel.stationName,
                                    subtitle = "${sel.line.replaceFirstChar { it.uppercase() }} · " +
                                        sel.direction.replaceFirstChar { it.uppercase() },
                                    lineColor = com.stationly.mobile.ui.summary.components.lineColorForTheme(
                                        sel.line,
                                        MaterialTheme.colorScheme.background.luminance() < 0.5f,
                                    ).let { c -> if (c == com.stationly.mobile.ui.theme.TflAmber) accent else c },
                                    selected = stationId == sel.station,
                                    accent = accent,
                                    onClick = {
                                        stationId = sel.station
                                        scope.launch { DreamSettings.setStationId(context, sel.station) }
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────────
 * SECTION wrapper — small uppercase label above a content block.
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label.uppercase(),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        content()
    }
}

/* ─────────────────────────────────────────────────────────────────────────
 * BIG LAYOUT PREVIEW — Single large card at the top of the picker that
 * mocks up the active layout. Adapts to the picked theme so the user
 * sees light / dark before committing. Fullscreen always rendered dark.
 * Landscape aspect (16:9) — that's how the dream actually looks when
 * docked, which is where it spends most of its time.
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun BigLayoutPreview(
    layout: DreamLayout,
    theme: DreamTheme,
    clockStyle: ClockStyle,
    accent: Color,
) {
    // Theme used for the mock canvas inside the preview.
    val previewIsDark = when {
        layout == DreamLayout.FULLSCREEN_BOARD -> true
        theme == DreamTheme.DARK               -> true
        theme == DreamTheme.LIGHT              -> false
        else /* SYSTEM */ -> MaterialTheme.colorScheme.background.luminance() < 0.5f
    }
    val canvasColor   = if (previewIsDark) DarkDreamColors.canvas   else LightDreamColors.canvas
    val onCanvasColor = if (previewIsDark) DarkDreamColors.onCanvas else LightDreamColors.onCanvas
    val previewAccent = if (previewIsDark) DarkDreamColors.brandAccent else LightDreamColors.brandAccent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        shape = RoundedCornerShape(20.dp),
        color = canvasColor,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
        ),
        tonalElevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (layout) {
                DreamLayout.CLOCK_AND_BOARD ->
                    BigClockBoardPreview(previewAccent, onCanvasColor, clockStyle)
                DreamLayout.FULLSCREEN_BOARD ->
                    BigFullscreenPreview(previewAccent)
            }
        }
    }
}

@Composable
private fun BigClockBoardPreview(accent: Color, onCanvas: Color, clockStyle: ClockStyle) {
    Row(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Left 30% — clock cluster, mirrors the chosen ClockStyle so the
        // preview actually reflects the analog / digital pick below.
        Column(
            modifier = Modifier.weight(0.30f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (clockStyle) {
                ClockStyle.DIGITAL -> Text(
                    text = "08:42",
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 28.sp,
                    letterSpacing = (-1.2).sp,
                )
                ClockStyle.ANALOG -> PreviewAnalogClock(accent, onCanvas, size = 64.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Mon 18",
                color = onCanvas.copy(alpha = 0.65f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "12°",
                color = onCanvas.copy(alpha = 0.65f),
                fontSize = 10.sp,
            )
        }
        // Right 70% — dot-matrix board card
        Column(
            modifier = Modifier
                .weight(0.70f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF080808))
                .border(
                    1.dp,
                    accent.copy(alpha = 0.55f),
                    RoundedCornerShape(8.dp),
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MiniBar(widthFraction = 0.7f, alpha = 0.95f, color = accent, heightDp = 6.dp)
            Spacer(Modifier.height(2.dp))
            repeat(4) {
                MiniBar(widthFraction = 1f, alpha = 0.55f, color = accent, heightDp = 4.dp)
            }
        }
    }
}

@Composable
private fun BigFullscreenPreview(accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.5.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Station strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = 0.85f))
            )
            Spacer(Modifier.height(2.dp))
            repeat(5) {
                MiniBar(widthFraction = 1f, alpha = 0.55f, color = accent, heightDp = 5.dp)
            }
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth()) {
                MiniBar(widthFraction = 0.30f, alpha = 0.9f, color = accent, heightDp = 6.dp)
                Spacer(Modifier.weight(1f))
                MiniBar(widthFraction = 0.20f, alpha = 0.65f, color = accent, heightDp = 6.dp)
            }
        }
    }
}

@Composable
private fun MiniBar(widthFraction: Float, alpha: Float, color: Color, heightDp: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(heightDp)
            .clip(RoundedCornerShape(1.dp))
            .background(color.copy(alpha = alpha))
    )
}

/* ─────────────────────────────────────────────────────────────────────────
 * LAYOUT CHIP ROW — two pill chips below the preview to switch between
 * Clock+Board and Fullscreen. Material 3 FilterChip semantics.
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun LayoutChipRow(
    selected: DreamLayout,
    accent: Color,
    strings: Map<String, String>,
    onPick: (DreamLayout) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DreamLayout.entries.forEach { l ->
                LayoutChip(
                    layout = l,
                    selected = l == selected,
                    accent = accent,
                    label = strings["dream.settings.layout.${l.storedAs}.name"]
                        ?: l.displayName,
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(l) },
                )
            }
        }
        // Sub-caption for the active layout — matches the Pixel picker's
        // single-line description under the variant chips.
        Text(
            text = strings["dream.settings.layout.${selected.storedAs}.desc"]
                ?: selected.description,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun LayoutChip(
    layout: DreamLayout,
    selected: Boolean,
    accent: Color,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        if (selected) accent
        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
        label = "chip_border",
    )
    val borderWidth by animateDpAsState(
        if (selected) 2.dp else 1.dp,
        label = "chip_border_width",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) accent.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface,
            )
            .border(borderWidth, borderColor, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/* ─────────────────────────────────────────────────────────────────────────
 * THEME TILE — mini board mockup rendered in that theme + label. Replaces
 * the old tiny circular swatch — much clearer what each theme actually
 * looks like on the dream canvas.
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun ThemeTile(
    theme: DreamTheme,
    selected: Boolean,
    accent: Color,
    strings: Map<String, String>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        if (selected) accent
        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
        label = "theme_border",
    )
    val borderWidth by animateDpAsState(
        if (selected) 2.dp else 1.dp,
        label = "theme_border_width",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) accent.copy(alpha = 0.06f)
                else MaterialTheme.colorScheme.surface,
            )
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ThemeMockup(theme = theme)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings["dream.settings.theme.${theme.storedAs}"]
                    ?: theme.displayName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Mini board mockup in the given theme — shows the canvas colour and a
 * dot-matrix board card inside, so the user sees what the dream actually
 * looks like in that theme. SYSTEM is split diagonally (light TL, dark BR).
 */
@Composable
private fun ThemeMockup(theme: DreamTheme) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(8.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
                RoundedCornerShape(8.dp),
            ),
    ) {
        when (theme) {
            DreamTheme.LIGHT  -> MiniBoardMockup(canvas = LightDreamColors.canvas, accent = LightDreamColors.brandAccent)
            DreamTheme.DARK   -> MiniBoardMockup(canvas = DarkDreamColors.canvas,  accent = DarkDreamColors.brandAccent)
            DreamTheme.SYSTEM -> {
                // Diagonal split — left half light, right half dark, both
                // showing a mini board so the user sees both states at once.
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        MiniBoardMockup(canvas = LightDreamColors.canvas, accent = LightDreamColors.brandAccent, halfMode = true)
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        MiniBoardMockup(canvas = DarkDreamColors.canvas, accent = DarkDreamColors.brandAccent, halfMode = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniBoardMockup(canvas: Color, accent: Color, halfMode: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(canvas)
            .padding(if (halfMode) 4.dp else 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF080808))
                .border(0.7.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(if (halfMode) 2.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.5.dp),
        ) {
            MiniBar(widthFraction = 0.75f, alpha = 0.95f, color = accent, heightDp = 2.5.dp)
            Spacer(Modifier.height(1.dp))
            repeat(if (halfMode) 2 else 3) {
                MiniBar(widthFraction = 1f, alpha = 0.55f, color = accent, heightDp = 2.dp)
            }
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────────
 * CLOCK STYLE TILE — small visual card for digital vs analog
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun ClockStyleTile(
    style: ClockStyle,
    selected: Boolean,
    accent: Color,
    strings: Map<String, String>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
        label = "clock_tile_border",
    )
    val borderWidth by animateDpAsState(
        if (selected) 2.dp else 1.dp,
        label = "clock_tile_border_width",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (style) {
                ClockStyle.DIGITAL -> Text(
                    text = "08:42",
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 30.sp,
                    letterSpacing = (-1.5).sp,
                    maxLines = 1,
                    softWrap = false,
                )
                ClockStyle.ANALOG -> MiniAnalogClock(accent)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings["dream.settings.clock.${style.storedAs}"]
                    ?: style.displayName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (selected) {
                Spacer(Modifier.width(8.dp))
                SelectedTick(accent, size = 16.dp)
            }
        }
    }
}

/**
 * Analog clock for the big preview — like MiniAnalogClock but parameterised
 * on size and minute-hand colour so it can sit on either the cream (light)
 * or black (dark) preview canvas with proper contrast.
 */
@Composable
private fun PreviewAnalogClock(accent: Color, onCanvas: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = (this.size.minDimension / 2f) - 2.dp.toPx()
        drawCircle(accent, r, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        val hourAngle = (-PI / 2 + PI / 6).toFloat()
        drawLine(
            color = accent,
            start = Offset(cx, cy),
            end = Offset(cx + cos(hourAngle) * r * 0.5f, cy + sin(hourAngle) * r * 0.5f),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val minuteAngle = (-PI / 2 + PI * 1.4).toFloat()
        drawLine(
            color = onCanvas.copy(alpha = 0.90f),
            start = Offset(cx, cy),
            end = Offset(cx + cos(minuteAngle) * r * 0.78f, cy + sin(minuteAngle) * r * 0.78f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(accent, 2.5.dp.toPx(), Offset(cx, cy))
    }
}

@Composable
private fun MiniAnalogClock(accent: Color) {
    val handFg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
    Canvas(modifier = Modifier.size(72.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = (size.minDimension / 2f) - 3.dp.toPx()
        drawCircle(accent, r, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        val hourAngle = (-PI / 2 + PI / 6).toFloat()
        drawLine(
            color = accent,
            start = Offset(cx, cy),
            end = Offset(cx + cos(hourAngle) * r * 0.5f, cy + sin(hourAngle) * r * 0.5f),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val minuteAngle = (-PI / 2 + PI * 1.4).toFloat()
        drawLine(
            color = handFg,
            start = Offset(cx, cy),
            end = Offset(cx + cos(minuteAngle) * r * 0.78f, cy + sin(minuteAngle) * r * 0.78f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(accent, 2.5.dp.toPx(), Offset(cx, cy))
    }
}

/* ─────────────────────────────────────────────────────────────────────────
 * STATION CARD — pretty row with a line-color dot
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun StationCard(
    title: String,
    subtitle: String,
    lineColor: Color,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        if (selected) accent.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
        label = "station_border",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(lineColor)
                    .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f), CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
            }
            if (selected) SelectedTick(accent)
        }
    }
}

@Composable
private fun SelectedTick(accent: Color, size: Dp = 22.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = "Selected",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size - 8.dp),
        )
    }
}
