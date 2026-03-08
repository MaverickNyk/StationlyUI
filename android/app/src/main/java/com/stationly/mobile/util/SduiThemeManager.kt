package com.stationly.mobile.util

import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.TextView
import com.stationly.core.model.sdui.SduiAppTheme
import com.stationly.core.model.sdui.SduiWidgetTheme

object SduiThemeManager {
    
    fun applyWidgetTheme(
        view: View,
        theme: SduiWidgetTheme?,
        onColorParsed: (Int) -> Unit = {}
    ): Int? {
        if (theme == null) return null
        
        var primary: Int? = null
        theme.primaryColor?.let {
            try {
                primary = Color.parseColor(it)
                onColorParsed(primary!!)
            } catch (e: Exception) {
                Log.e("SduiTheme", "Invalid primary color: $it")
            }
        }
        
        theme.backgroundColor?.let {
            try {
                val bg = Color.parseColor(it)
                view.setBackgroundColor(bg)
            } catch (e: Exception) {
                Log.e("SduiTheme", "Invalid background color: $it")
            }
        }
        
        return primary
    }

    fun parseColor(colorStr: String?, default: Int): Int {
        if (colorStr == null) return default
        return try {
            Color.parseColor(colorStr)
        } catch (e: Exception) {
            default
        }
    }

    fun getPrimaryColor(theme: com.stationly.core.model.sdui.SduiAppTheme?): androidx.compose.ui.graphics.Color {
        val colorHex = theme?.primaryColor ?: "#FFB81C"
        return try {
            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(colorHex))
        } catch (e: Exception) {
            androidx.compose.ui.graphics.Color(0xFFFFB81C)
        }
    }

    fun getBackgroundColor(theme: com.stationly.core.model.sdui.SduiAppTheme?): androidx.compose.ui.graphics.Color {
        val colorHex = theme?.backgroundColor ?: "#000000"
        return try {
            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(colorHex))
        } catch (e: Exception) {
            androidx.compose.ui.graphics.Color.Black
        }
    }
}
