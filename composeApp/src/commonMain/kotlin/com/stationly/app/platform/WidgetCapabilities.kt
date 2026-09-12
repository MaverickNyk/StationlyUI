package com.stationly.app.platform

/**
 * Whether the APP can re-point a placed widget at another station.
 *
 * ## Why this is a capability and not a string
 * Copy that tells somebody how to change a widget has to name a gesture, and the
 * gesture is not the same on both platforms:
 *
 *  - **iOS**: touch and hold the widget, tap "Edit Widget". The app cannot do it
 *    for them — `getCurrentConfigurations` returns `[]` inside a timeline and an
 *    app can never read, let alone write, a widget's AppIntent configuration.
 *  - **Android**: the app CAN. `AppWidgetManager.getAppWidgetIds()` enumerates
 *    the placed instances and the binding store is ours to write, so Settings →
 *    Widgets lists every widget and rebinds any of them. There is also a gear on
 *    the widget itself; there is no "Edit Widget" menu item to tap.
 *
 * So a single sentence sends one of the two platforms hunting for a control that
 * does not exist. The delete confirmation did exactly that on Android until this
 * existed — it was written for iOS, correctly, and then the shared UI became the
 * Android app.
 *
 * Same shape as `SupportCheckout.checkoutSupported` and decision D8: where a
 * surface depends on the platform being able to DO something, ask the platform,
 * do not guess from a callback being non-null or from a string.
 */
expect val widgetsAreEditableInApp: Boolean

/**
 * Whether the SYSTEM decides when the screensaver appears.
 *
 * ## Why the Start button had to go on Android
 * On iOS the app owns its screensaver: there is no system screensaver surface at
 * all, so "Screensaver" is an in-app route and a Start button is the only way to
 * ever see it. On Android a dream is a system service. The OS starts it — while
 * charging, docked, or idle on the lock screen, per the user's own choice in
 * Settings → Display → Screen saver — and the app cannot.
 *
 * So on Android the screen is a SETTINGS page and nothing else, and a Start
 * button on it is either a lie or a round trip: the Android build reaches this
 * screen FROM system Settings (the gear beside "Stationly"), so a button
 * sending them back to system Settings sends them where they just were.
 *
 * What replaces it is a sentence saying when the screensaver actually appears,
 * which is the thing somebody on this screen is really asking.
 *
 * Same shape as [widgetsAreEditableInApp] and decision D8: ask the platform
 * what it can do rather than inferring it from a callback being non-null.
 */
expect val screensaverIsStartedBySystem: Boolean
