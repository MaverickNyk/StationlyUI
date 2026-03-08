package com.stationly.core.model.sdui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class SduiWidgetComponent {
    @Serializable
    @SerialName("header")
    data class Header(
        val title: String,
        val style: String? = null,
        val color: String? = null
    ) : SduiWidgetComponent()

    @Serializable
    @SerialName("row")
    data class Row(
        val index: String,
        val destination: String,
        val eta: String,
        val destinationStyle: String? = null,
        val etaColor: String? = null,
        val animation: String? = null // e.g., "pulse"
    ) : SduiWidgetComponent()

    @Serializable
    @SerialName("status")
    data class Status(
        val severity: String,
        val reason: String
    ) : SduiWidgetComponent()

    @Serializable
    @SerialName("message")
    data class Message(
        val text: String,
        val textAlign: String? = "center",
        val color: String? = null
    ) : SduiWidgetComponent()
}

@Serializable
data class SduiWidgetTheme(
    val primaryColor: String? = null,
    val backgroundColor: String? = null
)

@Serializable
data class SduiWidgetPayload(
    val id: String,
    val title: String,
    val components: List<SduiWidgetComponent>,
    val theme: SduiWidgetTheme? = null
)
