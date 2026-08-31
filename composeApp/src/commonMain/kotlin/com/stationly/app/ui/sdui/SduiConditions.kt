package com.stationly.app.ui.sdui

import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiCondition

/**
 * The one evaluator for [SduiCondition].
 *
 * There used to be a private copy in `LoginScreen`, which was correct and
 * complete for the auth forms and knew only three operators. The widget guide
 * needed a fourth, and adding it to a private function would have left two
 * evaluators that agree today and diverge the first time either grows.
 *
 * ## What the map holds is the caller's business
 * The auth screens pass their form inputs; the widget guide passes
 * [SduiFacts]. The condition itself cannot tell the difference and does not
 * need to. See the [SduiCondition] docstring for why that seam is where it is.
 *
 * ## Unknown keys hide, they do not show
 * A missing fact fails `not_empty`, fails `equals`, and fails both numeric
 * comparisons. That direction is deliberate: the guide gates its "add a widget"
 * steps on the OS being new enough to HAVE the widget, and a client that cannot
 * answer must not be walked through a gesture that will find an empty gallery.
 * `empty` and `not_equals` are the two that pass on a missing key, which is the
 * same reading: "this device does not have that".
 */
fun SduiCondition.isSatisfied(values: Map<String, String>): Boolean {
    val v = values[dependsOn]?.trim() ?: ""
    return when (operator) {
        "not_empty"  -> v.isNotEmpty()
        "empty"      -> v.isEmpty()
        "equals"     -> v == value
        "not_equals" -> v != value
        // Compared as numbers, and false when either side is not one. A string
        // comparison here would read "9" as newer than "26".
        "gte"        -> compareNumeric(v, value) { a, b -> a >= b }
        "lte"        -> compareNumeric(v, value) { a, b -> a <= b }
        // An operator this client does not know is a payload from a newer
        // backend. Showing the block is the safe half of that: the copy is
        // still true, it is only shown more widely than intended.
        else         -> true
    }
}

private inline fun compareNumeric(
    left: String,
    right: String?,
    compare: (Double, Double) -> Boolean,
): Boolean {
    val a = left.toDoubleOrNull() ?: return false
    val b = right?.toDoubleOrNull() ?: return false
    return compare(a, b)
}

/**
 * The condition carried by any component that has one, or null.
 *
 * `SduiAppComponent` cannot declare `condition` on the base class: it is a
 * `@Serializable` sealed class, and a field on the parent would land in every
 * subtype's wire format including the ones that have never had one. So the
 * accessor lives here, and adding a condition to a new component type means
 * adding one line to this `when` rather than finding every call site.
 */
val SduiAppComponent.condition: SduiCondition?
    get() = when (this) {
        is SduiAppComponent.Text -> condition
        is SduiAppComponent.Card -> condition
        is SduiAppComponent.Section -> condition
        is SduiAppComponent.LinkRow -> condition
        is SduiAppComponent.Divider -> condition
        is SduiAppComponent.Spacer -> condition
        is SduiAppComponent.Button -> condition
        is SduiAppComponent.Dropdown -> condition
        is SduiAppComponent.Input -> condition
        is SduiAppComponent.Image -> condition
        is SduiAppComponent.Row -> condition
        is SduiAppComponent.Grid -> condition
        is SduiAppComponent.Tabs -> condition
        is SduiAppComponent.Demo -> condition
        is SduiAppComponent.Steps -> condition
        is SduiAppComponent.StatRow -> condition
        else -> null
    }

/**
 * Drop the components whose condition the facts do not satisfy, recursing into
 * cards and sections.
 *
 * A card whose children are all hidden is dropped with them: an empty titled
 * card is a heading over nothing, which reads as a screen that failed to load.
 */
fun List<SduiAppComponent>.visibleFor(values: Map<String, String>): List<SduiAppComponent> =
    mapNotNull { component ->
        if (component.condition?.isSatisfied(values) == false) return@mapNotNull null
        when (component) {
            is SduiAppComponent.Card -> {
                val kids = component.components.visibleFor(values)
                // A card with no children DECLARED is a title-and-body card and
                // stays; one whose children were all filtered out goes.
                if (component.components.isNotEmpty() && kids.isEmpty()) null
                else component.copy(components = kids)
            }
            is SduiAppComponent.Section -> {
                val kids = component.components.visibleFor(values)
                if (kids.isEmpty()) null else component.copy(components = kids)
            }
            // The layout containers filter the same way, and a container left
            // holding nothing is dropped rather than laid out as an empty gap.
            // `Row` also has to drop the WEIGHTS of the children it lost, or
            // every remaining child takes the wrong share.
            is SduiAppComponent.Row -> {
                val pairs = component.components.mapIndexedNotNull { i, child ->
                    val visibleChild = listOf(child).visibleFor(values).firstOrNull()
                    if (visibleChild != null) visibleChild to component.weights.getOrNull(i) else null
                }
                if (pairs.isEmpty()) null else component.copy(
                    components = pairs.map { it.first },
                    weights = if (component.weights.isEmpty()) emptyList()
                              else pairs.mapNotNull { it.second },
                )
            }
            is SduiAppComponent.Grid -> {
                val kids = component.components.visibleFor(values)
                if (kids.isEmpty()) null else component.copy(components = kids)
            }
            // A tab whose every child is hidden is dropped, and the control is
            // dropped with the last tab. The renderer filters again on the
            // selected tab, because this pass cannot know which one that is.
            is SduiAppComponent.Tabs -> {
                val kept = component.tabs
                    .map { tab -> tab to tab.components.visibleFor(values) }
                    .filter { (_, kids) -> kids.isNotEmpty() }
                    .map { (tab, kids) -> tab.copy(components = kids) }
                if (kept.isEmpty()) null else component.copy(tabs = kept)
            }
            else -> component
        }
    }
