package com.stationly.mobile.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.platform.ModeIconStore
import com.stationly.app.ui.common.SettingsCaption
import com.stationly.app.ui.common.SettingsSectionLabel
import com.stationly.app.ui.station.BoardArrangementSection
import com.stationly.app.ui.summary.components.lineColorForTheme
import com.stationly.app.ui.theme.StationlyThemeHost
import com.stationly.app.ui.theme.isDarkTheme
import com.stationly.core.model.UserSelection
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.BoardPin
import com.stationly.core.platform.Platform
import com.stationly.core.repository.UserSettings
import com.stationly.core.util.LineShortNames
import com.stationly.core.util.MultiLineBoardProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stationly.app.platform.HapticType
import com.stationly.app.ui.common.pressScale
import com.stationly.app.platform.performHaptic
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.SwapHoriz
import com.stationly.app.ui.common.SegmentedRow
import com.stationly.app.ui.common.pressHighlight
import com.stationly.core.model.user.PlatformNav
import com.stationly.app.ui.station.PinPicker
import androidx.compose.ui.draw.clip

/**
 * Everything about widgets, in one place, shaped for Android rather than ported
 * from iOS.
 *
 * ## The Android flow, and why it is not iOS's
 * iOS solves several stations with a WidgetKit stack, and edits a widget by
 * long-pressing it on the home screen — because an iOS app can neither place a
 * widget nor read one's configuration. Copying that would be copying a set of
 * limitations Android does not have.
 *
 * Android's own shape:
 *
 * ```
 *   Station settings --"Add to Home Screen"--+   (requestPinAppWidget: the
 *                                            |    launcher confirms, and the
 *                                            +--> widget arrives already
 *   Settings > Widgets --"Add a widget"------+    showing that station)
 *
 *   Settings > Widgets --> the list of placed widgets --> change any one
 *
 *   The widget's own gear -----------------------------> change THAT one
 *
 *   Widget gallery drag -------------------------------> "Which station?"
 * ```
 *
 * The first row is the point. The moment somebody wants a widget is while they
 * are looking at a station, and on Android the app can act on that then and
 * there — so most people never see a picker at all. The gallery drag still
 * works, because it is how Android users expect to add widgets; it is simply no
 * longer the only door.
 *
 * ## This is a settings screen, not a picker
 * It used to be three pickers: choose a station, commit, close. That was the
 * right shape while the only question was "which station", and the wrong one
 * the moment the widget's gear started opening it — a gear promises settings,
 * and a list that closes on the first tap is not settings.
 *
 * So CONFIGURE is now a page that stays open. The station list is its first
 * section, and underneath it sits the **same** [BoardArrangementSection] the
 * station's own settings screen shows: how many departures per platform, and
 * which platform to pin. Not a copy of it — the same composable, reading and
 * writing the same `BoardConfig`, which is what makes a change here show up on
 * the home screen card too. A widget is a view of a board, so the settings that
 * shape the board shape the widget.
 *
 * There is deliberately **no platform-sort control**. It existed, it was
 * removed on 2026-08-09, and it must not come back: its options acted at two
 * different levels, it was inert on every bus board, and the pin already
 * answers what sorting-by-platform was for. Block order is fixed — unassigned
 * last, then the pin, then the soonest train.
 *
 * ## Three modes, one screen
 * - **Manager** — no `appWidgetId`. "Which of my widgets?", plus adding one.
 *   Reached from inside the app.
 * - **Configure** — an `appWidgetId`. The settings page above. Reached from the
 *   launcher (placement, Reconfigure) and from a widget's gear.
 * - **Pin** — "Which station for a NEW one?", then ask the launcher to place it.
 *
 * ## `RESULT_CANCELED` first, always
 * Set before anything else, so backing out — or the process dying mid-screen —
 * leaves the launcher believing the placement failed. It then removes the
 * widget rather than leaving an unbound one behind. `RESULT_OK` is the only
 * thing that commits it.
 *
 * The launcher acts on the result when this Activity FINISHES, not when the
 * result is set, which is what lets CONFIGURE stay open after a first
 * placement: the binding is committed, the result is `OK`, and the user carries
 * on setting the widget up. The widget appears when they are done.
 */
class WidgetConfigureActivity : ComponentActivity() {

    private sealed interface Mode {
        /** Manage the widgets already on the home screen. */
        data object Manager : Mode

        /** The settings page for one existing widget. */
        data class Configure(val appWidgetId: Int) : Mode

        /** Choose the station for a widget that does not exist yet. */
        data object Pin : Mode
    }

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED, resultIntent())

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // A widget the user asked for from a station, seconds ago. The launcher
        // runs this Activity after pinning; there is nothing left to ask, so
        // bind it and get out of the way. See WidgetPinner for why the station
        // travels in prefs and why the claim expires.
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val pending = WidgetPinner.claimPending(this)
            if (pending != null) {
                commit(appWidgetId, pending)
                finish()
                return
            }
        }

        val hubs = hubs()

        setContent {
            StationlyThemeHost {
                var mode: Mode by remember {
                    mutableStateOf(
                        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) Mode.Manager
                        else Mode.Configure(appWidgetId),
                    )
                }

                val isFirstPlacement = remember {
                    appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
                        WidgetBindingStore.boundStation(
                            this@WidgetConfigureActivity,
                            appWidgetId,
                            WidgetBindingStore.currentUid(),
                        ) == null
                }

                Scaffold(
                    // ── The commit, pinned ──────────────────────────────────
                    //
                    // A bottom bar rather than a button at the end of the
                    // content, because on a FIRST placement backing out is
                    // RESULT_CANCELED and the launcher removes the widget:
                    // somebody who sets one up and presses back loses it. A bar
                    // that never scrolls away is the difference.
                    //
                    // Only in CONFIGURE. The manager has nothing to commit and
                    // the pin flow finishes on the tap that chooses.
                    bottomBar = {
                        if (mode is Mode.Configure) {
                            ActionBar(
                                label = if (isFirstPlacement) "Add widget" else "Done",
                                onClick = ::finish,
                            )
                        }
                    },
                ) { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .statusBarsPadding()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                      // Constrained and centred, so a tablet or an unfolded
                      // foldable gets a readable column rather than settings
                      // stretched across 10 inches. Same rule the screensaver
                      // settings screen uses.
                      Column(
                        Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                      ) {
                        Spacer(Modifier.height(16.dp))
                        when (val m = mode) {
                            is Mode.Manager -> WidgetsScreen(
                                widgets = placedWidgets(hubs),
                                canAdd = hubs.isNotEmpty() &&
                                    WidgetPinner.canPin(this@WidgetConfigureActivity),
                                hasBoards = hubs.isNotEmpty(),
                                onAdd = { mode = Mode.Pin },
                                onPick = { mode = Mode.Configure(it) },
                                onOpenApp = ::openAppAndFinish,
                            )

                            is Mode.Configure -> ConfigureScreen(
                                hubs = hubs,
                                appWidgetId = m.appWidgetId,
                                isFirstPlacement = isFirstPlacement,
                                onBind = { commit(m.appWidgetId, it) },
                                onDone = ::finish,
                                onNoBoards = ::openAppAndFinish,
                                loadPinOptions = ::pinOptions,
                                redraw = { redraw(m.appWidgetId) },
                            )

                            is Mode.Pin -> Column {
                                ScreenHeading(
                                    "Add a widget",
                                    "Pick the station for your new widget. Your launcher " +
                                        "will ask you where to put it.",
                                )
                                Spacer(Modifier.height(20.dp))
                                if (hubs.isEmpty()) {
                                    NoBoardsYet(::openAppAndFinish)
                                } else {
                                    SettingsSectionLabel("Choose station")
                                    Spacer(Modifier.height(10.dp))
                                    StationChoiceList(
                                        hubs = hubs,
                                        chosen = null,
                                        onPick = ::pinAndFinish,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                      }
                    }
                }
            }
        }
    }

    /**
     * Every widget on the home screen, with the station it currently shows.
     *
     * `getAppWidgetIds()` is the authority on what exists — the binding store
     * only says what each one is FOR, and can lag when a launcher is replaced or
     * a broadcast is dropped. System first, store second, so this never lists a
     * widget that is not there.
     */
    private fun placedWidgets(hubs: List<Hub>): List<PlacedWidget> =
        AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, DepartureWidgetProvider::class.java))
            .map { id ->
                val bound = WidgetBindingStore.boundStation(this, id, WidgetBindingStore.currentUid())
                PlacedWidget(id, hubs.firstOrNull { it.groupingId == bound })
            }

    /**
     * One entry per STATION, not per board.
     *
     * The binding is a `groupingId` — the hub the user picked, which is what the
     * home screen keys a card on and what a person means by "a station". Keying
     * on the full (station, line, direction) triple would bind a widget to one
     * direction of one line, so editing that board's lines later would silently
     * unbind their widget.
     *
     * The LINES are collected across every board at the hub, which is why this
     * groups rather than `distinctBy`-ing: a row showing one line at a station
     * the user tracks four lines at describes the board wrongly, and the row is
     * the only thing they have to recognise the station by.
     */
    private fun hubs(): List<Hub> =
        Platform.sqlStorage.getAllSelections()
            .groupBy { it.groupingId }
            .map { (groupingId, boards) ->
                Hub(
                    groupingId = groupingId,
                    name = boards.first().stationName,
                    mode = boards.first().mode,
                    lines = boards.map { it.line }.distinct(),
                    boards = boards,
                )
            }
            .sortedBy { it.name }

    /**
     * The platforms, poles and lines this hub can pin, read off its cached
     * predictions exactly the way the station settings screen reads them.
     *
     * Off the main thread: it is a SQL read per board plus the grouping pass.
     */
    private suspend fun pinOptions(hub: Hub): PinOptions = withContext(Dispatchers.IO) {
        val isBus = MultiLineBoardProcessor.isBus(hub.mode)
        val feeds = hub.boards.map { board ->
            MultiLineBoardProcessor.Feed(
                // The resolved per-direction naptan (the POLE), not the hub —
                // exactly what the home screen passes, so the picker groups the
                // way the real board does and can never offer a place that board
                // would not show.
                stationId = board.station,
                line = board.line,
                direction = board.direction,
                predictions = runCatching {
                    Platform.sqlStorage.getPredictions(board.station, board.line, board.direction)
                }.getOrNull().orEmpty(),
            )
        }
        PinOptions(
            isBus = isBus,
            // Exactly one of these is ever populated. A bus hub's poles have no
            // labels to offer, and a rail platform has no pole to name.
            platforms = if (isBus) emptyList()
            else MultiLineBoardProcessor.pinnablePlatforms(feeds = feeds, isBus = false),
            stops = if (isBus) MultiLineBoardProcessor.pinnableStops(feeds = feeds) else emptyList(),
            lines = hub.lines,
        )
    }

    /**
     * Write the binding and draw that widget — that one only, off the main
     * thread because it reads SQL. Rebinding one widget must not repaint the
     * others, which are on screen showing stations that did not change.
     */
    private fun commit(targetId: Int, groupingId: String) {
        val wasBoundTo = WidgetBindingStore.boundStation(this, targetId, WidgetBindingStore.currentUid())
        // Stamped with the account placing it, so another account signing in on
        // this phone gets the honest empty state rather than inheriting a live
        // widget in somebody else's configuration. See WidgetBindingStore.
        WidgetBindingStore.bind(this, targetId, groupingId, WidgetBindingStore.currentUid())
        // A widget pointed at a different station starts at that station's
        // FIRST platform. Keeping the index would open a Bank widget on "the
        // third one", which is a sentence about the station it used to show.
        if (wasBoundTo != groupingId) {
            WidgetPageStore.reset(this, targetId)
            // The pin goes with it: "Platform 9" is a sentence about the station
            // this widget used to show. The nav mode is about the WIDGET rather
            // than the station, so it stays.
            WidgetSettings.resetForRebind(this, targetId)
        }
        redraw(targetId)
        // A binding is a placement change: this is the moment a board acquires a
        // widget, or hands one over to another board. The app's own view of the
        // home screen is refreshed here rather than waiting for the next
        // foreground, so a user who binds from inside the app sees the station
        // screen agree with them immediately.
        WidgetPlacementProbe.observe(this)
        setResult(Activity.RESULT_OK, resultIntent())
    }

    /**
     * Repaint one widget.
     *
     * Also the settings page's feedback loop: changing "departures per platform"
     * has no visible effect until the widget is redrawn, and the widget is
     * usually behind this screen rather than on it. Redrawing on every change
     * means the answer is already correct when the user goes back to look.
     */
    private fun redraw(targetId: Int) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { DepartureWidgetProvider.updateOne(this@WidgetConfigureActivity, targetId) }
        }
    }

    /**
     * Hand the request to the launcher and step back.
     *
     * The user confirms in the launcher's own dialog — an app cannot put things
     * on somebody's home screen unasked, which is right. Finishing immediately
     * is deliberate: leaving this screen up behind that dialog would drop the
     * user back on a station picker they have already used.
     */
    private fun pinAndFinish(groupingId: String) {
        if (!WidgetPinner.pin(this, groupingId)) {
            // The launcher refused to even ask. Say so rather than closing
            // silently, which would read as the button doing nothing.
            Toast.makeText(
                this,
                "Your launcher can't add widgets this way. Long-press your Home Screen instead.",
                Toast.LENGTH_LONG,
            ).show()
            WidgetPinner.clearPending(this)
            // Stay where we are. The user is still in the app and there is
            // nothing on the home screen to go and look at.
            finish()
            return
        }
        // ── Land on the home screen, where the widget now is ────────────────
        //
        // Finishing alone drops the user back on whatever was behind this
        // screen, which is the app they started from — so the widget they just
        // added is behind it, and the flow ends with them pressing back twice
        // to find out whether it worked.
        //
        // An app cannot scroll the launcher to the page the widget landed on;
        // there is no API for it, and the launcher already animates there
        // itself. What an app CAN do is get out of the way, which is the half
        // that was missing.
        //
        // After `finish()`, so the app is not left sitting under the home
        // screen in the back stack.
        finish()
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
    }

    /**
     * No boards yet, so there is nothing to choose between. The widget is
     * deliberately NOT created — `RESULT_CANCELED` still stands, so the launcher
     * removes it. An empty widget the user has to come back and fix is worse
     * than no widget, and the app is one tap away.
     */
    private fun openAppAndFinish() {
        startActivity(
            Intent(this, com.stationly.mobile.MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
        finish()
    }

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    companion object {
        /** Open this screen for a widget that is already on the home screen. */
        fun reconfigureIntent(context: Context, appWidgetId: Int): Intent =
            Intent(context, WidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // From a RemoteViews PendingIntent, which has no task of its
                // own; CLEAR_TASK so a second tap does not stack a second copy.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

        /** Open the manager — "which of my widgets?" — from inside the app. */
        fun managerIntent(context: Context): Intent =
            Intent(context, WidgetConfigureActivity::class.java)
    }
}

// ── The data these screens draw ─────────────────────────────────────────────

/**
 * One station the user tracks, and everything a row needs to describe it.
 *
 * The mode carries the roundel, the lines carry the colours. Both are here
 * because a station row that is only a name is the same row for every station,
 * and picking the right one out of four then means reading rather than looking.
 */
private data class Hub(
    val groupingId: String,
    val name: String,
    val mode: String,
    val lines: List<String>,
    /** Every board at this hub — what the pin options are derived from. */
    val boards: List<UserSelection>,
)

/** One widget on the home screen, and what it currently shows. */
private data class PlacedWidget(
    val appWidgetId: Int,
    /** Null when the widget is unbound, or bound to a board since deleted. */
    val hub: Hub?,
)

/** What this hub offers the pin picker. */
private data class PinOptions(
    val isBus: Boolean = false,
    val platforms: List<String> = emptyList(),
    val stops: List<MultiLineBoardProcessor.StopOption> = emptyList(),
    val lines: List<String> = emptyList(),
)

// ── Screens ─────────────────────────────────────────────────────────────────

/**
 * MANAGER mode: the app's one widget destination.
 *
 * Adding comes FIRST, above the list. Somebody opening this with no widgets is
 * here to get one; somebody with three is here to change one. Putting the action
 * on top serves the first without burying the second, because three rows do not
 * push it off screen.
 */
@Composable
private fun WidgetsScreen(
    widgets: List<PlacedWidget>,
    canAdd: Boolean,
    hasBoards: Boolean,
    onAdd: () -> Unit,
    onPick: (Int) -> Unit,
    onOpenApp: () -> Unit,
) {
    ScreenHeading(
        "Widgets",
        "A widget shows one station's live departures on your Home Screen, " +
            "updated as the trains are. Add as many as you like, one for each station.",
    )
    Spacer(Modifier.height(20.dp))

    if (!hasBoards) {
        NoBoardsYet(onOpenApp)
        return
    }

    if (canAdd) {
        Button(onClick = { performHaptic(HapticType.TAP); onAdd() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add a widget")
        }
        Spacer(Modifier.height(24.dp))
    } else {
        // Some launchers cannot be asked to place a widget. Give the manual
        // route rather than a button that does nothing.
        SettingsCaption(
            "To add one: long-press your Home Screen, tap Widgets, then drag " +
                "Stationly out. You'll be asked which station it should show.",
        )
        Spacer(Modifier.height(24.dp))
    }

    if (widgets.isEmpty()) {
        NoWidgetsYet(canAdd = canAdd)
        return
    }

    SettingsSectionLabel("On your Home Screen")
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        widgets.forEach { widget ->
            val hub = widget.hub
            if (hub != null) {
                StationChoiceRow(
                    hub = hub,
                    isChosen = false,
                    trailing = "Change",
                    // This row goes somewhere rather than choosing something.
                    haptic = HapticType.TAP,
                    onClick = { onPick(widget.appWidgetId) },
                )
            } else {
                // Unbound, or bound to a board since deleted. Named as a state
                // to fix rather than dressed up as a station.
                UnboundWidgetRow(onClick = { onPick(widget.appWidgetId) })
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    SettingsCaption("Tap a widget to change its station or how much it shows.")
}

/**
 * Nothing on the home screen yet, said in a way that helps.
 *
 * ## Why this is not one grey line
 * It used to be "No widgets on your Home Screen yet." and nothing else, which
 * is a dead end on the one screen that exists to get you out of it. Somebody
 * opening this has either never had a widget or has just removed their last
 * one; either way the question in their head is what a widget IS and how to get
 * one, and the screen knew both and said neither.
 *
 * ## It draws the thing rather than describing it
 * A picture of a departure board answers "what will I get" in less time than a
 * sentence can, and it is the same dark panel the widget actually renders, so
 * nobody is surprised by what lands.
 *
 * ## Both routes, and which one this launcher supports
 * `canAdd` is `requestPinAppWidget` support, read at composition because a
 * launcher can be swapped. Where it is missing the button is not shown, so the
 * manual route is the only instruction and it has to be complete.
 */
@Composable
private fun NoWidgetsYet(canAdd: Boolean) {
    val border = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A miniature of the real thing: the dark panel, amber text, a platform
        // header and two departures. Deliberately not a screenshot, which would
        // go stale the first time the board's design moved.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(WidgetPreviewInk)
                .padding(10.dp),
        ) {
            Text(
                "Your station",
                color = WidgetPreviewAmber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            PreviewRow("Platform 2", "", bold = true)
            PreviewRow("Stratford", "Due")
            PreviewRow("Stratford", "4 min")
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Live departures on your Home Screen",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        SettingsCaption(
            if (canAdd) {
                "One station per widget, updating itself as the trains move. " +
                    "Tap Add a widget above, or long-press your Home Screen and " +
                    "choose Widgets."
            } else {
                "One station per widget, updating itself as the trains move. " +
                    "To add one, long-press your Home Screen, tap Widgets, then " +
                    "drag Stationly out."
            },
        )
        Spacer(Modifier.height(16.dp))
        // Removal is the launcher's, not ours. Said plainly rather than offered
        // as a button that cannot work: `deleteAppWidgetId` only affects widgets
        // the CALLER hosts, and a placed widget belongs to the launcher's host.
        // There is no API that lets this app remove one.
        SettingsCaption(
            "To remove a widget later, touch and hold it on your Home Screen and " +
                "choose Remove. That one is the launcher's job, not ours.",
        )
    }
}

/** One line of the miniature board. */
@Composable
private fun PreviewRow(left: String, right: String, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            left,
            color = WidgetPreviewAmber,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            textAlign = if (bold) androidx.compose.ui.text.style.TextAlign.Center
            else androidx.compose.ui.text.style.TextAlign.Start,
        )
        if (right.isNotBlank()) {
            Text(right, color = WidgetPreviewAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The widget's own ink, not the app theme's.
 *
 * The board is dark and amber in every app theme — that is the one thing about
 * it that does not follow the user's choice — so a preview drawn in theme
 * colours would be a preview of something else.
 */
private val WidgetPreviewInk = androidx.compose.ui.graphics.Color(0xFF0D0D0D)
private val WidgetPreviewAmber = androidx.compose.ui.graphics.Color(0xFFFFC819)

/**
 * CONFIGURE mode: one widget's settings page.
 *
 * ## It is about a STATION, and says so before anything else
 * The first version opened on a list of every station with the current one
 * ticked, which made the page read as "pick a station" every time it was
 * opened — including the nine times out of ten it was opened from a widget's
 * own gear, where the station is not in question and the user came to change
 * something else.
 *
 * So the station is a HEADER now: its roundel, its name, its lines. The page
 * is unmistakably the settings for that widget, and changing which station it
 * shows is a deliberate action behind a row that says so, rather than a list
 * sitting open waiting to be mis-tapped.
 *
 * ## The commit is pinned to the bottom, and it is not decoration
 * Every setting here writes through immediately, so the button is "I am done"
 * rather than "save my changes" — but it still has to be obvious, because on a
 * FIRST placement backing out is `RESULT_CANCELED` and the launcher removes the
 * widget. Someone who sets a widget up and presses back loses it. A sticky bar
 * that never scrolls away is the difference between that and a widget.
 *
 * Its label changes with the stake: "Add widget" the first time, "Done" after.
 */
@Composable
private fun ConfigureScreen(
    hubs: List<Hub>,
    appWidgetId: Int,
    isFirstPlacement: Boolean,
    onBind: (String) -> Unit,
    onDone: () -> Unit,
    onNoBoards: () -> Unit,
    loadPinOptions: suspend (Hub) -> PinOptions,
    redraw: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var bound by remember {
        mutableStateOf(WidgetBindingStore.boundStation(context, appWidgetId, WidgetBindingStore.currentUid()))
    }
    // Open on the picker only when there is genuinely nothing to be about.
    var choosing by remember { mutableStateOf(bound == null) }
    val hub = hubs.firstOrNull { it.groupingId == bound }

    // ── The widget's OWN settings ───────────────────────────────────────────
    //
    // Not `UserSettings`. This screen used to write the station's BoardConfig,
    // so changing a widget silently changed the card inside the app and the two
    // could never be set differently — even though a card sits in a scrolling
    // page and a widget sits in a fixed cell on somebody's home screen. Owner's
    // rule, 2026-09-12. See WidgetSettings.
    var widgetPin by remember(appWidgetId) {
        mutableStateOf(WidgetSettings.pinOf(context, appWidgetId))
    }
    var widgetNav by remember(appWidgetId) {
        mutableStateOf(WidgetSettings.navOf(context, appWidgetId))
    }

    var options by remember { mutableStateOf(PinOptions()) }
    LaunchedEffect(hub?.groupingId) {
        options = hub?.let { loadPinOptions(it) } ?: PinOptions()
    }

    if (hubs.isEmpty()) {
        ScreenHeading("Widget", "")
        Spacer(Modifier.height(20.dp))
        NoBoardsYet(onNoBoards)
        return
    }

    // ── Picking a station is its own screen, not a section ──────────────────
    if (choosing || hub == null) {
        ScreenHeading(
            "Choose station",
            "This widget will show live departures for the station you pick. " +
                "Add another widget for another station.",
        )
        Spacer(Modifier.height(20.dp))
        StationChoiceList(
            hubs = hubs,
            chosen = bound,
            onPick = { groupingId ->
                bound = groupingId
                onBind(groupingId)
                choosing = false
            },
        )
        if (hub != null) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { performHaptic(HapticType.TAP); choosing = false },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }
        return
    }

    // ── The station this widget is for ──────────────────────────────────────
    StationHeaderCard(hub = hub, onChange = { choosing = true })

    Spacer(Modifier.height(28.dp))

    // ── Pin, on the widget ──────────────────────────────────────────────────
    //
    // The same picker the station's settings screen uses, over the same
    // vocabulary, writing somewhere else. A second copy would drift the moment
    // one of them learned a new pin kind.
    if (options.platforms.isNotEmpty() || options.stops.isNotEmpty() ||
        options.lines.size > 1 || widgetPin != null
    ) {
        SettingsSectionLabel("Pin to top")
        Spacer(Modifier.height(10.dp))
        PinPicker(
            pin = widgetPin,
            platforms = options.platforms,
            stops = options.stops,
            lines = options.lines,
            onPin = { next ->
                widgetPin = next
                WidgetSettings.setPin(context, appWidgetId, next)
                redraw()
            },
        )
        Spacer(Modifier.height(10.dp))
        SettingsCaption(
            if (widgetPin == null) {
                "Nothing pinned. The platform with the soonest departure comes first."
            } else {
                "Pinned on this widget only. The board inside the app keeps its own order."
            },
        )
        Spacer(Modifier.height(28.dp))
    }

    // ── No depth control here, deliberately ─────────────────────────────────
    //
    // The station's own settings screen has one, because a card inside a
    // scrolling page can be any height. A widget cannot: it occupies a cell on
    // somebody's home screen, and a control that changes its height is a
    // control for moving everything around it. Stepping between platforms made
    // that visible — a platform with two trains drew a shorter page than one
    // with three, and the widget resized as you pressed the arrow.
    //
    // So the widget is always three per platform, padded with blanks when the
    // data is thinner, which is the iOS widget principle and the owner's call
    // on 2026-09-12. `PlatformPages.bodyPadded` is where that is enforced.

    // ── How you reach the platforms that do not fit ─────────────────────────
    //
    // Widget-only, and deliberately not on the station's settings screen: the
    // home screen scrolls because it is a page, and a card inside a scrolling
    // page cannot sensibly page. A control that did nothing on the screen it
    // was shown on would be worse than no control.
    Spacer(Modifier.height(28.dp))
    SettingsSectionLabel("Platforms")
    Spacer(Modifier.height(10.dp))
    SegmentedRow(
        options = PlatformNav.entries,
        selected = widgetNav,
        onSelect = { nav ->
            widgetNav = nav
            WidgetSettings.setNav(context, appWidgetId, nav)
            redraw()
        },
        label = { it.label },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    SettingsCaption(
        when (widgetNav) {
            PlatformNav.SCROLL ->
                "Every platform in one list. Scroll inside the widget to reach " +
                    "the rest. On a small widget that is fiddly."
            PlatformNav.STEP ->
                "One platform at a time, with an arrow either side of its name. " +
                    "A station with one platform shows it without the arrows."
        },
    )

    Spacer(Modifier.height(10.dp))

    Spacer(Modifier.height(28.dp))
}

/**
 * The station this widget is for, and the one way to change it.
 *
 * A card rather than a plain heading: it is the SUBJECT of the page, and it has
 * to look like the thing every setting below is about. The "Change station" row
 * is attached to it for the same reason — changing the station is a fact about
 * the header, not another setting.
 */
@Composable
private fun StationHeaderCard(hub: Hub, onChange: () -> Unit) {
    val border = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = remember(hub.mode) { ModeIconStore.cachedIconBitmap(hub.mode) }
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                CircleShape,
                            ),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "THIS WIDGET SHOWS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        hub.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dark = isDarkTheme()
                        hub.lines.take(4).forEach { line ->
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(lineColorForTheme(line, dark), CircleShape),
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        if (hub.lines.isNotEmpty()) Spacer(Modifier.width(2.dp))
                        Text(
                            LineShortNames.listLines(hub.lines)
                                .ifBlank { hub.mode.replaceFirstChar { c -> c.uppercase() } },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(1.dp)
                    .background(border),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressHighlight(onClick = onChange)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.SwapHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Change station",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────────

/**
 * The one action that commits this screen, pinned above the navigation bar.
 *
 * Full width and filled, because it is the primary action and the only one —
 * an outlined or text button here would read as optional, and on a first
 * placement it is the opposite of optional.
 */
@Composable
private fun ActionBar(label: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = { performHaptic(HapticType.TAP); onClick() },
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().height(52.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Title and the sentence under it. One shape, so every mode reads the same. */
@Composable
private fun ScreenHeading(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The one thing to do when there is nothing to configure yet. */
@Composable
private fun NoBoardsYet(onOpenApp: () -> Unit) {
    Text(
        "You haven't added a station yet.",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    SettingsCaption(
        "Add one in Stationly first. It'll be here when you come back, ready to put on your Home Screen.",
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = { performHaptic(HapticType.TAP); onOpenApp() }) { Text("Open Stationly") }
}

/** The user's stations, as rows they can recognise at a glance. */
@Composable
private fun StationChoiceList(
    hubs: List<Hub>,
    chosen: String?,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        hubs.forEach { hub ->
            StationChoiceRow(
                hub = hub,
                isChosen = hub.groupingId == chosen,
                onClick = { onPick(hub.groupingId) },
            )
        }
    }
}

/**
 * One station: its roundel, its name, its lines in their own colours.
 *
 * ## Why the roundel and not a generic icon
 * It is the same cached bitmap the home screen card, the station list and the
 * widget itself draw — the backend's own artwork for that mode — so a station
 * looks like itself everywhere. A picker that describes stations differently
 * from the screen they were added on makes the user re-identify them.
 *
 * The tinted disc is the pre-first-sync fallback, not a second design: a device
 * that has not yet fetched `/modes` still gets a row of the right shape.
 *
 * ## Why a dot per line
 * The colours survive the truncation the names may not, so a station with four
 * lines still shows that it has four even when the row is too narrow to name
 * them. Same rule as the station order list.
 */
@Composable
private fun StationChoiceRow(
    hub: Hub,
    isChosen: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
    haptic: HapticType? = HapticType.SELECTION,
) {
    val border = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    androidx.compose.material3.Surface(
        // `pressScale`, not `Surface(onClick =)`. The Material overload draws a
        // ripple, which is the loudest tell that a Compose screen is not a
        // native one, and every other card in this app shrinks instead. It also
        // carries the haptic — see Press.kt.
        //
        // SELECTION rather than TAP when this row IS the choice: picking a
        // station out of three says "you are on that one now". A row that
        // merely navigates (the manager's "Change") says "that registered", so
        // the caller passes TAP.
        modifier = Modifier.fillMaxWidth().pressScale(onClick = onClick, haptic = haptic),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = if (isChosen) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isChosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else border,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = remember(hub.mode) { ModeIconStore.cachedIconBitmap(hub.mode) }
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(28.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            CircleShape,
                        ),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hub.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dark = isDarkTheme()
                    hub.lines.take(4).forEach { line ->
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(lineColorForTheme(line, dark), CircleShape),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (hub.lines.isNotEmpty()) Spacer(Modifier.width(2.dp))
                    Text(
                        // The shared rule — see LineShortNames.listLines.
                        LineShortNames.listLines(hub.lines)
                            .ifBlank { hub.mode.replaceFirstChar { c -> c.uppercase() } },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                isChosen -> {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "Currently shown",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                trailing != null -> {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        trailing,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * A widget with no station, or one whose station has been deleted.
 *
 * Stated plainly rather than shown as a blank station row. The widget itself
 * says the same thing — it never guesses a station — and the two surfaces
 * agreeing is what makes it read as a thing to fix rather than a thing broken.
 */
@Composable
private fun UnboundWidgetRow(onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().pressScale(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Not set up yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Choose a station for this one",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { performHaptic(HapticType.TAP); onClick() }) { Text("Set up") }
        }
    }
}
