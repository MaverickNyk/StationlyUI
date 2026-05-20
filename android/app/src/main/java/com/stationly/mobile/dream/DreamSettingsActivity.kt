package com.stationly.mobile.dream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.core.platform.Platform
import com.stationly.mobile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuration screen for the Daydream. Reachable via the gear icon next to
 * "Stationly" in Settings → Display → Screen saver — wired through
 * `stationly_dream_info.xml`'s settingsActivity attribute.
 *
 * Two settings:
 *   - Clock style: Digital / Analog / None
 *   - Station to show (if the user has multiple boards on the home screen)
 */
class DreamSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = colorResource(R.color.tfl_amber),
                background = Color(0xFF0A0A0A),
                surface = Color(0xFF141414),
                onBackground = Color.White,
                onSurface = Color.White,
            )) {
                DreamSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DreamSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val amber = colorResource(R.color.tfl_amber)

    var clockStyle by remember { mutableStateOf(DreamSettings.getClockStyle(context)) }
    var stationId  by remember { mutableStateOf(DreamSettings.getStationId(context)) }

    // Pull the user's saved stations on the IO thread so we can show them
    // in the picker. Display-only — settings work with just a station id.
    var stations by remember { mutableStateOf<List<com.stationly.core.model.UserSelection>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        stations = withContext(Dispatchers.IO) { Platform.sqlStorage.getAllSelections() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("Screensaver", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF0A0A0A),
                    titleContentColor = Color.White,
                )
            )
        },
        containerColor = Color(0xFF0A0A0A),
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(horizontal = 20.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Clock style
            SectionLabel("Clock style")
            ClockStyle.entries.forEach { style ->
                SettingRow(
                    title = style.displayName,
                    subtitle = when (style) {
                        ClockStyle.DIGITAL -> "Bold signage numerals"
                        ClockStyle.ANALOG  -> "TfL-roundel face · classic"
                    },
                    selected = style == clockStyle,
                    accent = amber,
                    onClick = {
                        clockStyle = style
                        scope.launch { DreamSettings.setClockStyle(context, style) }
                    }
                )
            }

            // Station picker — only meaningful if the user has more than one
            if (stations.size > 1) {
                SectionLabel("Station to display")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        SettingRow(
                            title = "Auto (first board)",
                            subtitle = "Match the top board on your home screen",
                            selected = stationId == null,
                            accent = amber,
                            onClick = {
                                stationId = null
                                scope.launch { DreamSettings.setStationId(context, null) }
                            }
                        )
                    }
                    items(stations, key = { it.station + it.line }) { sel ->
                        SettingRow(
                            title = sel.stationName,
                            subtitle = "${sel.line.replaceFirstChar { it.uppercase() }} · " +
                                       sel.direction.replaceFirstChar { it.uppercase() },
                            selected = stationId == sel.station,
                            accent = amber,
                            onClick = {
                                stationId = sel.station
                                scope.launch { DreamSettings.setStationId(context, sel.station) }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                text = "Pick \"Stationly\" under Display → Screen saver to use this. " +
                       "It runs while your phone or tablet is charging or docked.",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.08f) else Color(0xFF141414),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.05f),
        ),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
