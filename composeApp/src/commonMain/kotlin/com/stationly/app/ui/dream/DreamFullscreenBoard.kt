package com.stationly.app.ui.dream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Fullscreen departure-board dream — port of Android
 * `dream/DreamFullscreenBoard.kt`.
 *
 * NOT literally edge-to-edge — that read as "stretched widget" rather than
 * "signage panel". The board renders as a rounded card (beefier amber border
 * + larger radius than the cluster dream) centred on the dark canvas.
 *
 * Cutout mirroring: the notch/Dynamic-Island inset is mirrored on the
 * opposite side so the board sits symmetrically against the camera block in
 * either rotation — same rule as Android.
 */
@Composable
fun FullscreenBoardLayout(snapshot: DreamSnapshot, sduiStrings: Map<String, String>) {
    val ld = LocalLayoutDirection.current
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val horizDp = maxOf(cutout.calculateLeftPadding(ld), cutout.calculateRightPadding(ld))
    val vertDp  = maxOf(cutout.calculateTopPadding(), cutout.calculateBottomPadding())

    // Min 6dp baseline so the amber border doesn't fuse with the bezel on a
    // device that reports zero cutout.
    val canvasPad = PaddingValues(
        start  = maxOf(horizDp, 6.dp),
        end    = maxOf(horizDp, 6.dp),
        top    = maxOf(vertDp,  6.dp),
        bottom = maxOf(vertDp,  6.dp),
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(canvasPad),
    ) {
        val isLandscape = maxWidth > maxHeight
        val shortEdgeDp = minOf(maxWidth, maxHeight).value

        // Base textScale anchored on short edge (2.8× tablet cap — signage,
        // not billboard), with a ~10% portrait penalty so stacked rows don't
        // feel crammed.
        val baseScale = (shortEdgeDp * 0.0018f + 0.76f).coerceIn(1.3f, 2.8f)
        val textScale = if (isLandscape) baseScale else baseScale * 0.90f

        val cardMaxWidth  = maxWidth
        val cardMaxHeight = maxHeight

        // Column + Center arrangement is the bulletproof centering pattern
        // when the child chains widthIn + fillMaxWidth.
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement   = Arrangement.Center,
            horizontalAlignment   = Alignment.CenterHorizontally,
        ) {
            DreamBoard(
                snapshot   = snapshot,
                modifier   = Modifier
                    .widthIn(max = cardMaxWidth)
                    .heightIn(max = cardMaxHeight)
                    .fillMaxWidth(),
                textScale  = textScale,
                showHeader = true,
                showClock  = true,
                sduiStrings = sduiStrings,
                fullscreen = true,
            )
        }
    }
}
