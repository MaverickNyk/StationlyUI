package com.stationly.mobile.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.mobile.BuildConfig

private val StagingOrange = Color(0xFFF97316)

@Composable
fun StagingBanner(modifier: Modifier = Modifier) {
    if (BuildConfig.FLAVOR != "staging") return

    val infiniteTransition = rememberInfiniteTransition(label = "staging_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "staging_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(StagingOrange.copy(alpha = alpha))
            .navigationBarsPadding()
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⚠  STAGING ENVIRONMENT",
            color = StagingOrange.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp
        )
    }
}
