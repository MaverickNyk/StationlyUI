package com.stationly.mobile.dream

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Top-level composable hosted by [StationlyDreamService]. Picks between the
 * available [DreamLayout]s based on the user's saved setting:
 *
 *   - [DreamLayout.CLOCK_AND_BOARD]  — clock cluster framing the departure
 *                                       board. Portrait stacks 35/65; landscape
 *                                       splits 30/70 left/right.
 *   - [DreamLayout.FULLSCREEN_BOARD] — just the departure board, centred on
 *                                       the dark canvas as a rounded signage
 *                                       panel. See [FullscreenBoardLayout].
 *
 * Refresh model — broadcast only. FCM lands → SQL updated → FCM service
 * fires [StationlyDreamService.ACTION_DREAM_REFRESH] → service's receiver
 * increments [refreshTick] → this [LaunchedEffect] re-reads SQL. We
 * deliberately do *not* poll: stale SQL re-read on a timer is wasted CPU.
 */
@Composable
fun DreamHost(refreshTick: StateFlow<Long>) {
    val context = LocalContext.current
    val tick by refreshTick.collectAsState()
    val layout by remember { mutableStateOf(DreamSettings.getLayout(context)) }
    val theme by remember { mutableStateOf(DreamSettings.getTheme(context)) }
    val clockStyle by remember { mutableStateOf(DreamSettings.getClockStyle(context)) }
    val preferredStation by remember { mutableStateOf(DreamSettings.getStationId(context)) }

    var snapshot by remember { mutableStateOf(DreamSnapshot(null, emptyList(), null)) }

    LaunchedEffect(tick) {
        snapshot = withContext(Dispatchers.IO) { loadDreamSnapshot(preferredStation) }
    }

    // Resolve the effective dark/light. The dream cascades:
    //   DARK / LIGHT  → hard override (user explicitly picked for the dream)
    //   SYSTEM        → follow the APP's theme choice (and if the app is
    //                   also on SYSTEM, defer to the device's dark-mode
    //                   setting). One setting at the top of the chain
    //                   ("system") drives everything by default; users
    //                   who want the dream to differ from the app can
    //                   pick a specific theme for it.
    val isDark = when (theme) {
        DreamTheme.DARK   -> true
        DreamTheme.LIGHT  -> false
        DreamTheme.SYSTEM -> when (com.stationly.mobile.ui.theme.AppSettings.getTheme(context)) {
            com.stationly.mobile.ui.theme.AppTheme.DARK   -> true
            com.stationly.mobile.ui.theme.AppTheme.LIGHT  -> false
            com.stationly.mobile.ui.theme.AppTheme.SYSTEM -> isSystemInDarkTheme()
        }
    }
    val colors = if (isDark) DarkDreamColors else LightDreamColors

    CompositionLocalProvider(LocalDreamColors provides colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvas)
                // Respect display cutouts (camera hole-punch) — the dream
                // renders edge-to-edge so without this our content slides
                // under the camera.
                .windowInsetsPadding(WindowInsets.displayCutout)
        ) {
            when (layout) {
                DreamLayout.FULLSCREEN_BOARD -> FullscreenBoardLayout(snapshot)
                DreamLayout.CLOCK_AND_BOARD  -> ClockAndBoardHost(snapshot, clockStyle)
            }
        }
    }
}

/**
 * Original "clock cluster + departure board" composition. Extracted from
 * DreamHost so the host can switch between layouts cleanly.
 */
@Composable
private fun ClockAndBoardHost(snapshot: DreamSnapshot, clockStyle: ClockStyle) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight

        // The device's short edge — same value in either orientation for a
        // given physical device. We anchor clock-size caps to this so a
        // rotation doesn't change which device class we're rendering for.
        val shortEdgeDp = minOf(maxWidth, maxHeight).value

        // Responsive scale for everything OTHER than the clock. Phone short
        // ≈ 360dp → ~0.9; tablet short ≈ 800dp → clamped 1.2.
        val scale = (shortEdgeDp / 400f).coerceIn(0.85f, 1.20f)
        val dim = DreamDims.fromScreen(scale, shortEdgeDp)

        // Outer padding scales mildly, never hugging the edges on a tablet.
        val hPad = ((if (isLandscape) 28f else 14f) * scale).dp
        val vPad = (22f * scale).dp

        // Cap the content width so super-wide tablets don't stretch the
        // layout edge-to-edge — content centres in a comfortable strip
        // instead of being pulled across the whole panel. Landscape is wider
        // than portrait because it needs room for clock column + board side
        // by side; portrait stacks them so a tighter strip reads better.
        val maxContentDp = if (isLandscape) 1000.dp else 560.dp
        val contentMaxWidth = minOf(maxWidth - hPad * 2, maxContentDp)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = hPad, vertical = vPad)
                .widthIn(max = contentMaxWidth)
                .fillMaxHeight()
        ) {
            if (isLandscape) {
                LandscapeLayout(snapshot, clockStyle, dim)
            } else {
                PortraitLayout(snapshot, clockStyle, dim)
            }
        }
    }
}

/**
 * Responsive size set used by every dream composable. All values come from
 * the device's short edge so a given device produces the same fonts and
 * dial sizes in both orientations — `sp` itself still respects the user's
 * accessibility text-size setting.
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
            //
            // Derived from the device's short edge so a tablet allows a
            // bigger clock when the slot is wide, but never to an absurd
            // billboard size. The actual rendered clock could be much
            // smaller — it's bounded by the slot width too.
            //
            // Phone short ≈ 360-412dp → digital max ≈ 95-100sp (caps
            // portrait before it grows past the date strip's visual weight;
            // landscape is already smaller than this so the cap doesn't
            // kick in there).
            // Tablet short ≈ 800dp → digital max ≈ 140sp.
            val digitalMaxSp = (shortEdgeDp * 0.12f + 50f).coerceIn(95f, 140f)
            val analogMaxDp  = (shortEdgeDp * 0.42f).coerceIn(180f, 320f)
            return DreamDims(
                titleSize        = (20f * k).sp,
                directionSize    = (12f * k).sp,
                statusSize       = (12f * k).sp,
                // Next-departure card proportions — sized to be clearly
                // subordinate to the station-name headline (titleSize 22sp)
                // and to the dot-matrix board below. Previously the
                // destination/ETA pulled focus from the title; these
                // values keep the card legible without competing.
                labelSize        = (9f * k).sp,
                destSize         = (15f * k).sp,
                platSize         = (11f * k).sp,
                etaSize          = (22f * k).sp,
                minSize          = (10f * k).sp,
                analogClockDp    = analogMaxDp.dp,
                digitalTimeSize  = digitalMaxSp.sp,
                // Widget rows: phone uses ~1.0×, tablet caps at ~1.2× so
                // the text reads as "slightly bigger phone" rather than
                // billboard.
                boardTextScale   = s.coerceIn(0.95f, 1.2f),
            )
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────────
 * LAYOUTS
 *   Portrait  — top 35% clock + date / weather, bottom 65% departure board.
 *   Landscape — left 30% clock + date / weather, right 70% departure board.
 * ───────────────────────────────────────────────────────────────────────── */

@Composable
private fun LandscapeLayout(snapshot: DreamSnapshot, clockStyle: ClockStyle, dim: DreamDims) {
    val lineColor = lineColorOf(snapshot.selection?.line)
    // Resolve the hero's prediction through the shared tick layer — so it
    // shifts to the next upcoming train as soon as the current top row's
    // train has departed (60s past target), without waiting for the next
    // FCM. Identical filter applied on home + widget for cross-surface
    // consistency. See PredictionTicker.tickPredictions.
    //
    // Sort by ETA AFTER ticking so the hero picks the soonest train ACROSS
    // ALL platforms — list order from SQL is "platforms by earliest train
    // at last FCM" which goes stale as rows depart and platforms re-rank.
    val tickedNextDeparture = com.stationly.core.util.StationlyFormatters
        .sortPredictions(
            com.stationly.mobile.ui.util.rememberTickedPredictions(snapshot.predictions)
        )
        .firstOrNull { it.destination.isNotBlank() && it.eta.isNotBlank() }
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Left 30% — clock + date / weather strip.
        // The clock auto-sizes to fill this column (see ClockPanel) — phone
        // landscape gets a smaller clock because 30% of a small screen IS
        // small; tablet landscape gets a chunky clock because 30% of a
        // tablet has tons of room. The 30:70 split is the constant; the
        // clock's font size is the variable.
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
            // Board's max height = ~75% of the right panel. Below this the
            // board wraps content (no awkward stretch). Above it the board
            // caps and the inner ScrollView gets a real fixed viewport — so
            // scrolling actually works on multi-platform stations.
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
                        NextTrainHero(
                            prediction = it,
                            lineColor = lineColor,
                            dim = dim,
                        )
                    }
                    DreamBoard(
                        snapshot = snapshot,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = boardMaxHeight),
                        textScale = dim.boardTextScale,
                        showHeader = false,
                    )
                } else {
                    EmptyStatePanel()
                }
            }
        }
    }
}

@Composable
private fun PortraitLayout(snapshot: DreamSnapshot, clockStyle: ClockStyle, dim: DreamDims) {
    val lineColor = lineColorOf(snapshot.selection?.line)
    // Same tick-aware hero resolution as the landscape layout — drops
    // departed rows, sorts by ETA across all platforms, picks the next
    // upcoming train.
    val tickedNextDeparture = com.stationly.core.util.StationlyFormatters
        .sortPredictions(
            com.stationly.mobile.ui.util.rememberTickedPredictions(snapshot.predictions)
        )
        .firstOrNull { it.destination.isNotBlank() && it.eta.isNotBlank() }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Top 35% — clock + date/weather strip together as one cluster.
        // The 30→35 bump from the original gives the analog dial + date
        // strip room to coexist with breathing space, instead of the strip
        // floating somewhere down in the content panel.
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

        // ── Bottom 65% — station + next-train + board, separated from the
        // clock cluster above by the outer column's spacedBy(20.dp).
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.65f),
        ) {
            // Board's max height = ~65% of the bottom panel. Wraps content
            // when there's only a handful of rows (no stretch); caps when
            // content overflows so the inner ScrollView can actually scroll.
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
                        NextTrainHero(
                            prediction = it,
                            lineColor = lineColor,
                            dim = dim,
                        )
                    }
                    DreamBoard(
                        snapshot = snapshot,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = boardMaxHeight),
                        textScale = dim.boardTextScale,
                        showHeader = false,
                    )
                } else {
                    EmptyStatePanel()
                }
            }
        }
    }
}
