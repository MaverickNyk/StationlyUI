package com.stationly.mobile.host

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The host is one Activity in a live app, and most of what keeps it safe is an
 * attribute in a manifest rather than a line of Kotlin. Nothing else in the
 * build would notice these going wrong: renaming the activity still compiles,
 * dropping `singleTask` still compiles, losing a deep-link filter still
 * compiles, and writing a scheme out as a literal still compiles. Each one
 * changes what the shipped app does, and three of the four do it silently.
 *
 * This replaced `V2HostManifestTest`, which asserted the OPPOSITE arrangement:
 * a v1 launcher owning every deep link and a staging-only second door beside it.
 * AV2-3.5 was the story allowed to undo those, and did — by moving the shared-UI
 * host into `com.stationly.mobile.MainActivity` rather than promoting the second
 * door's own name.
 */
class HostManifestTest {

    private val manifest = manifest("src/main/AndroidManifest.xml")

    @Test
    fun `the host is MainActivity, and it is the launcher`() {
        // The NAME is the assertion. Home-screen pins and launcher shortcuts
        // reference the component (`com.stationly.mobile/.MainActivity`), not
        // the package, so a v1 user who pinned the icon keeps a working one only
        // while this name exists. Renaming the class — or promoting a different
        // activity and deleting this one — greys out that icon with no error
        // anywhere, and the user's only recourse is to re-add it.
        val host = manifest.activity(".MainActivity")
        assertTrue("the app needs a launcher", host.isLauncher())
        assertEquals(
            "exactly one launcher. Two was the AV2-3.1 arrangement — a second, " +
                "staging-only door beside the shipped app — and it ended at the cutover.",
            1,
            manifest.activities().count { it.isLauncher() },
        )
    }

    @Test
    fun `the host owns every deep link, in the order v1 registered them`() {
        assertEquals(
            "a host that does not advertise a host name is never handed that " +
                "link, and nothing errors: the tap does nothing. `core`'s " +
                "parseDeepLink can route all four, and DeepLinkSchemeTest proves " +
                "it — but only for the ones Android actually delivers.",
            listOf("auth", "reset", "home", "verified"),
            manifest.activity(".MainActivity").deepLinkHosts(),
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
        assertEquals(
            emptyList<String>(),
            manifest.deepLinkSchemes().filterNot { it == "\${deepLinkScheme}" },
        )
    }

    @Test
    fun `the host keeps singleTask`() {
        // v1 shipped a blank screen without it: a relaunch spawned a new task
        // with a fresh Activity, the tasks piled up (three observed at once),
        // and returning to a freshly-restored instance found the NavHost back
        // stack empty. Every launch has to funnel through onNewIntent, which is
        // also where the deep links above are read.
        assertEquals("singleTask", manifest.activity(".MainActivity").getAttr("launchMode"))
    }

    @Test
    fun `the host does not carry a task affinity of its own`() {
        // `taskAffinity="com.stationly.mobile.v2"` was on the second door, to
        // stop the two launchers clearing each other off one stack. With one
        // door left, an affinity that is not the package's own would put the app
        // in a task named after a package that no longer exists — and split it
        // from anything the app itself starts.
        assertFalse(
            "the two-door task affinity should have gone with the second door",
            manifest.activity(".MainActivity").hasAttr("taskAffinity"),
        )
    }

    @Test
    fun `the Application class is the one that initialises Platform`() {
        // `Platform.initialize` runs in StationlyApplication.onCreate, which
        // Android completes before any component of the process starts. There is
        // no runtime flag on Platform to check, so this is the proof: replace
        // the Application class and the first composition touches an
        // uninitialised Platform.
        assertEquals(".StationlyApplication", manifest.applicationName())
    }

    @Test
    fun `no staging manifest survives the cutover`() {
        // It existed to register the second door and nothing else. Left behind
        // empty, it is an invitation to put something flavour-specific in a file
        // that only one of two builds reads.
        assertFalse(
            "src/staging/AndroidManifest.xml registered the v2 door; the door is gone",
            listOf(
                File("src/staging/AndroidManifest.xml"),
                File("android/app/src/staging/AndroidManifest.xml"),
            ).any { it.isFile },
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
