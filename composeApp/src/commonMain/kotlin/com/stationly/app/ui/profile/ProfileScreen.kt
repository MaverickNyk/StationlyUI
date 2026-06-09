package com.stationly.app.ui.profile

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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Train as TrainOutlined
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Train as TrainRounded
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.app.ui.common.LocalOpenUrl
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.app.ui.theme.LocalThemeTokens
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SubscribedStation

/**
 * Compose-Multiplatform (iOS) port of the redesigned Android ProfileScreen,
 * wired to the existing iOS ProfileViewModel (user info from uiState; sign-out /
 * delete-account / delete-station through the VM + Swift AuthBridge).
 *
 * iOS adaptations: monogram avatar (no Coil network image yet), drawn brand
 * mark (no R.drawable), edit-name dialog omitted (no provider name-update on
 * the iOS auth path). SDUI About section + homeConfig dialog strings reuse the
 * same backend keys as Android.
 */
private val Amber    @Composable get() = MaterialTheme.colorScheme.primary
private val Surface0 @Composable get() = MaterialTheme.colorScheme.background
private val Surface1 @Composable get() = MaterialTheme.colorScheme.surface
private val Surface2 @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val White90  @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.90f)
private val White55  @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
private val White25  @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
private val White08  @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
private val DangerRed @Composable get() = LocalThemeTokens.current.error

private const val STATIONLY_WEB_URL = "https://stationly.co.uk"
private const val APP_VERSION = "1.0"

private val ProfileAboutFallback: List<SduiAppComponent> = listOf(
    SduiAppComponent.Card(
        id = "about_info",
        title = "Stationly",
        body = "Real-time London transport departures at your fingertips. Track buses, tubes, DLR, and Overground — all from one board.",
        style = "brand"
    ),
    SduiAppComponent.Section(
        id = "links_section",
        components = listOf(
            SduiAppComponent.LinkRow(id = "website", title = "Visit Website", subtitle = "stationly.co.uk", url = STATIONLY_WEB_URL, icon = "public"),
            SduiAppComponent.LinkRow(id = "privacy", title = "Privacy Policy", subtitle = "How we handle your data", url = "$STATIONLY_WEB_URL/privacy", icon = "privacy_tip"),
            SduiAppComponent.LinkRow(id = "terms", title = "Terms of Service", subtitle = "Usage terms and conditions", url = "$STATIONLY_WEB_URL/terms", icon = "description"),
            SduiAppComponent.LinkRow(id = "contact", title = "Contact Us", subtitle = "Questions or feedback", url = "mailto:info@stationly.co.uk", icon = "email"),
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authProvider: PlatformAuthProvider,
    onNavigateBack: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: ProfileViewModel = viewModel { ProfileViewModel(authProvider) }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val homeConfig = uiState.homeConfig

    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showDeleteStationDialog by remember { mutableStateOf<SubscribedStation?>(null) }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Amber), contentAlignment = Alignment.Center) {
                            Text("S", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Stationly", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = White90, letterSpacing = (-0.3).sp)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.padding(8.dp).size(40.dp).background(White08, CircleShape)
                    ) {
                        Icon(Icons.Rounded.ArrowBackIosNew, "Back", tint = White90, modifier = Modifier.size(18.dp))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ProfileHeaderCard(
                        name = uiState.displayName.ifBlank { "User" },
                        email = uiState.email,
                        provider = uiState.signInProvider,
                        memberSince = uiState.memberSince.ifBlank { "Recently" }
                    )
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    SectionHeader(homeConfig["profile.stations.title"] ?: "My Stations", Icons.Rounded.TrainRounded)
                }

                if (uiState.isLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                            CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (uiState.stations.isEmpty()) {
                    item {
                        EmptyStationsCard(
                            title = homeConfig["profile.stations.empty_title"] ?: "No stations yet",
                            subtitle = homeConfig["profile.stations.empty_subtitle"] ?: "Set up a board to start tracking departures"
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
                    SectionHeader(homeConfig["profile.about.title"] ?: "About Stationly", Icons.Rounded.Info)
                }

                val aboutToRender = uiState.aboutComponents.ifEmpty { ProfileAboutFallback }
                aboutToRender.forEach { component ->
                    item(key = "sdui_${component.id}") {
                        when (component) {
                            is SduiAppComponent.Card -> AboutCard(component)
                            is SduiAppComponent.Section -> AboutSection(component)
                            else -> {}
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    SignOutButton(
                        label = homeConfig["profile.signout.label"] ?: "Sign Out",
                        isSigningOut = uiState.isSigningOut,
                        onClick = { viewModel.signOut(onLoggedOut) }
                    )
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "Delete Account",
                            color = White25, fontSize = 13.sp,
                            modifier = Modifier.clickable { showDeleteAccountDialog = true }.padding(vertical = 8.dp, horizontal = 16.dp)
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }

            if (uiState.isSigningOut || uiState.isDeletingAccount) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = Amber)
                        Text(if (uiState.isDeletingAccount) "Deleting account…" else "Signing out…", color = White90, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    // ── Delete Station dialog ──
    showDeleteStationDialog?.let { station ->
        val isDeletingThis = uiState.deletingStationId == station.id
        var wasDeleting by remember { mutableStateOf(false) }
        LaunchedEffect(isDeletingThis) {
            if (isDeletingThis) wasDeleting = true
            else if (wasDeleting) { wasDeleting = false; showDeleteStationDialog = null }
        }
        val dsTitle   = homeConfig["profile.delete_station.title"] ?: "Delete This Board?"
        val dsBody    = (homeConfig["profile.delete_station.body"] ?: "You’re about to remove your {name} board.").replace("{name}", station.name)
        val dsBullets = (homeConfig["profile.delete_station.bullets"]
            ?: "Live departure tracking will stop,Departure notifications will be unsubscribed,Widget will be cleared")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val dsFooter  = homeConfig["profile.delete_station.footer"] ?: "You can always set up a new board from the home screen."
        val dsConfirm = homeConfig["profile.delete_station.confirm"] ?: "Delete Board"
        val dsCancel  = homeConfig["profile.delete_station.cancel"] ?: "Keep It"

        AlertDialog(
            onDismissRequest = { if (!isDeletingThis) showDeleteStationDialog = null },
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
                    onClick = { if (!isDeletingThis) viewModel.deleteStation(station) },
                    enabled = !isDeletingThis,
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) {
                    if (isDeletingThis) CircularProgressIndicator(color = DangerRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text(dsConfirm, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (!isDeletingThis) TextButton(onClick = { showDeleteStationDialog = null }, colors = ButtonDefaults.textButtonColors(contentColor = White55)) { Text(dsCancel) }
            }
        )
    }

    // ── Delete Account dialog ──
    if (showDeleteAccountDialog) {
        val daTitle   = homeConfig["profile.delete_account.title"] ?: "Delete Your Account?"
        val daIntro   = homeConfig["profile.delete_account.intro"] ?: "This action is permanent and cannot be undone. You will lose:"
        val daBullets = (homeConfig["profile.delete_account.bullets"]
            ?: "All your saved stations and boards,Your notification preferences,Your profile and account data")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val daFooter  = homeConfig["profile.delete_account.footer"] ?: "You’ll need to create a new account to use Stationly again."
        val daConfirm = homeConfig["profile.delete_account.confirm"] ?: "Delete Permanently"
        val daCancel  = homeConfig["profile.delete_account.cancel"] ?: "Keep Account"

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
                    uiState.error?.let { err ->
                        Spacer(Modifier.height(4.dp))
                        Text(err, color = DangerRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteAccount(onLoggedOut) },
                    enabled = !uiState.isDeletingAccount,
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) {
                    if (uiState.isDeletingAccount) CircularProgressIndicator(color = DangerRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text(daConfirm, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (!uiState.isDeletingAccount) TextButton(onClick = { showDeleteAccountDialog = false; viewModel.clearError() }, colors = ButtonDefaults.textButtonColors(contentColor = White55)) { Text(daCancel) }
            }
        )
    }
}

@Composable
private fun ProfileHeaderCard(name: String, email: String, provider: String, memberSince: String) {
    Surface(color = Surface1, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, White08)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(96.dp).border(2.5.dp, Brush.linearGradient(listOf(Amber, Amber.copy(0.3f))), CircleShape))
                Box(Modifier.size(86.dp).background(Surface2, CircleShape), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), color = Amber, fontSize = 34.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(name, color = White90, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(email, color = White55, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = White08, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.Email, null, tint = Amber, modifier = Modifier.size(13.dp))
                        Text(provider, color = White55, fontSize = 11.sp)
                    }
                }
                Surface(color = White08, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Rounded.CalendarMonth, null, tint = Amber, modifier = Modifier.size(13.dp))
                        Text("Since $memberSince", color = White55, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Amber, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(title.uppercase(), color = White55, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun StationCard(station: SubscribedStation, isDeleting: Boolean, onDelete: () -> Unit) {
    val modeIcon = when (station.mode.lowercase()) {
        "tube" -> Icons.Filled.Subway
        "bus" -> Icons.Filled.DirectionsBus
        "dlr" -> Icons.Filled.Tram
        else -> Icons.Filled.Train
    }
    Surface(color = Surface1, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, White08)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Amber.copy(0.1f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(modeIcon, null, tint = Amber, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(station.name, color = White90, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoChip(station.line.replaceFirstChar { it.uppercase() })
                    InfoChip(station.direction.replaceFirstChar { it.uppercase() })
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
    Surface(color = Surface1, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, White08)) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.TrainOutlined, null, tint = White25, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, color = White55, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = White25, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AboutCard(card: SduiAppComponent.Card) {
    Surface(color = Surface1, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, White08)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(card.title ?: "Stationly", color = Amber, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(card.body ?: "", color = White55, fontSize = 13.sp, lineHeight = 19.sp)
            if (card.style == "brand") {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip("v$APP_VERSION")
                    InfoChip("TfL Powered")
                    InfoChip("Made in London")
                }
            }
        }
    }
}

@Composable
private fun AboutSection(section: SduiAppComponent.Section) {
    val openUrl = LocalOpenUrl.current
    val links = section.components.filterIsInstance<SduiAppComponent.LinkRow>()
    Surface(color = Surface1, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, White08)) {
        Column(Modifier.fillMaxWidth()) {
            links.forEachIndexed { i, link ->
                LinkRowItem(link) { openUrl(link.url, link.title) }
                if (i < links.lastIndex) HorizontalDivider(color = White08, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun LinkRowItem(link: SduiAppComponent.LinkRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).background(Amber.copy(0.1f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(iconFor(link.icon), null, tint = Amber, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(link.title, color = White90, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            link.subtitle?.let { Text(it, color = White55, fontSize = 12.sp) }
        }
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = White25, modifier = Modifier.size(16.dp))
    }
}

private fun iconFor(icon: String?): ImageVector = when (icon) {
    "public"      -> Icons.Rounded.Public
    "description" -> Icons.Outlined.Description
    "email"       -> Icons.Outlined.Email
    "star"        -> Icons.Rounded.Star
    else          -> Icons.AutoMirrored.Rounded.OpenInNew
}

@Composable
private fun SignOutButton(label: String, isSigningOut: Boolean, onClick: () -> Unit) {
    Button(
        onClick = { if (!isSigningOut) onClick() },
        enabled = !isSigningOut,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Surface1, contentColor = White90,
            disabledContainerColor = Surface1, disabledContentColor = White90.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        if (isSigningOut) {
            CircularProgressIndicator(color = DangerRed, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text("Signing out…", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        } else {
            Icon(Icons.Rounded.Logout, null, tint = DangerRed, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = White90)
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(color = White08, shape = RoundedCornerShape(6.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = White55, fontSize = 11.sp)
    }
}

@Composable
private fun WarningBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).background(DangerRed.copy(0.6f), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, color = White55, fontSize = 14.sp)
    }
}
