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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SubscribedStation
import com.stationly.core.service.SduiApiServiceFactory
import com.stationly.mobile.service.FirebaseAuthManager
import com.stationly.mobile.ui.common.SduiCard
import com.stationly.mobile.ui.common.SduiSection
import com.stationly.mobile.ui.common.rememberFirebaseAuthState
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
    authManager: FirebaseAuthManager,
    profileViewModel: ProfileViewModel = viewModel()
) {
    // Subscribe to Firebase auth state so this screen can't outlive a sign-out.
    val firebaseUser by rememberFirebaseAuthState()
    LaunchedEffect(firebaseUser) {
        if (firebaseUser == null) onLoggedOut()
    }
    // If Firebase has no user, bail out before rendering anything — the LaunchedEffect
    // above is already routing us back to login. Returning here also prevents the
    // "Stationly User" placeholder from ever flashing on screen.
    val user = firebaseUser ?: return
    val userEmail = user.email.orEmpty()
    val userName = user.displayName ?: userEmail.substringBefore('@').ifBlank { "User" }
    val photoUrl = user.photoUrl?.toString()
    val providerId = user.providerData
        .firstOrNull { it.providerId != "firebase" }?.providerId
    val providerLabel = when (providerId) {
        "google.com" -> "Google"
        "apple.com" -> "Apple"
        "password" -> "Email"
        else -> "Stationly"
    }
    val memberSince = remember(user.uid) {
        user.metadata?.creationTimestamp?.let { ts ->
            java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.UK).format(java.util.Date(ts))
        } ?: "Recently"
    }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: PackageManager.NameNotFoundException) { "1.0" }
    }

    val stations by profileViewModel.stations.collectAsState()
    val isLoadingProfile by profileViewModel.isLoading.collectAsState()
    val aboutComponents by profileViewModel.aboutComponents.collectAsState()
    val deletingStationId by profileViewModel.deletingStationId.collectAsState()
    val homeConfig by profileViewModel.homeConfig.collectAsState()
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showDeleteStationDialog by remember { mutableStateOf<SubscribedStation?>(null) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    var deleteAccountError by remember { mutableStateOf<String?>(null) }
    // Live-editable display name (optimistically updated when the edit dialog
    // succeeds; falls back to the FirebaseUser's name otherwise).
    var editedName by remember(user.uid, userName) { mutableStateOf<String?>(null) }
    val displayedName = editedName ?: userName
    var showEditNameDialog by remember { mutableStateOf(false) }
    var isSigningOut by remember { mutableStateOf(false) }

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
                ProfileHeaderCard(
                    name = displayedName,
                    email = userEmail,
                    photoUrl = photoUrl,
                    provider = providerLabel,
                    memberSince = memberSince,
                    onEditName = { showEditNameDialog = true }
                )
            }

            // ── My Stations Section ──
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader(homeConfig["profile.stations.title"] ?: "My Stations", Icons.Rounded.Train)
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
                item {
                    EmptyStationsCard(
                        title    = homeConfig["profile.stations.empty_title"]    ?: "No stations yet",
                        subtitle = homeConfig["profile.stations.empty_subtitle"] ?: "Set up a board to start tracking departures"
                    )
                }
            } else {
                items(stations, key = { "${it.id}_${it.line}" }) { station ->
                    StationCard(
                        station = station,
                        isDeleting = deletingStationId == station.id,
                        onDelete = { if (deletingStationId == null) showDeleteStationDialog = station }
                    )
                }
            }

            // ── About Stationly Section (SDUI-driven) ──
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader(homeConfig["profile.about.title"] ?: "About Stationly", Icons.Rounded.Info)
            }

            if (aboutComponents.isEmpty()) {
                // Fallback while loading or if server unreachable
                item {
                    Surface(
                        color = Surface1,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, White08)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("Stationly", color = Amber, fontWeight = FontWeight.Black, fontSize = 20.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Real-time London transport departures at your fingertips.",
                                color = White55, fontSize = 13.sp, lineHeight = 19.sp
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
            } else {
                // SDUI-rendered About content
                aboutComponents.forEach { component ->
                    item(key = "sdui_${component.id}") {
                        when (component) {
                            is SduiAppComponent.Card -> {
                                // Inject local app version chip into the brand card
                                if (component.style == "brand") {
                                    Surface(
                                        color = Surface1,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, White08)
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                            Text(
                                                component.title ?: "Stationly",
                                                color = Amber,
                                                fontWeight = FontWeight.Black,
                                                fontSize = 20.sp,
                                                letterSpacing = 0.5.sp
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                component.body ?: "",
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
                                } else {
                                    SduiCard(component)
                                }
                            }
                            is SduiAppComponent.Section -> SduiSection(component, Amber) { url ->
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            }
                            else -> {}
                        }
                    }
                }
            }

            // ── Sign Out ──
            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        if (!isSigningOut) {
                            isSigningOut = true
                            coroutineScope.launch {
                                authManager.logout()
                                onLoggedOut()
                            }
                        }
                    },
                    enabled = !isSigningOut,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface1,
                        contentColor = White90,
                        disabledContainerColor = Surface1,
                        disabledContentColor = White90.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, White08)
                ) {
                    if (isSigningOut) {
                        CircularProgressIndicator(
                            color = DangerRed, strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Signing out…", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    } else {
                        Icon(Icons.Rounded.Logout, null, tint = DangerRed, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(homeConfig["profile.signout.label"] ?: "Sign Out", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = White90)
                    }
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

    // ── Edit Display Name Dialog ──
    if (showEditNameDialog) {
        var nameDraft by remember(displayedName) { mutableStateOf(displayedName) }
        var nameError by remember { mutableStateOf<String?>(null) }
        var isSavingName by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!isSavingName) showEditNameDialog = false },
            containerColor = Surface2,
            titleContentColor = White90,
            textContentColor = White55,
            title = { Text("Edit your name", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = {
                            nameDraft = it
                            if (nameError != null) nameError = null
                        },
                        label = { Text("Display name") },
                        singleLine = true,
                        isError = nameError != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        // Material3 defaults to blue accents which read wrong on
                        // Stationly's amber + dark theme. Match the AuthField look
                        // from LoginScreen so the dialog feels like the same product.
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor    = Amber,
                            unfocusedBorderColor  = White25,
                            focusedTextColor      = White90,
                            unfocusedTextColor    = White90,
                            focusedLabelColor     = Amber,
                            unfocusedLabelColor   = White55,
                            focusedContainerColor   = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor           = Amber,
                            errorBorderColor      = DangerRed,
                            errorLabelColor       = DangerRed,
                            errorCursorColor      = DangerRed
                        )
                    )
                    nameError?.let {
                        Text(it, color = DangerRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isSavingName && nameDraft.trim() != displayedName,
                    onClick = {
                        isSavingName = true
                        nameError = null
                        profileViewModel.updateDisplayName(nameDraft) { result ->
                            isSavingName = false
                            result.onSuccess { newName ->
                                editedName = newName
                                showEditNameDialog = false
                            }
                            result.onFailure { e ->
                                nameError = e.message ?: "Couldn't save. Please try again."
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Amber)
                ) {
                    if (isSavingName) {
                        CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isSavingName) {
                    TextButton(
                        onClick = { showEditNameDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = White55)
                    ) { Text("Cancel") }
                }
            }
        )
    }

    // ── Delete Station Confirmation Dialog ──
    showDeleteStationDialog?.let { station ->
        val dsTitle   = homeConfig["profile.delete_station.title"]   ?: "Delete This Board?"
        val dsBody    = (homeConfig["profile.delete_station.body"]   ?: "You\u2019re about to remove your {name} board.")
            .replace("{name}", station.name)
        val dsBullets = (homeConfig["profile.delete_station.bullets"]
            ?: "Live departure tracking will stop,Departure notifications will be unsubscribed,Widget will be cleared")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val dsFooter  = homeConfig["profile.delete_station.footer"]  ?: "You can always set up a new board from the home screen."
        val dsConfirm = homeConfig["profile.delete_station.confirm"] ?: "Delete Board"
        val dsCancel  = homeConfig["profile.delete_station.cancel"]  ?: "Keep It"

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
                        val stationToDelete = station
                        showDeleteStationDialog = null
                        profileViewModel.deleteStation(stationToDelete)
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

    // ── Delete Account Confirmation Dialog ──
    if (showDeleteAccountDialog) {
        val daTitle   = homeConfig["profile.delete_account.title"]   ?: "Delete Your Account?"
        val daIntro   = homeConfig["profile.delete_account.intro"]   ?: "This action is permanent and cannot be undone. You will lose:"
        val daBullets = (homeConfig["profile.delete_account.bullets"]
            ?: "All your saved stations and boards,Your notification preferences,Your profile and account data")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val daFooter  = homeConfig["profile.delete_account.footer"]  ?: "You\u2019ll need to create a new account to use Stationly again."
        val daConfirm = homeConfig["profile.delete_account.confirm"] ?: "Delete Permanently"
        val daCancel  = homeConfig["profile.delete_account.cancel"]  ?: "Keep Account"

        AlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteAccountDialog = false },
            containerColor = Surface2,
            titleContentColor = White90,
            textContentColor = White55,
            icon = {
                Icon(Icons.Rounded.WarningAmber, null, tint = DangerRed, modifier = Modifier.size(32.dp))
            },
            title = {
                Text(daTitle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(daIntro)
                    daBullets.forEach { WarningBullet(it) }
                    Spacer(Modifier.height(4.dp))
                    Text(daFooter, color = White25, fontSize = 12.sp)
                    deleteAccountError?.let { err ->
                        Spacer(Modifier.height(4.dp))
                        Text(err, color = DangerRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeletingAccount = true
                        deleteAccountError = null
                        coroutineScope.launch {
                            try {
                                // Force-refresh the ID token before the delete call so the
                                // backend sees a fresh Firebase token rather than a cached
                                // one that might be near-expiry. Mirrors Firebase's own
                                // "recent login required" semantics for sensitive ops.
                                runCatching { user.getIdToken(true).await() }
                                val uid = user.uid
                                SduiApiServiceFactory.create().deleteAccount(uid)
                                com.stationly.mobile.service.AuthLog.accountDeleted(uid)
                                authManager.logout()
                                onLoggedOut()
                            } catch (e: Exception) {
                                val msg = e.message.orEmpty()
                                val reauthNeeded = msg.contains("requires-recent-login", ignoreCase = true)
                                    || msg.contains("recent login", ignoreCase = true)
                                    || msg.contains("401")
                                    || msg.contains("403")
                                com.stationly.mobile.service.AuthLog.accountDeleteFailed(
                                    if (reauthNeeded) "reauth_required" else msg.ifBlank { e::class.simpleName.orEmpty() }
                                )
                                isDeletingAccount = false
                                deleteAccountError = if (reauthNeeded) {
                                    "For security, please sign out and sign in again before deleting your account."
                                } else {
                                    "Could not delete your account. Please check your connection and try again."
                                }
                            }
                        }
                    },
                    enabled = !isDeletingAccount,
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) {
                    if (isDeletingAccount) {
                        CircularProgressIndicator(color = DangerRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Text(daConfirm, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isDeletingAccount) {
                    TextButton(
                        onClick = { showDeleteAccountDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = White55)
                    ) { Text(daCancel) }
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
    provider: String, memberSince: String,
    onEditName: () -> Unit = {}
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
            // Avatar with amber ring. Google sign-ins carry a photoUrl from
            // Google, email sign-ins fall back to the first-letter monogram.
            // User-uploaded photos aren't supported on the Spark plan (no
            // Firebase Storage); revisit when we have Blaze or a self-host path.
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

            // Name + inline edit pencil. Whole row is clickable so the touch
            // target is generous on mobile.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onEditName)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    name, color = White90,
                    fontWeight = FontWeight.Bold, fontSize = 22.sp
                )
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Edit name",
                    tint = White55,
                    modifier = Modifier.size(16.dp)
                )
            }
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
private fun StationCard(station: SubscribedStation, isDeleting: Boolean = false, onDelete: () -> Unit) {
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

            if (isDeleting) {
                CircularProgressIndicator(
                    color = DangerRed, strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp).padding(0.dp)
                )
            } else {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, "Remove", tint = White25, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   Empty Stations Card
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun EmptyStationsCard(
    title: String = "No stations yet",
    subtitle: String = "Set up a board to start tracking departures"
) {
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
            Text(title, color = White55, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = White25, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
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
