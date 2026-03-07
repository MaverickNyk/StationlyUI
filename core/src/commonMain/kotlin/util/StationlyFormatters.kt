package com.stationly.core.util

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Unified formatters for all platforms
 */
object StationlyFormatters {
    
    fun formatStatusReason(reason: String): String {
        if (reason.isBlank()) return ""
        var text = if (reason.contains(":")) reason.substringAfter(":").trim() else reason
        if (text.isEmpty()) return ""
        
        val firstDot = text.indexOf('.')
        if (firstDot != -1) {
            val secondDot = text.indexOf('.', firstDot + 1)
            text = if (secondDot != -1) text.substring(0, secondDot + 1)
            else text.substring(0, firstDot + 1)
        }
        return " $text"
    }

    fun formatDestination(name: String): String {
        val cleanName = name.replace(" Underground Station", "")
            .replace(" DLR Station", "")
            .replace(" Rail Station", "")
            .trim()
        return if (cleanName.length > 25) cleanName.take(22) + "..." else cleanName
    }

    fun getRandomFunnyMessage(): String {
        return listOf(
            "🚇 Wrangling the pigeons...",
            "☕ Heating up the third rail...",
            "🛤️ Checking for leaves on the line...",
            "📡 Decoding underground sonar...",
            "🧬 Sequencing DNA of delayed trains...",
            "🧼 Polishing the platform tiles...",
            "⚡ Searching for Platform 9¾...",
            "📢 Mind the Gap!",
            "🎫 Checking your travel magic..."
        ).random()
    }
    
    fun formatLastUpdated(lastUpdated: Long): String {
        if (lastUpdated == 0L) return "Never"
        val now = Clock.System.now().toEpochMilliseconds()
        val diff = now - lastUpdated
        val seconds = diff / 1000
        
        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            else -> "${seconds / 3600}h ago"
        }
    }

    fun formatETA(etaIso: String): String {
        return try {
            val etaTime = Instant.parse(etaIso)
            val now = Clock.System.now()
            val duration = etaTime - now
            
            when {
                duration.inWholeSeconds < 30 -> "Due"
                duration.inWholeSeconds < 60 -> "1 min"
                else -> "${(duration.inWholeSeconds + 30) / 60} min"
            }
        } catch (e: Exception) {
            "Due"
        }
    }

    fun sortPredictions(predictions: List<com.stationly.core.model.PredictionDisplay>): List<com.stationly.core.model.PredictionDisplay> {
        return predictions.sortedWith(compareBy { 
            val raw = it.eta.lowercase().trim()
            when {
                raw.contains("due") -> 0
                raw.contains("min") -> raw.replace(" min", "").toIntOrNull() ?: 999
                else -> raw.toIntOrNull() ?: 999
            }
        })
    }
}
