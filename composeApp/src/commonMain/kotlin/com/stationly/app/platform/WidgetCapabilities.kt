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
