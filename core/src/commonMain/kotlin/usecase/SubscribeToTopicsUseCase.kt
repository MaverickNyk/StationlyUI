package com.stationly.core.usecase

import com.stationly.core.model.UserSelection
import com.stationly.core.platform.NotificationManager

/**
 * Subscribe To Topics Use Case
 * 
 * Subscribes to FCM/WebSocket topics for real-time updates.
 * This is called when a user saves a new station.
 */
class SubscribeToTopicsUseCase(
    private val notificationManager: NotificationManager
) {
    
    suspend operator fun invoke(selection: UserSelection) {
        val topics = listOf(
            "Station_${selection.station}",
            "LineStatus_${selection.mode}_${selection.line}"
        )
        notificationManager.subscribeToTopics(topics)
    }
}