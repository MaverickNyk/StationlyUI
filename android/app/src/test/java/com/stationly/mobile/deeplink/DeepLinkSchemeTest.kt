package com.stationly.mobile.deeplink

import com.stationly.core.model.deeplink.DeepLinkRoute
import com.stationly.core.model.deeplink.parseDeepLink
import com.stationly.mobile.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of a deep link, held together.
 *
 * A build **registers** a scheme in its manifest and separately **accepts** one
 * in its code. Nothing makes those agree: registering `stationly-staging://` and
 * comparing against a hardcoded `"stationly"` compiles, installs, and runs. The
 * link arrives, is dropped, and nothing errors — no log line, no crash, the tap
 * simply does nothing. iOS shipped exactly that and lost two days to it
 * (`docs/SESSION_2026-08-17_WIDGET_STATION.md`), which is why Android's version
 * of the mistake gets a test before it gets a chance.
 *
 * These assertions are flavour-neutral on purpose: they must hold for whichever
 * variant the runner picked, so that adding a third environment cannot quietly
 * skip them.
 */
class DeepLinkSchemeTest {

    private val scheme = BuildConfig.DEEP_LINK_SCHEME

    /** The other environment's scheme — the one this build must NOT answer to. */
    private val foreignScheme =
        if (scheme == "stationly") "stationly-staging" else "stationly"

    @Test
    fun `this build declares a scheme, and it is one the build file actually defines`() {
        assertTrue("BuildConfig.DEEP_LINK_SCHEME is empty", scheme.isNotBlank())
        // `deepLinkScheme(...)` sets the manifest placeholder and this
        // BuildConfig field from ONE argument, so finding the call proves the
        // value the code just read is the value the manifest was built with.
        assertTrue(
            "no deepLinkScheme(\"$scheme\") in build.gradle.kts — the manifest and " +
                "the code are being told different things",
            buildFile().contains("deepLinkScheme(\"$scheme\")"),
        )
    }

    @Test
    fun `the manifest names no scheme literal of its own`() {
        val literals = Regex("android:scheme=\"([^\"$]+)\"")
            .findAll(manifest())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "every <data android:scheme> must be \${deepLinkScheme}. A literal here " +
                "is the whole bug: it pins one environment's scheme into a file that " +
                "both environments are built from.",
            emptyList<String>(),
            literals,
        )
    }

    @Test
    fun `every link under this build's own scheme routes where v1 routed it`() {
        assertEquals(DeepLinkRoute.Home, route("://home"))
        assertEquals(DeepLinkRoute.PasswordResetComplete, route("://auth"))
        assertEquals(DeepLinkRoute.ResetPassword("XYZ789"), route("://reset?oobCode=XYZ789"))
        assertEquals(DeepLinkRoute.VerifyEmail("ABC123"), route("://verified?oobCode=ABC123"))
        // A known host without its code stays unhandled: v1 checked
        // isNullOrBlank rather than opening a reset screen that cannot complete.
        assertEquals(DeepLinkRoute.Unhandled, route("://reset"))
        assertEquals(DeepLinkRoute.Unhandled, route("://reset?oobCode="))
    }

    @Test
    fun `the other environment's links are not ours to answer`() {
        // The half that only a two-environment build can get wrong. On staging
        // this is `stationly://reset?…` — a link that, before AV2-3.4, this app
        // both registered and accepted while prod did too.
        assertEquals(
            DeepLinkRoute.Unhandled,
            parseDeepLink("$foreignScheme://reset?oobCode=XYZ789", scheme),
        )
        assertEquals(DeepLinkRoute.Unhandled, parseDeepLink("$foreignScheme://home", scheme))
    }

    private fun route(suffix: String): DeepLinkRoute = parseDeepLink("$scheme$suffix", scheme)

    /**
     * Gradle runs unit tests with the module directory as the working
     * directory; an IDE may use the repo root. Accept either, as
     * `V2HostManifestTest` does.
     */
    private fun read(relative: String): String =
        listOf(File(relative), File("android/app/$relative")).firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relative from ${File(".").absolutePath}")

    private fun manifest() = read("src/main/AndroidManifest.xml")

    private fun buildFile() = read("build.gradle.kts")
}
