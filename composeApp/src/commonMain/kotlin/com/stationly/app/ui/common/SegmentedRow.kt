package com.stationly.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * A row of mutually exclusive options with the selection sliding between them.
 *
 * One component rather than a copy per screen. There are two of these now (the
 * theme in home settings, expanded/collapsed in station settings) and they were
 * drifting apart in padding, corner radius and icon size while claiming to be
 * the same control — which is exactly the kind of difference a user cannot name
 * but does notice.
 *
 * ## The selection moves
 * The selected fill used to appear under whichever segment had just been tapped
 * and vanish from the old one. Two things happening at once read as a flicker;
 * one thing travelling reads as a control responding. The pill is a single Box
 * whose offset is animated with a slightly under-damped spring, so it arrives
 * with a hint of overshoot instead of stopping dead.
 *
 * Its position is read inside `offset {}` — layout phase only — so a segmented
 * control never recomposes its own labels while the pill is in flight.
 */
@Composable
fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
    /** Degrees to turn [icon] by, for pairs drawn as one glyph and its opposite. */
    iconRotation: (T) -> Float = { 0f },
) {
    if (options.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    val onBackground = MaterialTheme.colorScheme.onBackground

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, onBackground.copy(alpha = 0.10f)),
    ) {
        BoxWithConstraints(modifier = Modifier.padding(4.dp)) {
            val segmentWidth = maxWidth / options.size
            val segmentPx = with(LocalDensity.current) { segmentWidth.toPx() }
            val index = options.indexOf(selected).coerceAtLeast(0)
            val slide = animateFloatAsState(
                targetValue = index * segmentPx,
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.5f,
                ),
                label = "segment_slide",
            )

            // The travelling fill. Sized to one segment and offset into place
            // rather than re-parented under the selected option, which is what
            // makes it one object moving instead of two fading.
            //
            // Nested inside `matchParentSize` so it can be full height without a
            // height constraint of its own — the row's height comes from its
            // content, which has not been measured when this is composed.
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(slide.value.roundToInt(), 0) }
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .background(primary.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                options.forEach { option ->
                    val isSelected = option == selected
                    val tint by animateColorAsState(
                        if (isSelected) primary else onBackground.copy(alpha = 0.55f),
                        label = "segment_tint",
                    )
                    Box(
                        modifier = Modifier
                            .width(segmentWidth)
                            .pressScale(onClick = { onSelect(option) }, scale = 0.94f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (icon != null) {
                                Icon(
                                    icon(option),
                                    null,
                                    tint = tint,
                                    modifier = Modifier
                                        .size(17.dp)
                                        .graphicsLayer { rotationZ = iconRotation(option) },
                                )
                                Spacer(Modifier.width(7.dp))
                            }
                            Text(
                                label(option),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = tint,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
