package com.stationly.mobile.ui.common

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.mobile.ui.theme.TflAmber

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable

import androidx.compose.material.icons.filled.Close

/**
 * ServiceUnavailableScreen – Premium empty state for connection errors.
 */
@Composable
fun ServiceUnavailableScreen(
    context: String = "service",
    overridingErrorMessage: String? = null,
    onRetry: () -> Unit = {},
    onDismiss: (() -> Unit)? = null
) {
    // Elegant error state: Minimalistic, clean typography, soft colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .padding(top = 24.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.05f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close, 
                            contentDescription = "Close", 
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            
            // Soft, non-aggressive icon
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.03f),
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.CloudOff,
                        contentDescription = "Offline",
                        tint = Color(0xFFB3B3B3), // Premium gray
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "Can't reach servers",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )
            
            Spacer(Modifier.height(16.dp))

            Text(
                overridingErrorMessage ?: messageForContext(context),
                color = Color(0xFFA0A0A0),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier.height(52.dp).padding(horizontal = 32.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                contentPadding = PaddingValues(horizontal = 32.dp)
            ) {
                Text(
                    "Try again",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

private fun messageForContext(context: String) = when (context) {
    "selection" -> "Can't reach Stationly servers.\nYour previous setup is saved — you can still view your board."
    "board"     -> "Can't reach Stationly servers.\nLive TfL data continues via direct signals."
    else        -> "Can't reach Stationly servers.\nPlease check your connection and try again."
}

/**
 * Slim non-blocking banner for screens that have cached data to show.
 * Use this instead of ServiceUnavailableScreen when the user can still
 * interact with the screen in a degraded state.
 */
@Composable
fun OfflineBanner(
    visible: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(300)) { -it } + fadeIn(tween(300)),
        exit  = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1208))
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = TflAmber,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Can't reach Stationly servers",
                color = Color(0xFFCCAA44),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("Retry", color = TflAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = Color(0xFF886633),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

