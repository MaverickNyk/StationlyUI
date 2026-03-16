package com.stationly.core.model.sdui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class SduiAppComponent {
    abstract val id: String?

    @Serializable
    @SerialName("dropdown")
    data class Dropdown(
        override val id: String,
        val label: String,
        val dependsOn: String? = null,
        val dataSourceUrl: String
    ) : SduiAppComponent()

    @Serializable
    @SerialName("button")
    data class Button(
        override val id: String,
        val label: String,
        val action: String,
        val color: String? = null,
        val enabled: Boolean = true
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
        val style: String = "text"
    ) : SduiAppComponent()
}

@Serializable
data class SduiAppTheme(
    val primaryColor: String? = null,
    val backgroundColor: String? = null
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
    val iconUrl: String? = null
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
