package com.stationly.app.ui.whatsnew

import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.platform.Platform
import com.stationly.core.platform.WhatsNewMarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one-time "What's new" screen for someone who has just updated from the
 * first Android release.
 *
 * ## Who sees it
 * Only a device whose database ran the v1 → 2 migration, which leaves
 * [WhatsNewMarker] behind. A fresh install never has the marker, a later
 * update never writes it, and iOS has no code that writes it at all. The
 * server can still switch it off (`whats_new.v2.show = false`), in which case
 * the marker is kept, so switching it back on reaches the same people.
 *
 * ## Why it ends with support
 * The people updating from v1 have used Stationly the longest and are the
 * warmest audience the app will ever have, and this is the one moment it gets
 * to speak to all of them at once. One honest line and a button, after the
 * news. No ask before they have seen what they got.
 *
 * Shown from the home screen ([consider]) and drawn at the app root, above
 * every screen and under the support sheet, so its button can open the sheet
 * on top of it.
 */
object WhatsNew {

    private val _content = MutableStateFlow<WhatsNewContent?>(null)

    /** What to draw, or null when nothing is showing. */
    val content: StateFlow<WhatsNewContent?> = _content.asStateFlow()

    /** Judged once per process; the marker decides across processes. */
    private var considered = false

    /**
     * Show the screen if this device is owed it.
     *
     * Call from the home screen once its boards have loaded: the marker is
     * written when the database first opens after the update, and by then the
     * home screen has read the boards from it.
     */
    suspend fun consider(homeConfig: Map<String, String>) {
        if (considered || homeConfig.isEmpty()) return
        considered = true
        val pending = runCatching { Platform.storageManager.loadDurable(WhatsNewMarker.KEY) }.getOrNull()
        if (pending != WhatsNewMarker.PENDING) return
        if (homeConfig["whats_new.v2.show"]?.equals("false", ignoreCase = true) == true) return
        _content.value = WhatsNewContent.from(homeConfig)
        ActivityLog.record(ActivityEvents.WHATS_NEW_SHOWN)
    }

    /**
     * Closed, by its button or by back. Spends the marker: it never shows again.
     *
     * @param via `button` | `back` | `support`, logged.
     */
    suspend fun dismiss(via: String) {
        if (_content.value == null) return
        _content.value = null
        if (via == "support") {
            ActivityLog.record(ActivityEvents.WHATS_NEW_SUPPORT_TAPPED)
        } else {
            ActivityLog.record(ActivityEvents.WHATS_NEW_DISMISSED, "via", via)
        }
        runCatching { Platform.storageManager.removeDurable(WhatsNewMarker.KEY) }
    }

    /** Tests only. */
    internal fun resetForTest() {
        considered = false
        _content.value = null
    }
}

/** One row of news. `icon` is a token: boards, filter, widget, sync, alert. */
@Serializable
data class WhatsNewItem(
    val icon: String = "",
    val title: String = "",
    val body: String = "",
)

/** Everything the screen draws, from the server with a compiled-in fallback. */
data class WhatsNewContent(
    val title: String,
    val subtitle: String,
    val items: List<WhatsNewItem>,
    val support: String,
    val cta: String,
    val done: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The same words the backend serves (`WhatsNewService`). Kept here so a
         * device that updates offline, or before home-config has ever loaded,
         * still says something true. House rules: no dashes, no "donate".
         */
        internal val FALLBACK_ITEMS = listOf(
            WhatsNewItem(
                "boards", "Up to four stations",
                "Keep a board for each station you use. Swipe between them, or see them all in a list.",
            ),
            WhatsNewItem(
                "filter", "Only the trains you want",
                "Filter a board to the destinations or stops you care about, and hide the rest.",
            ),
            WhatsNewItem(
                "widget", "A widget for each station",
                "Every widget on your home screen can show its own station.",
            ),
            WhatsNewItem(
                "sync", "The same boards everywhere",
                "Sign in on your phone and your iPhone or tablet, and your boards follow you.",
            ),
        )

        fun from(strings: Map<String, String>): WhatsNewContent {
            val items = strings["whats_new.v2.items.json"]
                ?.let { raw -> runCatching { json.decodeFromString<List<WhatsNewItem>>(raw) }.getOrNull() }
                ?.filter { it.title.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?: FALLBACK_ITEMS
            return WhatsNewContent(
                title = strings["whats_new.v2.title"] ?: "What's new in Stationly",
                subtitle = strings["whats_new.v2.subtitle"]
                    ?: "Your stations and widgets came across with you. Here is what changed.",
                items = items,
                support = strings["whats_new.v2.support"]
                    ?: ("Stationly is free, with no adverts and nothing locked away. " +
                        "It is kept running by the people who use it. If it makes your journey easier, " +
                        "you can support it here or from your profile, whenever suits you."),
                cta = strings["whats_new.v2.cta"] ?: "Support Stationly",
                done = strings["whats_new.v2.done"] ?: "Got it",
            )
        }
    }
}
