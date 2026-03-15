@file:OptIn(ExperimentalMaterial3Api::class)
package com.stationly.mobile.ui.summary

import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.Platform
import com.stationly.core.util.StationlyFormatters
import com.stationly.mobile.R
import com.stationly.mobile.ui.theme.TflAmber
import kotlinx.coroutines.delay

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
                                    if (selections.isEmpty()) Icons.Default.Add else Icons.Default.Edit,
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
                        
                        items(currentSelections, key = { it.station }) { selection ->
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
            
            // Bottom glow
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
private fun SummaryHeader(count: Int, lastUpdated: Long) {
    Column {
        Text(
            text = "Active Boards",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = Color.White
        )
        Text(
            text = "Monitoring $count station${if (count > 1) "s" else ""} for departures.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )
        
        if (lastUpdated > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(12.dp)
            ) {
                val timeStr = remember(lastUpdated) {
                    com.stationly.core.util.StationlyFormatters.formatLastUpdated(lastUpdated)
                }
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Departure boards sync confirmed • $timeStr",
                        color = Color(0xFF4CAF50),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun Board(
    selection: UserSelection,
    predictions: List<PredictionDisplay>,
    hasPredictions: Boolean,
    lineStatus: String?,
    sduiPayload: com.stationly.core.model.sdui.SduiWidgetPayload? = null,
    lastUpdated: Long,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = selection.line.replaceFirstChar { it.uppercase() } + " Line",
                    style = MaterialTheme.typography.labelLarge,
                    color = TflAmber,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = selection.stationName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White
                )
            }
            
            Surface(
                onClick = onDelete,
                color = Color.White.copy(alpha = 0.05f),
                shape = CircleShape,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.Gray.copy(alpha = 0.5f),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        // The Digital Board Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(TflAmber.copy(alpha = 0.03f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { context ->
                    LayoutInflater.from(context).inflate(R.layout.widget_departure_board, null, false) as LinearLayout
                },
                update = { view ->
                    val context = view.context
                    
                    // Hide settings button as we use Compose delete button
                    view.findViewById<View>(R.id.btn_settings).visibility = View.GONE
                    
                    // Last Updated Chronometer
                    val chrono = view.findViewById<Chronometer>(R.id.last_updated_timer)
                    chrono.visibility = View.VISIBLE
                    chrono.base = SystemClock.elapsedRealtime()
                    chrono.format = "%s ago"
                    chrono.start()
                    
                    // Status row
                    val statusContainer = view.findViewById<View>(R.id.status_container)
                    val severityText = view.findViewById<TextView>(R.id.status_severity)
                    val reasonText = view.findViewById<TextView>(R.id.status_reason)
                    
                    // Persistent Status Row
                    statusContainer.visibility = View.VISIBLE
                    if (lineStatus != null) {
                        val severity = if (lineStatus.contains(":")) lineStatus.substringBefore(":") else lineStatus
                        val reason = if (lineStatus.contains(":")) lineStatus.substringAfter(":") else ""
                        severityText.text = severity
                        reasonText.text = reason
                        reasonText.isSelected = true
                    } else {
                        severityText.text = "Status"
                        reasonText.text = "Connecting to TfL signals..."
                    }
                    
                    val rowsContainer = view.findViewById<LinearLayout>(R.id.rows_container)
                    val waitingContainer = view.findViewById<LinearLayout>(R.id.waiting_container)
                    rowsContainer.removeAllViews()
                    
                    var dynTextColor = context.getColor(R.color.tfl_amber)
                    
                    if (sduiPayload != null) {
                        // SYNCED SDUI Rendering Path (App and Widget are now identical)
                        val theme = sduiPayload.theme
                        theme?.primaryColor?.let {
                            dynTextColor = com.stationly.mobile.util.SduiThemeManager.parseColor(it, dynTextColor)
                            view.findViewById<TextView>(R.id.line_name).setTextColor(dynTextColor)
                            chrono.setTextColor(dynTextColor)
                        }
                        
                        theme?.backgroundColor?.let {
                            val dynBgColor = com.stationly.mobile.util.SduiThemeManager.parseColor(it, android.graphics.Color.BLACK)
                            view.findViewById<LinearLayout>(R.id.departure_board).setBackgroundColor(dynBgColor)
                        }

                        view.findViewById<TextView>(R.id.line_name).text = sduiPayload.title
                        waitingContainer.visibility = View.GONE
                        
                        // Render components strictly from the bound payload
                        sduiPayload.components.forEach { component ->
                            when (component) {
                                is com.stationly.core.model.sdui.SduiWidgetComponent.Header -> {
                                    val header = LayoutInflater.from(context).inflate(R.layout.widget_platform_header, rowsContainer, false)
                                    val pTv = header.findViewById<TextView>(R.id.platform_name)
                                    pTv.text = component.title
                                    val headerColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.color, dynTextColor)
                                    pTv.setTextColor(headerColor)
                                    rowsContainer.addView(header)
                                }
                                is com.stationly.core.model.sdui.SduiWidgetComponent.Row -> {
                                    val row = LayoutInflater.from(context).inflate(R.layout.widget_departure_row, rowsContainer, false)
                                    val nTv = row.findViewById<TextView>(R.id.departure_number)
                                    val dTv = row.findViewById<TextView>(R.id.destination_text)
                                    val eTv = row.findViewById<TextView>(R.id.eta_text)

                                    nTv.text = component.index
                                    dTv.text = component.destination
                                    eTv.text = component.eta

                                    nTv.setTextColor(dynTextColor)
                                    dTv.setTextColor(dynTextColor)
                                    val etaColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.etaColor, dynTextColor)
                                    eTv.setTextColor(etaColor)

                                    if (component.animation == "pulse" && component.eta == "Due") {
                                        val anim = android.view.animation.AlphaAnimation(1f, 0.4f).apply {
                                            duration = 1000
                                            repeatMode = android.view.animation.Animation.REVERSE
                                            repeatCount = android.view.animation.Animation.INFINITE
                                        }
                                        row.startAnimation(anim)
                                    } else {
                                        row.clearAnimation()
                                    }

                                    rowsContainer.addView(row)
                                }
                                is com.stationly.core.model.sdui.SduiWidgetComponent.Status -> {
                                    // Status is typically handled by the persistent status row in this layout
                                }
                                is com.stationly.core.model.sdui.SduiWidgetComponent.Message -> {
                                    val row = LayoutInflater.from(context).inflate(R.layout.widget_departure_row, rowsContainer, false)
                                    val nTv = row.findViewById<TextView>(R.id.departure_number)
                                    val dTv = row.findViewById<TextView>(R.id.destination_text)
                                    row.findViewById<TextView>(R.id.eta_text).text = ""
                                    nTv.text = "-"
                                    dTv.text = component.text
                                    
                                    val msgColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.color, dynTextColor)
                                    
                                    nTv.setTextColor(dynTextColor)
                                    dTv.setTextColor(msgColor)
                                    rowsContainer.addView(row)
                                }
                            }
                        }
                    } else {
                        // Unified Legacy Path (Synced with Widget)
                        view.findViewById<TextView>(R.id.line_name).text = selection.stationName
                        waitingContainer.visibility = View.GONE
                        
                        val legacyRows = com.stationly.core.util.GlobalBoardProcessor.prepareLegacyRows(
                            predictions,
                            selection.line,
                            true // Inside app always has selection context
                        )

                        legacyRows.forEach { row ->
                            when (row) {
                                is com.stationly.core.util.LegacyRow.Header -> {
                                    val header = LayoutInflater.from(context).inflate(R.layout.widget_platform_header, rowsContainer, false)
                                    header.findViewById<TextView>(R.id.platform_name).text = row.title
                                    rowsContainer.addView(header)
                                }
                                is com.stationly.core.util.LegacyRow.Departure -> {
                                    val dep = LayoutInflater.from(context).inflate(R.layout.widget_departure_row, rowsContainer, false)
                                    dep.findViewById<TextView>(R.id.departure_number).text = if (row.index > 0) row.index.toString() else ""
                                    dep.findViewById<TextView>(R.id.destination_text).text = row.destination
                                    dep.findViewById<TextView>(R.id.eta_text).text = row.eta
                                    
                                    // Apply standard TFL Amber to legacy rows
                                    dep.findViewById<TextView>(R.id.departure_number).setTextColor(dynTextColor)
                                    dep.findViewById<TextView>(R.id.destination_text).setTextColor(dynTextColor)
                                    dep.findViewById<TextView>(R.id.eta_text).setTextColor(dynTextColor)
                                    
                                    rowsContainer.addView(dep)
                                }
                                is com.stationly.core.util.LegacyRow.Message -> {
                                    val header = LayoutInflater.from(context).inflate(R.layout.widget_platform_header, rowsContainer, false)
                                    header.findViewById<TextView>(R.id.platform_name).text = row.text
                                    rowsContainer.addView(header)
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun StationExploreSection() {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = "Station Intelligence",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ExploreCard(
                icon = Icons.Default.Star,
                title = "Crowd Levels",
                subtitle = "Expected light",
                modifier = Modifier.weight(1f)
            )
            ExploreCard(
                icon = Icons.Default.Notifications,
                title = "Alerts",
                subtitle = "None nearby",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ExploreCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xFF151515),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = TflAmber, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(text = subtitle, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyState(onAddSelection: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.stationly_logo),
                contentDescription = null,
                modifier = Modifier.size(80.dp).graphicsLayer { alpha = 0.8f },
                contentScale = ContentScale.Fit
            )
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Welcome to Stationly",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Your digital window to London's transit.",
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp
                )
            }
            
            Button(
                onClick = onAddSelection,
                colors = ButtonDefaults.buttonColors(containerColor = TflAmber, contentColor = Color.Black),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(56.dp).fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Setup New Board", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyStationsState(onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.AddCircle,
            contentDescription = null,
            tint = TflAmber,
            modifier = Modifier
                .size(72.dp)
                .padding(bottom = 24.dp)
        )
        
        Text(
            text = "Your Board is Empty",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Connect your first London station to see live departure signals.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = TflAmber),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(56.dp).fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Design Your Board", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
