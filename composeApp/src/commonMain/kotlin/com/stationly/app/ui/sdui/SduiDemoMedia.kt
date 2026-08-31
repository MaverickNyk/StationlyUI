package com.stationly.app.ui.sdui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * The picture half of the widget guide: a still, or a short looping animated
 * GIF or frame sequence.
 */
@Composable
fun SduiDemoMedia(
    url: String?,
    frames: List<String> = emptyList(),
    frameMs: Int = 120,
    loop: Boolean = true,
    aspectRatio: Float = 1f,
    caption: String? = null,
    corner: Int = 14,
    fit: String = "fit",
    background: String? = null,
    modifier: Modifier = Modifier,
) {
    if (url.isNullOrBlank() && frames.isEmpty()) return

    val ratio = if (aspectRatio > 0f) aspectRatio else 1f
    val scale = if (fit == "fill") ContentScale.Crop else ContentScale.Fit
    val ground = parseHexColor(background)
        ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)

    // Sizing:
    // - Wide media (e.g. hero board 2.14): fills width.
    // - Tall portrait media (ratio < 0.7, e.g. phone recording 0.46): compact height (280dp) with exact ratio.
    // - Square-ish media (0.7 <= ratio < 1.5, e.g. stack demo 0.93): neat window frame (max 260dp width).
    val boxModifier = when {
        ratio < 0.7f -> Modifier
            .height(280.dp)
            .aspectRatio(ratio)
        ratio < 1.5f -> Modifier
            .fillMaxWidth()
            .widthIn(max = 260.dp)
            .aspectRatio(ratio)
        else -> Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            boxModifier
                .clip(RoundedCornerShape(corner.coerceIn(0, 48).dp))
                .background(ground),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !url.isNullOrBlank() -> SduiSmartImage(
                    url = url,
                    contentDescription = caption,
                    contentScale = scale,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> FrameStrip(
                    frames = frames,
                    stepMs = frameMs.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS).toLong(),
                    loop = loop,
                    caption = caption,
                    scale = scale,
                )
            }
        }
        if (!caption.isNullOrBlank()) {
            Text(
                text = caption,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

/**
 * Plays [frames] on a timer, one frame ahead of itself.
 */
@Composable
private fun FrameStrip(
    frames: List<String>,
    stepMs: Long,
    loop: Boolean,
    caption: String?,
    scale: ContentScale,
) {
    var index by remember(frames) { mutableIntStateOf(0) }

    LaunchedEffect(frames, stepMs, loop) {
        while (true) {
            delay(stepMs)
            val next = index + 1
            if (next < frames.size) {
                index = next
            } else {
                if (!loop) return@LaunchedEffect
                index = 0
            }
        }
    }

    val shown = index.coerceIn(frames.indices)
    AsyncImage(
        model = frames[shown],
        contentDescription = caption,
        contentScale = scale,
        modifier = Modifier.fillMaxSize(),
    )
    val ahead = if (shown + 1 < frames.size) shown + 1 else if (loop) 0 else shown
    if (ahead != shown) {
        AsyncImage(
            model = frames[ahead],
            contentDescription = null,
            contentScale = scale,
            modifier = Modifier.fillMaxSize().alpha(0f),
        )
    }
}

private const val MIN_FRAME_MS = 40
private const val MAX_FRAME_MS = 2000

internal fun parseHexColor(hex: String?): Color? {
    val raw = hex?.trim()?.removePrefix("#") ?: return null
    val value = raw.toLongOrNull(16) ?: return null
    return when (raw.length) {
        6 -> Color(value or 0xFF000000L)
        8 -> Color(value)
        else -> null
    }
}
