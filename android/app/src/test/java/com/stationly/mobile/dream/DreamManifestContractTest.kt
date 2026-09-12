package com.stationly.mobile.dream

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The screensaver is wired entirely in XML, by string, and none of it fails to
 * build.
 *
 * ## Why this exists, and why now
 * AV2-6.2 deleted ten files from this package and left two: the `DreamService`
 * and the settings Activity. Both are referenced by NAME from
 * `AndroidManifest.xml`, and one of them is referenced by name again from
 * `res/xml/stationly_dream_info.xml`. A manifest reference to a class that no
 * longer exists compiles perfectly and fails when the system tries to start it.
 *
 * The screensaver is also the one surface in the app **that cannot be opened by
 * tapping anything** — the OS binds it when the device docks or charges. So a
 * failure here shows up as a blank screen on somebody's bedside table, with no
 * crash dialog, no route back to a bug report, and nothing in the app to notice
 * it. That combination is why the widget got
 * `WidgetConfigurationContractTest` and why this is its counterpart.
 *
 * ## The four things asserted, and what each one costs
 *
 *  1. **Both components exist.** A dangling `android:name` is a screensaver that
 *     never starts and a gear that throws where the user cannot see it.
 *  2. **`settingsActivity` resolves to a declared component.** This is the
 *     reference that would have broken silently in AV2-6.2 — it names the class
 *     twice over, package and all, in a file nothing else touches.
 *  3. **`BIND_DREAM_SERVICE` and the intent filter.** Without the permission any
 *     app could bind the dream; without the action the system never offers
 *     "Stationly" in the screensaver picker at all, and the feature is simply
 *     absent with no error anywhere.
 *  4. **`taskAffinity=""` + `singleTask` + `excludeFromRecents`.** System
 *     Settings starts the settings Activity. Without these Android stacks it
 *     onto Stationly's own task, so pressing back walks the user through
 *     `MainActivity` and the whole app history instead of returning to Settings
 *     — and the screensaver picker appears in Recents as if it were the app.
 *     Documented as load-bearing in three places before this test, and asserted
 *     in none.
 */
class DreamManifestContractTest {

    private val manifest = xml("src/main/AndroidManifest.xml")
    private val dreamInfo = xml("src/main/res/xml/stationly_dream_info.xml")

    private val serviceName = ".dream.StationlyDreamService"
    private val settingsName = ".dream.DreamSettingsActivity"

    @Test
    fun `both components the system starts are declared`() {
        assertNotNull(
            "the screensaver service is not in the manifest — the OS has nothing to bind",
            service(serviceName),
        )
        assertNotNull(
            "the settings Activity is not in the manifest — the gear in system " +
                "Settings throws where nobody can see it",
            activity(settingsName),
        )
    }

    /**
     * The reference AV2-6.2 could have broken without anything noticing: the
     * dream metadata names the settings Activity by its full component, in a
     * file nothing else in the build reads.
     */
    @Test
    fun `the dream metadata points at an Activity that exists`() {
        val configured = dreamInfo.attr("settingsActivity")
        assertEquals(
            "stationly_dream_info.xml names a different Activity than the manifest declares",
            "com.stationly.mobile/.dream.DreamSettingsActivity",
            configured,
        )

        // And the manifest half of the same claim, so changing ONE side fails.
        assertNotNull(activity(settingsName))

        assertEquals(
            "the service's metadata no longer points at stationly_dream_info",
            "@xml/stationly_dream_info",
            metaData(service(serviceName)!!, "android.service.dream")?.attr("resource"),
        )
    }

    @Test
    fun `only the OS can bind the dream, and the OS can find it`() {
        val service = service(serviceName)!!
        assertEquals(
            "without BIND_DREAM_SERVICE any app could bind the screensaver",
            "android.permission.BIND_DREAM_SERVICE",
            service.attr("permission"),
        )
        assertEquals(
            "a dream service must be exported for the system to bind it",
            "true",
            service.attr("exported"),
        )
        assertTrue(
            "without the DreamService action the system never offers Stationly in " +
                "the screensaver picker, and the feature is absent with no error",
            actions(service).contains("android.service.dreams.DreamService"),
        )
    }

    @Test
    fun `the settings Activity stays out of the app's task`() {
        val settings = activity(settingsName)!!
        // Empty string, not absent. `taskAffinity=""` is the value that isolates
        // it; removing the attribute entirely inherits the app's affinity, which
        // is the bug.
        assertEquals(
            "taskAffinity is what keeps back-navigation returning to Settings " +
                "instead of walking through MainActivity",
            "",
            settings.getAttributeNS(ANDROID_NS, "taskAffinity"),
        )
        assertTrue(
            "taskAffinity must be DECLARED as empty, not merely absent",
            settings.getAttributeNodeNS(ANDROID_NS, "taskAffinity") != null,
        )
        assertEquals("singleTask", settings.attr("launchMode"))
        assertEquals("true", settings.attr("excludeFromRecents"))
        assertEquals(
            "system Settings starts this Activity, so it has to be exported",
            "true",
            settings.attr("exported"),
        )
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

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun service(name: String): Element? =
        elements(manifest, "service").firstOrNull { it.attr("name") == name }

    private fun activity(name: String): Element? =
        elements(manifest, "activity").firstOrNull { it.attr("name") == name }

    private fun metaData(parent: Element, name: String): Element? =
        elements(parent, "meta-data").firstOrNull { it.attr("name") == name }

    private fun actions(parent: Element): List<String> =
        elements(parent, "action").mapNotNull { it.attr("name") }
}
