package com.stationly.mobile.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.core.model.UserSelection
import com.stationly.mobile.ui.theme.*
import com.stationly.mobile.R
import androidx.compose.foundation.Image

/**
 * SelectionScreen - Jetpack Compose UI for station selection
 * 
 * Modern, production-ready UI with:
 * - Material 3 design
 * - Smooth animations
 * - Error handling
 * - Success feedback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionScreen(
    onNavigateToSummary: () -> Unit,
    viewModel: SelectionViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedSelections by viewModel.savedSelections.collectAsState()
    
    var showModeDropdown by remember { mutableStateOf(false) }
    var showLineDropdown by remember { mutableStateOf(false) }
    var showStationDropdown by remember { mutableStateOf(false) }
    var showDirectionDropdown by remember { mutableStateOf(false) }
    
    // Show success dialog
    if (uiState.showSuccessDialog) {
        SuccessDialog(
            onDismiss = { viewModel.dismissSuccessDialog() },
            onContinue = {
                viewModel.dismissSuccessDialog()
                onNavigateToSummary()
            }
        )
    }
    
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Surface(
                color = Color.Black,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.stationly_logo),
                        contentDescription = "Logo",
                        modifier = Modifier.size(30.dp).padding(end = 8.dp)
                    )
                    Text(
                        text = "Stationly", 
                        color = TflAmber, 
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ) 
                }
            }
        },
        floatingActionButton = {
            if (uiState.selectedMode != null && uiState.selectedLine != null &&
                uiState.selectedStation != null && uiState.selectedDirection != null) {
                FloatingActionButton(
                    onClick = { viewModel.saveSelection() },
                    containerColor = TflAmber,
                    contentColor = Color.Black
                ) {
                    Icon(Icons.Default.Check, "Save Selection")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding)
                .padding(16.dp)
        ) {
            // Error message
            uiState.error?.let { error ->
                ErrorBanner(
                    message = error,
                    onDismiss = { viewModel.clearError() }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Loading indicator
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = TflAmber)
                }
                return@Column
            }
            
            // Selection Form
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Mode Selection
                item {
                    SelectionCard(
                        title = "1. Select Mode",
                        selectedValue = uiState.selectedMode,
                        options = uiState.modes,
                        showDropdown = showModeDropdown,
                        onDropdownToggle = { showModeDropdown = it },
                        onOptionSelected = { viewModel.onModeSelected(it) }
                    )
                }
                
                // Line Selection
                if (uiState.selectedMode != null) {
                    item {
                        SelectionCard(
                            title = "2. Select Line",
                            selectedValue = uiState.selectedLine,
                            options = uiState.availableLines,
                            showDropdown = showLineDropdown,
                            onDropdownToggle = { showLineDropdown = it },
                            onOptionSelected = { viewModel.onLineSelected(it) }
                        )
                    }
                }
                
                // Direction Selection
                if (uiState.selectedLine != null) {
                    item {
                        SelectionCard(
                            title = "3. Select Direction",
                            selectedValue = uiState.selectedDirection,
                            options = uiState.availableDirections,
                            showDropdown = showDirectionDropdown,
                            onDropdownToggle = { showDirectionDropdown = it },
                            onOptionSelected = { viewModel.onDirectionSelected(it) }
                        )
                    }
                }

                // Station Selection
                if (uiState.selectedDirection != null) {
                    item {
                        SelectionCard(
                            title = "4. Select Station",
                            selectedValue = uiState.selectedStationName ?: uiState.selectedStation,
                            options = uiState.availableStations,
                            showDropdown = showStationDropdown,
                            onDropdownToggle = { showStationDropdown = it },
                            onOptionSelected = { 
                                // Extract station ID from format "Station Name (ID)"
                                val stationId = it.substringAfterLast(" (").removeSuffix(")")
                                val stationName = it.substringBefore(" (")
                                viewModel.onStationSelected(stationId, stationName)
                            }
                        )
                    }
                }
                
                // Saved Selections
                if (savedSelections.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Saved Selections",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = TflAmber,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    
                    items(savedSelections) { selection ->
                        SavedSelectionItem(
                            selection = selection,
                            onDelete = { viewModel.deleteSelection(selection) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionCard(
    title: String,
    selectedValue: String?,
    options: List<String>,
    showDropdown: Boolean,
    onDropdownToggle: (Boolean) -> Unit,
    onOptionSelected: (String) -> Unit
) {
    
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onDropdownToggle(!showDropdown) },
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TflAmber
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                   modifier = Modifier.fillMaxWidth(),
                   horizontalArrangement = Arrangement.SpaceBetween,
                   verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedValue ?: "Select...",
                        color = if (selectedValue != null) TflAmber else TflAmber.copy(alpha=0.5f),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onDropdownToggle(!showDropdown) }) {
                        Icon(Icons.Default.ArrowDropDown, "Dropdown", tint = TflAmber)
                    }
                }
                
                // Dropdown Menu
                if (showDropdown && options.isNotEmpty()) {
                    DropdownMenu(
                        expanded = showDropdown,
                        onDismissRequest = { onDropdownToggle(false) },
                        modifier = Modifier.fillMaxWidth().background(SurfaceDark)
                    ) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option, color = TflAmber) },
                                onClick = {
                                    onOptionSelected(option)
                                    onDropdownToggle(false)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedSelectionItem(
    selection: UserSelection,
    onDelete: () -> Unit
) {
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selection.stationName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TflAmber
                )
                Text(
                    text = "${selection.line} • ${selection.direction}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TflAmber.copy(alpha = 0.7f)
                )
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Delete, "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun SuccessDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text("View Departures")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Add More")
            }
        },
        title = {
            Text("Selection Saved!")
        },
        text = {
            Text("Your station selection has been saved. You can now view real-time departure information.")
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Success",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    )
}