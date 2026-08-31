package com.stationly.app.ui.profile

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.app.ui.common.LoadingOverlay
import com.stationly.app.ui.common.StationlySpinner
import com.stationly.app.ui.common.LocalOpenUrl
import com.stationly.app.ui.support.LocalSupport
import com.stationly.app.ui.support.SupportProfileCard
import com.stationly.app.ui.support.SupporterAvatarBadge
import com.stationly.app.ui.support.SupporterPill
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.app.ui.theme.DisplayFamily
import com.stationly.app.ui.theme.LocalThemeTokens
import com.stationly.app.resources.Res
import com.stationly.app.resources.stationly_logo
import com.stationly.core.model.sdui.SduiAppComponent
import org.jetbrains.compose.resources.painterResource

/**
 * Compose-Multiplatform (iOS) port of the redesigned Android ProfileScreen,
 * wired to the existing iOS ProfileViewModel (user info from uiState; sign-out /
 * delete-account / delete-station through the VM + Swift AuthBridge).
 *
 * iOS adaptations: drawn brand mark (composeResources not bundled yet).
 * Edit-name goes through PlatformAuthProvider.updateDisplayName (Swift
 * AuthBridge `updateDisplayName` command on iOS). SDUI About section +
 * homeConfig dialog strings reuse the same backend keys as Android.
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

/**
 * Where "Rate Stationly" goes on iOS. Becomes a real listing once the app
 * ships; until then it is a harmless 404 rather than a link that cannot open
 * at all. Swap in the `itms-apps://…?action=write-review` deep link (which
 * opens the review sheet directly) once the App Store ID exists.
 */
private const val APP_STORE_URL = "https://apps.apple.com/app/stationly"

private val ProfileAboutFallback: List<SduiAppComponent> = listOf(
    SduiAppComponent.Card(
        id = "about_info",
        title = "Stationly",
        body = "Live London departures. Buses, tubes, DLR and Overground on one board.",
        style = "brand"
    ),
    SduiAppComponent.Section(
        id = "links_section",
        components = listOf(
            SduiAppComponent.LinkRow(id = "website", title = "Visit Website", subtitle = "stationly.co.uk", url = STATIONLY_WEB_URL, icon = "public"),
            SduiAppComponent.LinkRow(id = "privacy", title = "Privacy Policy", subtitle = "How we handle your data", url = "$STATIONLY_WEB_URL/privacy", icon = "privacy_tip"),
            SduiAppComponent.LinkRow(id = "terms", title = "Terms of Service", subtitle = "Usage terms and conditions", url = "$STATIONLY_WEB_URL/terms", icon = "description"),
            SduiAppComponent.LinkRow(id = "contact", title = "Contact Us", subtitle = "Questions or feedback", url = "mailto:info@stationly.co.uk", icon = "email"),
            // Android's fallback carries a fifth "rate" row pointing at
            // `market://details?id=com.stationly.mobile`; iOS was missing the
            // row entirely. Same row, App Store destination — `market://` has
            // no handler on iOS and would dead-end (see the rewrite in
            // AboutSection for backend-sent Android URLs).
            SduiAppComponent.LinkRow(id = "rate", title = "Rate Stationly", subtitle = "Love the app? Let us know", url = APP_STORE_URL, icon = "star"),
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

    // Profile fetches home-config independently of the home screen, and is
    // reachable on a cold launch without ever having shown one. Feeding it here
    // too is what makes the support card correct on first open rather than on
    // second.
    val supportVm = LocalSupport.current
    LaunchedEffect(homeConfig) { supportVm?.onHomeConfig(homeConfig) }
    // Collected HERE, not inside the list: `item {}` bodies run in
    // LazyListScope, which is not a composable scope, so a collect inside one
    // would not compile — and hoisting it also means the list content lambda
    // reads plain values rather than re-collecting on every scroll.
    val supportState = supportVm?.uiState?.collectAsStateWithLifecycle()?.value

    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        com.stationly.app.ui.common.StationlyLogo(size = 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Stationly", style = MaterialTheme.typography.titleMedium, fontFamily = DisplayFamily, fontWeight = FontWeight.Black, color = White90, letterSpacing = (-0.3).sp)
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
                        name = uiState.displayName,
                        loading = uiState.isIdentityLoading,
                        email = uiState.email,
                        photoUrl = uiState.photoUrl,
                        // Passed rather than read inside: the state is already
                        // collected above for the support card, and collecting
                        // it twice on one screen would be two subscriptions to
                        // the same flow.
                        isSupporter = supportState?.isSupporter == true,
                        supporterLabel = supportState?.config?.badge?.label ?: "Supporter",
                        onEditName = { showEditNameDialog = true }
                    )
                }

                // NO "My Stations" list here. It was a second, read-only copy of
                // the home screen's own stations — the same boards, listed again,
                // with a delete button as the only thing you could do to them.
                // Stations are managed where they live: the home card's settings
                // for one station, and home settings for the order of all of them.
                //
                // NO screensaver row either. It sat here because the profile was
                // once the only settings screen the port had; the home screen now
                // has its own, the screensaver is a property of the home screen,
                // and two entrances to one destination is one too many. The SDUI
                // keys `profile.screensaver.title` / `.subtitle` are no longer
                // read by anything.

                // ── Support ──────────────────────────────────────────
                //
                // ABOVE "About Stationly" on purpose. About is where the app
                // explains itself; this is where the person reading that
                // explanation can act on it, and putting it underneath would
                // bury the one thing on this screen that is a request rather
                // than a setting.
                //
                // Rendered only when there is something to render: a payable
                // config, or a badge to show. A support card with no checkout
                // behind it is worse than none.
                if (supportVm != null && supportState != null && supportState.showProfileCard) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        SectionHeader(
                            supportState.strings["support_money.profile.section"] ?: "Support Stationly",
                            Icons.Rounded.Favorite,
                        )
                    }
                    item {
                        SupportProfileCard(
                            config = supportState.config,
                            strings = supportState.strings,
                            isSupporter = supportState.isSupporter,
                            contributionCount = supportState.contributionCount,
                            onOpen = { supportVm.openSheet() },
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

            // Shared modal overlay (Android parity) — unlike the previous
            // hand-rolled scrim it CONSUMES all pointer input, so a stray tap
            // on a station card can't interrupt a destructive auth op mid-call.
            LoadingOverlay(
                visible = uiState.isSigningOut,
                label = "Signing out…",
            )
            LoadingOverlay(
                visible = uiState.isDeletingAccount,
                label = "Deleting account…",
            )
        }
    }

    // ── Edit Display Name dialog ── (ported from Android ProfileScreen)
    if (showEditNameDialog) {
        val currentName = uiState.displayName
        var nameDraft by remember(currentName) { mutableStateOf(currentName) }
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
                        // Stationly's amber theme — match the AuthField look.
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor      = Amber,
                            unfocusedBorderColor    = White25,
                            focusedTextColor        = White90,
                            unfocusedTextColor      = White90,
                            focusedLabelColor       = Amber,
                            unfocusedLabelColor     = White55,
                            focusedContainerColor   = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor             = Amber,
                            errorBorderColor        = DangerRed,
                            errorLabelColor         = DangerRed,
                            errorCursorColor        = DangerRed
                        )
                    )
                    nameError?.let { Text(it, color = DangerRed, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isSavingName && nameDraft.trim() != currentName,
                    onClick = {
                        isSavingName = true
                        nameError = null
                        viewModel.updateDisplayName(nameDraft) { result ->
                            isSavingName = false
                            result.onSuccess { showEditNameDialog = false }
                            result.onFailure { e ->
                                nameError = e.message ?: "Couldn't save. Please try again."
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Amber)
                ) {
                    // Inline, because this one does NOT block the screen: the
                    // dialog stays up, Cancel stays reachable, and the rest of
                    // the app is untouched. Everything destructive on this
                    // screen uses the full-screen overlay instead — see the two
                    // `LoadingOverlay` calls above.
                    if (isSavingName) StationlySpinner(size = 16.dp, color = Amber)
                    else Text("Save", fontWeight = FontWeight.Bold)
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
                    // No spinner here. `isDeletingAccount` already raises the
                    // full-screen overlay, and a second spinner inside the
                    // button under it was the same wait told twice — the
                    // button's job while the overlay is up is to be disabled,
                    // which it is.
                    Text(daConfirm, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (!uiState.isDeletingAccount) TextButton(onClick = { showDeleteAccountDialog = false; viewModel.clearError() }, colors = ButtonDefaults.textButtonColors(contentColor = White55)) { Text(daCancel) }
            }
        )
    }
}

@Composable
private fun ProfileHeaderCard(
    name: String,
    loading: Boolean,
    email: String,
    photoUrl: String?,
    isSupporter: Boolean,
    supporterLabel: String,
    onEditName: () -> Unit
) {
    Surface(color = Surface1, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, White08)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Avatar with amber ring. Google sign-ins carry a photoUrl; email
            // sign-ins fall back to the first-letter monogram (blank while the
            // identity keys are still resolving — never an "U"-for-User).
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(96.dp).border(2.5.dp, Brush.linearGradient(listOf(Amber, Amber.copy(0.3f))), CircleShape))
                if (photoUrl != null) {
                    coil3.compose.AsyncImage(
                        model = photoUrl,
                        contentDescription = "Profile photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(86.dp).clip(CircleShape).background(Surface2, CircleShape)
                    )
                } else {
                    Box(Modifier.size(86.dp).background(Surface2, CircleShape), contentAlignment = Alignment.Center) {
                        if (!loading && name.isNotBlank()) {
                            Text(name.take(1).uppercase(), color = Amber, fontSize = 34.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
                // The same mark as the home screen's top bar, at the size this
                // avatar can carry. It sits INSIDE the amber ring's 96dp box so
                // it reads as attached to the photo rather than floating beside
                // it, and the ring colour is the card's own surface so it cuts a
                // clean hole in whatever is behind it.
                SupporterAvatarBadge(
                    visible = isSupporter,
                    size = 30.dp,
                    ringColor = Surface1,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            // Named, once, under the avatar. The mark alone says "something",
            // and a first-time supporter has no way to learn what unless the
            // word appears beside it at least somewhere on this screen.
            if (isSupporter) {
                Spacer(Modifier.height(10.dp))
                SupporterPill(label = supporterLabel)
            }
            Spacer(Modifier.height(16.dp))
            if (loading) {
                // Identity not yet resolved — skeletons, not "User"/"Recently".
                SkeletonBar(width = 150.dp, height = 22.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(width = 190.dp, height = 13.dp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name.ifBlank { "User" }, color = White90, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onEditName, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Edit, "Edit name", tint = White55, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                // Avatar, name, email. NO chips under them.
                //
                // There were two, and neither survived being read as a stranger
                // would read it. "Since Recently" was the fallback string
                // showing through — `member_since` is only written when Firebase
                // reports a creation date, so the card announced a date it did
                // not have. And the provider chip put a mail glyph beside the
                // word "Apple", because Material has no Apple mark and the code
                // branched only on Google.
                //
                // Both told the user something they already knew (their own
                // email is on the line above) in a shape that implied it
                // mattered. `signin_provider` is still written and still read —
                // `syncProfile` sends it to the backend — it just is not
                // decoration any more.
            }
        }
    }
}

/** Pulsing placeholder bar shown while the profile identity is still resolving. */
@Composable
private fun SkeletonBar(width: Dp, height: Dp) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.25f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(850, easing = EaseInOut), RepeatMode.Reverse),
        label = "skeleton_alpha"
    )
    Box(
        Modifier
            .size(width, height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = pulse * 0.18f))
    )
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
private fun AboutCard(card: SduiAppComponent.Card) {
    Surface(color = Surface1, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, White08)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(card.title ?: "Stationly", color = Amber, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(card.body ?: "", color = White55, fontSize = 13.sp, lineHeight = 19.sp)
            if (card.style == "brand") {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Read once. It cannot change while the process is
                    // alive, and this sits inside a list item that recomposes
                    // with the rest of the About card.
                    val version = remember { com.stationly.core.platform.Platform.appVersion() }
                    InfoChip("v$version")
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
        // Wording only, no spinner: `isSigningOut` raises the full-screen
        // overlay, which is already saying "Signing out…" over the top of this
        // row. Two spinners for one operation is how the app came to look like
        // it was waiting on two different things.
        if (isSigningOut) {
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
