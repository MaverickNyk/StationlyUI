package com.stationly.mobile.widget

import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.LegacyRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Two widgets, two stations — and the manifest half of that is what nobody
 * would notice going wrong.
 *
 * Android's answer to "several stations" is not a stack. It is many instances of
 * one provider, each with its own `appWidgetId`, and a configuration Activity
 * that tells each instance which station it is for. The Kotlin side of that is
 * `WidgetBindingStore` and `DepartureWidgetProvider.renderWidget`. The half
 * asserted here is the half that is three attributes in an XML file:
 *
 *  - Drop `android:configure` and the launcher stops asking. Every widget is
 *    created unbound, renders the empty state forever, and nothing errors.
 *  - Set `configuration_optional` and a widget dragged from the gallery skips
 *    the question entirely — which would leave the provider to guess a station,
 *    the one thing it must never do.
 *  - Forget the `<activity>` and the launcher's `startActivity` throws where
 *    the user cannot see it; the widget is silently never placed.
 *
 * None of the three stops the build.
 */
class WidgetConfigurationContractTest {

    private val widgetInfo = xml("src/main/res/xml/departure_widget_info.xml")
    private val manifest = xml("src/main/AndroidManifest.xml")

    @Test
    fun `the widget asks which station it is for, at placement time`() {
        assertEquals(
            "android:configure is what makes the launcher run the picker before " +
                "creating the widget — and what makes it NOT create one if the " +
                "user backs out.",
            "com.stationly.mobile.widget.WidgetConfigureActivity",
            widgetInfo.attr("configure"),
        )
    }

    @Test
    fun `the question is never optional`() {
        // `configuration_optional` (API 31+) tells the launcher it may skip the
        // configuration activity. For a departure board that would mean placing
        // a widget with no station and no way to have asked — leaving the
        // provider to pick one, which is the rule that does not bend.
        val features = widgetInfo.attr("widgetFeatures").orEmpty()
        assertFalse(
            "configuration_optional would let a widget be placed unasked",
            features.contains("configuration_optional"),
        )
        assertTrue(
            "reconfigurable is what puts the picker in the launcher's own widget " +
                "menu on Android 9+, so a placed widget can be re-pointed there " +
                "as well as from inside the app",
            features.contains("reconfigurable"),
        )
    }

    @Test
    fun `the configuration activity is declared, and reachable by the launcher`() {
        val activity = manifest.activities().firstOrNull {
            it.attr("name") == ".widget.WidgetConfigureActivity"
        } ?: error("no <activity> for WidgetConfigureActivity — the launcher cannot start it")

        // exported=true is required, not a hole: the launcher is another process
        // and has to be able to start this. It carries no intent-filter, so it
        // is reachable only by component, with an appWidgetId the system issued.
        assertEquals("true", activity.attr("exported"))
        assertEquals(
            "a configuration screen with an intent-filter would be reachable by " +
                "action from any app on the device",
            0,
            activity.getElementsByTagName("intent-filter").length,
        )
        // Its own (empty) affinity and no recents entry: it is a sheet the
        // launcher put up, not a place in the app. Without these, backing out
        // lands in Stationly's task with the half-placed widget behind you.
        assertEquals("", activity.attr("taskAffinity"))
        assertEquals("true", activity.attr("excludeFromRecents"))
    }

    @Test
    fun `an unbound widget says which state it is in, and never borrows a board`() {
        // The account HAS boards; this widget has not been told which one. The
        // words for that are not the words for a new user with no boards at
        // all, and neither may be a departure.
        val unbound = GlobalBoardProcessor.prepareLegacyRows(
            predictions = emptyList(),
            lineName = "",
            hasSelection = true,
            isLoggedIn = true,
            isBound = false,
        )
        val newUser = GlobalBoardProcessor.prepareLegacyRows(
            predictions = emptyList(),
            lineName = "",
            hasSelection = false,
            isLoggedIn = true,
            isBound = false,
        )

        // The placeholder rows reuse `LegacyRow.Departure` as a layout row —
        // index 0, blank eta — so "no departures" is not the assertion. What
        // matters is that no row carries a TIME: a widget with no station has
        // nothing it could honestly put in that column.
        assertTrue(
            "an unbound widget rendered an ETA — there is no station it could " +
                "have come from",
            unbound.filterIsInstance<LegacyRow.Departure>().all { it.eta.isBlank() },
        )
        assertEquals(
            "the unbound state should ask which station, not tell someone with " +
                "four boards to pick one",
            "📍 Which station?",
            unbound.filterIsInstance<LegacyRow.Header>().single().title,
        )
        assertEquals(
            "a user with no boards at all still gets the set-up wording",
            "🚉 You're one stop away",
            newUser.filterIsInstance<LegacyRow.Header>().single().title,
        )
        // And the two states are genuinely different words, which is the whole
        // point of the parameter.
        assertTrue(unbound != newUser)
    }

    @Test
    fun `the pin flow cannot bind a widget to a stale choice`() {
        // WidgetPinner leaves the chosen station in prefs for the configuration
        // Activity to claim, because `requestPinAppWidget` never tells anyone
        // the new widget's id. That value has to expire: a stale one would bind
        // the NEXT widget dragged out of the gallery to a station picked an hour
        // ago — a silent wrong answer, which is the single outcome this whole
        // package exists to prevent.
        val ttl = constant(WidgetPinner::class.java, "PENDING_TTL_MS")
        assertTrue(
            "the pending-pin TTL must be short enough that it cannot outlive the " +
                "launcher's confirm dialog by much; it was $ttl ms",
            ttl in 30_000L..5 * 60_000L,
        )
    }

    @Test
    fun `the binding store and the pin flow share one prefs file`() {
        // The pending station rides in the same file as the bindings. If they
        // drift apart, `claimPending` reads an empty value forever and every
        // pinned widget silently falls back to asking — which looks like the
        // pin flow "not working" rather than like a bug.
        assertEquals("widget_prefs", WidgetBindingStore.PREFS)
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private fun xml(relative: String): Element {
        // Gradle runs unit tests with the module directory as the working
        // directory; an IDE may use the repo root. Accept either.
        val file = listOf(File(relative), File("android/app/$relative")).firstOrNull { it.isFile }
            ?: error("cannot find $relative from ${File(".").absolutePath}")
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun Element.attr(name: String): String? =
        getAttributeNS(ANDROID_NS, name).takeIf { getAttributeNodeNS(ANDROID_NS, name) != null }

    /** A private const read back off the class, as the storage contract tests do. */
    private fun constant(owner: Class<*>, name: String): Long =
        owner.getDeclaredField(name).apply { isAccessible = true }.getLong(null)

    private fun Element.activities(): List<Element> {
        val nodes = getElementsByTagName("activity")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
}
