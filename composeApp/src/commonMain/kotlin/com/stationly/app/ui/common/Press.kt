package com.stationly.app.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Touch feedback that belongs on iOS.
 *
 * Material's ripple is a circle spreading from the contact point, and it is the
 * single loudest tell that a Compose app is not a native one. iOS does two
 * things instead, and which one depends on the shape being touched:
 *
 *  - **Something card-shaped shrinks slightly** under the finger, as though it
 *    were being pressed into the screen ([pressScale]).
 *  - **A full-width row fills** with a faint grey and holds it until release
 *    ([pressHighlight]). It does not scale — a row spanning the screen scaling
 *    down pulls its own edges away from the screen edges, which reads as the
 *    layout breaking rather than as a press.
 *
 * Both animate the *press*, not the release: the fill or the shrink arrives
 * immediately and springs back on lift, which is what makes a tap feel answered
 * rather than merely registered.
 */
@Composable
fun Modifier.pressScale(
    onClick: () -> Unit,
    enabled: Boolean = true,
    scale: Float = 0.97f,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Read inside `graphicsLayer` so the whole effect is draw-phase: pressing a
    // tile must never recompose what is drawn on it.
    val press = animateFloatAsState(
        targetValue = if (pressed && enabled) scale else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh),
        label = "press_scale",
    )
    this
        .graphicsLayer {
            scaleX = press.value
            scaleY = press.value
        }
        .clickable(
            interactionSource = interaction,
            // No ripple. The scale IS the feedback, and running both gives a
            // press two different personalities at once.
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

@Composable
fun Modifier.pressHighlight(
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (color == Color.Unspecified) {
        androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    } else color
    val press = animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh),
        label = "press_highlight",
    )
    this
        .drawBehind {
            val f = press.value
            if (f > 0.01f) drawRect(color = tint.copy(alpha = 0.06f * f))
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}
