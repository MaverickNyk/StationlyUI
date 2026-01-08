package com.stationly.core.usecase

import com.stationly.core.model.UserSelection
import com.stationly.core.platform.NotificationManager

/**
 * Unsubscribe From Topics Use Case
 * 
 * Unsubscribes from FCM/WebSocket topics.
 * This is called when a user deletes a station or changes selection.
 */
class UnsubscribeFromTopicsUseCase(
    private val notificationManager: NotificationManager
) {
    
    suspend operator fun invoke(selection: UserSelection) {
        val topics = listOf(
            "Station_${selection.station}",
            "LineStatus_${selection.mode}_${selection.line}"
        )
        notificationManager.unsubscribeFromTopics(topics)
    }
}