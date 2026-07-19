package com.stationly.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

/**
 * ServiceUnavailableScreen – Premium empty state for connection errors.
 *
 * Faithful port of Android `ui/common/ServiceUnavailableScreen.kt` (brand
 * row, CloudOff badge, context copy, single "Try again" CTA, optional
 * dismiss X). The only mechanical difference is the brand mark: Android
 * reads `R.drawable.stationly_logo`; here it goes through [StationlyLogo]
 * (the same PNG once composeResources are bundled, drawn-"S" fallback
 * otherwise).
 */
@Composable
fun ServiceUnavailableScreen(
    context: String = "service",
    overridingErrorMessage: String? = null,
    onRetry: () -> Unit = {},
    onDismiss: (() -> Unit)? = null
) {
    val onCanvas = MaterialTheme.colorScheme.onBackground
    val amber    = MaterialTheme.colorScheme.primary
    // Elegant error state: Minimalistic, clean typography, soft colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        // Brand wink at the top of the screen — reassures the user this
        // is still Stationly even when the network's gone wrong.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StationlyLogo(size = 20.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                "STATIONLY",
                color = amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.5.sp,
            )
        }

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
                    color = onCanvas.copy(alpha = 0.05f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = onCanvas.copy(alpha = 0.6f),
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
                color = onCanvas.copy(alpha = 0.03f),
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.CloudOff,
                        contentDescription = "Offline",
                        tint = onCanvas.copy(alpha = 0.45f),
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "Can't reach servers",
                color = onCanvas,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                overridingErrorMessage ?: messageForContext(context),
                color = onCanvas.copy(alpha = 0.65f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = onCanvas,
                    contentColor   = MaterialTheme.colorScheme.background,
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
