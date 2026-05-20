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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.Platform
import com.stationly.mobile.R
import com.stationly.mobile.ui.summary.components.TFL_LINE_COLORS
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
 * Three things the user can configure:
 *   - Layout style: clock-and-board vs fullscreen-board (visual tiles)
 *   - Clock style:  digital vs analog (only relevant for clock-and-board)
 *   - Station to show, if more than one is on the home screen
 */
class DreamSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = colorResource(R.color.tfl_amber),
                background = Color(0xFF0A0A0A),
                surface = Color(0xFF141414),
                onBackground = Color.White,
                onSurface = Color.White,
            )) {
                DreamSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DreamSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val amber = colorResource(R.color.tfl_amber)

    var layout     by remember { mutableStateOf(DreamSettings.getLayout(context)) }
    var clockStyle by remember { mutableStateOf(DreamSettings.getClockStyle(context)) }
    var stationId  by remember { mutableStateOf(DreamSettings.getStationId(context)) }

    // Pull the user's saved stations on the IO thread so we can show them
    // in the picker. Display-only — settings work with just a station id.
    var stations by remember { mutableStateOf<List<UserSelection>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        stations = withContext(Dispatchers.IO) { Platform.sqlStorage.getAllSelections() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Screensaver", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF0A0A0A),
                    titleContentColor = Color.White,
                )
            )
        },
        containerColor = Color(0xFF0A0A0A),
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            HeroBlurb(amber)

            // ── Layout (visual previews) ──────────────────────────────────
            SectionLabel("Layout")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DreamLayout.entries.forEach { l ->
                    LayoutPreviewTile(
                        layout = l,
                        selected = l == layout,
                        accent = amber,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            layout = l
                            scope.launch { DreamSettings.setLayout(context, l) }
                        }
                    )
                }
            }

            // ── Clock style (only for clock-and-board) ───────────────────
            if (layout == DreamLayout.CLOCK_AND_BOARD) {
                SectionLabel("Clock style")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ClockStyle.entries.forEach { style ->
                        ClockStyleTile(
                            style = style,
                            selected = style == clockStyle,
                            accent = amber,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                clockStyle = style
                                scope.launch { DreamSettings.setClockStyle(context, style) }
                            }
                        )
                    }
                }
            }

            // ── Station picker (only when more than one is set) ───────────
            if (stations.size > 1) {
                SectionLabel("Station to display")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StationCard(
                        title = "Auto",
                        subtitle = "Match the top board on your home screen",
                        lineColor = amber.copy(alpha = 0.55f),
                        selected = stationId == null,
                        accent = amber,
                        onClick = {
                            stationId = null
                            scope.launch { DreamSettings.setStationId(context, null) }
                        }
                    )
                    stations.forEach { sel ->
                        StationCard(
                            title = sel.stationName,
                            subtitle = "${sel.line.replaceFirstChar { it.uppercase() }} · " +
                                sel.direction.replaceFirstChar { it.uppercase() },
                            lineColor = TFL_LINE_COLORS[sel.line.lowercase()] ?: amber,
                            selected = stationId == sel.station,
                            accent = amber,
                            onClick = {
                                stationId = sel.station
                                scope.launch { DreamSettings.setStationId(context, sel.station) }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            FooterHint(amber)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────────
 * HERO
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun HeroBlurb(amber: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(amber)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "STATIONLY · DREAM",
                color = amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.5.sp,
            )
        }
        Text(
            text = "Pick how your screensaver looks",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp,
        )
        Text(
            text = "Each layout uses the same live data — choose the one that fits where you'll see it.",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
    )
}

/* ─────────────────────────────────────────────────────────────────────────
 * LAYOUT PREVIEW TILE — the centerpiece. Big visual card with a mini render
 * of the actual dream layout inside.
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun LayoutPreviewTile(
    layout: DreamLayout,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Animate the border + ring colour on selection so the picker feels
    // responsive, not just toggled.
    val borderColor by animateColorAsState(
        if (selected) accent else Color.White.copy(alpha = 0.06f),
        label = "tile_border",
    )
    val borderWidth by animateDpAsState(
        if (selected) 2.dp else 1.dp,
        label = "tile_border_width",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent.copy(alpha = 0.06f) else Color(0xFF121212))
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Mini preview — a 16:10 dark rectangle that contains a small render
        // of what the chosen layout looks like in practice.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF050505))
                .border(0.5.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
        ) {
            when (layout) {
                DreamLayout.CLOCK_AND_BOARD  -> ClockBoardMiniPreview(accent)
                DreamLayout.FULLSCREEN_BOARD -> FullscreenMiniPreview(accent)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = layout.displayName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = layout.description,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
            if (selected) SelectedTick(accent)
        }
    }
}

@Composable
private fun ClockBoardMiniPreview(accent: Color) {
    Row(
        modifier = Modifier.fillMaxSize().padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // Left 30% — clock cluster
        Column(
            modifier = Modifier.weight(0.30f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "08:42",
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                letterSpacing = (-0.6).sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Mon 18",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 6.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // Right 70% — board
        Column(
            modifier = Modifier.weight(0.70f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            // Title strip
            MiniBar(widthFraction = 0.55f, alpha = 0.95f, color = accent, heightDp = 5.dp)
            Spacer(Modifier.height(3.dp))
            // Rows
            repeat(4) {
                MiniBar(widthFraction = 1f, alpha = 0.55f, color = accent, heightDp = 3.dp)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun FullscreenMiniPreview(accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp)
            .clip(RoundedCornerShape(5.dp))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Header strip (station name)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accent.copy(alpha = 0.55f))
        )
        Spacer(Modifier.height(2.dp))
        // Rows
        repeat(5) {
            MiniBar(widthFraction = 1f, alpha = 0.55f, color = accent, heightDp = 3.dp)
            Spacer(Modifier.height(1.5.dp))
        }
        Spacer(Modifier.weight(1f))
        // Footer row: clock left, "ago" right
        Row(modifier = Modifier.fillMaxWidth()) {
            MiniBar(widthFraction = 0.30f, alpha = 0.9f, color = accent, heightDp = 4.dp)
            Spacer(Modifier.weight(1f))
            MiniBar(widthFraction = 0.20f, alpha = 0.65f, color = accent, heightDp = 4.dp)
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
 * CLOCK STYLE TILE — small visual card for digital vs analog
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun ClockStyleTile(
    style: ClockStyle,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        if (selected) accent else Color.White.copy(alpha = 0.06f),
        label = "clock_tile_border",
    )
    val borderWidth by animateDpAsState(
        if (selected) 2.dp else 1.dp,
        label = "clock_tile_border_width",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = 0.06f) else Color(0xFF121212))
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
                text = style.displayName,
                color = Color.White,
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
private fun MiniAnalogClock(accent: Color) {
    val red = Color(0xFFE51E25)
    Canvas(modifier = Modifier.size(72.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = (size.minDimension / 2f) - 3.dp.toPx()
        // Roundel ring
        drawCircle(red, r, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        // Hour hand
        val hourAngle = (-PI / 2 + PI / 6).toFloat()
        drawLine(
            color = accent,
            start = Offset(cx, cy),
            end = Offset(cx + cos(hourAngle) * r * 0.5f, cy + sin(hourAngle) * r * 0.5f),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // Minute hand
        val minuteAngle = (-PI / 2 + PI * 1.4).toFloat()
        drawLine(
            color = Color.White.copy(alpha = 0.9f),
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
        if (selected) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.05f),
        label = "station_border",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.06f) else Color(0xFF141414),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Line-colour dot (or amber for "Auto")
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(lineColor)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
            }
            if (selected) SelectedTick(accent)
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────────
 * FOOTER + selection check
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun FooterHint(amber: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF141414),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = amber.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Pick \"Stationly\" under Display → Screen saver to turn this on. " +
                    "It runs whenever your phone or tablet is charging or docked.",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
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
            tint = Color.Black,
            modifier = Modifier.size(size - 8.dp),
        )
    }
}
