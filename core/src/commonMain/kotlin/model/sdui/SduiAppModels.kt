package com.stationly.core.model.sdui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class SduiAppComponent {
    @Serializable
    @SerialName("dropdown")
    data class Dropdown(
        val id: String,
        val label: String,
        val dependsOn: String? = null,
        val dataSourceUrl: String
    ) : SduiAppComponent()

    @Serializable
    @SerialName("button")
    data class Button(
        val id: String,
        val label: String,
        val action: String,
        val color: String? = null,
        val enabled: Boolean = true
    ) : SduiAppComponent()

    @Serializable
    @SerialName("image") 
    data class Image(
        val id: String,
        val imageUrl: String,
        val contentDescription: String? = null,
        val width: Int? = null,
        val height: Int? = null
    ) : SduiAppComponent()
    
    @Serializable
    @SerialName("text")
    data class Text(
        val id: String,
        val text: String,
        val style: String = "body"
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
    val label: String
)
