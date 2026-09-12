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
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic

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
 *
 * ## They fire the haptic too, and that is the point of putting it here
 * A press has two halves — something to see and something to feel — and they
 * are the same event. Leaving the second half to each call site meant it was
 * present on the screens somebody remembered and absent everywhere else: the
 * whole of the dream settings screen and the whole of the widget configuration
 * screen shipped silent, not by decision but by omission.
 *
 * So the haptic travels with the primitive. A screen built next year gets it by
 * using the same modifier every other screen uses, rather than by its author
 * knowing to add it.
 *
 * [HapticType.TAP] is the default because most presses are "that registered".
 * Pass [HapticType.SELECTION] for one exclusive choice among a few — a
 * segmented control, a chip row, a station picker — where the message is "you
 * are on that one now" rather than "that registered". Pass `null` only when the
 * caller fires its own, which is rare and always because the decision is
 * conditional on something the modifier cannot see.
 */
@Composable
fun Modifier.pressScale(
    onClick: () -> Unit,
    enabled: Boolean = true,
    scale: Float = 0.97f,
    haptic: HapticType? = HapticType.TAP,
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
            onClick = {
                // Before the action, not after: the action can navigate, and a
                // haptic that arrives once the next screen is up reads as
                // belonging to the new screen rather than to the tap.
                haptic?.let(::performHaptic)
                onClick()
            },
        )
}

@Composable
fun Modifier.pressHighlight(
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    haptic: HapticType? = HapticType.TAP,
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
            onClick = {
                haptic?.let(::performHaptic)
                onClick()
            },
        )
}

/**
 * Wrap an `onClick` so it buzzes, without changing how it looks.
 *
 * [pressScale] and [pressHighlight] carry the haptic for everything that uses
 * the app's own press feel. This is for the controls that deliberately keep
 * Material's — `Button`, `IconButton`, `TextButton` — where swapping the
 * indication would be a visual redesign rather than a feedback fix.
 *
 * ```
 * IconButton(onClick = hapticClick(onOpenSettings)) { … }
 * ```
 *
 * The haptic fires BEFORE the action, for the same reason it does in the
 * modifiers: the action can navigate, and feedback that lands after the next
 * screen is up reads as belonging to that screen rather than to the tap.
 */
@Composable
fun hapticClick(
    haptic: HapticType = HapticType.TAP,
    onClick: () -> Unit,
): () -> Unit = {
    performHaptic(haptic)
    onClick()
}
