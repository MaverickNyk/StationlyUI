@file:OptIn(ExperimentalMaterial3Api::class)
package com.stationly.mobile.ui.summary

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.stationly.mobile.R
import com.stationly.mobile.ui.common.AnnouncementBanner
import com.stationly.mobile.ui.summary.components.*
import com.stationly.mobile.ui.theme.TflAmber

@OptIn(ExperimentalMaterial3Api::class)
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
    val announcement by viewModel.announcement.collectAsState()
    val homeConfig by viewModel.homeConfig.collectAsState()

    val firebaseUser = remember { FirebaseAuth.getInstance().currentUser }
    val userName = remember(firebaseUser) {
        firebaseUser?.displayName?.split(" ")?.firstOrNull()
            ?: firebaseUser?.email?.split("@")?.firstOrNull()
    }

    // Reload selections from SQLite whenever this screen resumes.
    // Handles the case where ProfileScreen (or any other screen) deleted a station
    // from SQLite while SummaryViewModel's in-memory cache was stale.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadSelectionsFromDb()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                pulseAlpha = pulseAlpha,
                liveLabel = homeConfig["topbar.live_label"] ?: "Live Network"
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
                    EmptyStationsState(onNavigateToSelection, strings = homeConfig)
                } else {
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refreshAll() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            item {
                                SummaryHeader(
                                    count = currentSelections.size,
                                    lastUpdated = uiState.lastUpdated,
                                    userName = userName,
                                    strings = homeConfig
                                )
                            }

                            announcement?.let { banner ->
                                item(key = "announcement_${banner.id}") {
                                    AnnouncementBanner(
                                        announcement = banner,
                                        onDismiss = { viewModel.dismissAnnouncement() }
                                    )
                                }
                            }

                            items(currentSelections, key = { "${it.station}_${it.line}" }) { selection ->
                                val selectionPredictions = predictions[selection.station] ?: emptyList()

                                Box(modifier = Modifier.animateItem()) {
                                    Board(
                                        selection = selection,
                                        predictions = selectionPredictions,
                                        hasPredictions = selectionPredictions.isNotEmpty(),
                                        lineStatus = lineStatuses["${selection.mode}_${selection.line}".lowercase()],
                                        sduiPayload = sduiPayloads[selection.station],
                                        lastUpdated = stationUpdates[selection.station] ?: 0L,
                                        onDelete = { viewModel.deleteSelection(selection) },
                                        nextPrediction = selectionPredictions.firstOrNull(),
                                        homeConfig = homeConfig
                                    )
                                }
                            }

                            item {
                                StationExploreSection(lineStatuses = lineStatuses, strings = homeConfig)
                            }
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
    pulseAlpha: Float,
    liveLabel: String = "Live Network"
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
                            text = liveLabel,
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
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            val photoUrl = firebaseUser?.photoUrl?.toString()
            IconButton(
                onClick = onNavigateToProfile,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                if (photoUrl != null) {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = "Profile",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f), CircleShape)
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.05f),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val initial = (firebaseUser?.displayName
                                ?: firebaseUser?.email ?: "U").take(1).uppercase()
                            Text(
                                initial,
                                color = TflAmber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
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
