package com.stationly.app.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.app.ui.sdui.SduiRenderer
import com.stationly.app.ui.theme.TflAmber
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SubscribedStation

private val Surface0 = Color(0xFF0A0A0A)
private val Surface1 = Color(0xFF141414)
private val Surface2 = Color(0xFF1C1C1C)
private val White90 = Color.White.copy(alpha = 0.90f)
private val White55 = Color.White.copy(alpha = 0.55f)
private val White25 = Color.White.copy(alpha = 0.25f)
private val White08 = Color.White.copy(alpha = 0.08f)
private val DangerRed = Color(0xFFFF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authProvider: PlatformAuthProvider,
    onNavigateBack: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: ProfileViewModel = viewModel { ProfileViewModel(authProvider) }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showDeleteStationDialog by remember { mutableStateOf<SubscribedStation?>(null) }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Account",
                        fontWeight = FontWeight.Bold,
                        color = White90,
                        letterSpacing = 0.3.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(40.dp)
                            .background(White08, CircleShape)
                    ) {
                        Icon(
                            Icons.Rounded.ArrowBackIosNew, "Back",
                            tint = White90, modifier = Modifier.size(18.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ProfileHeaderCard(
                    name = uiState.displayName,
                    email = uiState.email,
                    provider = uiState.signInProvider,
                    memberSince = uiState.memberSince
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                ProfileSectionHeader(
                    uiState.homeConfig["profile.stations.title"] ?: "My Stations",
                    Icons.Rounded.Train
                )
            }

            if (uiState.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator(color = TflAmber, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                }
            } else if (uiState.stations.isEmpty()) {
                item {
                    EmptyStationsCard(
                        title    = uiState.homeConfig["profile.stations.empty_title"] ?: "No stations yet",
                        subtitle = uiState.homeConfig["profile.stations.empty_subtitle"] ?: "Set up a board to start tracking departures"
                    )
                }
            } else {
                items(uiState.stations, key = { "${it.id}_${it.line}" }) { station ->
                    StationCard(
                        station = station,
                        isDeleting = uiState.deletingStationId == station.id,
                        onDelete = { if (uiState.deletingStationId == null) showDeleteStationDialog = station }
                    )
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                ProfileSectionHeader(
                    uiState.homeConfig["profile.about.title"] ?: "About Stationly",
                    Icons.Rounded.Info
                )
            }

            if (uiState.aboutComponents.isEmpty()) {
                item { AboutFallbackCard() }
            } else {
                uiState.aboutComponents.forEach { component ->
                    item(key = "sdui_${component.id}") {
                        SduiRenderer(
                            component = component,
                            onLinkOpen = { url -> runCatching { uriHandler.openUri(url) } },
                            onAction = {}
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { if (!uiState.isSigningOut) viewModel.signOut(onLoggedOut) },
                    enabled = !uiState.isSigningOut,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface1, contentColor = White90,
                        disabledContainerColor = Surface1, disabledContentColor = White90.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, White08)
                ) {
                    if (uiState.isSigningOut) {
                        CircularProgressIndicator(color = DangerRed, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Signing out…", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    } else {
                        Icon(Icons.Rounded.Logout, null, tint = DangerRed, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            uiState.homeConfig["profile.signout.label"] ?: "Sign Out",
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = White90
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Delete Account",
                        color = White25, fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { showDeleteAccountDialog = true }
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    )
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    // Delete station dialog
    showDeleteStationDialog?.let { station ->
        val dsTitle   = uiState.homeConfig["profile.delete_station.title"]   ?: "Delete This Board?"
        val dsBody    = (uiState.homeConfig["profile.delete_station.body"]   ?: "You're about to remove your {name} board.")
            .replace("{name}", station.name)
        val dsBullets = (uiState.homeConfig["profile.delete_station.bullets"]
            ?: "Live departure tracking will stop,Departure notifications will be unsubscribed,Widget will be cleared")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val dsFooter  = uiState.homeConfig["profile.delete_station.footer"]  ?: "You can always set up a new board from the home screen."
        val dsConfirm = uiState.homeConfig["profile.delete_station.confirm"] ?: "Delete Board"
        val dsCancel  = uiState.homeConfig["profile.delete_station.cancel"]  ?: "Keep It"

        AlertDialog(
            onDismissRequest = { showDeleteStationDialog = null },
            containerColor = Surface2,
            titleContentColor = White90,
            textContentColor = White55,
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = DangerRed, modifier = Modifier.size(28.dp)) },
            title = { Text(dsTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(dsBody, fontWeight = FontWeight.Medium)
                    dsBullets.forEach { WarningBullet(it) }
                    Spacer(Modifier.height(2.dp))
                    Text(dsFooter, color = White25, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val s = station
                        showDeleteStationDialog = null
                        viewModel.deleteStation(s)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) { Text(dsConfirm, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteStationDialog = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = White55)
                ) { Text(dsCancel) }
            }
        )
    }

    // Delete account dialog
    if (showDeleteAccountDialog) {
        val daTitle   = uiState.homeConfig["profile.delete_account.title"]   ?: "Delete Your Account?"
        val daIntro   = uiState.homeConfig["profile.delete_account.intro"]   ?: "This action is permanent and cannot be undone. You will lose:"
        val daBullets = (uiState.homeConfig["profile.delete_account.bullets"]
            ?: "All your saved stations and boards,Your notification preferences,Your profile and account data")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val daFooter  = uiState.homeConfig["profile.delete_account.footer"]  ?: "You'll need to create a new account to use Stationly again."
        val daConfirm = uiState.homeConfig["profile.delete_account.confirm"] ?: "Delete Permanently"
        val daCancel  = uiState.homeConfig["profile.delete_account.cancel"]  ?: "Keep Account"

        AlertDialog(
            onDismissRequest = { if (!uiState.isDeletingAccount) showDeleteAccountDialog = false },
            containerColor = Surface2,
            titleContentColor = White90,
            textContentColor = White55,
            icon = { Icon(Icons.Rounded.WarningAmber, null, tint = DangerRed, modifier = Modifier.size(32.dp)) },
            title = { Text(daTitle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(daIntro)
                    daBullets.forEach { WarningBullet(it) }
                    Spacer(Modifier.height(4.dp))
                    Text(daFooter, color = White25, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAccount {
                            showDeleteAccountDialog = false
                            onLoggedOut()
                        }
                    },
                    enabled = !uiState.isDeletingAccount,
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) {
                    if (uiState.isDeletingAccount) {
                        CircularProgressIndicator(color = DangerRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Text(daConfirm, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!uiState.isDeletingAccount) {
                    TextButton(
                        onClick = { showDeleteAccountDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = White55)
                    ) { Text(daCancel) }
                }
            }
        )
    }
}

@Composable
private fun ProfileHeaderCard(name: String, email: String, provider: String, memberSince: String) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar with amber ring
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(96.dp)
                        .background(
                            Brush.linearGradient(listOf(TflAmber, TflAmber.copy(alpha = 0.3f))),
                            CircleShape
                        )
                )
                Box(
                    Modifier
                        .size(86.dp)
                        .background(Surface2, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        name.take(1).uppercase(),
                        color = TflAmber, fontSize = 34.sp, fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(name, color = White90, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(email, color = White55, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = White08, shape = RoundedCornerShape(20.dp)) {
                    Text(
                        provider,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = White55, fontSize = 11.sp
                    )
                }
                if (memberSince.isNotBlank()) {
                    Surface(color = White08, shape = RoundedCornerShape(20.dp)) {
                        Text(
                            "Since $memberSince",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = White55, fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TflAmber, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title.uppercase(), color = White55,
            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
        )
    }
}

@Composable
private fun StationCard(station: SubscribedStation, isDeleting: Boolean, onDelete: () -> Unit) {
    val modeIcon = when (station.mode.lowercase()) {
        "tube"                         -> Icons.Filled.Subway
        "bus"                          -> Icons.Filled.DirectionsBus
        "dlr"                          -> Icons.Filled.Tram
        "overground", "elizabeth-line" -> Icons.Filled.Train
        else                           -> Icons.Filled.Train
    }
    Surface(
        color = Surface1, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(42.dp).background(TflAmber.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(modeIcon, null, tint = TflAmber, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    station.name, color = White90, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProfileInfoChip(station.line.replaceFirstChar { it.uppercase() })
                    ProfileInfoChip(station.direction.replaceFirstChar { it.uppercase() })
                }
            }
            if (isDeleting) {
                CircularProgressIndicator(color = DangerRed, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, "Remove", tint = White25, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyStationsCard(title: String, subtitle: String) {
    Surface(
        color = Surface1, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Train, null, tint = White25, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, color = White55, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = White25, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AboutFallbackCard() {
    Surface(
        color = Surface1, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Stationly", color = TflAmber, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Real-time London transport departures at your fingertips.",
                color = White55, fontSize = 13.sp, lineHeight = 19.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileInfoChip("TfL Powered")
                ProfileInfoChip("Made in London")
            }
        }
    }
}

@Composable
private fun ProfileInfoChip(text: String) {
    Surface(color = White08, shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = White55, fontSize = 11.sp
        )
    }
}

@Composable
private fun WarningBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 7.dp).size(5.dp)
                .background(DangerRed.copy(alpha = 0.6f), CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = White55, fontSize = 14.sp)
    }
}
