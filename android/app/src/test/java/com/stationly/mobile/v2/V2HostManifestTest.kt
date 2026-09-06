package com.stationly.mobile.v2

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The v2 host is a SECOND door into an app that is live on `versionCode 2`, and
 * everything that keeps it safe is an attribute in a manifest rather than a
 * line of Kotlin. Nothing else in the build would notice these going wrong:
 * moving the activity into `src/main` still compiles, dropping `singleTask`
 * still compiles, adding a `stationly://` filter still compiles, and writing a
 * scheme out as a literal still compiles. Each one changes what the shipped app
 * does.
 *
 * So this test reads the manifests as files. It is not testing Android; it is
 * testing the manifest decisions AV2-3.1 and AV2-3.4 took, which AV2-3.5 is
 * allowed to undo — deliberately, by editing this test — but that nothing else
 * should.
 */
class V2HostManifestTest {

    private val mainManifest = manifest("src/main/AndroidManifest.xml")
    private val stagingManifest = manifest("src/staging/AndroidManifest.xml")

    @Test
    fun `the v2 host is registered only in the staging manifest`() {
        assertEquals(
            "V2MainActivity must not reach a prod build: the :composeApp dependency " +
                "it needs is scoped to the staging flavour, so a prod build that " +
                "declared it would install an activity that cannot start.",
            emptyList<String>(),
            mainManifest.activityNames().filter { it.contains("V2MainActivity") },
        )
        assertEquals(listOf(".v2.V2MainActivity"), stagingManifest.activityNames())
    }

    @Test
    fun `v1 MainActivity is still the launcher and still owns the deep links`() {
        val v1 = mainManifest.activity(".MainActivity")
        assertTrue("v1 must stay a launcher until AV2-3.5", v1.isLauncher())
        assertEquals(
            "AV2-3.4 made the scheme per-flavour but deliberately left the four " +
                "filters here; AV2-3.5 moves them, when there is one door left.",
            listOf("auth", "reset", "home", "verified"),
            v1.deepLinkHosts(),
        )
    }

    @Test
    fun `no manifest pins one environment's scheme`() {
        // `${deepLinkScheme}` resolves to `stationly` for prod and
        // `stationly-staging` for staging, from a single argument in
        // build.gradle.kts that also sets BuildConfig.DEEP_LINK_SCHEME. A
        // literal here would register one environment's scheme in both builds —
        // and installing either would then answer for the other's links.
        // `DeepLinkSchemeTest` holds the code half of the same contract.
        listOf(mainManifest, stagingManifest).forEach { m ->
            assertEquals(
                emptyList<String>(),
                m.deepLinkSchemes().filterNot { it == "\${deepLinkScheme}" },
            )
        }
    }

    @Test
    fun `the v2 host advertises no deep link scheme`() {
        // Two activities advertising one scheme would put a disambiguation
        // dialog in front of the user on every link tap, on a build that exists
        // to be tested by hand. So the second door is reached with `am start -n`
        // instead — see V2MainActivity.
        assertEquals(emptyList<String>(), stagingManifest.activity(".v2.V2MainActivity").deepLinkHosts())
    }

    @Test
    fun `the v2 host keeps singleTask and its own task affinity`() {
        val v2 = stagingManifest.activity(".v2.V2MainActivity")
        // singleTask: v1 shipped a blank screen without it — a relaunch spawned
        // a new task and the restored NavHost came back with an empty back stack.
        assertEquals("singleTask", v2.getAttr("launchMode"))
        // Its own affinity: sharing v1's would put both doors in one task, where
        // launching either one clears the other off the top.
        assertEquals("com.stationly.mobile.v2", v2.getAttr("taskAffinity"))
        assertTrue("the second door needs its own launcher icon", v2.isLauncher())
    }

    @Test
    fun `the staging manifest does not take over the Application class`() {
        // The proof for AV2-3.1 task (d). `Platform.initialize` runs in
        // StationlyApplication.onCreate, which Android completes before any
        // component starts — but only while staging still uses that class. A
        // staging <application android:name="..."> would silently replace it and
        // the first composition would touch an uninitialised Platform.
        assertEquals(".StationlyApplication", mainManifest.applicationName())
        assertFalse(
            "the staging manifest must add activities, not replace the Application",
            stagingManifest.application().hasAttr("name"),
        )
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private fun manifest(relative: String): Element {
        // Gradle runs unit tests with the module directory as the working
        // directory; an IDE may use the repo root. Accept either rather than
        // failing for a reason that has nothing to do with the assertion.
        val file = listOf(File(relative), File("android/app/$relative")).firstOrNull { it.isFile }
            ?: error("cannot find $relative from ${File(".").absolutePath}")
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun Element.application(): Element =
        getElementsByTagName("application").item(0) as Element

    private fun Element.applicationName(): String? = application().getAttr("name")

    private fun Element.activities(): List<Element> =
        application().getElementsByTagName("activity").asList()

    private fun Element.activityNames(): List<String> = activities().mapNotNull { it.getAttr("name") }

    private fun Element.activity(name: String): Element =
        activities().firstOrNull { it.getAttr("name") == name }
            ?: error("no <activity android:name=\"$name\"> in this manifest")

    private fun Element.getAttr(name: String): String? =
        getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }

    private fun Element.hasAttr(name: String): Boolean = getAttr(name) != null

    private fun Element.intentFilters(): List<Element> =
        getElementsByTagName("intent-filter").asList()

    private fun Element.isLauncher(): Boolean = intentFilters().any { filter ->
        filter.getElementsByTagName("category").asList()
            .any { it.getAttr("name") == "android.intent.category.LAUNCHER" }
    }

    /** The `android:host` of every `<data>` element under this activity, in order. */
    private fun Element.deepLinkHosts(): List<String> = dataElements().mapNotNull { it.getAttr("host") }

    /** The `android:scheme` of every `<data>` element anywhere in this manifest. */
    private fun Element.deepLinkSchemes(): List<String> = dataElements().mapNotNull { it.getAttr("scheme") }

    private fun Element.dataElements(): List<Element> =
        intentFilters().flatMap { it.getElementsByTagName("data").asList() }

    private fun NodeList.asList(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }
}
