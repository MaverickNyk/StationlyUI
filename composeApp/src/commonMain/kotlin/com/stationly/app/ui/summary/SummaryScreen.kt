package com.stationly.app.ui.summary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.stationly.app.ui.common.AnnouncementBanner
import com.stationly.app.ui.common.OfflineBanner
import com.stationly.app.ui.summary.components.Board
import com.stationly.app.ui.summary.components.EmptyStationsState
import com.stationly.app.ui.summary.components.SummaryHeader
import com.stationly.app.ui.theme.TflAmber
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    onNavigateToSelection: () -> Unit,
    onNavigateToProfile: () -> Unit,
    viewModel: SummaryViewModel = viewModel { SummaryViewModel() }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selections by viewModel.selections.collectAsStateWithLifecycle()
    val predictions by viewModel.predictions.collectAsStateWithLifecycle()
    val lineStatuses by viewModel.lineStatuses.collectAsStateWithLifecycle()
    val failedLineStatusKeys by viewModel.failedLineStatusKeys.collectAsStateWithLifecycle()
    val stationUpdates by viewModel.stationUpdates.collectAsStateWithLifecycle()
    val sduiPayloads by viewModel.sduiPayloads.collectAsStateWithLifecycle()
    val announcement by viewModel.announcement.collectAsStateWithLifecycle()
    val homeConfig by viewModel.homeConfig.collectAsStateWithLifecycle()
    val forceUpdate by viewModel.forceUpdate.collectAsStateWithLifecycle()
    val deletingBoardId by viewModel.isDeletingBoard.collectAsStateWithLifecycle()
    val showWidgetPromo by viewModel.showWidgetPromo.collectAsStateWithLifecycle()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
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
                liveLabel = homeConfig["topbar.live_label"] ?: "Live Network",
                userInitial = uiState.userInitial
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
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            item {
                                SummaryHeader(
                                    count = currentSelections.size,
                                    lastUpdated = uiState.lastUpdated,
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
                                val statusKey = "${selection.mode}_${selection.line}".lowercase()

                                Board(
                                    selection = selection,
                                    predictions = selectionPredictions,
                                    hasPredictions = selectionPredictions.isNotEmpty(),
                                    lineStatus = lineStatuses[statusKey],
                                    lineStatusFailed = failedLineStatusKeys.contains(statusKey),
                                    sduiPayload = sduiPayloads[selection.station],
                                    lastUpdated = stationUpdates[selection.station] ?: 0L,
                                    onDelete = { if (deletingBoardId == null) viewModel.deleteSelection(selection) },
                                    nextPrediction = selectionPredictions.firstOrNull(),
                                    homeConfig = homeConfig,
                                    isDeleting = deletingBoardId == selection.station
                                )
                            }
                        }

                        // Refresh indicator overlaid at top
                        if (uiState.isRefreshing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                CircularProgressIndicator(
                                    color = TflAmber,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            var updateDismissed by remember { mutableStateOf(false) }
            if (forceUpdate && !updateDismissed) {
                UpdateNudgeDialog(
                    title = homeConfig["app.update.title"] ?: "New update available",
                    message = homeConfig["app.update.message"] ?: "Update Stationly for the latest features and improvements.",
                    cta = homeConfig["app.update.cta"] ?: "Update Now",
                    dismiss = homeConfig["app.update.dismiss"] ?: "Maybe Later",
                    storeUrl = homeConfig["app.storeUrl"] ?: "https://apps.apple.com/app/stationly",
                    onDismiss = { updateDismissed = true }
                )
            }

            OfflineBanner(
                visible = uiState.isBackendOffline,
                onRetry = { viewModel.retryLoad() },
                onDismiss = { viewModel.clearError() }
            )

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryTopBar(
    onNavigateToProfile: () -> Unit,
    onNavigateToSelection: () -> Unit,
    selectionsEmpty: Boolean,
    pulseAlpha: Float,
    liveLabel: String = "Live Network",
    userInitial: String = "?"
) {
    CenterAlignedTopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Logo: amber "S" avatar (no drawable required until resource is added)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(TflAmber),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "S",
                        color = Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                }
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
            IconButton(
                onClick = onNavigateToProfile,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.05f),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            userInitial,
                            color = TflAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
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

@Composable
private fun UpdateNudgeDialog(
    title: String,
    message: String,
    cta: String,
    dismiss: String,
    storeUrl: String,
    onDismiss: () -> Unit
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1A1A1A)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(TflAmber),
                    contentAlignment = Alignment.Center
                ) {
                    Text("S", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 26.sp)
                }
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    message,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { uriHandler.openUri(storeUrl); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = TflAmber, contentColor = Color.Black),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(cta, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(dismiss, color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
                }
            }
        }
    }
}
