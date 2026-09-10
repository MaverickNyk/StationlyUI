package com.stationly.app.ui.summary.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.app.ui.theme.LocalThemeTokens
import com.stationly.core.config.BoardPolicyStore

/**
 * The one modal every quota refusal uses.
 *
 * ## Why an in-tree overlay and not `Dialog` or `ModalBottomSheet`
 * Both of those want a window of their own. This is a plain `Box` hosted as the
 * last child of a screen's root `Box`, so it is simply drawn last — nothing to
 * negotiate with UIKit, nothing to animate itself open from a hidden state.
 * `zIndex` is belt-and-braces for callers who host it somewhere other than last.
 *
 * ## [visible] is a parameter, not an `if` at the call site
 * The caller renders this unconditionally and passes the flag. Wrapping the
 * whole composable in `if (flag)` — which is what this did originally — means it
 * enters composition already visible, so the enter transition never runs, and
 * leaves composition the instant the flag clears, so the exit never runs either.
 * The animation was decorative in the literal sense: present in the source and
 * absent on screen. Held here, both directions actually play.
 */
@Composable
fun QuotaLimitOverlay(
    visible: Boolean,
    title: String,
    message: String,
    cta: String,
    onDismiss: () -> Unit,
) {
    val t = LocalThemeTokens.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(130)),
        modifier = Modifier.zIndex(99999f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(t.scrim)
                // Tapping the scrim dismisses. No ripple: the backdrop is not a
                // button, it is the rest of the screen being unavailable.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.92f),
                exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.96f),
            ) {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = t.cardElevated),
                    border = BorderStroke(1.dp, t.borderSubtle),
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        // Swallows taps so they do not reach the dismissing
                        // scrim underneath.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = t.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(54.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = t.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = title,
                            color = t.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = message,
                            color = t.textMuted,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )

                        Spacer(Modifier.height(24.dp))

                        Button(
                            onClick = {
                                performHaptic(HapticType.TAP)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = t.primary,
                                contentColor = t.onPrimary,
                            ),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text(
                                text = cta,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * "You already have as many stations as you are allowed."
 *
 * Every string is served; the compiled defaults in [BoardPolicyStore] are the
 * offline answer, never a blank modal.
 */
@Composable
fun StationLimitSheet(visible: Boolean, onDismiss: () -> Unit) {
    val policy = BoardPolicyStore.current
    QuotaLimitOverlay(
        visible = visible,
        title = policy.boardsLimitTitle,
        message = policy.boardsLimitMessage,
        cta = policy.boardsLimitCta,
        onDismiss = onDismiss,
    )
}

/** "You already have as many lines as one station is allowed." */
@Composable
fun LineLimitSheet(visible: Boolean, onDismiss: () -> Unit) {
    val policy = BoardPolicyStore.current
    QuotaLimitOverlay(
        visible = visible,
        title = policy.linesLimitTitle,
        message = policy.linesLimitMessage,
        // Deliberately shares the boards CTA. It is one word of
        // acknowledgement — a second key would be a second thing to keep in
        // sync for no gain in what it can say.
        cta = policy.boardsLimitCta,
        onDismiss = onDismiss,
    )
}
