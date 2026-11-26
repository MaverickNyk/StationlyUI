package com.stationly.app.ui.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.common.pressScale
import com.stationly.app.ui.theme.LocalThemeTokens

/**
 * The What's new screen. See [WhatsNew] for who sees it and when.
 *
 * Full screen and opaque: it is the first thing someone sees after the update,
 * and a half-sheet over a board that is still loading reads as an error. The
 * news first, then one quiet card about support, then the two buttons:
 * "Support Stationly" on top and filled, "Got it" under it, outlined.
 *
 * @param showSupport false when support cannot take a payment on this device
 *   right now (the server has it off): the line and its button are left out
 *   rather than offered with nothing behind them.
 */
@Composable
fun WhatsNewScreen(
    content: WhatsNewContent,
    showSupport: Boolean,
    onDone: (via: String) -> Unit,
    onSupport: () -> Unit,
) {
    val t = LocalThemeTokens.current

    @OptIn(ExperimentalComposeUiApi::class)
    BackHandler(enabled = true) { onDone("back") }

    Box(
        Modifier
            .fillMaxSize()
            .background(t.canvas)
            // Swallow taps, so nothing reaches the home screen underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 36.dp, bottom = 16.dp),
            ) {
                Text(
                    content.title,
                    color = t.textPrimary,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.6).sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(content.subtitle, color = t.textMuted, fontSize = 15.sp, lineHeight = 22.sp)

                Spacer(Modifier.height(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    content.items.forEach { WhatsNewRow(it) }
                }

                if (showSupport && content.support.isNotBlank()) {
                    Spacer(Modifier.height(30.dp))
                    Surface(
                        color = t.card,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(t.primary),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(content.support, color = t.textPrimary, fontSize = 13.5.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }

            Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Support on top, filled; "Got it" under it, outlined (owner's
                // call, 2026-11-26). With support off, "Got it" is the only
                // button and takes the filled style.
                val supportShown = showSupport && content.cta.isNotBlank()
                if (supportShown) {
                    WhatsNewButton(content.cta, filled = true, onClick = onSupport)
                }
                WhatsNewButton(content.done, filled = !supportShown, onClick = { onDone("button") })
            }
        }
    }
}

@Composable
private fun WhatsNewButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val t = LocalThemeTokens.current
    Surface(
        color = if (filled) t.primary else t.canvas,
        shape = RoundedCornerShape(14.dp),
        border = if (filled) null else androidx.compose.foundation.BorderStroke(1.dp, t.primary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().pressScale(onClick = onClick),
    ) {
        Text(
            label,
            color = if (filled) t.onPrimary else t.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        )
    }
}

@Composable
private fun WhatsNewRow(item: WhatsNewItem) {
    val t = LocalThemeTokens.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(t.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(item.icon), contentDescription = null, tint = t.primary, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (item.body.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(item.body, color = t.textMuted, fontSize = 13.5.sp, lineHeight = 19.sp)
            }
        }
    }
}

private fun iconFor(token: String): ImageVector = when (token.lowercase()) {
    "boards" -> Icons.Rounded.GridView
    "filter" -> Icons.Rounded.FilterList
    "widget" -> Icons.Rounded.Widgets
    "sync" -> Icons.Rounded.Sync
    "alert" -> Icons.Rounded.Notifications
    else -> Icons.Rounded.Star
}
