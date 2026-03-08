@file:OptIn(ExperimentalMaterial3Api::class)
package com.stationly.mobile.ui.selection

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import android.util.Log
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

@Composable
fun SelectionScreen(
    onNavigateToSummary: () -> Unit,
    viewModel: SelectionViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
    val isFormComplete by remember(uiState.selections, uiState.layout) {
        derivedStateOf {
            val dropdowns = uiState.layout?.components?.filterIsInstance<SduiAppComponent.Dropdown>() ?: emptyList()
            dropdowns.isNotEmpty() && dropdowns.all { uiState.selections.containsKey(it.id) }
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
                            onClick = onNavigateToSummary,
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
                        
                        Surface(
                            color = dynamicPrimaryColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, dynamicPrimaryColor.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "Live Connect",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = dynamicPrimaryColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                if (uiState.isLoading && !uiState.isSaving) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = dynamicPrimaryColor, strokeWidth = 3.dp)
                    }
                    return@Column
                }

                // Log layout components on load
                LaunchedEffect(uiState.layout) {
                    uiState.layout?.components?.forEach { component ->
                        Log.d("SDUI", "Loaded component: ${component.javaClass.simpleName} with ID: ${
                            when (component) {
                                is SduiAppComponent.Text -> "N/A"
                                is SduiAppComponent.Input -> component.id
                                is SduiAppComponent.Dropdown -> component.id
                                is SduiAppComponent.Button -> component.id
                                is SduiAppComponent.Image -> component.id
                            }
                        }")
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    val components = uiState.layout?.components ?: emptyList()
                    
                    components.forEachIndexed { index, component ->
                        item {
                            when (component) {
                                is SduiAppComponent.Text -> {
                                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                        Text(
                                            text = component.text,
                                            style = if (component.style == "title") 
                                                MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black) 
                                                else MaterialTheme.typography.bodyLarge,
                                            color = if (component.style == "title") Color.White else Color.Gray,
                                            lineHeight = if (component.style == "title") 40.sp else 24.sp,
                                            letterSpacing = if (component.style == "title") (-1).sp else 0.sp
                                        )
                                        if (component.style == "title") {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .width(60.dp)
                                                    .height(4.dp)
                                                    .background(dynamicPrimaryColor, RoundedCornerShape(2.dp))
                                            )
                                        }
                                    }
                                }
                                is SduiAppComponent.Dropdown -> {
                                    val shouldShow = component.dependsOn == null || uiState.selections.containsKey(component.dependsOn)
                                    val dropdownIndex = index // Use for staggered animation
                                    
                                    AnimatedVisibility(
                                        visible = shouldShow,
                                        enter = fadeIn() + expandVertically() + slideInHorizontally(),
                                        exit = fadeOut() + shrinkVertically()
                                    ) {
                                        SduiSelectionCard(
                                            label = component.label,
                                            primaryColor = dynamicPrimaryColor,
                                            options = uiState.dropdownData[component.id] ?: emptyList(),
                                            selectedId = uiState.selections[component.id],
                                            onSelect = { option ->
                                                viewModel.onSelectionChanged(component.id, option.id)
                                            }
                                        )
                                    }
                                }
                                is SduiAppComponent.Image -> {
                                    // Image rendering with placeholder support or coil (omitted for brevity, just spacer placeholder)
                                    Spacer(modifier = Modifier.height(component.height?.dp ?: 100.dp))
                                }
                                is SduiAppComponent.Input -> {
                                    val currentValue = uiState.selections[component.id] ?: component.text ?: ""
                                    androidx.compose.material3.OutlinedTextField(
                                        value = currentValue,
                                        onValueChange = { newValue ->
                                            viewModel.onSelectionChanged(component.id, newValue)
                                        },
                                        label = { Text(component.label) },
                                        placeholder = component.placeholder?.let { { Text(it) } },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = dynamicPrimaryColor,
                                            focusedLabelColor = dynamicPrimaryColor,
                                            cursorColor = dynamicPrimaryColor
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true
                                    )
                                }
                                is SduiAppComponent.Button -> {
                                    // Managed by the bottom bar
                                }
                            }
                        }
                    }
                }
            }
        }

        // Premium Saving Overlay
        AnimatedVisibility(
            visible = uiState.isSaving,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SavingOverlay(dynamicPrimaryColor, uiState.layout?.loadingMessage ?: "Preparing Your Live Board")
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
        color = Color(0xFF111111),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selectedId != null) 2.dp else 1.dp,
            color = if (selectedId != null) primaryColor else Color.White.copy(alpha = 0.1f)
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
                    if (options.isEmpty()) {
                        Text(
                            text = "Loading data...",
                            color = primaryColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
                
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (selectedId != null) primaryColor else Color.White.copy(alpha = 0.05f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = if (selectedId != null) Color.Black else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
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
    val infiniteTransition = rememberInfiniteTransition(label = "train")
    val slide by infiniteTransition.animateFloat(
        initialValue = -50f,
        targetValue = 50f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "slide"
    )
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color.White.copy(alpha = 0.2f))
                )
                
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier
                        .size(48.dp)
                        .offset(x = slide.dp)
                )
                
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = (2f - scale) * 0.3f
                        }
                        .background(primaryColor, CircleShape)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = loadingText,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Establishing high-frequency connection...",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            CircularProgressIndicator(
                color = primaryColor,
                strokeWidth = 3.dp,
                modifier = Modifier.size(24.dp)
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
                items(filteredOptions) { option ->
                    val isSelected = option.id == selectedId
                    
                    Surface(
                        onClick = { onSelect(option) },
                        color = if (isSelected) primaryColor.copy(alpha = 0.12f) else Color(0xFF252525),
                        shape = RoundedCornerShape(12.dp),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, primaryColor) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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