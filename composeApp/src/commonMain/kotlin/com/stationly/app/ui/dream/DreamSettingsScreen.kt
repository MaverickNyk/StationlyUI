package com.stationly.app.ui.dream

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.common.MINI_DEPARTURES
import com.stationly.app.ui.common.MiniBoard
import com.stationly.app.ui.common.MiniBoardClock
import com.stationly.app.ui.common.MiniBoardStatus
import com.stationly.app.ui.summary.components.lineColorForTheme
import com.stationly.app.ui.theme.TflAmber
import com.stationly.app.ui.util.HomeConfigCache
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.Platform
import com.stationly.core.service.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Screensaver configuration screen — port of Android's
 * `dream/DreamSettingsActivity.kt` Compose content. On Android this is
 * reached from system Settings (the Daydream gear); iOS has no system
 * screensaver surface, so it lives as an in-app route (Profile →
 * Screensaver) with a Start button on top — that button is the iOS stand-in
 * for the system "preview/start" affordance.
 *
 * Layout (Pixel-screensaver-picker inspired): big preview of the active
 * layout, chip selector, theme tiles (hidden for fullscreen — pinned dark),
 * clock-style tiles (hidden for fullscreen), station picker when the user
 * has more than one board.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DreamSettingsScreen(onBack: () -> Unit, onStartDream: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary

    // SDUI string map — cache-first (Android HomeConfigStore parity), then a
    // best-effort network refresh; hardcoded fallbacks keep it functional.
    var strings by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        HomeConfigCache.load().takeIf { it.isNotEmpty() }?.let { strings = it }
        runCatching { NetworkModule.sduiApi.getHomeConfig().strings }
            .onSuccess { strings = it; HomeConfigCache.save(it) }
    }

    var layout     by remember { mutableStateOf(DreamSettings.getLayout()) }
    var theme      by remember { mutableStateOf(DreamSettings.getTheme()) }
    var clockStyle by remember { mutableStateOf(DreamSettings.getClockStyle()) }
    var stationId  by remember { mutableStateOf(DreamSettings.getStationId()) }

    var stations by remember { mutableStateOf<List<UserSelection>>(emptyList()) }
    LaunchedEffect(Unit) {
        stations = withContext(Dispatchers.Default) { Platform.sqlStorage.getAllSelections() }
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
                //
                // The station name in it is the REAL one — whichever board the
                // picker at the bottom of this screen has selected, or the
                // first if it is on Auto. The departures under it are not (see
                // MINI_DEPARTURES); the name is what the picker changes, so it is
                // the one thing the preview has to answer honestly.
                BigLayoutPreview(
                    layout = layout,
                    theme = theme,
                    clockStyle = clockStyle,
                    stationName = stations.firstOrNull { it.station == stationId }?.stationName
                        ?: stations.firstOrNull()?.stationName
                        ?: "Stationly",
                )

                // ── Start button — iOS-only entry (Android launches dreams
                //    from the system dock trigger; in-app needs its own). ──
                Button(
                    onClick = {
                        // Records that the screensaver has actually been used —
                        // retires the home "Set as Screensaver" promo, the iOS
                        // stand-in for Android reading the system's chosen dream.
                        DreamSettings.markStarted()
                        onStartDream()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor   = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        strings["dream.settings.start"] ?: "Start screensaver",
                        fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                }

                // ── Layout chips ────────────────────────────────────────
                LayoutChipRow(
                    selected = layout,
                    accent = accent,
                    strings = strings,
                    onPick = {
                        layout = it
                        DreamSettings.setLayout(it)
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
                                        DreamSettings.setTheme(t)
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
                                        DreamSettings.setClockStyle(style)
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
                            StationPickCard(
                                title    = strings["dream.settings.station.auto.title"]    ?: "Auto",
                                subtitle = strings["dream.settings.station.auto.subtitle"] ?: "Match the top board on your home screen",
                                lineColor = accent.copy(alpha = 0.55f),
                                selected = stationId == null,
                                accent = accent,
                                onClick = {
                                    stationId = null
                                    DreamSettings.setStationId(null)
                                },
                            )
                            stations.forEach { sel ->
                                StationPickCard(
                                    title = sel.stationName,
                                    subtitle = "${sel.line.replaceFirstChar { it.uppercase() }} · " +
                                        sel.direction.replaceFirstChar { it.uppercase() },
                                    lineColor = lineColorForTheme(
                                        sel.line,
                                        MaterialTheme.colorScheme.background.luminance() < 0.5f,
                                    ).let { c -> if (c == TflAmber) accent else c },
                                    selected = stationId == sel.station,
                                    accent = accent,
                                    onClick = {
                                        stationId = sel.station
                                        DreamSettings.setStationId(sel.station)
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

/* ── SECTION wrapper — small uppercase label above a content block ──────── */

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

/* ── BIG LAYOUT PREVIEW — 16:9 mock of the active layout ────────────────── */

@Composable
private fun BigLayoutPreview(
    layout: DreamLayout,
    theme: DreamTheme,
    clockStyle: ClockStyle,
    stationName: String,
) {
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
                    BigClockBoardPreview(previewAccent, onCanvasColor, clockStyle, stationName)
                DreamLayout.FULLSCREEN_BOARD ->
                    BigFullscreenPreview(stationName)
            }
        }
    }
}

@Composable
private fun BigClockBoardPreview(
    accent: Color,
    onCanvas: Color,
    clockStyle: ClockStyle,
    stationName: String,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Left 30% — clock cluster, mirrors the chosen ClockStyle. Same split
        // the real DreamHost uses.
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
        // Right 70% — the board itself.
        PreviewBoard(
            stationName = stationName,
            rowCount = 3,
            showClock = false,
            modifier = Modifier.weight(0.70f).fillMaxHeight(),
        )
    }
}

@Composable
private fun BigFullscreenPreview(stationName: String) {
    PreviewBoard(
        stationName = stationName,
        rowCount = 4,
        showClock = true,
        modifier = Modifier.fillMaxSize().padding(12.dp),
    )
}

/**
 * A real departure board, in miniature.
 *
 * This used to be four grey [Box]es of decreasing width. They said "something
 * goes here" when the question the screen is asking is "which of these two
 * layouts do you want" — and you cannot answer that from placeholder bars,
 * because what actually differs between the layouts is how much BOARD you get
 * and where the clock sits relative to it.
 *
 * The board itself is [MiniBoard], shared with the station card's layout picker:
 * two previews of the same board that disagreed about what is on it would look
 * like two different products. What this adds is the part that is specific to
 * the dream — the status strip, and the clock the fullscreen layout carries
 * inside the board while the cluster layout has its own on the left.
 */
@Composable
private fun PreviewBoard(
    stationName: String,
    rowCount: Int,
    showClock: Boolean,
    modifier: Modifier = Modifier,
) {
    MiniBoard(
        modifier = modifier,
        header = stationName,
        departures = MINI_DEPARTURES.take(rowCount),
    ) {
        Spacer(Modifier.weight(1f))
        MiniBoardStatus()
        if (showClock) MiniBoardClock()
    }
}

/* ── LAYOUT CHIP ROW ────────────────────────────────────────────────────── */

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
                    selected = l == selected,
                    accent = accent,
                    label = strings["dream.settings.layout.${l.storedAs}.name"]
                        ?: l.displayName,
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(l) },
                )
            }
        }
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

/* ── THEME TILE — mini board mockup rendered in that theme ──────────────── */

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
 * Mini board mockup in the given theme. SYSTEM splits light/dark halves so
 * the user sees both states at once.
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

/**
 * A lit row, abstracted to a bar.
 *
 * Only for the theme and clock-style tiles, which are ~40dp square. Real
 * departure text is illegible at that size, and the question those tiles ask is
 * about the canvas AROUND the board, not the board. The big preview above shows
 * actual departures — see [PreviewBoard].
 */
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

/* ── CLOCK STYLE TILE ───────────────────────────────────────────────────── */

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

@Composable
private fun PreviewAnalogClock(accent: Color, onCanvas: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = (this.size.minDimension / 2f) - 2.dp.toPx()
        drawCircle(accent, r, Offset(cx, cy), style = Stroke(2.dp.toPx()))
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
        drawCircle(accent, r, Offset(cx, cy), style = Stroke(2.dp.toPx()))
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

/* ── STATION CARD — row with a line-colour dot ──────────────────────────── */

@Composable
private fun StationPickCard(
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
