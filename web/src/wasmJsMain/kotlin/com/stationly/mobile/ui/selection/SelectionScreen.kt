package com.stationly.mobile.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.core.model.UserSelection
import com.stationly.mobile.ui.theme.*

/**
 * SelectionScreen - Web implementation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionScreen(
    onNavigateBack: () -> Unit,
    onSelectionSaved: () -> Unit,
    viewModel: SelectionViewModel = remember { SelectionViewModel() }
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedSelections by viewModel.savedSelections.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Station") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Error banner
            uiState.error?.let { error ->
                ErrorBanner(
                    message = error,
                    onDismiss = { viewModel.clearError() }
                )
            }
            
            // Success dialog
            if (uiState.showSuccessDialog) {
                SuccessDialog(
                    message = uiState.success ?: "Selection saved!",
                    onDismiss = { 
                        viewModel.dismissSuccessDialog()
                        onSelectionSaved()
                    }
                )
            }
            
            // Loading indicator
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            
            // Selection flow
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Mode selection
                if (uiState.modes.isNotEmpty() && uiState.selectedMode == null) {
                    item {
                        SelectionSection(
                            title = "1. Select Mode",
                            options = uiState.modes,
                            onSelect = { viewModel.onModeSelected(it) }
                        )
                    }
                }
                
                // Line selection
                if (uiState.availableLines.isNotEmpty() && uiState.selectedLine == null) {
                    item {
                        SelectionSection(
                            title = "2. Select Line",
                            options = uiState.availableLines,
                            onSelect = { viewModel.onLineSelected(it) }
                        )
                    }
                }
                
                // Direction selection
                if (uiState.availableDirections.isNotEmpty() && uiState.selectedDirection == null) {
                    item {
                        SelectionSection(
                            title = "3. Select Direction",
                            options = uiState.availableDirections,
                            onSelect = { viewModel.onDirectionSelected(it) }
                        )
                    }
                }
                
                // Station selection
                if (uiState.availableStations.isNotEmpty() && uiState.selectedStation == null) {
                    item {
                        StationSelectionSection(
                            title = "4. Select Station",
                            options = uiState.availableStations,
                            onSelect = { stationString ->
                                // Parse station ID from "Name (ID)" format
                                val stationId = stationString.substringAfterLast("(").removeSuffix(")")
                                val stationName = stationString.substringBefore(" (")
                                viewModel.onStationSelected(stationId, stationName)
                            }
                        )
                    }
                }
                
                // Save button
                if (uiState.selectedMode != null && 
                    uiState.selectedLine != null && 
                    uiState.selectedStation != null && 
                    uiState.selectedDirection != null) {
                    item {
                        Button(
                            onClick = { viewModel.saveSelection() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Selection")
                        }
                    }
                }
                
                // Show current selection summary
                if (uiState.selectedMode != null || uiState.selectedLine != null || 
                    uiState.selectedStation != null || uiState.selectedDirection != null) {
                    item {
                        CurrentSelectionCard(
                            mode = uiState.selectedMode,
                            line = uiState.selectedLine,
                            direction = uiState.selectedDirection,
                            station = uiState.selectedStationName
                        )
                    }
                }
                
                // Saved selections
                if (savedSelections.isNotEmpty()) {
                    item {
                        Text(
                            text = "Saved Stations",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    
                    items(savedSelections) { selection ->
                        SavedSelectionCard(
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
private fun SelectionSection(
    title: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        options.forEach { option ->
            Text(
                text = option,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge
            )
            if (option != options.last()) {
                Divider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    thickness = 1.dp
                )
            }
        }
    }
}

@Composable
private fun StationSelectionSection(
    title: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        LazyColumn(
            modifier = Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(options) { option ->
                Text(
                    text = option,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (option != options.last()) {
                    Divider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        thickness = 1.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentSelectionCard(
    mode: String?,
    line: String?,
    direction: String?,
    station: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Current Selection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            mode?.let {
                Text("Mode: $it", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            line?.let {
                Text("Line: $it", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            direction?.let {
                Text("Direction: $it", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            station?.let {
                Text("Station: $it", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun SavedSelectionCard(
    selection: UserSelection,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${selection.line} • ${selection.direction}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(18.dp)
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
        color = MaterialTheme.colorScheme.errorContainer
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
                Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun SuccessDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
        title = {
            Text("Success")
        },
        text = {
            Text(message)
        }
    )
}