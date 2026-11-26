package com.stationly.app.ui.support

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.theme.LocalThemeTokens

/**
 * The running-costs line: one sentence and a thin bar under it.
 *
 * Deliberately quiet. It is a fact about the month, not a second call to
 * action, so it has no button of its own and sits under the pitch it
 * supports. The bar fills from empty on first draw so the figure reads as
 * a quantity rather than as decoration.
 *
 * Below the served threshold (`show_raised_from_minor`, £100 by default) only
 * the cost sentence shows: no figure and no bar, because a £0 line read as
 * "nobody cares" (owner, 2026-11-26). The figure is never invented.
 */
@Composable
fun SupportProgressBar(progress: SupportProgress, modifier: Modifier = Modifier) {
    val t = LocalThemeTokens.current
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(progress.fraction) { target = progress.fraction }
    val shown by animateFloatAsState(target, tween(700), label = "supportProgress")

    // Merged, so a screen reader reads the sentence once, as one item.
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(progress.sentence, color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
        if (!progress.showBar) return@Column
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(t.borderSubtle),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(t.primary),
            )
        }
    }
}
