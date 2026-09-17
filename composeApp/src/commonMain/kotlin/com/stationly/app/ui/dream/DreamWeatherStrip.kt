package com.stationly.app.ui.dream

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalDateTime

/**
 * Date + (optional) temperature. Sits below whichever clock the user picked.
 * Port of Android `dream/DreamWeatherStrip.kt`.
 *
 *   Mon 18 May
 *   ☀️  12°
 *
 * The temperature line is populated by [WeatherStation]; when the fetch
 * fails the chip just doesn't render.
 */
@Composable
internal fun DateAndWeatherStrip(dim: DreamDims) {
    val now by rememberClockNow()

    val temp by WeatherStation.temperatureC.collectAsState()
    val symbol by WeatherStation.symbolCode.collectAsState()

    DisposableEffect(Unit) {
        WeatherStation.start()
        onDispose { WeatherStation.stop() }
    }

    val dateFontSize = (dim.titleSize.value * 0.72f).sp
    val tempFontSize = (dim.titleSize.value * 0.62f).sp

    val themeColors = LocalDreamColors.current
    val onCanvas    = themeColors.onCanvas

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatDreamDate(now),
            color = onCanvas.copy(alpha = 0.85f),
            fontWeight = FontWeight.SemiBold,
            fontSize = dateFontSize,
            letterSpacing = 0.5.sp,
        )
        temp?.let { t ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = weatherEmoji(symbol),
                    fontSize = tempFontSize,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$t°",
                    // Match the date/day colour — white in dark mode, black in
                    // light — instead of the brand amber.
                    color = onCanvas.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                    fontSize = tempFontSize,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

/** "EEE d MMM" in en-GB — e.g. "Mon 18 May" (SimpleDateFormat parity). */
private fun formatDreamDate(t: LocalDateTime): String {
    val day = t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val month = t.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$day ${t.dayOfMonth} $month"
}

/**
 * Map met.no's `symbol_code` strings to a single emoji. Codes include
 * variants like `clearsky_day`, `partlycloudy_polartwilight` — match the
 * salient root.
 */
private fun weatherEmoji(symbol: String?): String = when {
    symbol == null -> "🌡️"
    symbol.contains("thunder") -> "⛈️"
    symbol.contains("snow") || symbol.contains("sleet") -> "❄️"
    symbol.contains("rain") || symbol.contains("showers") -> "🌧️"
    symbol.contains("fog") -> "🌫️"
    symbol.contains("partlycloudy") -> if (symbol.endsWith("_night")) "☁️" else "⛅"
    symbol.contains("cloudy") -> "☁️"
    symbol.contains("fair") -> if (symbol.endsWith("_night")) "🌙" else "🌤️"
    symbol.contains("clearsky") -> if (symbol.endsWith("_night")) "🌙" else "☀️"
    else -> "🌡️"
}
