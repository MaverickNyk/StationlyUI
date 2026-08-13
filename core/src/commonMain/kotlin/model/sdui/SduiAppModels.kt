package com.stationly.core.model.sdui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Field-level validation rules carried in the SDUI model.
 * Clients evaluate these on submit — no platform-specific validation logic needed.
 */
@Serializable
data class SduiValidation(
    val required: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,     // regex — clients apply via their platform regex engine
    val errorMessage: String? = null // shown to the user when the rule fails
)

/**
 * Conditional visibility rule.
 * A component with a condition is hidden until the condition is satisfied.
 * operator: "not_empty" | "equals" | "empty"
 */
@Serializable
data class SduiCondition(
    val dependsOn: String,
    val operator: String = "not_empty",
    val value: String? = null
)

@Serializable
sealed class SduiAppComponent {
    abstract val id: String?

    @Serializable
    @SerialName("dropdown")
    data class Dropdown(
        override val id: String,
        val label: String,
        val dependsOn: String? = null,
        val dataSourceUrl: String,
        val style: String? = null,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("button")
    data class Button(
        override val id: String,
        val label: String,
        val action: String,
        val color: String? = null,
        val enabled: Boolean = true,
        val variant: String = "primary",  // primary | secondary | ghost | danger
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("image") 
    data class Image(
        override val id: String,
        val imageUrl: String,
        val contentDescription: String? = null,
        val style: String? = null,
        val textAlign: String? = null,
        val width: Int? = null,
        val height: Int? = null
    ) : SduiAppComponent()
    
    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        val text: String,
        val style: String = "body",
        val textAlign: String? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("input")
    data class Input(
        override val id: String,
        val label: String,
        val placeholder: String? = null,
        val text: String? = null,
        val style: String = "text",
        val helpText: String? = null,
        val validation: SduiValidation? = null,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("location")
    data class Location(
        override val id: String,
        val label: String,
        val icon: String? = null,
        val action: String? = null
    ) : SduiAppComponent()
    @Serializable
    @SerialName("flow_picker")
    data class FlowPicker(
        override val id: String,
        val label: String? = null,
        val dependsOn: String? = null,
        val options: List<FlowOption>
    ) : SduiAppComponent()

    @Serializable
    data class FlowOption(
        val id: String,
        val label: String,
        val icon: String? = null,
        val description: String? = null
    )

    /** A card surface wrapping child components */
    @Serializable
    @SerialName("card")
    data class Card(
        override val id: String,
        val title: String? = null,
        val body: String? = null,
        val style: String? = null,
        val components: List<SduiAppComponent> = emptyList()
    ) : SduiAppComponent()

    /** A named section grouping child components */
    @Serializable
    @SerialName("section")
    data class Section(
        override val id: String,
        val title: String? = null,
        val components: List<SduiAppComponent> = emptyList()
    ) : SduiAppComponent()

    /** A tappable row that opens a URL */
    @Serializable
    @SerialName("link_row")
    data class LinkRow(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val url: String,
        val icon: String? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("divider")
    data class Divider(
        override val id: String? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("spacer")
    data class Spacer(
        override val id: String? = null,
        val size: Int = 8
    ) : SduiAppComponent()

    /** A dismissible announcement banner shown on the home screen */
    @Serializable
    @SerialName("announcement")
    data class Announcement(
        override val id: String,
        val title: String,
        val body: String,
        val variant: String = "info",   // info | warning | tip
        val dismissKey: String? = null,
        val url: String? = null
    ) : SduiAppComponent()
}

/**
 * Flat string-map returned by /sdui/app/home-config.
 * Lets the server override any hardcoded label in the home / explore / empty-state UI.
 */
@Serializable
data class SduiStrings(
    val id: String,
    val strings: Map<String, String> = emptyMap()
)

@Serializable
data class SduiAppTheme(
    val primaryColor: String? = null,
    val backgroundColor: String? = null
)

/**
 * App-wide theme tokens returned by `GET /sdui/app/theme-tokens`. Each value
 * is an optional hex string; missing keys fall back to the app's hardcoded
 * defaults. The Android side caches the last successful response in
 * SharedPrefs so the app boots with the latest known palette even offline.
 *
 * Three buckets:
 *   - [light]     overrides applied when the app is in light theme
 *   - [dark]      overrides applied when the app is in dark theme
 *   - [constants] theme-independent tokens (logo red, the dot-matrix amber)
 *
 * Per-bucket keys (all optional, all hex `#RRGGBB`):
 *   canvas, card, cardElevated, scrim,
 *   textPrimary, textMuted, textSubtle,
 *   borderSubtle, borderStrong,
 *   primary, onPrimary, primaryContainer, onPrimaryContainer,
 *   success, warning, error, info, due, live
 *
 * Constants bucket keys:
 *   brandSignage, roundelRed
 */
@Serializable
data class SduiThemeTokens(
    val id: String = "app_theme_tokens",
    val version: Int = 1,
    val light: Map<String, String> = emptyMap(),
    val dark: Map<String, String> = emptyMap(),
    val constants: Map<String, String> = emptyMap(),
)

@Serializable
data class SduiAppScreen(
    val id: String,
    val title: String,
    val theme: SduiAppTheme? = null,
    val components: List<SduiAppComponent>,
    val loadingMessage: String? = null,
    val successMessage: String? = null
)

@Serializable
data class SduiDropdownOption(
    val id: String,
    val label: String,
    val iconUrl: String? = null,
    val secondaryLabel: String? = null,
    val color: String? = null,
    val tags: List<String>? = null,  // TfL line brand colors (hex) for the lines serving this station
    // Mode-only fields — populated when /modes is the data source.
    // tintHex backs the widget + dream station-row roundel tint when no
    // cached icon is present yet. iconVersion is used by ModeIconCache
    // to invalidate stale icons when the backend bumps its asset bundle.
    val tintHex: String? = null,
    val iconVersion: String? = null,
    /**
     * Line-only: the short display form ("Picc.", "H&C"), served by the lines
     * API and cached into [com.stationly.core.util.LineNameStore].
     *
     * Same shape of contract as [tintHex] above — display metadata the backend
     * owns, learned when the option list is fetched and kept for the surfaces
     * that need it later. Null for every non-line dropdown, for bus routes
     * (whose id is already the shortest true label), and for any backend that
     * predates the field; all three fall back to
     * [com.stationly.core.util.LineShortNames]' local table.
     */
    val shortName: String? = null,
    val upcomingStations: List<String>? = null,
    /**
     * The same stops as [upcomingStations] — same order, same length — carrying
     * the naptan id as well as the name.
     *
     * Filters match on `id`, never on the name: the route sequence calls a stop
     * "Hammersmith (Dist&Picc Line)" where a live prediction says "Hammersmith",
     * so name comparison fails on exactly the short-terminating services a
     * "via" filter has to catch.
     *
     * Nullable because a cached 24h payload (or an older backend) predates the
     * field; callers fall back to [upcomingStations] for display and simply
     * cannot offer id-accurate filtering until it arrives.
     */
    val upcomingStops: List<SduiRouteStop>? = null,
    val directionName: String? = null,
    val towards: String? = null,
    val destinations: List<SduiDropdownOption>? = null,
    /** Station this route sequence is relative to; every stop list starts AFTER it. */
    val originStationId: String? = null,
)

/** One stop on a route sequence: the naptan id to match on, plus a display name. */
@Serializable
data class SduiRouteStop(
    val id: String,
    val name: String,
)

@Serializable
data class SubscribedStation(
    val id: String, // the RESOLVED naptanId departures are fetched from
    val name: String,
    val line: String,
    val mode: String,
    val direction: String,
    /**
     * The hub this board belongs to, so a restore rebuilds one card per stop
     * rather than one per pole. Nullable: the backend may not persist it yet, in
     * which case restore falls back to grouping on [id] — the pre-hub behaviour.
     */
    val parentStationId: String? = null,
)

@Serializable
data class DeviceInfo(
    val platform: String? = null,    // "android" | "ios" | "web"
    val osVersion: String? = null,   // e.g. "Android 14 (SDK 34)"
    val model: String? = null,       // e.g. "Google Pixel 8"
    val appVersion: String? = null   // e.g. "1.0-staging"
)

@Serializable
data class SyncProfileRequest(
    val uid: String,
    val email: String,
    val displayName: String? = null,
    val photoURL: String? = null,
    val signInProvider: String? = null,
    // Stable per-install device id — lets the backend track active device
    // sessions so subscription counts only release on the last device's logout.
    val deviceId: String? = null,
    // Optional device metadata stored alongside the session for that deviceId.
    val deviceInfo: DeviceInfo? = null
)

@Serializable
data class SyncStationsRequest(
    val uid: String,
    val stations: List<SubscribedStation>
)

@Serializable
data class UserProfileResponse(
    val uid: String,
    val email: String,
    val displayName: String,
    val photoURL: String? = null,
    val address: String? = null,
    /**
     * LEGACY board list — what Android reads and writes.
     *
     * iOS must NOT reconcile against this: Android replaces it wholesale on
     * every board setup, so a shared account's iOS boards are not in it. Use
     * [boards]. See [com.stationly.core.model.user.Board] for the full account
     * of why the two lists exist.
     */
    val stations: List<SubscribedStation>,
    /**
     * The real board list — what iOS reads and writes.
     *
     * The backend guarantees this is present on read, deriving it from
     * [stations] for an account that has only ever used Android, so a user's
     * first iOS login restores the board they already had. Defaulted here as
     * well, because a backend that predates the field simply omits it and an
     * older response must not fail to decode.
     */
    val boards: List<com.stationly.core.model.user.Board> = emptyList(),
    /** LWW guard for [boards] — the device clock of the last accepted write. */
    val boardsUpdatedAt: Long = 0L,
)

/** Body of `POST /user/sync/boards`. */
@Serializable
data class SyncBoardsRequest(
    val boards: List<com.stationly.core.model.user.Board>,
    /** Device clock, epoch millis. The server drops a write older than stored. */
    val updatedAt: Long,
    /**
     * Permission to replace a stored board list with an EMPTY one.
     *
     * The endpoint is a full replacement, so `boards: []` is indistinguishable
     * from "delete everything this account has" — and both platforms have a
     * window where their local list is legitimately empty but not authoritative
     * (the login path wipes local SQL before restoring from the cloud). Without
     * an explicit signal the server has to guess, and guessing wrong once costs
     * the user every board on every device.
     *
     * Defaults to false, so a client that has never heard of this field cannot
     * clear an account by omission. Ignored entirely when [boards] is non-empty.
     */
    val allowEmpty: Boolean = false,
)

/**
 * Response to either sync-write.
 *
 * [applied] false with a 200 is the normal, expected outcome of a stale write,
 * NOT an error: the server is telling the client its copy is behind. A client
 * that treats it as a failure would retry the same stale payload forever.
 */
@Serializable
data class SyncStateResponse(
    val success: Boolean = false,
    val applied: Boolean = true,
    val reason: String? = null,
)
