package com.stationly.app.ui.sdui

import com.stationly.app.platform.DeviceIdentity
import com.stationly.core.repository.UserSettings

/**
 * What this device can say about itself, as the flat string map an
 * [com.stationly.core.model.sdui.SduiCondition] resolves against.
 *
 * ## Why the server needs these at all
 * The widget guide is server-authored down to which sections exist, and the two
 * questions that decide what it should say cannot be answered on the server:
 * whether this OS is new enough to have the widget, and whether the reader has
 * already added one. Sending the facts up with a request would make the guide a
 * per-device response and uncacheable; sending the RULES down and resolving them
 * here keeps one payload for everybody.
 *
 * ## The facts
 * | Key | Example | Notes |
 * |---|---|---|
 * | `platform` | `ios` | |
 * | `os.version` | `26.0` | the number only, no OS name |
 * | `os.major` | `26` | what a `gte` gate should compare against |
 * | `widget.supported` | `yes` / `` | see below; blank rather than `no`, so `empty` reads naturally |
 * | `widget.count` | `2` | instances, not boards. `UserSettings.widgetTotal` |
 * | `board.count` | `4` | boards the user has saved |
 *
 * ## `widget.supported` is a floor this file has to know
 * The app deploys to iOS 16 and the WIDGET EXTENSION deploys to iOS 26
 * (`iosApp/project.yml`, forced by the unconditional `.pushHandler`, see
 * `StationlyWidgetBundle`). On anything older the widget is not merely absent
 * from the app, it is absent from the system gallery, so a guide that walks that
 * user through the gesture sends them to look for something that was never
 * installed. The floor lives here as [WIDGET_MIN_IOS] rather than in the
 * payload because it is a build fact, not a judgement: it changes when the
 * extension's deployment target changes and at no other time, which fails
 * test 1 in `docs/SDUI.md` §3.
 */
object SduiFacts {

    /**
     * The extension's `IPHONEOS_DEPLOYMENT_TARGET`. Keep in lockstep with
     * `iosApp/project.yml`. If that target is ever lowered, this is the line
     * that stops the guide hiding itself from devices that can now run it.
     */
    const val WIDGET_MIN_IOS = 26

    fun current(): Map<String, String> {
        val info = DeviceIdentity.deviceInfo()
        val major = majorVersion(info.osVersion)
        val widgetSupported = info.platform != "ios" || (major != null && major >= WIDGET_MIN_IOS)
        return buildMap {
            put("platform", info.platform.orEmpty())
            put("os.version", numericVersion(info.osVersion).orEmpty())
            put("os.major", major?.toString().orEmpty())
            // Blank, not "no". `empty` / `not_empty` are the operators the auth
            // screens already use, so a payload author reaches for them first,
            // and "no" is truthy to both of them.
            put("widget.supported", if (widgetSupported) "yes" else "")
            put("widget.count", UserSettings.widgetTotal.value.toString())
            put("board.count", UserSettings.widgets.value.size.toString())
        }
    }

    /**
     * `"iOS 26.0"` → `"26.0"`, `"Android 14 (SDK 34)"` → `"14"`.
     *
     * [com.stationly.core.model.sdui.DeviceInfo.osVersion] is one field carrying
     * two platforms' formats, both of which put the OS NAME first and the number
     * second. Parsing it here rather than adding a second field keeps the one
     * thing the backend already stores as the one thing this reads.
     */
    private fun numericVersion(osVersion: String?): String? =
        osVersion
            ?.substringAfter(' ', "")
            ?.takeWhile { it.isDigit() || it == '.' }
            ?.trim('.')
            ?.takeIf { it.isNotEmpty() }

    private fun majorVersion(osVersion: String?): Int? =
        numericVersion(osVersion)?.substringBefore('.')?.toIntOrNull()
}
