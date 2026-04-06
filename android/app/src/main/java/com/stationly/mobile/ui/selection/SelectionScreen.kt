@file:OptIn(ExperimentalMaterial3Api::class)
package com.stationly.mobile.ui.selection

import androidx.compose.foundation.BorderStroke
import com.stationly.core.model.sdui.SduiAppScreen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiAppTheme
import com.stationly.core.model.sdui.SduiDropdownOption
import com.stationly.mobile.R
import androidx.compose.foundation.Image
import coil.compose.AsyncImage

@Composable
fun SelectionScreen(
    onNavigateToSummary: () -> Unit,
    viewModel: SelectionViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Handle back button to pop last selection instead of exiting immediately
    androidx.activity.compose.BackHandler(enabled = true) {
        if (uiState.selections.containsKey("mode")) {
            viewModel.popLastSelection()
        } else {
            onNavigateToSummary()
        }
    }

    // Base Colors
    val defaultBgColor = Color.Black
    val defaultPrimaryColor = Color(0xFFFFB81C) // Tfl Amber

    // Memoize dynamic SDUI colors from server layout
    val dynamicBgColor by remember(uiState.layout) {
        derivedStateOf {
            uiState.layout?.theme?.backgroundColor?.let {
                try { Color(android.graphics.Color.parseColor(it)) } catch (e: Exception) { defaultBgColor }
            } ?: defaultBgColor
        }
    }
    
    val dynamicPrimaryColor by remember(uiState.layout) {
        derivedStateOf {
            uiState.layout?.theme?.primaryColor?.let {
                try { Color(android.graphics.Color.parseColor(it)) } catch (e: Exception) { defaultPrimaryColor }
            } ?: defaultPrimaryColor
        }
    }

    // Direct navigation when success triggered
    LaunchedEffect(uiState.showSuccessDialog) {
        if (uiState.showSuccessDialog) {
            onNavigateToSummary()
            viewModel.dismissSuccessDialog()
        }
    }

    // Dynamic completion check
    val isFormComplete by remember(uiState.selections) {
        derivedStateOf {
            uiState.selections.containsKey("mode") &&
            uiState.selections.containsKey("station") &&
            uiState.selections.containsKey("line") &&
            uiState.selections.containsKey("direction")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(dynamicBgColor, Color(0xFF101010), Color(0xFF050505))
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { 
                                if (uiState.selections.containsKey("mode")) viewModel.popLastSelection() 
                                else onNavigateToSummary() 
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.05f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        
                        // Premium Static Header
                        val headerText = if (uiState.selections.containsKey("mode")) {
                            val modeId = uiState.selections["mode"]
                            val modeName = uiState.modes.find { it.id == modeId }?.label ?: "Board"
                            "New $modeName Board"
                        } else {
                            "New Board"
                        }

                        Text(
                            text = headerText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = isFormComplete,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Transparent,
                        tonalElevation = 8.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                    )
                                )
                                .padding(24.dp)
                                .navigationBarsPadding()
                        ) {
                            Button(
                                onClick = { 
                                    uiState.layout?.components
                                        ?.filterIsInstance<SduiAppComponent.Button>()
                                        ?.firstOrNull()?.let { viewModel.onActionTriggered(it.action) }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = dynamicPrimaryColor),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    val buttonData = uiState.layout?.components
                                        ?.filterIsInstance<SduiAppComponent.Button>()
                                        ?.firstOrNull()
                                    
                                    val buttonColor = if (buttonData?.color != null) {
                                        try { Color(android.graphics.Color.parseColor(buttonData.color)) } catch (e: Exception) { dynamicPrimaryColor }
                                    } else {
                                        dynamicPrimaryColor
                                    }

                                    Text(
                                        buttonData?.label ?: "Add Board",
                                        color = if (buttonColor == dynamicPrimaryColor) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
            ) {
                // 🎨 Elite SDUI-Driven Transition Logic
                val components = uiState.layout?.components ?: emptyList()
                val selectedModeId = uiState.selections["mode"]
                val currentTrack = uiState.currentTrack
                
                // Explicit stage logic based on the user's desired sequence
                val currentStage = remember(uiState.selections, uiState.currentTrack) {
                    when {
                        !uiState.selections.containsKey("mode") -> "MODE_PICKER"
                        !uiState.selections.containsKey("tracking_flow") -> "PATH_PICKER"
                        else -> "CONFIG_PICKER"
                    }
                }

                val activeComponent = components.find { it.id == "mode" || it.id == "tracking_flow" || it.id == "line" || it.id == "station" || it.id == "direction" } // For debugging/labels if needed

                // Header / Background effects could go here

                if (uiState.layout == null && uiState.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = dynamicPrimaryColor, strokeWidth = 3.dp)
                    }
                    return@Column
                }

                val context = LocalContext.current
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        // User allowed location: officially select the discovery path
                        viewModel.onSelectionChanged("tracking_flow", "discovery")
                        viewModel.fetchNearbyStations(modeId = selectedModeId)
                    }
                }

                // Auto-set track if it comes back from navigation or similar
                LaunchedEffect(uiState.selections, uiState.dropdownData) {
                    if (selectedModeId != null && currentTrack == null) {
                        if (uiState.selections.size > 1) viewModel.setCurrentTrack("manual")
                        else if (uiState.dropdownData.containsKey("station")) viewModel.setCurrentTrack("discovery")
                    }
                }

                // Main Transition Container
                AnimatedContent(
                    targetState = currentStage,
                    transitionSpec = {
                        if (targetState == "MODE_PICKER") {
                            fadeIn(tween(700)) + scaleIn(initialScale = 0.9f) togetherWith
                            fadeOut(tween(400)) + scaleOut(targetScale = 1.1f)
                        } else {
                            fadeIn(tween(600)) + slideInVertically(initialOffsetY = { it / 3 }) togetherWith
                            fadeOut(tween(400)) + slideOutVertically(targetOffsetY = { -it / 3 })
                        }
                    },
                    label = "stage_transition"
                ) { stage ->
                    when (stage) {
                        "MODE_PICKER" -> {
                            FloatingModeSelector(
                                modes = uiState.dropdownData["mode"] ?: emptyList(),
                                dynamicPrimaryColor = dynamicPrimaryColor,
                                onModeSelect = { viewModel.onSelectionChanged("mode", it.id) }
                            )
                        }
                        else -> {
                            // Stage 2: Path Picker OR Stage 3: Config Picker
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(bottom = 120.dp, top = 24.dp)
                            ) {
                                // Sticky-like Header for Selected Mode
                                item {
                                    val selectedMode = uiState.modes.find { it.id == selectedModeId }
                                    TopModeHeader(
                                        mode = selectedMode,
                                        dynamicPrimaryColor = dynamicPrimaryColor,
                                        onBack = { 
                                            viewModel.clearSelections()
                                            viewModel.setCurrentTrack(null)
                                        }
                                    )
                                }

                                if (stage == "PATH_PICKER") {
                                    item {
                                        val flowPicker = components.find { it is SduiAppComponent.FlowPicker } as? SduiAppComponent.FlowPicker
                                        if (flowPicker != null) {
                                            SduiFlowSelectionGrid(
                                                component = flowPicker,
                                                dynamicPrimaryColor = dynamicPrimaryColor,
                                                onSelect = { optionId ->
                                                    if (optionId == "discovery") {
                                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                                            viewModel.onSelectionChanged(flowPicker.id, optionId)
                                                            viewModel.setCurrentTrack("discovery")
                                                            viewModel.fetchNearbyStations(modeId = uiState.selections["mode"])
                                                        } else {
                                                            launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                                        }
                                                    } else {
                                                        viewModel.onSelectionChanged(flowPicker.id, optionId)
                                                        viewModel.setCurrentTrack("manual")
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                if (stage == "CONFIG_PICKER") {
                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (uiState.currentTrack == "discovery") "Discovery Mode" else "Manual Setup",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = dynamicPrimaryColor,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = if (uiState.currentTrack == "discovery") "Automatic signal detection" else "Network-wide browsing",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White.copy(alpha = 0.5f)
                                                )
                                            }
                                            
                                            // Modern Switch Path Chip
                                            Surface(
                                                onClick = {
                                                    viewModel.setCurrentTrack(null)
                                                },
                                                color = Color.White.copy(alpha = 0.05f),
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.SyncAlt, contentDescription = null, tint = dynamicPrimaryColor, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Switch", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }

                                    if (uiState.currentTrack == "discovery" && uiState.isLocating) {
                                        item {
                                             Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    CircularProgressIndicator(color = dynamicPrimaryColor, modifier = Modifier.size(32.dp))
                                                    Spacer(Modifier.height(16.dp))
                                                    Text("Finding nearby signals...", color = Color.White.copy(alpha = 0.4f))
                                                }
                                            }
                                        }
                                    }

                                    if (uiState.currentTrack == "discovery" && uiState.noNearbyStationsFound) {
                                        item {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                                color = Color.Red.copy(alpha = 0.1f),
                                                shape = RoundedCornerShape(20.dp),
                                                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f))
                                            ) {
                                                Column(modifier = Modifier.padding(24.dp)) {
                                                    Icon(Icons.Default.LocationOff, contentDescription = null, tint = Color.Red, modifier = Modifier.size(32.dp))
                                                    Spacer(Modifier.height(16.dp))
                                                    Text(
                                                        "No Signals Found Nearby",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        "We couldn't find any ${selectedModeId ?: "transport"} stations within range of your current location.",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = Color.White.copy(alpha = 0.7f)
                                                    )
                                                    Spacer(Modifier.height(20.dp))
                                                    Button(
                                                        onClick = { viewModel.setCurrentTrack("manual"); viewModel.onSelectionChanged("tracking_flow", "manual") },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                                        shape = RoundedCornerShape(12.dp)
                                                    ) {
                                                        Text("Try Manual Setup", color = Color.White)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Selection Logic for Dropdowns
                                    val components = uiState.layout?.components ?: emptyList()
                                    
                                    val displayComponents = if (uiState.currentTrack == "discovery") {
                                        components.sortedBy { 
                                            when(it.id) {
                                                "mode" -> 0
                                                "station" -> 1
                                                "line" -> 2
                                                "direction" -> 3
                                                else -> 4
                                            }
                                        }
                                    } else {
                                        components
                                    }

                                    displayComponents.forEach { component ->
                                        if (component is SduiAppComponent.Dropdown) {
                                            val isMode = component.id == "mode"
                                            
                                            if (!isMode) {
                                                val hasData = uiState.dropdownData[component.id]?.isNotEmpty() ?: false
                                                                                              val isDiscoveryStation = component.id == "station" && uiState.currentTrack == "discovery"
                                                val isDiscoveryLine = component.id == "line" && uiState.currentTrack == "discovery"
                                                val isDiscoveryDirection = component.id == "direction" && uiState.currentTrack == "discovery"
 
                                                val effectivelySatisfied = if (uiState.currentTrack == "discovery") {
                                                    when {
                                                        isDiscoveryStation -> true
                                                        isDiscoveryLine -> uiState.selections.containsKey("station")
                                                        isDiscoveryDirection -> uiState.selections.containsKey("line")
                                                        else -> uiState.selections.containsKey(component.dependsOn)
                                                    }
                                                } else {
                                                    component.dependsOn == null || uiState.selections.containsKey(component.dependsOn)
                                                }

                                                val shouldShow = effectivelySatisfied

                                                item(key = component.id) {
                                                    AnimatedVisibility(
                                                        visible = shouldShow,
                                                        enter = fadeIn() + expandVertically(),
                                                        exit = fadeOut() + shrinkVertically()
                                                    ) {
                                                        SduiSelectionCard(
                                                            label = if (isDiscoveryStation && uiState.isLocating) "Detecting Nearby Stations..." else component.label,
                                                            primaryColor = dynamicPrimaryColor,
                                                            options = uiState.dropdownData[component.id] ?: emptyList(),
                                                            selectedId = uiState.selections[component.id],
                                                            onSelect = { option ->
                                                                viewModel.onSelectionChanged(component.id, option.id)
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Premium Saving Overlay
        AnimatedVisibility(
            visible = uiState.isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SavingOverlay(dynamicPrimaryColor, uiState.layout?.loadingMessage ?: "Preparing Your Live Board")
        }

        // ── Service Unavailable Overlay (Must be at the very end to render on top) ──
        androidx.compose.animation.AnimatedVisibility(
            visible = uiState.layout == null && uiState.isBackendOffline,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(300))
        ) {
            com.stationly.mobile.ui.common.ServiceUnavailableScreen(
                context = "selection",
                overridingErrorMessage = uiState.error,
                onRetry = { viewModel.retryLoad() },
                onDismiss = { onNavigateToSummary() } // Go back if stuck
            )
        }
    }
}

@Composable
private fun SduiSelectionCard(
    label: String,
    primaryColor: Color,
    options: List<SduiDropdownOption>,
    selectedId: String?,
    onSelect: (SduiDropdownOption) -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    val scale by animateFloatAsState(
        targetValue = if (showSheet) 0.98f else 1f,
        label = "click_scale"
    )

    Surface(
        onClick = { showSheet = true },
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(scaleX = scale, scaleY = scale),
        color = Color(0xFF030303).copy(alpha = 0.6f),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selectedId != null) 1.5.dp else 1.dp,
            color = if (selectedId != null) primaryColor.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = primaryColor,
                    letterSpacing = 1.sp
                )
                if (selectedId != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = primaryColor, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val selectedOption = options.find { it.id == selectedId }
                val labelText = selectedOption?.label ?: "Select..."
                
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (selectedOption?.iconUrl != null) {
                        AsyncImage(
                            model = selectedOption.iconUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(32.dp)
                                .padding(end = 12.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                    val lines = labelText.split("\n")
                        lines.forEachIndexed { index, line ->
                            Text(
                                text = line,
                                color = if (selectedId != null) Color.White else Color.White.copy(alpha = 0.3f),
                                style = if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                                fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        
                        val secondary = selectedOption?.secondaryLabel
                        if (secondary != null) {
                            Text(
                                text = secondary,
                                color = primaryColor.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    if (options.isEmpty()) {
                        Text(
                            text = "Loading data...",
                            color = primaryColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
                
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (selectedId != null) primaryColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                            CircleShape
                        )
                        .border(1.dp, if (selectedId != null) primaryColor.copy(alpha = 0.4f) else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = if (selectedId != null) primaryColor else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showSheet) {
        SelectionBottomSheet(
            title = label,
            primaryColor = primaryColor,
            options = options,
            selectedId = selectedId,
            sheetState = sheetState,
            onDismiss = { showSheet = false },
            onSelect = { 
                onSelect(it)
                showSheet = false
            }
        )
    }
}

@Composable
private fun SavingOverlay(primaryColor: Color, loadingText: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "saving_anim")

    // The original train sliding left-right
    val slide by infiniteTransition.animateFloat(
        initialValue = -56f,
        targetValue = 56f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "slide"
    )

    // The original pulsing glow circle
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Animated ellipsis for the subtitle
    val dotPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots"
    )
    val dots = ".".repeat(dotPhase.toInt().coerceIn(0, 3))

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Blocks ALL touch events so dropdowns/buttons under this can't be triggered
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent() } }
            }
            .background(Color.Black.copy(alpha = 0.96f))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Train animation box
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                // Track rail
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.Center)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.15f),
                                    Color.White.copy(alpha = 0.15f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Pulsing glow ring (behind the icon)
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            scaleX = glowScale
                            scaleY = glowScale
                            alpha = (2f - glowScale) * 0.25f
                        }
                        .background(primaryColor, CircleShape)
                )

                // Sliding train icon
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier
                        .size(52.dp)
                        .offset(x = slide.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = loadingText,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Establishing live connection$dots",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Thin amber rail — looks like a TfL status indicator
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = primaryColor,
                trackColor = primaryColor.copy(alpha = 0.12f)
            )
        }
    }
}

@Composable
private fun SelectionBottomSheet(
    title: String,
    primaryColor: Color,
    options: List<SduiDropdownOption>,
    selectedId: String?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSelect: (SduiDropdownOption) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.DarkGray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            
            Text(
                text = "Please select an option below",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
            )

            if (options.size > 10) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    placeholder = { Text("Search stations...", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = primaryColor) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = null, tint = Color.Gray)
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryColor,
                        unfocusedBorderColor = Color.DarkGray,
                        focusedContainerColor = Color(0xFF252525),
                        unfocusedContainerColor = Color(0xFF252525),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            val filteredOptions = options.filter {
                it.label.contains(searchQuery, ignoreCase = true)
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filteredOptions,
                    key = { it.id }
                ) { option ->
                    val isSelected = option.id == selectedId
                    
                    Surface(
                        onClick = { onSelect(option) },
                        color = if (isSelected) primaryColor.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) primaryColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (option.iconUrl != null) {
                                AsyncImage(
                                    model = option.iconUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .padding(end = 12.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                val lines = option.label.split("\n")
                                lines.forEachIndexed { index, line ->
                                    Text(
                                        text = line,
                                        color = if (isSelected) primaryColor else Color.White,
                                        style = if (index == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                                        lineHeight = if (index == 0) 24.sp else 20.sp
                                    )
                                }
                                
                                val secondary = option.secondaryLabel
                                if (secondary != null) {
                                    Surface(
                                        color = if (isSelected) primaryColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                                        shape = CircleShape,
                                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                                    ) {
                                        Text(
                                            text = secondary,
                                            color = if (isSelected) primaryColor else Color.White.copy(alpha = 0.6f),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = primaryColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
                
                if (filteredOptions.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No results found", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SduiLocationCard(
    label: String,
    primaryColor: Color,
    isLocating: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = Color(0xFF111111),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(primaryColor.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLocating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = primaryColor,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = label,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isLocating) "Finding nearby stations..." else "Tap to use current location",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun FloatingModeSelector(
    modes: List<SduiDropdownOption>,
    dynamicPrimaryColor: Color,
    onModeSelect: (SduiDropdownOption) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (modes.isEmpty()) {
            CircularProgressIndicator(color = dynamicPrimaryColor)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalArrangement = Arrangement.spacedBy(48.dp),
                modifier = Modifier
                    .padding(24.dp)
                    .wrapContentHeight(), // Crucial for vertically centering the entire grid nicely
                contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
            ) {
                items(modes) { mode ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null, 
                                onClick = { onModeSelect(mode) }
                            )
                    ) {
                        if (!mode.iconUrl.isNullOrEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(116.dp)
                                    .background(Color.White, CircleShape)
                                    .border(2.dp, dynamicPrimaryColor.copy(alpha = 0.5f), CircleShape)
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = mode.iconUrl,
                                    contentDescription = mode.label,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(116.dp)
                                    .background(Color.White, CircleShape)
                                    .border(2.dp, dynamicPrimaryColor.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(mode.label.take(1), fontSize = 42.sp, color = Color.Black)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = mode.label, 
                            color = Color.White, 
                            style = MaterialTheme.typography.titleLarge, 
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TopModeHeader(mode: SduiDropdownOption?, dynamicPrimaryColor: Color, onBack: () -> Unit) {
    if (mode == null) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Selected Mode", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Text(mode.label, color = dynamicPrimaryColor, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SduiFlowSelectionGrid(
    component: SduiAppComponent.FlowPicker,
    dynamicPrimaryColor: Color,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        component.options.forEach { option ->
            Surface(
                onClick = { onSelect(option.id) },
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    val icon = when(option.icon) {
                        "gps_fixed" -> Icons.Default.LocationOn
                        "search" -> Icons.Default.Search
                        else -> Icons.Default.Settings
                    }
                    Icon(icon, contentDescription = null, tint = if (option.id == "discovery") dynamicPrimaryColor else Color.Gray, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(option.label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (option.description != null) {
                            Text(option.description!!, color = Color.LightGray.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}