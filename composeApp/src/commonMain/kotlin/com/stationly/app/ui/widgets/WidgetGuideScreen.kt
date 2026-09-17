package com.stationly.app.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.common.LocalOpenUrl
import com.stationly.app.ui.sdui.SduiFacts
import com.stationly.app.ui.sdui.SduiRenderer
import com.stationly.app.ui.sdui.visibleFor
import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.service.SduiApiServiceFactory

/**
 * The widget guide: what the widget is, how to add one, how to stack several,
 * and why the board can look a minute old.
 *
 * ## Why this is a destination and not a banner
 * A home-screen "add a widget" promo shipped here once and was removed on
 * 2026-08-23 (`SummaryViewModel`, `docs/SESSION_2026-08-23_IOS_POLISH.md` §3):
 * iOS gives an app no way to place a widget, so the card had no button and
 * could only recite Home Screen instructions at somebody who had not asked.
 * That reasoning kills the nudge, not the explanation. This screen is the
 * explanation, reached the way every other setting is reached, by somebody who
 * came looking for it.
 *
 * **Do not turn it back into a banner.** If the guide needs promoting, the
 * answer is a better row in settings, not an advert on the board.
 *
 * ## Server-driven, with three floors under it
 * The layout is `GET /sdui/app/widget-guide`. The screen reads, in order: the disk
 * cache, then the network, and `WidgetGuideDefaults` if it has had neither. So
 * the first paint is instant, an offline reader still gets the whole guide in
 * words, and the backend can rewrite every sentence and every recording without
 * a release. See `docs/IOS_WIDGET_GUIDE.md` for the payload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetGuideScreen(
    onBack: () -> Unit,
) {
    // Read here rather than taken as a parameter, the same way the profile
    // link rows do it: `App.kt` decides whether a URL opens in the in-app
    // browser or leaves the app, and no screen should have an opinion.
    val openUrl = LocalOpenUrl.current
    var screen by remember { mutableStateOf(WidgetGuideDefaults.screen) }

    // Resolved ONCE per visit rather than per frame. The facts behind it, the
    // OS version and the widget count, cannot change while this screen is
    // up: the probe that moves the count runs on foreground, and coming back
    // from the Home Screen re-enters this composable anyway.
    val facts = remember { SduiFacts.current() }

    LaunchedEffect(Unit) {
        ActivityLog.record(
            ActivityEvents.WIDGET_GUIDE_OPENED,
            "count",
            facts["widget.count"].orEmpty(),
        )
        // Cache first so the words are on screen before the request is made.
        WidgetGuideCache.load()?.let { screen = it }
        runCatching { SduiApiServiceFactory.create().getWidgetGuideLayout() }
            .onSuccess { fetched ->
                // A 200 carrying nothing must not blank the screen. The
                // compiled guide is better than an empty one. Same rule as the
                // cache write; see `WidgetGuideCache.save`.
                if (fetched.components.isNotEmpty()) {
                    screen = fetched
                    WidgetGuideCache.save(fetched)
                }
            }
    }

    // Conditions resolved here, above the renderer, so a component whose
    // condition fails is never handed to it. Doing it inside the renderer would
    // mean every container re-deciding for its children on every recomposition.
    val components = remember(screen, facts) { screen.components.visibleFor(facts) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        screen.title.ifBlank { "Widgets" },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                    )
                },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            components.forEach { component ->
                SduiRenderer(
                    component = component,
                    onLinkOpen = { url -> openUrl(url, screen.title) },
                    facts = facts,
                )
            }
            // Clears the home indicator. The last card in a scrolling column
            // otherwise sits under it and reads as cut off.
            Spacer(Modifier.height(32.dp).fillMaxWidth())
        }
    }
}
