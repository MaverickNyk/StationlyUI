package com.stationly.app.ui.summary.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.theme.TflAmber

@Composable
fun EmptyStationsState(
    onNavigateToSelection: () -> Unit,
    strings: Map<String, String> = emptyMap()
) {
    val infiniteTransition = rememberInfiniteTransition(label = "empty_pulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = EaseInOut), RepeatMode.Reverse),
        label = "ring_scale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(1800, easing = EaseInOut), RepeatMode.Reverse),
        label = "ring_alpha"
    )

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onBackground = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(40.dp)
        ) {
            // Pulsing ring + big "+" button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(120.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = ringAlpha }
                        .border(
                            width = 1.5.dp,
                            brush = Brush.radialGradient(
                                listOf(primary.copy(alpha = 0.5f), Color.Transparent)
                            ),
                            shape = CircleShape
                        )
                )
                Button(
                    onClick = onNavigateToSelection,
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primary,
                        contentColor = onPrimary
                    )
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add station",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = strings["empty.title"] ?: "No boards yet",
                color = onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = strings["empty.subtitle"] ?: "Tap the + button to set up your first live departure board.",
                color = onBackground.copy(alpha = 0.5f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(32.dp))

            // Decorative TfL line dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    Color(0xFF0098D4), // Victoria
                    Color(0xFFE32017), // Central
                    TflAmber,          // District/Circle
                    Color(0xFF9B0056), // Metropolitan
                    Color(0xFF6950A1)  // Elizabeth
                ).forEach { color ->
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(color, CircleShape)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = strings["empty.hint"] ?: "Tube · Bus · DLR · Overground · Elizabeth line",
                color = onBackground.copy(alpha = 0.3f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
