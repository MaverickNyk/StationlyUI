package com.stationly.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen scrim overlay that blocks all underlying input while an
 * async action is in flight (logout, save board, sign-in, delete account).
 *
 * Faithful port of Android `ui/common/LoadingOverlay.kt` — same scrim
 * alpha, card, spinner and label metrics.
 *
 * Why this exists: every loading state in the app used to render a small
 * spinner inside its triggering button — the rest of the screen stayed
 * tappable, so a user could double-tap delete-account during the network
 * call, or escape from logout by tapping a station card. The overlay
 * pattern is industry-standard precisely because async flows that touch
 * auth or destructive state MUST be modal until they resolve.
 *
 * Usage:
 *
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     YourScreenContent()
 *     LoadingOverlay(visible = isSigningOut, label = "Signing out…")
 * }
 * ```
 *
 * The overlay:
 *   - Fades in/out — feels less abrupt than a hard cut
 *   - Consumes ALL pointer input via `pointerInput { detectTapGestures {} }`,
 *     so taps / drags on the underlying composables never fire
 *   - Tints the screen with a theme-aware scrim
 *   - Shows a centered card with a brand-primary spinner + optional label
 *
 * Layering: sits ABOVE sibling content in the same Box (give it zIndex if
 * declaration order can't guarantee that) but BELOW system UI (dialogs,
 * IME, sheets) since it's just a composable in the same window.
 */
@Composable
fun LoadingOverlay(
    visible: Boolean,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
                // Swallow every gesture — taps, drags, long-presses — so
                // the screen behind is truly inert while the spinner is up.
                .pointerInput(visible) { detectTapGestures { /* consumed */ } },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp),
                    )
                    if (!label.isNullOrBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = label,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp,
                        )
                    }
                }
            }
        }
    }
}
