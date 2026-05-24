package com.stationly.mobile.ui.summary.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.mobile.R
import com.stationly.mobile.ui.theme.TflAmber

@Composable
fun EmptyStationsState(onAction: () -> Unit, strings: Map<String, String> = emptyMap()) {
    // Entrance animation — items fade + slide up on first composition
    var entered by remember { mutableStateOf(false) }
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(600, easing = EaseOut),
        label = "enter_alpha"
    )
    val slideOffset by animateFloatAsState(
        targetValue = if (entered) 0f else 40f,
        animationSpec = tween(600, easing = EaseOut),
        label = "enter_slide"
    )
    LaunchedEffect(Unit) { entered = true }

    // Subtle logo pulse
    val infiniteTransition = rememberInfiniteTransition(label = "logo_pulse")
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2200, easing = EaseInOut), RepeatMode.Reverse),
        label = "logo_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.background)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Subtle amber glow behind the logo
        Box(
            modifier = Modifier
                .size(200.dp)
                .align(Alignment.TopCenter)
                .offset(y = 80.dp)
                .background(
                    Brush.radialGradient(
                        listOf(TflAmber.copy(alpha = 0.08f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                .graphicsLayer {
                    alpha = enterAlpha
                    translationY = slideOffset
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Logo
            Image(
                painter = painterResource(id = R.drawable.stationly_logo),
                contentDescription = "Stationly",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(82.dp)
                    .scale(logoScale)
                    .clip(RoundedCornerShape(20.dp))
                    .graphicsLayer { alpha = 0.9f }
            )

            Spacer(Modifier.height(28.dp))

            Text(
                text = strings["empty.title"] ?: "Your board is empty",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Black,
                fontSize = 26.sp,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = strings["empty.subtitle"] ?: "Pick a station and we'll show you live\ndepartures — just like the real board.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(36.dp))

            // CTA button — solid primary background, onPrimary text. The
            // previous gradient with a pale-yellow mid-stop washed the
            // label out on light theme; solid primary holds contrast in
            // both themes (white text in light, black text in dark).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(
                        Icons.Rounded.RocketLaunch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        strings["empty.cta"] ?: "Set Up My Board",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        letterSpacing = 0.3.sp
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Feature chips — server-controlled, comma-separated
            val chips = (strings["empty.chips"] ?: "Tube,Bus,DLR,Overground")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                chips.forEach { FeatureChip(it) }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = strings["empty.footer"] ?: "Powered by TfL Open Data",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun FeatureChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun WelcomeEmptyState(onAddSelection: () -> Unit) {
    EmptyStationsState(onAddSelection)
}
