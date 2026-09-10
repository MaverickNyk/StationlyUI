package com.stationly.app.ui.dream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.platform.KeepScreenAwake
import com.stationly.app.ui.theme.AppTheme
import com.stationly.app.ui.theme.LocalAppTheme
import com.stationly.app.ui.util.HomeConfigCache
import com.stationly.app.ui.util.rememberTickedPredictions
import com.stationly.core.service.NetworkModule
import com.stationly.core.util.FreshDataNotifier
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Top-level dream composable — port of Android `dream/DreamHost.kt`, hosted
 * as an in-app route instead of a system DreamService (iOS has no
 * third-party screensaver API; keep-awake while composed is the iOS
 * equivalent of "runs while docked").
 *
 * Refresh model — event-driven, no polling: FCM/refresh writes SQL then
 * emits [FreshDataNotifier]; we re-read SQL on each emission (plus once on
 * entry). Matches Android's ACTION_DREAM_REFRESH broadcast semantics.
 */
@Composable
fun DreamHost(onExit: () -> Unit) {
    KeepScreenAwake()

    val layout by remember { mutableStateOf(DreamSettings.getLayout()) }
    val theme by remember { mutableStateOf(DreamSettings.getTheme()) }
    val clockStyle by remember { mutableStateOf(DreamSettings.getClockStyle()) }
    val preferredStation by remember { mutableStateOf(DreamSettings.getStationId()) }

    var snapshot by remember { mutableStateOf(DreamSnapshot(null, emptyList(), null)) }

    // SDUI strings (board.fallback.*, mode labels, platform prefixes) —
    // cache-first like Android's HomeConfigStore read, then a best-effort
    // network refresh; hardcoded fallbacks keep every label functional offline.
    var sduiStrings by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        HomeConfigCache.load().takeIf { it.isNotEmpty() }?.let { sduiStrings = it }
        runCatching { NetworkModule.sduiApi.getHomeConfig().strings }
            .onSuccess { sduiStrings = it; HomeConfigCache.save(it) }
    }

    // Initial load + reload on every fresh-data signal.
    LaunchedEffect(preferredStation) {
        snapshot = withContext(Dispatchers.Default) { loadDreamSnapshot(preferredStation) }
        FreshDataNotifier.events.collect {
            snapshot = withContext(Dispatchers.Default) { loadDreamSnapshot(preferredStation) }
        }
    }

    // Resolve the effective dark/light. DARK/LIGHT → hard override; SYSTEM →
    // follow the APP's theme choice (and if that is also SYSTEM, the device).
    // Fullscreen layout is the dot-matrix signage panel — always dark.
    val appTheme = LocalAppTheme.current.theme
    val systemDark = isSystemInDarkTheme()
    val isDark = when {
        layout == DreamLayout.FULLSCREEN_BOARD -> true
        theme == DreamTheme.DARK               -> true
        theme == DreamTheme.LIGHT              -> false
        else /* SYSTEM */                      -> when (appTheme) {
            AppTheme.DARK   -> true
            AppTheme.LIGHT  -> false
            AppTheme.SYSTEM -> systemDark
        }
    }
    val colors = if (isDark) DarkDreamColors else LightDreamColors

    // Exit affordance — Android dreams exit via the power button; an in-app
    // route needs its own way out. A tap on the canvas reveals a small Done
    // pill (auto-hides after 4s) so the dream stays chrome-free at rest.
    var showExit by remember { mutableStateOf(false) }
    LaunchedEffect(showExit) {
        if (showExit) { delay(4000); showExit = false }
    }

    CompositionLocalProvider(LocalDreamColors provides colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvas)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showExit = !showExit }
        ) {
            when (layout) {
                DreamLayout.FULLSCREEN_BOARD -> FullscreenBoardLayout(snapshot, sduiStrings)
                DreamLayout.CLOCK_AND_BOARD  -> ClockAndBoardHost(snapshot, clockStyle, sduiStrings)
            }

            AnimatedVisibility(
                visible = showExit,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(
                    "Done",
                    color = colors.onCanvas.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(top = 54.dp, end = 20.dp)
                        .background(colors.onCanvas.copy(alpha = 0.10f), RoundedCornerShape(50))
                        .clickable(onClick = onExit)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * "Clock cluster + departure board" composition — 30:70 landscape split,
 * 35:65 portrait stack (owner-validated ratios; do not change them to fix
 * sizing — fix sizing via [DreamDims]).
 */
@Composable
private fun ClockAndBoardHost(
    snapshot: DreamSnapshot,
    clockStyle: ClockStyle,
    sduiStrings: Map<String, String>,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Keep the cluster out of the notch; the fullscreen layout
            // mirrors cutouts symmetrically itself.
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        val isLandscape = maxWidth > maxHeight

        // The device's short edge — same value in either orientation, so
        // rotation doesn't change which device class we render for.
        val shortEdgeDp = minOf(maxWidth, maxHeight).value

        // Responsive scale for everything OTHER than the clock.
        val scale = (shortEdgeDp / 400f).coerceIn(0.85f, 1.50f)
        val dim = DreamDims.fromScreen(scale, shortEdgeDp)

        val hPad = ((if (isLandscape) 28f else 14f) * scale).dp
        val vPad = (22f * scale).dp

        // Cap the content width so tablets don't stretch edge-to-edge.
        val maxContentDp = if (isLandscape) 1400.dp else 800.dp
        val contentMaxWidth = minOf(maxWidth - hPad * 2, maxContentDp)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = hPad, vertical = vPad)
                .widthIn(max = contentMaxWidth)
                .fillMaxHeight()
        ) {
            if (isLandscape) {
                LandscapeLayout(snapshot, clockStyle, dim, sduiStrings)
            } else {
                PortraitLayout(snapshot, clockStyle, dim, sduiStrings)
            }
        }
    }
}

/**
 * Responsive size set used by every dream composable. All values derive from
 * the device's short edge so a given device produces the same fonts and dial
 * sizes in both orientations.
 */
internal data class DreamDims(
    val titleSize: TextUnit,
    val directionSize: TextUnit,
    val statusSize: TextUnit,
    val labelSize: TextUnit,
    val destSize: TextUnit,
    val platSize: TextUnit,
    val etaSize: TextUnit,
    val minSize: TextUnit,
    val analogClockDp: Dp,
    val digitalTimeSize: TextUnit,
    val boardTextScale: Float,
) {
    companion object {
        fun fromScreen(s: Float, shortEdgeDp: Float): DreamDims {
            val k = s
            // Clock MAXIMUMS — ClockPanel fills its slot up to these caps.
            val digitalMaxSp = (shortEdgeDp * 0.12f + 50f).coerceIn(95f, 170f)
            val analogMaxDp  = (shortEdgeDp * 0.42f).coerceIn(180f, 380f)
            return DreamDims(
                titleSize        = (20f * k).sp,
                directionSize    = (12f * k).sp,
                statusSize       = (12f * k).sp,
                labelSize        = (9f * k).sp,
                destSize         = (15f * k).sp,
                platSize         = (11f * k).sp,
                etaSize          = (22f * k).sp,
                minSize          = (10f * k).sp,
                analogClockDp    = analogMaxDp.dp,
                digitalTimeSize  = digitalMaxSp.sp,
                // Board rows derive directly from shortEdgeDp (NOT the 1.5-capped
                // layout scale) so tablets reach the 1.6× row cap.
                boardTextScale   = (shortEdgeDp / 400f).coerceIn(0.95f, 1.60f),
            )
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────────
 * LAYOUTS — Portrait: top 35% clock cluster / bottom 65% board.
 *           Landscape: left 30% clock cluster / right 70% board.
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun LandscapeLayout(
    snapshot: DreamSnapshot,
    clockStyle: ClockStyle,
    dim: DreamDims,
    sduiStrings: Map<String, String>,
) {
    val lineColor = lineColorOf(snapshot.selection?.line)
    // Hero through the shared tick layer, sorted by ETA AFTER ticking so it
    // picks the soonest train ACROSS platforms (SQL order goes stale).
    val tickedNextDeparture = StationlyFormatters
        .sortPredictions(rememberTickedPredictions(snapshot.predictions))
        .firstOrNull { it.destination.isNotBlank() && it.eta.isNotBlank() }
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Left 30% — clock + date / weather strip (fills its slot).
        Box(
            modifier = Modifier
                .weight(0.30f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            ClockAmbientGlow(lineColor)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ClockPanel(clockStyle, dim)
                Spacer(Modifier.height(18.dp))
                DateAndWeatherStrip(dim)
            }
        }

        // ── Right 70% — summary header + next-train hero + board.
        BoxWithConstraints(
            modifier = Modifier
                .weight(0.70f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            // Board caps at ~75% of the right panel; below that it wraps.
            val boardMaxHeight = maxHeight * 0.75f
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (snapshot.hasData) {
                    StationHeader(
                        selection = snapshot.selection!!,
                        lineColor = lineColor,
                        isDisrupted = snapshot.isDisrupted,
                        statusLine = snapshot.statusLine,
                        dim = dim,
                    )
                    tickedNextDeparture?.let {
                        NextTrainHero(prediction = it, lineColor = lineColor, dim = dim)
                    }
                    DreamBoard(
                        snapshot = snapshot,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = boardMaxHeight),
                        textScale = dim.boardTextScale,
                        showHeader = true,
                        sduiStrings = sduiStrings,
                    )
                } else {
                    EmptyStatePanel()
                }
            }
        }
    }
}

@Composable
private fun PortraitLayout(
    snapshot: DreamSnapshot,
    clockStyle: ClockStyle,
    dim: DreamDims,
    sduiStrings: Map<String, String>,
) {
    val lineColor = lineColorOf(snapshot.selection?.line)
    val tickedNextDeparture = StationlyFormatters
        .sortPredictions(rememberTickedPredictions(snapshot.predictions))
        .firstOrNull { it.destination.isNotBlank() && it.eta.isNotBlank() }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Top 35% — clock + date/weather strip as one cluster.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f),
            contentAlignment = Alignment.Center,
        ) {
            ClockAmbientGlow(lineColor)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ClockPanel(clockStyle, dim)
                Spacer(Modifier.height(16.dp))
                DateAndWeatherStrip(dim)
            }
        }

        // Bottom 65% — station + next-train + board.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.65f),
        ) {
            val boardMaxHeight = maxHeight * 0.65f
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (snapshot.hasData) {
                    StationHeader(
                        selection = snapshot.selection!!,
                        lineColor = lineColor,
                        isDisrupted = snapshot.isDisrupted,
                        statusLine = snapshot.statusLine,
                        dim = dim,
                    )
                    tickedNextDeparture?.let {
                        NextTrainHero(prediction = it, lineColor = lineColor, dim = dim)
                    }
                    DreamBoard(
                        snapshot = snapshot,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = boardMaxHeight),
                        textScale = dim.boardTextScale,
                        showHeader = true,
                        sduiStrings = sduiStrings,
                    )
                } else {
                    EmptyStatePanel()
                }
            }
        }
    }
}
