package com.stationly.app.ui.theme

import com.stationly.core.model.sdui.SduiThemeTokens
import com.stationly.core.platform.Platform
import com.stationly.core.service.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Compose-Multiplatform port of android/.../ui/theme/ThemeRepository.kt.
 * Bridges the SDUI theme-tokens endpoint and the on-device cache, using the
 * shared [Platform.storageManager] instead of Android SharedPrefs.
 *
 * Read path ([loadCachedOverrides], suspend): last successful SDUI payload from
 * storage, or empty tokens (first install) so defaults shine through.
 *
 * Write path ([refreshInBackground], fire-and-forget): fetch from the backend,
 * cache the JSON. Failures are swallowed; the cached/default palette keeps
 * working. Picked up on the NEXT launch, never mid-session.
 */
object ThemeRepository {

    private const val KEY_OVERRIDES_JSON = "theme_overrides_json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /** SupervisorJob so a failed fetch never cancels a parent scope. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun loadCachedOverrides(): SduiThemeTokens {
        val raw = Platform.storageManager.loadString(KEY_OVERRIDES_JSON) ?: return SduiThemeTokens()
        return runCatching { json.decodeFromString<SduiThemeTokens>(raw) }.getOrElse { SduiThemeTokens() }
    }

    fun refreshInBackground() {
        scope.launch {
            runCatching {
                val fresh = NetworkModule.sduiApi.getThemeTokens()
                val encoded = json.encodeToString(SduiThemeTokens.serializer(), fresh)
                Platform.storageManager.saveString(KEY_OVERRIDES_JSON, encoded)
            }
        }
    }
}
