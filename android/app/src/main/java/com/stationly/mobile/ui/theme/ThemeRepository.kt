package com.stationly.mobile.ui.theme

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.stationly.core.model.sdui.SduiThemeTokens
import com.stationly.core.service.SduiApiServiceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Bridges the SDUI theme-tokens endpoint and the on-device cache.
 *
 * **Read path** (synchronous, on the main thread, called during composition):
 *   - [loadCachedOverrides] reads the last successful SDUI payload from
 *     SharedPrefs. Returns an empty [SduiThemeTokens] if nothing's cached
 *     (= first install, or pref was wiped). Defaults take over from there.
 *
 * **Write path** (background coroutine, fire-and-forget):
 *   - [refreshInBackground] kicks off a network request to the backend,
 *     parses the JSON, and writes it back to SharedPrefs. Failures are
 *     swallowed silently — the cached/default palette keeps working.
 *
 * The cached overrides are applied to the default tokens via [merge] when
 * the [StationlyThemeHost] composes; see that function for the override key
 * conventions.
 *
 * Why SharedPrefs and not Room / DataStore? The data is tiny (~1 KB
 * serialised), only ever read once per launch, and DataStore would add
 * a Flow boundary we don't need for a value that doesn't change
 * mid-session by design.
 */
object ThemeRepository {

    private const val TAG = "ThemeRepository"
    private const val PREFS = "StationlyPrefs"
    private const val KEY_OVERRIDES_JSON = "theme_overrides_json"

    /**
     * Lenient JSON config — unknown keys from a newer backend are silently
     * tolerated (forward compat); we can add a new token to the server
     * before bumping the app and old apps won't crash.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /** Long-lived scope for background refreshes. SupervisorJob so a
     *  failed fetch doesn't cancel the parent app scope. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Read the last successful SDUI sync. Cheap; safe to call from
     * composition. Empty overrides → defaults shine through unchanged.
     */
    fun loadCachedOverrides(context: Context): SduiThemeTokens {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OVERRIDES_JSON, null) ?: return SduiThemeTokens()
        return runCatching { json.decodeFromString<SduiThemeTokens>(raw) }
            .getOrElse {
                Log.w(TAG, "Cached theme JSON unparseable, falling back to defaults", it)
                SduiThemeTokens()
            }
    }

    /**
     * Fire-and-forget background refresh. Call once per app launch from the
     * [StationlyThemeHost] LaunchedEffect — the response goes to the cache,
     * NOT to the running composition (avoids mid-session colour flips).
     * Picked up on the next cold launch.
     */
    fun refreshInBackground(context: Context) {
        scope.launch {
            runCatching {
                val fresh = SduiApiServiceFactory.create().getThemeTokens()
                val encoded = json.encodeToString(SduiThemeTokens.serializer(), fresh)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_OVERRIDES_JSON, encoded).apply()
                Log.d(TAG, "Theme tokens refreshed (version ${fresh.version})")
            }.onFailure { Log.w(TAG, "Theme refresh failed (kept cached/defaults)", it) }
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * MERGE — applies a partial SDUI override map onto a baseline [ThemeTokens].
 * Each key in the override map is one of the constants below; values are
 * `#RRGGBB` (or `#AARRGGBB`) hex strings. Unknown keys are ignored.
 * ────────────────────────────────────────────────────────────────────────── */

internal fun ThemeTokens.merge(overrides: Map<String, String>, constants: Map<String, String>): ThemeTokens {
    if (overrides.isEmpty() && constants.isEmpty()) return this
    fun pick(key: String, fallback: Color): Color =
        overrides[key]?.let(::parseHex) ?: fallback
    fun pickConst(key: String, fallback: Color): Color =
        constants[key]?.let(::parseHex) ?: fallback
    return copy(
        canvas             = pick("canvas",             canvas),
        card               = pick("card",               card),
        cardElevated       = pick("cardElevated",       cardElevated),
        scrim              = pick("scrim",              scrim),
        textPrimary        = pick("textPrimary",        textPrimary),
        textMuted          = pick("textMuted",          textMuted),
        textSubtle         = pick("textSubtle",         textSubtle),
        borderSubtle       = pick("borderSubtle",       borderSubtle),
        borderStrong       = pick("borderStrong",       borderStrong),
        primary            = pick("primary",            primary),
        onPrimary          = pick("onPrimary",          onPrimary),
        primaryContainer   = pick("primaryContainer",   primaryContainer),
        onPrimaryContainer = pick("onPrimaryContainer", onPrimaryContainer),
        success            = pick("success",            success),
        warning            = pick("warning",            warning),
        error              = pick("error",              error),
        info               = pick("info",               info),
        due                = pick("due",                due),
        live               = pick("live",               live),
        brandSignage       = pickConst("brandSignage",  brandSignage),
        roundelRed         = pickConst("roundelRed",    roundelRed),
    )
}

/** Parses `#RRGGBB`, `#AARRGGBB`, `RRGGBB`, `AARRGGBB`. Null on garbage. */
private fun parseHex(hex: String): Color? = runCatching {
    val cleaned = hex.removePrefix("#")
    val withAlpha = if (cleaned.length == 6) "FF$cleaned" else cleaned
    if (withAlpha.length != 8) return@runCatching null
    Color(withAlpha.toLong(16))
}.getOrNull()
