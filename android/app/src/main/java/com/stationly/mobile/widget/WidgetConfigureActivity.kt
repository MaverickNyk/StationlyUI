package com.stationly.mobile.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * Which station is this widget for?
 *
 * ## Why this screen is the answer to "Android has no widget stack"
 * It does not need one. iOS solves several stations with a WidgetKit stack —
 * one frame, many configurations, the user swipes — and Android's home screen is
 * a free grid that has always allowed many instances of one provider, each with
 * its own `appWidgetId`. Two stations is two widgets. What Android was missing
 * was not a stack, it was a way to tell each instance which station it is for,
 * and that is a `android:configure` Activity: the launcher starts it when the
 * widget is dropped and does not create the widget at all unless it returns
 * `RESULT_OK`.
 *
 ## Four ways in, one screen, two modes
 *  1. **Placement.** The launcher starts this with `EXTRA_APPWIDGET_ID` when the
 *     user drags the widget out of the gallery.
 *  2. **The gear on any widget**, bound or not — the widget you tapped is the
 *     widget you meant, so it opens straight on that one's station picker.
 *  3. **Reconfigure**, on Android 9+, from the launcher's own widget menu
 *     (`widgetFeatures="reconfigurable"` in `departure_widget_info.xml`).
 *  4. **From inside the app** — Home settings → Widget stations. No id, so the
 *     screen opens in MANAGER mode: every placed widget, the station each one
 *     shows, tap to change. **This is the one iOS can never have.** An iOS app
 *     cannot read a widget's AppIntent configuration at all, so it has no list
 *     to show and no binding to write; changing an iOS widget means finding it
 *     on the home screen and long-pressing it.
 *
 * The two modes are one screen because they are one job with the question asked
 * in a different order: the launcher already knows which widget and asks which
 * station; the app knows neither and asks which widget first.
 *
 * ## `RESULT_CANCELED` first, always
 * Set before anything else, so backing out — or the process dying mid-screen —
 * leaves the launcher believing the placement failed. It then removes the widget
 * rather than leaving an unbound one on the home screen. Returning `RESULT_OK`
 * is the only thing that commits it, and this screen does that only after a
 * station has actually been chosen.
 */
class WidgetConfigureActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // FIRST. See the class KDoc — a cancelled configuration must not leave a
        // widget behind, and every path out of this screen that is not an
        // explicit choice is a cancellation.
        setResult(Activity.RESULT_CANCELED, resultIntent())

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // One read, at open. Neither list can change while this screen is up:
        // it is the only thing on screen and the app is not running behind it.
        val boards = boards()

        setContent {
            StationlyThemeHost {
                // Which widget is being configured. Starts as whatever launched
                // this — an id from the launcher, or none, which means the user
                // came from inside the app and is choosing a widget first.
                var target by remember { mutableStateOf(appWidgetId) }

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
                        if (target == AppWidgetManager.INVALID_APPWIDGET_ID) {
                            WidgetList(
                                widgets = placedWidgets(boards),
                                onPick = { target = it },
                            )
                        } else {
                            StationPicker(
                                boards = boards,
                                currentlyBound = WidgetBindingStore.boundStation(this@WidgetConfigureActivity, target),
                                // Only the placement flow needs to commit a
                                // RESULT to the launcher; a rebind from inside
                                // the app just writes and redraws.
                                onPick = { groupingId -> commit(target, finishAfter = true, groupingId = groupingId) },
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
     * `AppWidgetManager.getAppWidgetIds()` is the authority on what exists —
     * the binding store only says what each one is FOR, and can lag when a
     * launcher is replaced or a broadcast is dropped. Asking the system first
     * and the store second is what stops this listing widgets that are not
     * there.
     */
    private fun placedWidgets(boards: List<UserSelection>): List<PlacedWidget> {
        val ids = AppWidgetManager.getInstance(this).getAppWidgetIds(
            android.content.ComponentName(this, DepartureWidgetProvider::class.java),
        )
        return ids.map { id ->
            val bound = WidgetBindingStore.boundStation(this, id)
            PlacedWidget(
                appWidgetId = id,
                stationName = boards.firstOrNull { it.groupingId == bound }?.stationName,
            )
        }
    }

    /**
     * One row per STATION, not per board.
     *
     * The binding is a `groupingId` — the hub the user picked, which is what the
     * home screen keys a card on and what a person means by "a station". Keying
     * on the full (station, line, direction) triple would bind a widget to one
     * direction of one line, so editing that board's lines later would silently
     * unbind their widget.
     */
    private fun boards(): List<UserSelection> =
        Platform.sqlStorage.getAllSelections().distinctBy { it.groupingId }

    /**
     * Write the binding, draw the widget, and tell the launcher to keep it.
     *
     * The draw happens BEFORE `finish()` and off the main thread: without it the
     * user watches the launcher place an empty box and fill in a second later,
     * because the first `onUpdate` the system sends arrives before this
     * Activity's result is processed and would find no binding.
     */
    private fun commit(targetId: Int, finishAfter: Boolean, groupingId: String) {
        WidgetBindingStore.bind(this, targetId, groupingId)
        // THIS widget only. Rebinding one must not repaint the others, which are
        // on screen showing stations that did not change — and off the main
        // thread, because it reads SQL.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                DepartureWidgetProvider.updateOne(this@WidgetConfigureActivity, targetId)
            }
        }
        // Only meaningful for the placement flow, and harmless otherwise: a
        // launcher that did not start this for a result ignores it.
        setResult(Activity.RESULT_OK, resultIntent())
        if (finishAfter) finish()
    }

    /**
     * No boards yet, so there is nothing to choose between.
     *
     * The widget is deliberately NOT created: `RESULT_CANCELED` still stands, so
     * the launcher removes it. An empty widget the user has to come back and fix
     * is worse than no widget, and the app is one tap away.
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
        /**
         * Re-open this screen for a widget that is already on the home screen.
         *
         * Used by the unbound widget's own tap target. `FLAG_ACTIVITY_NEW_TASK`
         * because it starts from a `RemoteViews` PendingIntent with no task of
         * its own, and `CLEAR_TASK` so a second tap does not stack a second
         * copy behind the first.
         */
        fun reconfigureIntent(context: Context, appWidgetId: Int): Intent =
            Intent(context, WidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
    }
}

/** One widget on the home screen, and what it currently shows. */
private data class PlacedWidget(
    val appWidgetId: Int,
    /** Null when the widget is unbound, or bound to a board since deleted. */
    val stationName: String?,
)

/**
 * MANAGER mode: every placed widget, so the user can change any of them from
 * here rather than hunting across home screens.
 */
@Composable
private fun WidgetList(
    widgets: List<PlacedWidget>,
    onPick: (Int) -> Unit,
) {
    Text(
        "Widget stations",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        if (widgets.isEmpty()) {
            "You don't have any Stationly widgets on your Home Screen yet."
        } else {
            "Each widget shows one station. Tap one to change what it shows."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    if (widgets.isEmpty()) {
        // Deliberately not an empty list with a spinner or a dash. There is
        // nothing wrong and nothing loading — the user simply has not added a
        // widget, and the only useful thing to say is how.
        Text(
            "Long-press your Home Screen, choose Widgets, then drag Stationly " +
                "out. You'll be asked which station it should show.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

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
                        if (widget.stationName == null) {
                            "Tap to choose a station"
                        } else {
                            "Tap to show a different station"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** PICKER mode: which of the user's stations should this one widget show. */
@Composable
private fun StationPicker(
    boards: List<UserSelection>,
    currentlyBound: String?,
    onPick: (String) -> Unit,
    onNoBoards: () -> Unit,
) {
    Text(
        "Which station?",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        if (boards.isEmpty()) {
            "This widget shows live departures for one of your stations."
        } else {
            "This widget will show live departures for the station you pick. " +
                "Add another widget for another station."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    if (boards.isEmpty()) {
        NoBoards(onOpenApp = onNoBoards)
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(boards, key = { it.groupingId }) { board ->
            BoardRow(
                board = board,
                isCurrent = board.groupingId == currentlyBound,
                onClick = { onPick(board.groupingId) },
            )
        }
    }
}

@Composable
private fun BoardRow(
    board: UserSelection,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
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
                // The mode rather than the line: a hub can carry several lines
                // and naming one of them would describe a board narrower than
                // the widget is bound to.
                board.mode.replaceFirstChar { it.uppercase() } +
                    if (isCurrent) " · currently shown" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoBoards(onOpenApp: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "You haven't added a station yet.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenApp) { Text("Open Stationly") }
    }
}
