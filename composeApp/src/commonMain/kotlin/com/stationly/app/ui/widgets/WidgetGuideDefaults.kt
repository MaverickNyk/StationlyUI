package com.stationly.app.ui.widgets

import com.stationly.app.ui.sdui.SduiFacts
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.model.sdui.SduiCondition
import com.stationly.core.model.sdui.SduiStep
import com.stationly.core.model.sdui.SduiTab

/**
 * The widget guide compiled into the binary.
 *
 * ## Why a screen this server-driven still ships a copy
 * `docs/SDUI.md` §3 test 3: a safe default ships in the binary. It is not a
 * nicety here, it is the point. This is the screen someone opens when the
 * widget is not doing what they expected, and "not doing what I expected"
 * includes "this phone has no signal on the Underground". A help screen that
 * needs the network to explain itself is not help.
 *
 * ## What the server is expected to replace
 * Everything, and the pictures are simply absent here: the board screenshot and
 * any recordings are backend assets rather than megabytes in the app bundle, so
 * this copy declares no media at all. That is the deliberate floor: an offline
 * reader gets the words, which carry the instructions on their own.
 *
 * Keep this in step with the payload in `docs/IOS_WIDGET_GUIDE.md`. When the
 * two disagree the server wins on device, and the difference is invisible until
 * somebody is offline.
 */
object WidgetGuideDefaults {

    /** Gate for anything that assumes the widget exists on this OS. */
    private val widgetAvailable = SduiCondition(
        dependsOn = "widget.supported",
        operator = "not_empty",
    )

    /** Its complement, for the one card that explains the absence. */
    private val widgetUnavailable = SduiCondition(
        dependsOn = "widget.supported",
        operator = "empty",
    )

    val screen: SduiAppScreen = SduiAppScreen(
        id = "widget_guide",
        title = "Widgets",
        components = listOf(
            SduiAppComponent.StatRow(
                id = "widget_count",
                fact = "widget.count",
                zero = "No widgets on your Home Screen yet",
                one = "1 widget on your Home Screen",
                many = "{count} widgets on your Home Screen",
                condition = widgetAvailable,
            ),

            // The one card for a device that cannot have the widget at all. It
            // names the version, because the reader's next question is always
            // "from when".
            SduiAppComponent.Card(
                id = "unsupported",
                title = "Widgets need iOS ${SduiFacts.WIDGET_MIN_IOS}",
                body = "Stationly's departure board widget uses a part of iOS " +
                    "that only arrived in iOS ${SduiFacts.WIDGET_MIN_IOS}, so it " +
                    "will not appear in the widget gallery on this iPhone. " +
                    "Everything else in the app works as normal.",
                condition = widgetUnavailable,
            ),

            SduiAppComponent.Tabs(
                id = "guide_tabs",
                condition = widgetAvailable,
                tabs = listOf(
                    // First tab, and the walkthrough leads it: this is what
                    // somebody opens the screen to find.
                    SduiTab(
                        title = "Add a widget",
                        icon = "add",
                        components = listOf(
                            SduiAppComponent.Card(
                                id = "widget_intro",
                                title = "Live departures at a glance",
                                body = "Glance at upcoming departures for any station right from your Home Screen without opening the app. Zero clicks needed.",
                            ),
                            SduiAppComponent.Steps(
                                id = "add_steps",
                                steps = listOf(
                                    SduiStep(
                                        title = "Touch and hold the Home Screen",
                                        body = "Anywhere empty, until the icons jiggle.",
                                        icon = "touch", tint = "#FFC819",
                                    ),
                                    SduiStep(
                                        title = "Tap Edit, then Add Widget",
                                        body = "Top-left corner.",
                                        icon = "edit", tint = "#4FC3F7",
                                    ),
                                    SduiStep(
                                        title = "Search for Stationly",
                                        body = "Pick a size and add it.",
                                        icon = "search", tint = "#81C784",
                                    ),
                                    SduiStep(
                                        title = "Choose the station",
                                        body = "Hold the widget, tap Edit Widget.",
                                        icon = "station", tint = "#F06292",
                                    ),
                                ),
                            ),
                            // No `Demo` here. The board shot is a backend asset,
                            // and a demo with no media renders nothing, so the
                            // offline guide is the words alone by design.
                        ),
                    ),
                    SduiTab(
                        title = "Multiple widgets",
                        icon = "layers",
                        components = listOf(
                            SduiAppComponent.Card(
                                id = "one_each",
                                title = "One widget per station",
                                body = "A widget shows a single station, so add as " +
                                    "many as you track: one for each end of your " +
                                    "commute, one for the station near work. Repeat " +
                                    "the steps in Add a widget and pick a different " +
                                    "station each time.",
                            ),
                            SduiAppComponent.Steps(
                                id = "stack_steps",
                                title = "Stack them",
                                steps = listOf(
                                    SduiStep(
                                        title = "Drag one onto another",
                                        body = "Same size only. iOS turns the pair into a stack.",
                                        icon = "drag", tint = "#FFC819",
                                    ),
                                    SduiStep(
                                        title = "Swipe to switch station",
                                        body = "The stack holds up to ten widgets in one slot.",
                                        icon = "layers", tint = "#4FC3F7",
                                    ),
                                    SduiStep(
                                        title = "Leave Smart Rotate on",
                                        body = "iOS brings the board it thinks you want to the top.",
                                        icon = "rotate", tint = "#81C784",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
}
