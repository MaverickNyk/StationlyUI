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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stationly.app.ui.theme.StationlyThemeHost
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
 * ## Three modes, one screen
 * - **Manager** — no `appWidgetId`. "Which of my widgets?", plus adding one.
 *   Reached from inside the app.
 * - **Bind** — an `appWidgetId`. "Which station for this one?" Reached from the
 *   launcher (placement, Reconfigure) and from a widget's gear.
 * - **Pin** — "Which station for a NEW one?", then ask the launcher to place it.
 *
 * One screen because it is one job asked in a different order, and two of the
 * three share the station list verbatim.
 *
 * ## `RESULT_CANCELED` first, always
 * Set before anything else, so backing out — or the process dying mid-screen —
 * leaves the launcher believing the placement failed. It then removes the
 * widget rather than leaving an unbound one behind. `RESULT_OK` is the only
 * thing that commits it.
 */
class WidgetConfigureActivity : ComponentActivity() {

    private sealed interface Mode {
        /** Manage the widgets already on the home screen. */
        data object Manager : Mode

        /** Choose the station for one existing widget. */
        data class Bind(val appWidgetId: Int) : Mode

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

        val boards = boards()

        setContent {
            StationlyThemeHost {
                var mode: Mode by remember {
                    mutableStateOf(
                        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) Mode.Manager
                        else Mode.Bind(appWidgetId),
                    )
                }

                Scaffold { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp),
                    ) {
                        Spacer(Modifier.height(16.dp))
                        when (val m = mode) {
                            is Mode.Manager -> WidgetsScreen(
                                widgets = placedWidgets(boards),
                                canAdd = boards.isNotEmpty() &&
                                    WidgetPinner.canPin(this@WidgetConfigureActivity),
                                hasBoards = boards.isNotEmpty(),
                                onAdd = { mode = Mode.Pin },
                                onPick = { mode = Mode.Bind(it) },
                                onOpenApp = ::openAppAndFinish,
                            )

                            is Mode.Bind -> StationPicker(
                                title = "Which station?",
                                subtitle = "This widget will show live departures for the " +
                                    "station you pick. Add another widget for another station.",
                                boards = boards,
                                currentlyBound = WidgetBindingStore
                                    .boundStation(this@WidgetConfigureActivity, m.appWidgetId),
                                onPick = { commit(m.appWidgetId, it); finish() },
                                onNoBoards = ::openAppAndFinish,
                            )

                            is Mode.Pin -> StationPicker(
                                title = "Add a widget",
                                subtitle = "Pick the station for your new widget. Your launcher " +
                                    "will ask you where to put it.",
                                boards = boards,
                                currentlyBound = null,
                                onPick = ::pinAndFinish,
                                onNoBoards = ::openAppAndFinish,
                            )
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
    private fun placedWidgets(boards: List<UserSelection>): List<PlacedWidget> =
        AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, DepartureWidgetProvider::class.java))
            .map { id ->
                val bound = WidgetBindingStore.boundStation(this, id)
                PlacedWidget(id, boards.firstOrNull { it.groupingId == bound }?.stationName)
            }

    /**
     * One row per STATION, not per board. The binding is a `groupingId` — the
     * hub the user picked, which is what the home screen keys a card on and what
     * a person means by "a station". Keying on the full (station, line,
     * direction) triple would bind a widget to one direction of one line, so
     * editing that board's lines later would silently unbind their widget.
     */
    private fun boards(): List<UserSelection> =
        Platform.sqlStorage.getAllSelections().distinctBy { it.groupingId }

    /**
     * Write the binding and draw that widget — that one only, off the main
     * thread because it reads SQL. Rebinding one widget must not repaint the
     * others, which are on screen showing stations that did not change.
     */
    private fun commit(targetId: Int, groupingId: String) {
        WidgetBindingStore.bind(this, targetId, groupingId)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { DepartureWidgetProvider.updateOne(this@WidgetConfigureActivity, targetId) }
        }
        setResult(Activity.RESULT_OK, resultIntent())
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
        }
        finish()
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

/** One widget on the home screen, and what it currently shows. */
private data class PlacedWidget(
    val appWidgetId: Int,
    /** Null when the widget is unbound, or bound to a board since deleted. */
    val stationName: String?,
)

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
    Text("Widgets", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Each widget shows one station's live departures. Add as many as you like.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    if (!hasBoards) {
        Text(
            "Add a station in Stationly first, then come back and put it on your Home Screen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenApp) { Text("Open Stationly") }
        return
    }

    if (canAdd) {
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add a widget")
        }
        Spacer(Modifier.height(20.dp))
    } else {
        // Some launchers cannot be asked to place a widget. Give the manual
        // route rather than a button that does nothing.
        Text(
            "To add one: long-press your Home Screen, tap Widgets, then drag " +
                "Stationly out. You'll be asked which station it should show.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
    }

    if (widgets.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.GridView,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "No widgets on your Home Screen yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Text(
        "ON YOUR HOME SCREEN",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(widgets, key = { it.appWidgetId }) { widget ->
            Card(
                onClick = { onPick(widget.appWidgetId) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        widget.stationName ?: "Not set up yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (widget.stationName == null) "Tap to choose a station"
                        else "Tap to show a different station",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Which of the user's stations. Shared verbatim by BIND and PIN. */
@Composable
private fun StationPicker(
    title: String,
    subtitle: String,
    boards: List<UserSelection>,
    currentlyBound: String?,
    onPick: (String) -> Unit,
    onNoBoards: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        if (boards.isEmpty()) "This widget shows live departures for one of your stations."
        else subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    if (boards.isEmpty()) {
        Text("You haven't added a station yet.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onNoBoards) { Text("Open Stationly") }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(boards, key = { it.groupingId }) { board ->
            val isCurrent = board.groupingId == currentlyBound
            Card(
                onClick = { onPick(board.groupingId) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        board.stationName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        // The mode rather than the line: a hub can carry several
                        // lines and naming one would describe a board narrower
                        // than the widget is bound to.
                        board.mode.replaceFirstChar { it.uppercase() } +
                            if (isCurrent) " · currently shown" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
