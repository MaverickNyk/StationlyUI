package com.stationly.mobile.ui.profile

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stationly.core.model.sdui.SubscribedStation
import com.stationly.core.service.SduiApiServiceFactory
import com.stationly.mobile.service.FirebaseAuthManager
import kotlinx.coroutines.launch

/* ═══════════════════════════════════════════════════════════════
   Palette
   ═══════════════════════════════════════════════════════════════ */
private val Amber = Color(0xFFFFB81C)
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
    onNavigateBack: () -> Unit,
    onLoggedOut: () -> Unit,
    authManager: FirebaseAuthManager
) {
    val user = authManager.currentUser
    val userEmail = user?.email ?: "Stationly User"
    val userName = user?.displayName ?: userEmail.split("@").firstOrNull() ?: "User"
    val photoUrl = user?.photoUrl?.toString()
    val providerId = user?.providerData
        ?.firstOrNull { it.providerId != "firebase" }?.providerId
    val providerLabel = when (providerId) {
        "google.com" -> "Google"
        "apple.com" -> "Apple"
        "password" -> "Email"
        else -> "Stationly"
    }
    val memberSince = remember {
        user?.metadata?.creationTimestamp?.let { ts ->
            val date = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.UK).format(java.util.Date(ts))
            date
        } ?: "Recently"
    }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: PackageManager.NameNotFoundException) { "1.0" }
    }

    // Fetch user profile (stations) from backend
    var stations by remember { mutableStateOf<List<SubscribedStation>>(emptyList()) }
    var isLoadingProfile by remember { mutableStateOf(true) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showDeleteStationDialog by remember { mutableStateOf<SubscribedStation?>(null) }
    var isDeletingAccount by remember { mutableStateOf(false) }

    LaunchedEffect(user?.uid) {
        if (user != null) {
            try {
                val profile = SduiApiServiceFactory.create().getUserProfile(user.uid)
                stations = profile.stations
            } catch (_: Exception) { }
            isLoadingProfile = false
        }
    }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Account",
                        style = MaterialTheme.typography.titleMedium,
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Profile Header Card ──
            item {
                ProfileHeaderCard(userName, userEmail, photoUrl, providerLabel, memberSince)
            }

            // ── My Stations Section ──
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader("My Stations", Icons.Rounded.Train)
            }

            if (isLoadingProfile) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator(
                            color = Amber, strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            } else if (stations.isEmpty()) {
                item { EmptyStationsCard() }
            } else {
                items(stations, key = { "${it.id}_${it.line}" }) { station ->
                    StationCard(
                        station = station,
                        onDelete = { showDeleteStationDialog = station }
                    )
                }
            }

            // ── About Stationly Section ──
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader("About Stationly", Icons.Rounded.Info)
            }

            item {
                // About banner
                Surface(
                    color = Surface1,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, White08)
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(
                            "Stationly",
                            color = Amber,
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Real-time London transport departures at your fingertips. " +
                            "Track buses, tubes, DLR, and Overground — all from one board.",
                            color = White55,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoChip("v$appVersion")
                            InfoChip("TfL Powered")
                            InfoChip("Made in London")
                        }
                    }
                }
            }

            item {
                Surface(
                    color = Surface1,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, White08)
                ) {
                    Column {
                        ProfileActionRow(
                            icon = Icons.Outlined.Public,
                            title = "Visit Website",
                            subtitle = "stationly.co.uk",
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://stationly.co.uk")))
                            }
                        )
                        RowDivider()
                        ProfileActionRow(
                            icon = Icons.Outlined.PrivacyTip,
                            title = "Privacy Policy",
                            subtitle = "How we handle your data",
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://stationly.co.uk/privacy")))
                            }
                        )
                        RowDivider()
                        ProfileActionRow(
                            icon = Icons.Outlined.Description,
                            title = "Terms of Service",
                            subtitle = "Usage terms and conditions",
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://stationly.co.uk/terms")))
                            }
                        )
                        RowDivider()
                        ProfileActionRow(
                            icon = Icons.Outlined.Email,
                            title = "Contact Us",
                            subtitle = "Questions or feedback",
                            onClick = {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:hello@stationly.co.uk")
                                    putExtra(Intent.EXTRA_SUBJECT, "Stationly App Feedback")
                                }
                                context.startActivity(intent)
                            }
                        )
                        RowDivider()
                        ProfileActionRow(
                            icon = Icons.Outlined.Star,
                            title = "Rate Stationly",
                            subtitle = "Love the app? Let us know",
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")))
                                } catch (_: Exception) {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")))
                                }
                            }
                        )
                    }
                }
            }

            // ── Acknowledgements ──
            item {
                Surface(
                    color = Surface1,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, White08)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            "Powered by TfL Open Data",
                            color = White55,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Contains OS data \u00a9 Crown copyright and database rights 2025. " +
                            "Powered by TfL Open Data. Neither TfL nor the UK Government endorse this app.",
                            color = White25,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // ── Sign Out ──
            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            authManager.logout()
                            onLoggedOut()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface1,
                        contentColor = White90
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, White08)
                ) {
                    Icon(Icons.Rounded.Logout, null, tint = DangerRed, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Sign Out", fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp, color = White90
                    )
                }
            }

            // ── Delete Account (hidden at bottom, subtle) ──
            item {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Delete Account",
                        color = White25,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { showDeleteAccountDialog = true }
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    )
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    // ── Delete Station Confirmation Dialog ──
    showDeleteStationDialog?.let { station ->
        AlertDialog(
            onDismissRequest = { showDeleteStationDialog = null },
            containerColor = Surface2,
            titleContentColor = White90,
            textContentColor = White55,
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = DangerRed, modifier = Modifier.size(28.dp)) },
            title = { Text("Remove Station", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Remove ${station.name} (${station.line}) from your saved stations? " +
                    "You can always add it back later."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val stationToDelete = station
                        showDeleteStationDialog = null
                        coroutineScope.launch {
                            try {
                                val uid = user?.uid ?: return@launch
                                val updated = stations.filter {
                                    !(it.id == stationToDelete.id && it.line == stationToDelete.line)
                                }
                                SduiApiServiceFactory.create().syncStations(uid, updated)
                                stations = updated
                            } catch (_: Exception) { }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) { Text("Remove", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteStationDialog = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = White55)
                ) { Text("Cancel") }
            }
        )
    }

    // ── Delete Account Confirmation Dialog ──
    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteAccountDialog = false },
            containerColor = Surface2,
            titleContentColor = White90,
            textContentColor = White55,
            icon = {
                Icon(
                    Icons.Rounded.WarningAmber, null,
                    tint = DangerRed, modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text("Delete Your Account?", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This action is permanent and cannot be undone. You will lose:")
                    WarningBullet("All your saved stations and boards")
                    WarningBullet("Your notification preferences")
                    WarningBullet("Your profile and account data")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "You'll need to create a new account to use Stationly again.",
                        color = White25, fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeletingAccount = true
                        coroutineScope.launch {
                            try {
                                val uid = user?.uid ?: return@launch
                                SduiApiServiceFactory.create().deleteAccount(uid)
                                authManager.logout()
                                onLoggedOut()
                            } catch (_: Exception) {
                                isDeletingAccount = false
                                showDeleteAccountDialog = false
                            }
                        }
                    },
                    enabled = !isDeletingAccount,
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) {
                    if (isDeletingAccount) {
                        CircularProgressIndicator(
                            color = DangerRed, strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text("Delete Permanently", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isDeletingAccount) {
                    TextButton(
                        onClick = { showDeleteAccountDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = White55)
                    ) { Text("Keep Account") }
                }
            }
        )
    }
}

/* ═══════════════════════════════════════════════════════════════
   Profile Header Card — avatar, name, email, provider, member since
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun ProfileHeaderCard(
    name: String, email: String, photoUrl: String?,
    provider: String, memberSince: String
) {
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
                        .border(2.5.dp, Brush.linearGradient(listOf(Amber, Amber.copy(0.3f))), CircleShape)
                )
                if (photoUrl != null) {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = "Profile photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(86.dp)
                            .clip(CircleShape)
                            .background(Surface2, CircleShape)
                    )
                } else {
                    Box(
                        Modifier.size(86.dp).background(Surface2, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            name.take(1).uppercase(),
                            color = Amber,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                name, color = White90,
                fontWeight = FontWeight.Bold, fontSize = 22.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(email, color = White55, fontSize = 14.sp)

            Spacer(Modifier.height(14.dp))

            // Provider + member since badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Provider badge
                Surface(color = White08, shape = RoundedCornerShape(20.dp)) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            if (provider == "Google") Icons.Rounded.AlternateEmail else Icons.Rounded.Email,
                            null, tint = Amber, modifier = Modifier.size(13.dp)
                        )
                        Text(provider, color = White55, fontSize = 11.sp)
                    }
                }
                // Member since badge
                Surface(color = White08, shape = RoundedCornerShape(20.dp)) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CalendarMonth,
                            null, tint = Amber, modifier = Modifier.size(13.dp)
                        )
                        Text("Since $memberSince", color = White55, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Section Header
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Amber, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title.uppercase(), color = White55,
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
    }
}

/* ═══════════════════════════════════════════════════════════════
   Station Card
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun StationCard(station: SubscribedStation, onDelete: () -> Unit) {
    val modeIcon = when (station.mode.lowercase()) {
        "tube" -> Icons.Filled.Subway
        "bus" -> Icons.Filled.DirectionsBus
        "dlr" -> Icons.Filled.Tram
        "overground", "elizabeth-line" -> Icons.Filled.Train
        else -> Icons.Filled.Train
    }

    Surface(
        color = Surface1,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(Amber.copy(0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(modeIcon, null, tint = Amber, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    station.name,
                    color = White90, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoChip(station.line.replaceFirstChar { it.uppercase() })
                    InfoChip(station.direction.replaceFirstChar { it.uppercase() })
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, "Remove", tint = White25, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Empty Stations Card
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun EmptyStationsCard() {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Train, null, tint = White25, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text("No stations yet", color = White55, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Set up a board to start tracking departures",
                color = White25, fontSize = 13.sp, textAlign = TextAlign.Center
            )
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Action Row — clickable setting item
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun ProfileActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(White08, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = White55, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White90, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, color = White25, fontSize = 12.sp)
        }
        if (trailing != null) {
            Text(trailing, color = White25, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = White25, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RowDivider() {
    Divider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp, color = White08
    )
}

/* ═══════════════════════════════════════════════════════════════
   Info Chip — small tag-style label
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun InfoChip(text: String) {
    Surface(color = White08, shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = White55, fontSize = 11.sp
        )
    }
}

/* ═══════════════════════════════════════════════════════════════
   Warning bullet (delete account dialog)
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun WarningBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .background(DangerRed.copy(0.6f), CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = White55, fontSize = 14.sp)
    }
}
