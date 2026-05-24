package com.stationly.mobile.dream

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Date + (optional) temperature. Sits below whichever clock the user picked.
 *
 * Layout — date on one line, weather chip on the second line:
 *
 *   Mon 18 May
 *   ☀️  12°
 *
 * The temperature/emoji line is populated by [WeatherStation], which polls
 * met.no every 30 minutes off the device's last-known location. If location
 * permission isn't granted or the call fails, the chip just doesn't render.
 */
@Composable
internal fun DateAndWeatherStrip(dim: DreamDims) {
    val now by rememberClockNow()
    val dateFmt = remember { java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.UK) }

    val context = LocalContext.current
    val weather = remember { WeatherStation.of(context) }
    val temp by weather.temperatureC.collectAsState()
    val symbol by weather.symbolCode.collectAsState()

    DisposableEffect(Unit) {
        weather.start()
        onDispose { weather.stop() }
    }

    val dateFontSize = (dim.titleSize.value * 0.72f).sp
    val tempFontSize = (dim.titleSize.value * 0.62f).sp

    val themeColors = LocalDreamColors.current
    val onCanvas    = themeColors.onCanvas
    val brandAmber  = themeColors.brandAccent

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = dateFmt.format(now),
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
                    color = brandAmber,
                    fontWeight = FontWeight.Bold,
                    fontSize = tempFontSize,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

/**
 * Map met.no's `symbol_code` strings to a single emoji. Codes include
 * variants like `clearsky_day`, `clearsky_night`, `partlycloudy_polartwilight`,
 * `lightsnowshowersandthunder_day` — we just match the salient root.
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
