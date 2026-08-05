package com.stationly.app.ui.station

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.openAppNotificationSettings
import com.stationly.app.platform.performHaptic
import com.stationly.app.ui.theme.AppTheme
import com.stationly.app.ui.theme.LocalAppTheme
import com.stationly.app.ui.util.StationPrefsRepository
import com.stationly.core.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Settings for the home screen as a whole, reached from the gear in the top bar.
 *
 * Everything here applies across stations. Anything that is a property of ONE
 * station — its layout, its pin, its lines — belongs on that station's own
 * settings screen, which is one tap away from its card; splitting the two is
 * what keeps either list short enough to read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSettingsScreen(
    onBack: () -> Unit,
    onOpenScreensaver: () -> Unit,
) {
    val themeState = LocalAppTheme.current
    val prefs by StationPrefsRepository.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The ids of every tracked station, so "open every station" can reach the
    // ones that have no preferences row yet — a station with default settings is
    // deliberately absent from the map (see StationPrefsRepository.persist).
    var stationIds by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        StationPrefsRepository.ensureLoaded()
        stationIds = withContext(Dispatchers.Default) {
            Platform.sqlStorage.getAllSelections().map { it.groupingId }.distinct()
        }
    }

    val pinnedCount = prefs.values.count { it.pinned }
    val openCount = prefs.values.count { it.openByDefault }
    val heroHiddenCount = prefs.values.count { it.hideHero }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Home settings", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // ── Appearance ──
            HomeSectionLabel("Appearance")
            HomeCard {
                AppTheme.entries.forEachIndexed { index, option ->
                    if (index > 0) HomeDivider()
                    ChoiceRow(
                        icon = when (option) {
                            AppTheme.LIGHT -> Icons.Rounded.LightMode
                            AppTheme.DARK -> Icons.Rounded.DarkMode
                            AppTheme.SYSTEM -> Icons.Rounded.BrightnessAuto
                        },
                        title = option.displayName,
                        subtitle = when (option) {
                            AppTheme.LIGHT -> "Always light, whatever iOS is set to"
                            AppTheme.DARK -> "Always dark, whatever iOS is set to"
                            AppTheme.SYSTEM -> "Follows your iOS appearance setting"
                        },
                        selected = themeState.theme == option,
                        onClick = {
                            if (themeState.theme != option) {
                                performHaptic(HapticType.TAP)
                                themeState.onChange(option)
                            }
                        },
                    )
                }
            }
            HomeCaption(
                "The departure board itself stays dark amber in every theme — that " +
                    "is signage, not app chrome."
            )

            Spacer(Modifier.height(28.dp))

            // ── Boards ──
            //
            // The whole section is skipped while there are no stations (or while
            // the id list is still being read), because every row in it is a
            // statement about stations that do not exist yet.
            if (stationIds.isNotEmpty()) {
                HomeSectionLabel("All stations")
                HomeCard {
                    // Bulk versions of the per-station switches, and one-shot
                    // ACTIONS rather than toggles: with three stations disagreeing
                    // there is no half-applied state a switch could honestly show.
                    //
                    // Each row appears only when it would actually change
                    // something. A row that is already true of every station is a
                    // no-op the user has to read and then decide to ignore.
                    var needsDivider = false
                    if (openCount < stationIds.size) {
                        ActionRowSimple(
                            icon = Icons.Rounded.UnfoldMore,
                            title = "Open every station by default",
                            subtitle = if (openCount == 0) {
                                "Right now only your top station opens on launch"
                            } else {
                                "$openCount of ${stationIds.size} open on launch today"
                            },
                            onClick = {
                                performHaptic(HapticType.TAP)
                                scope.launch {
                                    StationPrefsRepository.setOpenByDefaultForAll(true, stationIds)
                                }
                            },
                        )
                        needsDivider = true
                    }
                    if (openCount > 0) {
                        if (needsDivider) HomeDivider()
                        ActionRowSimple(
                            icon = Icons.Rounded.UnfoldLess,
                            title = "Collapse every station on launch",
                            subtitle = "Only the top station starts open",
                            onClick = {
                                performHaptic(HapticType.TAP)
                                scope.launch {
                                    StationPrefsRepository.setOpenByDefaultForAll(false, stationIds)
                                }
                            },
                        )
                        needsDivider = true
                    }
                    if (heroHiddenCount > 0) {
                        if (needsDivider) HomeDivider()
                        ActionRowSimple(
                            icon = Icons.Rounded.Timer,
                            title = "Show the countdown everywhere",
                            subtitle = "$heroHiddenCount ${stations(heroHiddenCount)} currently hide it",
                            onClick = {
                                performHaptic(HapticType.TAP)
                                scope.launch { StationPrefsRepository.showHeroEverywhere() }
                            },
                        )
                        needsDivider = true
                    }
                    if (pinnedCount > 0) {
                        if (needsDivider) HomeDivider()
                        ActionRowSimple(
                            icon = Icons.Rounded.PushPin,
                            title = "Clear all pins",
                            subtitle = "$pinnedCount ${stations(pinnedCount)} pinned to the top",
                            onClick = {
                                performHaptic(HapticType.TAP)
                                scope.launch { StationPrefsRepository.clearPins() }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))
            }

            // ── Elsewhere ──
            HomeSectionLabel("More")
            HomeCard {
                ActionRowSimple(
                    icon = Icons.Rounded.Bedtime,
                    title = "Screensaver",
                    subtitle = "Turn your phone into a departure board while it charges",
                    onClick = onOpenScreensaver,
                )
                HomeDivider()
                ActionRowSimple(
                    icon = Icons.Rounded.Notifications,
                    title = "Notifications",
                    subtitle = "Opens Stationly's notification settings in iOS",
                    onClick = { openAppNotificationSettings() },
                )
            }
        }
    }
}

private fun stations(count: Int) = if (count == 1) "station" else "stations"

@Composable
private fun HomeSectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

@Composable
private fun HomeCaption(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
    )
}

@Composable
private fun HomeCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)),
    ) {
        Column { content() }
    }
}

@Composable
private fun HomeDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
    )
}

@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        if (selected) {
            Box(modifier = Modifier.size(8.dp).background(accent, CircleShape))
        }
    }
}

@Composable
private fun ActionRowSimple(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            icon,
            null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
