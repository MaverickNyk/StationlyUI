@file:OptIn(ExperimentalMaterial3Api::class)
package com.stationly.mobile.ui.summary

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.mobile.R
import com.stationly.mobile.ui.summary.components.*
import com.stationly.mobile.ui.theme.TflAmber

@Composable
fun SummaryScreen(
    onNavigateToSelection: () -> Unit,
    onNavigateToProfile: () -> Unit,
    viewModel: SummaryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val selections by viewModel.selections.collectAsState()
    val predictions by viewModel.predictions.collectAsState()
    val lineStatuses by viewModel.lineStatuses.collectAsState()
    val stationUpdates by viewModel.stationUpdates.collectAsState()
    val sduiPayloads by viewModel.sduiPayloads.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            SummaryTopBar(
                onNavigateToProfile = onNavigateToProfile,
                onNavigateToSelection = onNavigateToSelection,
                selectionsEmpty = selections.isEmpty(),
                pulseAlpha = pulseAlpha
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF0A0A0A), Color.Black)
                    )
                )
                .padding(padding)
        ) {
            AnimatedContent(
                targetState = selections,
                transitionSpec = {
                    fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
                },
                label = "selections_content"
            ) { currentSelections ->
                if (currentSelections.isEmpty()) {
                    EmptyStationsState(onNavigateToSelection)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp)
                    ) {
                        item {
                            SummaryHeader(currentSelections.size, uiState.lastUpdated)
                        }
                        
                        items(currentSelections, key = { "${it.station}_${it.line}" }) { selection ->
                            Board(
                                selection = selection,
                                predictions = predictions[selection.station] ?: emptyList(),
                                hasPredictions = predictions[selection.station]?.isNotEmpty() == true,
                                lineStatus = lineStatuses["${selection.mode}_${selection.line}".lowercase()],
                                sduiPayload = sduiPayloads[selection.station],
                                lastUpdated = stationUpdates[selection.station] ?: 0L,
                                onDelete = { viewModel.deleteSelection(selection) }
                            )
                        }
                        
                        item {
                            StationExploreSection()
                        }
                    }
                }
            }
            
            // ── Service Unavailable Overlay (rendered above content) ──
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.isBackendOffline,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(400)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300))
            ) {
                com.stationly.mobile.ui.common.ServiceUnavailableScreen(
                    context = "board",
                    overridingErrorMessage = uiState.error,
                    onRetry = { viewModel.retryLoad() },
                    onDismiss = { viewModel.clearError() }
                )
            }

            // Bottom decorative glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, TflAmber.copy(alpha = 0.03f))
                        )
                    )
            )
        }
    }
}

@Composable
private fun SummaryTopBar(
    onNavigateToProfile: () -> Unit,
    onNavigateToSelection: () -> Unit,
    selectionsEmpty: Boolean,
    pulseAlpha: Float
) {
    CenterAlignedTopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.stationly_logo),
                    contentDescription = "Logo",
                    modifier = Modifier.size(32.dp).clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Stationly", 
                        color = Color.White, 
                        fontWeight = FontWeight.Black, 
                        fontSize = 20.sp,
                        letterSpacing = (-0.5).sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF4CAF50).copy(alpha = pulseAlpha), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Live Network", 
                            color = Color.Gray, 
                            fontSize = 10.sp, 
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onNavigateToProfile,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.05f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AccountCircle, 
                            contentDescription = "Profile", 
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = onNavigateToSelection,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = TflAmber.copy(alpha = 0.1f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (selectionsEmpty) Icons.Default.Add else Icons.Default.Edit,
                            contentDescription = "Action",
                            tint = TflAmber
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Black,
            titleContentColor = Color.White
        )
    )
}
