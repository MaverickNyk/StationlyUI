@file:OptIn(ExperimentalMaterial3Api::class)
package com.stationly.mobile.ui.summary

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stationly.mobile.R
import com.stationly.mobile.ui.common.AnnouncementBanner
import com.stationly.mobile.ui.common.NotificationPermissionEffect
import com.stationly.mobile.ui.common.rememberFirebaseAuthState
import com.stationly.mobile.ui.summary.components.*
import com.stationly.mobile.ui.theme.TflAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    onNavigateToSelection: () -> Unit,
    onNavigateToProfile: () -> Unit,
    viewModel: SummaryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val selections by viewModel.selections.collectAsState()
    val predictions by viewModel.predictions.collectAsState()
    val lineStatuses by viewModel.lineStatuses.collectAsState()
    val failedLineStatusKeys by viewModel.failedLineStatusKeys.collectAsState()
    val stationUpdates by viewModel.stationUpdates.collectAsState()
    val sduiPayloads by viewModel.sduiPayloads.collectAsState()
    val announcement by viewModel.announcement.collectAsState()
    val homeConfig by viewModel.homeConfig.collectAsState()
    val forceUpdate by viewModel.forceUpdate.collectAsState()
    val deletingBoardId by viewModel.isDeletingBoard.collectAsState()
    val showWidgetPromo by viewModel.showWidgetPromo.collectAsState()
    val showDreamPromo by viewModel.showDreamPromo.collectAsState()
    val showNotificationDeniedBanner by viewModel.showNotificationDeniedBanner.collectAsState()

    val firebaseUser by rememberFirebaseAuthState()
    val userName = firebaseUser?.displayName?.split(" ")?.firstOrNull()
        ?: firebaseUser?.email?.substringBefore('@')

    // Ask the user for POST_NOTIFICATIONS (API 33+) on first arrival to the
    // home screen. Without this prompt the device default `importance=NONE`
    // sticks and every notification — admin push, line-status auto-alert,
    // future alarms — silently no-ops inside `NotificationDispatcher.dispatch`.
    // The effect itself is idempotent and a no-op on older Android, on
    // already-granted state, and after the first ask. See its docstring.
    //
    // On decision (grant OR deny), re-evaluate the "notifications off"
    // banner so the user sees it immediately on denial without waiting
    // for the next ON_RESUME.
    NotificationPermissionEffect(
        onDecision = { viewModel.checkNotificationDeniedBanner() }
    )

    // Reload selections from SQLite whenever this screen resumes.
    // Handles the case where ProfileScreen (or any other screen) deleted a station
    // from SQLite while SummaryViewModel's in-memory cache was stale.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadSelectionsFromDb()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SummaryTopBar(
                onNavigateToProfile = onNavigateToProfile,
                onNavigateToSelection = onNavigateToSelection,
                selectionsEmpty = selections.isEmpty(),
            )
        }
    ) { padding ->
        // Flat canvas — earlier surface→background gradient created a
        // visible band right under the TopAppBar (TopAppBar uses
        // `background`, gradient top is `surface` which is a slightly
        // different shade). Flat `background` blends both seamlessly.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            // Tiny theme toggle pinned to the screen's bottom-right,
            // right above the system gesture-nav handle. Compact mode
            // makes it small + low-alpha so it's discoverable without
            // ever competing with the actual content above.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(end = 6.dp, bottom = 2.dp)
                    .zIndex(2f),
            ) {
                com.stationly.mobile.ui.common.ThemeToggleButton(compact = true)
            }

            AnimatedContent(
                targetState = selections,
                transitionSpec = {
                    fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
                },
                label = "selections_content"
            ) { currentSelections ->
                if (currentSelections.isEmpty()) {
                    EmptyStationsState(onNavigateToSelection, strings = homeConfig)
                } else {
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refreshAll() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            // SummaryHeader (greeting, clock, "Live · N board
                            // active") intentionally removed — the brand
                            // lockup in the top bar already establishes
                            // context; the boards are what the user came for.
                            announcement?.let { banner ->
                                item(key = "announcement_${banner.id}") {
                                    AnnouncementBanner(
                                        announcement = banner,
                                        onDismiss = { viewModel.dismissAnnouncement() }
                                    )
                                }
                            }

                            // SDUI master switches — if the backend sets `show`
                            // to "false", suppress the banner regardless of
                            // local detection. Defaults to true for back-compat.
                            val widgetPromoEnabled = (homeConfig["home.promo.widget.show"] ?: "true").equals("true", ignoreCase = true)
                            val dreamPromoEnabled  = (homeConfig["home.promo.dream.show"]  ?: "true").equals("true", ignoreCase = true)

                            // SDUI kill-switch for the banner — same shape as
                            // `home.promo.widget.show` / `home.promo.dream.show`.
                            // Defaults true; backend can set "false" to
                            // suppress the banner entirely (e.g. during a
                            // notification-platform outage when "enable
                            // notifications" wouldn't actually help).
                            val notifBannerEnabled = (homeConfig["home.notif_denied.show"] ?: "true")
                                .equals("true", ignoreCase = true)

                            if (showNotificationDeniedBanner && notifBannerEnabled) {
                                item(key = "notification_denied") {
                                    NotificationDeniedBanner(
                                        strings = homeConfig,
                                        onDismiss = { viewModel.dismissNotificationDeniedBanner() },
                                        onEnable = { viewModel.dismissNotificationDeniedBanner() },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }

                            if (showWidgetPromo && widgetPromoEnabled) {
                                item(key = "widget_promo") {
                                    WidgetPromoCard(
                                        strings = homeConfig,
                                        onDismiss = { viewModel.dismissWidgetPromo() },
                                        onAdd = { viewModel.hideWidgetPromoForSession() },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }

                            if (showDreamPromo && dreamPromoEnabled) {
                                item(key = "dream_promo") {
                                    DreamPromoCard(
                                        strings = homeConfig,
                                        onDismiss = { viewModel.dismissDreamPromo() },
                                        onSetUp = { viewModel.hideDreamPromoForSession() },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }

                            items(currentSelections, key = { "${it.station}_${it.line}" }) { selection ->
                                val selectionPredictions = predictions[selection.station] ?: emptyList()
                                val statusKey = "${selection.mode}_${selection.line}".lowercase()

                                Box(modifier = Modifier.animateItem()) {
                                    Board(
                                        selection = selection,
                                        predictions = selectionPredictions,
                                        hasPredictions = selectionPredictions.isNotEmpty(),
                                        lineStatus = lineStatuses[statusKey],
                                        lineStatusFailed = failedLineStatusKeys.contains(statusKey),
                                        sduiPayload = sduiPayloads[selection.station],
                                        lastUpdated = stationUpdates[selection.station] ?: 0L,
                                        onDelete = { if (deletingBoardId == null) viewModel.deleteSelection(selection) },
                                        homeConfig = homeConfig,
                                        isDeleting = deletingBoardId == selection.station
                                    )
                                }
                            }

                            item {
                                StationExploreSection(lineStatuses = lineStatuses, strings = homeConfig)
                            }
                        }
                    }
                }
            }
            
            // ── Update nudge — dismissible bottom dialog, never blocks the app ──
            var updateDismissed by remember { mutableStateOf(false) }
            if (forceUpdate && !updateDismissed) {
                UpdateNudgeDialog(
                    title    = homeConfig["app.update.title"]   ?: "New update available",
                    message  = homeConfig["app.update.message"] ?: "Update Stationly for the latest features and improvements.",
                    cta      = homeConfig["app.update.cta"]     ?: "Update Now",
                    dismiss  = homeConfig["app.update.dismiss"] ?: "Maybe Later",
                    storeUrl = homeConfig["app.storeUrl"]       ?: "https://play.google.com/store/apps/details?id=com.stationly.mobile",
                    onDismiss = { updateDismissed = true }
                )
            }

            // ── Slim offline banner — non-blocking, slides in from top ──
            com.stationly.mobile.ui.common.OfflineBanner(
                visible = uiState.isBackendOffline,
                onRetry = { viewModel.retryLoad() },
                onDismiss = { viewModel.clearError() }
            )

            // Bottom decorative glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, TflAmber.copy(alpha = 0.03f))
                        )
                    )
            )
        }
    }
}

@Composable
private fun SummaryTopBar(
    onNavigateToProfile: () -> Unit,
    onNavigateToSelection: () -> Unit,
    selectionsEmpty: Boolean,
) {
    CenterAlignedTopAppBar(
        title = {
            // Single-line brand lockup: logo + wordmark, vertically
            // centred. Previously a two-line "Stationly / Live Network"
            // stack made the logo float between them; that was dropped
            // when we removed the SummaryHeader greeting block.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.stationly_logo),
                    contentDescription = "Logo",
                    modifier = Modifier.size(32.dp).clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Stationly",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = com.stationly.mobile.ui.theme.DisplayFamily,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    letterSpacing = (-0.5).sp
                )
            }
        },
        navigationIcon = {
            val firebaseUser by rememberFirebaseAuthState()
            val photoUrl = firebaseUser?.photoUrl?.toString()
            IconButton(
                onClick = onNavigateToProfile,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                if (photoUrl != null) {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = "Profile",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f), CircleShape)
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val initial = (firebaseUser?.displayName
                                ?: firebaseUser?.email ?: "U").take(1).uppercase()
                            Text(
                                initial,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        },
        actions = {
            // Theme toggle moved to a floating mini-button at the screen's
            // bottom-right so it no longer competes with the edit/add
            // pencil for the same corner.
            IconButton(
                onClick = onNavigateToSelection,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (selectionsEmpty) Icons.Default.Add else Icons.Default.Edit,
                            contentDescription = "Action",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor    = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        )
    )
}

/**
 * Shared layout for the home-screen promo cards (widget + screensaver
 * today; any future "set up X" prompts can reuse this). Title / subtitle /
 * cta are SDUI-driven by the caller — defaults come from homeConfig with
 * hardcoded fallbacks so the banner never renders blank.
 */
@Composable
private fun PromoBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    cta: String?,
    onCta: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }

            if (!cta.isNullOrBlank()) {
                TextButton(
                    onClick = onCta,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(cta, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun WidgetPromoCard(
    strings: Map<String, String>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = AppWidgetManager.getInstance(context)
    val canPin = manager.isRequestPinAppWidgetSupported

    PromoBanner(
        icon = Icons.Default.Add,
        title    = strings["home.promo.widget.title"]    ?: "Add a home screen widget",
        subtitle = strings["home.promo.widget.subtitle"] ?: "Pin your live board for one-tap glances — no need to open the app",
        cta      = if (canPin) strings["home.promo.widget.cta"] ?: "Add" else null,
        onCta    = {
            val provider = ComponentName(context, com.stationly.mobile.widget.DepartureWidgetProvider::class.java)
            // After user confirms in the system dialog, fire home intent so
            // they land on the home screen and can resize / reposition the widget.
            val goHome = PendingIntent.getActivity(
                context, 0,
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.requestPinAppWidget(provider, null, goHome)
            onAdd()
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/**
 * Surfaces when the user has denied (or never granted) the
 * `POST_NOTIFICATIONS` runtime permission. Without notifications enabled,
 * line-status auto-alerts and admin pushes silently no-op inside
 * `NotificationDispatcher` — without this banner the user has no way to
 * know they're missing them.
 *
 * Tap "Enable" → opens the app-specific notification settings screen.
 * The banner reflows on next `ON_RESUME` (via `checkNotificationDeniedBanner`)
 * so flipping the permission back on in Settings makes it disappear without
 * another nudge.
 */
@Composable
private fun NotificationDeniedBanner(
    strings: Map<String, String>,
    onDismiss: () -> Unit,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    PromoBanner(
        icon = Icons.Default.Notifications,
        title    = strings["home.notif_denied.title"]
            ?: "Turn on notifications",
        subtitle = strings["home.notif_denied.subtitle"]
            ?: "Stationly can alert you when your line has delays, closures, or recovers.",
        cta      = strings["home.notif_denied.cta"] ?: "Enable",
        onCta    = {
            // System app-notification settings deep-link. Available on
            // API 26+; we're already API 33+ if this banner is showing
            // (NotificationPermissionEffect is a no-op below TIRAMISU).
            val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback: generic app settings page.
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            onEnable()
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
private fun DreamPromoCard(
    strings: Map<String, String>,
    onDismiss: () -> Unit,
    onSetUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    PromoBanner(
        icon = Icons.Default.Bedtime,
        title    = strings["home.promo.dream.title"]    ?: "Set as Screensaver",
        subtitle = strings["home.promo.dream.subtitle"] ?: "Live departures when docked or charging",
        cta      = strings["home.promo.dream.cta"]      ?: "Set up",
        onCta    = {
            // Opens system Settings → Display → Screen saver. ACTION_DREAM_SETTINGS
            // is a public Settings action; FLAG_ACTIVITY_NEW_TASK keeps us safe if
            // LocalContext is the app context, not an activity context.
            val intent = Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            onSetUp()
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
private fun UpdateNudgeDialog(
    title: String,
    message: String,
    cta: String,
    dismiss: String,
    storeUrl: String,
    onDismiss: () -> Unit
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = androidx.compose.ui.Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.stationly_logo),
                    contentDescription = null,
                    modifier = androidx.compose.ui.Modifier.size(52.dp).clip(CircleShape)
                )
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(androidx.compose.ui.Modifier.height(4.dp))
                Button(
                    onClick = { uriHandler.openUri(storeUrl); onDismiss() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor   = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(cta, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                TextButton(onClick = onDismiss, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                    Text(
                        dismiss,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
