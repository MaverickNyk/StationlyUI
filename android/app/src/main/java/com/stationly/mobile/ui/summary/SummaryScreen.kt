package com.stationly.mobile.ui.summary

import android.os.SystemClock
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.mobile.R
import com.stationly.mobile.ui.theme.TflAmber
import com.stationly.mobile.ui.theme.BoardBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    onNavigateToSelection: () -> Unit,
    viewModel: SummaryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val selections by viewModel.selections.collectAsState()
    
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 12.dp).size(20.dp),
                                strokeWidth = 2.dp,
                                color = TflAmber
                            )
                        } else {
                            IconButton(onClick = { viewModel.refreshAll() }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Refresh, "Refresh", tint = TflAmber)
                            }
                        }
                        if (selections.isEmpty()) {
                            IconButton(onClick = onNavigateToSelection, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Add, "Add Station", tint = TflAmber)
                            }
                        } else {
                            IconButton(onClick = onNavigateToSelection, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Edit, "Edit Station", tint = TflAmber)
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
                .background(Color.Black)
                .padding(padding)
        ) {
            uiState.error?.let { error ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            if (selections.isEmpty()) {
                EmptyState(onAddSelection = onNavigateToSelection)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    items(selections, key = { it.station }) { selection ->
                        DepartureCard(
                            selection = selection,
                            predictions = viewModel.getPredictionsForSelection(selection),
                            hasPredictions = viewModel.hasPredictions(selection),
                            lineStatus = viewModel.getLineStatusForSelection(selection),
                            lastUpdated = uiState.lastUpdated,
                            onDelete = { viewModel.deleteSelection(selection) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DepartureCard(
    selection: UserSelection,
    predictions: List<PredictionDisplay>,
    hasPredictions: Boolean,
    lineStatus: String?,
    lastUpdated: Long,
    onDelete: () -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            val view = LayoutInflater.from(context).inflate(R.layout.widget_departure_board, null, false) as LinearLayout
            
            // Setup static layout stuff
            val deleteBtn = view.findViewById<ImageView>(R.id.btn_settings)
            deleteBtn.setImageResource(R.drawable.ic_delete)
            deleteBtn.contentDescription = "Delete Selection"
            
            val statusReason = view.findViewById<TextView>(R.id.status_reason)
            statusReason.isSelected = true // enable marquee locally
            
            view
        },
        update = { view ->
            val context = view.context
            
            view.findViewById<View>(R.id.btn_settings).setOnClickListener { onDelete() }
            
            view.findViewById<TextView>(R.id.line_name).text = selection.stationName
            
            // Last Updated Chronometer
            val chrono = view.findViewById<Chronometer>(R.id.last_updated_timer)
            if (lastUpdated > 0L) {
                chrono.visibility = View.VISIBLE
                chrono.base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - lastUpdated)
                chrono.start()
            } else {
                chrono.visibility = View.GONE
                chrono.stop()
            }
            
            // Status row
            val statusContainer = view.findViewById<View>(R.id.status_container)
            val severityText = view.findViewById<TextView>(R.id.status_severity)
            val reasonText = view.findViewById<TextView>(R.id.status_reason)
            
            if (lineStatus != null) {
                statusContainer.visibility = View.VISIBLE
                val severity = if (lineStatus.contains(":")) lineStatus.substringBefore(":") else lineStatus
                val reason = if (lineStatus.contains(":")) lineStatus.substringAfter(":") else ""
                severityText.text = severity
                reasonText.text = reason
                reasonText.isSelected = true
            } else {
                statusContainer.visibility = View.GONE
            }
            
            // Rows
            val rowsContainer = view.findViewById<LinearLayout>(R.id.rows_container)
            val waitingContainer = view.findViewById<View>(R.id.waiting_container)
            
            rowsContainer.removeAllViews()
            
            if (hasPredictions && predictions.isNotEmpty()) {
                waitingContainer.visibility = View.GONE
                
                val grouped = predictions.groupBy { it.platform }
                grouped.forEach { (platform, platformPreds) ->
                    val header = LayoutInflater.from(context).inflate(R.layout.widget_platform_header, rowsContainer, false)
                    val combinedTitle = if (selection.line.isNotEmpty()) {
                        "${selection.line.replaceFirstChar { it.uppercase() }} : $platform"
                    } else platform
                    header.findViewById<TextView>(R.id.platform_name).text = combinedTitle
                    rowsContainer.addView(header)
                    
                    for (i in 0 until 3) {
                        val row = LayoutInflater.from(context).inflate(R.layout.widget_departure_row, rowsContainer, false)
                        val numView = row.findViewById<TextView>(R.id.departure_number)
                        val destView = row.findViewById<TextView>(R.id.destination_text)
                        val etaView = row.findViewById<TextView>(R.id.eta_text)
                        
                        numView.text = (i + 1).toString()
                        
                        if (i < platformPreds.size) {
                            val pred = platformPreds[i]
                            destView.text = pred.destination
                            etaView.text = pred.eta
                        } else {
                            destView.text = "-"
                            etaView.text = ""
                        }
                        rowsContainer.addView(row)
                    }
                }
            } else if (!hasPredictions) {
                waitingContainer.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.funny_message).text = "🚇 Fetching live signals..."
                view.findViewById<Chronometer>(R.id.countdown).visibility = View.GONE
            } else {
                waitingContainer.visibility = View.GONE
                val header = LayoutInflater.from(context).inflate(R.layout.widget_platform_header, rowsContainer, false)
                header.findViewById<TextView>(R.id.platform_name).text = "Service Update"
                rowsContainer.addView(header)
                
                val row = LayoutInflater.from(context).inflate(R.layout.widget_departure_row, rowsContainer, false)
                row.findViewById<TextView>(R.id.departure_number).text = "-"
                row.findViewById<TextView>(R.id.destination_text).text = "All trains have departed!"
                row.findViewById<TextView>(R.id.eta_text).text = ""
                rowsContainer.addView(row)
            }
        }
    )
}

@Composable
private fun EmptyState(onAddSelection: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "No Stations Selected",
                color = TflAmber,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Add your first station to see real-time departure information.",
                color = TflAmber.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onAddSelection,
                colors = ButtonDefaults.buttonColors(containerColor = TflAmber, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Station", fontWeight = FontWeight.Bold)
            }
        }
    }
}
