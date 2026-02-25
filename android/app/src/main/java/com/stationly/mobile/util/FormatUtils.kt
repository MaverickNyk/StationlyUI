package com.stationly.mobile.util

object FormatUtils {
    
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
            "⚡ Searching for Platform 9¾...",
            "📢 Mind the Gap!",
            "🪄 Clearing leaves from the tracks...",
            "☕ Driver's having a quick tea break...",
            "🏃‍♂️ Sprinting through the Ministry..."
        ).random()
    }
}
