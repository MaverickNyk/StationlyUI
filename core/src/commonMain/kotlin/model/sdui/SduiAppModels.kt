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
    val tags: List<String>? = null  // TfL line brand colors (hex) for the lines serving this station
)

@Serializable
data class SubscribedStation(
    val id: String, // stationId (naptanId)
    val name: String,
    val line: String,
    val mode: String,
    val direction: String
)

@Serializable
data class SyncProfileRequest(
    val uid: String,
    val email: String,
    val displayName: String? = null,
    val photoURL: String? = null,
    val signInProvider: String? = null
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
    val stations: List<SubscribedStation>
)
