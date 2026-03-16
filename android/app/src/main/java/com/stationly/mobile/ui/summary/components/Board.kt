package com.stationly.mobile.ui.summary.components

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SduiWidgetComponent
import com.stationly.core.model.sdui.SduiWidgetPayload
import com.stationly.mobile.R
import com.stationly.mobile.ui.theme.TflAmber
import com.stationly.mobile.util.SduiThemeManager

@Composable
fun Board(
    selection: UserSelection,
    predictions: List<PredictionDisplay>,
    hasPredictions: Boolean,
    lineStatus: String?,
    sduiPayload: SduiWidgetPayload? = null,
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
                    
                    // Hide settings button
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
                        val theme = sduiPayload.theme
                        theme?.primaryColor?.let {
                            dynTextColor = SduiThemeManager.parseColor(it, dynTextColor)
                            view.findViewById<TextView>(R.id.line_name).setTextColor(dynTextColor)
                            chrono.setTextColor(dynTextColor)
                        }
                        
                        theme?.backgroundColor?.let {
                            val dynBgColor = SduiThemeManager.parseColor(it, android.graphics.Color.BLACK)
                            view.findViewById<LinearLayout>(R.id.departure_board).setBackgroundColor(dynBgColor)
                        }

                        view.findViewById<TextView>(R.id.line_name).text = sduiPayload.title
                        waitingContainer.visibility = View.GONE
                        
                        sduiPayload.components.forEach { component ->
                            when (component) {
                                is SduiWidgetComponent.Header -> {
                                    val header = LayoutInflater.from(context).inflate(R.layout.widget_platform_header, rowsContainer, false)
                                    val pTv = header.findViewById<TextView>(R.id.platform_name)
                                    pTv.text = component.title
                                    val headerColor = SduiThemeManager.parseColor(component.color, dynTextColor)
                                    pTv.setTextColor(headerColor)
                                    rowsContainer.addView(header)
                                }
                                is SduiWidgetComponent.Row -> {
                                    val row = LayoutInflater.from(context).inflate(R.layout.widget_departure_row, rowsContainer, false)
                                    val nTv = row.findViewById<TextView>(R.id.departure_number)
                                    val dTv = row.findViewById<TextView>(R.id.destination_text)
                                    val eTv = row.findViewById<TextView>(R.id.eta_text)

                                    nTv.text = component.index
                                    dTv.text = component.destination
                                    eTv.text = component.eta

                                    nTv.setTextColor(dynTextColor)
                                    dTv.setTextColor(dynTextColor)
                                    val etaColor = SduiThemeManager.parseColor(component.etaColor, dynTextColor)
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
                                is SduiWidgetComponent.Message -> {
                                    val row = LayoutInflater.from(context).inflate(R.layout.widget_departure_row, rowsContainer, false)
                                    val nTv = row.findViewById<TextView>(R.id.departure_number)
                                    val dTv = row.findViewById<TextView>(R.id.destination_text)
                                    row.findViewById<TextView>(R.id.eta_text).text = ""
                                    nTv.text = "-"
                                    dTv.text = component.text
                                    
                                    val msgColor = SduiThemeManager.parseColor(component.color, dynTextColor)
                                    
                                    nTv.setTextColor(dynTextColor)
                                    dTv.setTextColor(msgColor)
                                    rowsContainer.addView(row)
                                }
                                else -> {}
                            }
                        }
                    } else {
                        view.findViewById<TextView>(R.id.line_name).text = selection.stationName
                        waitingContainer.visibility = View.GONE
                        
                        val legacyRows = com.stationly.core.util.GlobalBoardProcessor.prepareLegacyRows(
                            predictions,
                            selection.line,
                            true
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
