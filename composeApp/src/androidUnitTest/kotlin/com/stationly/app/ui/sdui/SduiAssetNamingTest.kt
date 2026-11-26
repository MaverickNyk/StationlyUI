package com.stationly.app.ui.sdui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two pure decisions inside the Android [SduiAssetCache].
 *
 * The download and the disk writes need a device. What does not, and what is
 * worth pinning, is how a backend URL becomes a file name — because that string
 * reaches the filesystem, the URL is chosen by a server, and getting it wrong
 * fails in two directions at once: a name that collides serves the wrong asset,
 * and a name that never repeats re-downloads forever.
 */
class SduiAssetNamingTest {

    @Test
    fun `the version is the v parameter, so the same asset is one file`() {
        assertEquals("6a1f9c2b", assetVersion("https://cdn/widget_stack.gif?v=6a1f9c2b"))
        assertEquals(
            assetVersion("https://cdn/a.gif?v=abc"),
            assetVersion("https://cdn/a.gif?v=abc"),
            "the same URL must key the same, or nothing is ever a cache hit",
        )
        // Not necessarily the first parameter.
        assertEquals("zz9", assetVersion("https://cdn/a.gif?w=1&v=zz9&h=2"))
    }

    @Test
    fun `a new version is a different file, which is the whole invalidation rule`() {
        assertNotEquals(
            assetVersion("https://cdn/widget_stack.gif?v=6a1f9c2b"),
            assetVersion("https://cdn/widget_stack.gif?v=7b2e0d13"),
        )
    }

    @Test
    fun `an unversioned URL is still cacheable, and keys apart from the versioned one`() {
        val bare = assetVersion("https://cdn/widget_stack.gif")
        assertTrue(bare.isNotBlank(), "an unversioned asset must still be storable")
        // The one that matters: if these collided, a payload that later GAINED
        // a version would keep serving the download made before it had one.
        assertNotEquals(bare, assetVersion("https://cdn/widget_stack.gif?v=6a1f9c2b"))
    }

    @Test
    fun `the file name cannot climb out of the cache directory`() {
        // The stem comes from a server-chosen URL, so it is sanitised before it
        // is a path. Anything that is not a letter, digit, underscore or hyphen
        // is flattened — dots included, which is what stops `..`.
        val (base, ext) = baseAndExtension("https://cdn/..%2f..%2fetc%2fpasswd.gif")!!
        assertEquals("gif", ext)
        assertTrue(
            base.none { it == '/' || it == '.' },
            "separators survived sanitisation: $base",
        )

        val (dotted, _) = baseAndExtension("https://cdn/....gif")!!
        assertTrue(!dotted.contains(".."), "relative segments survived: $dotted")
    }

    @Test
    fun `a URL with no usable name or extension is not cached at all`() {
        // Null rather than a guessed name: the contract's own "no media here"
        // answer, which the caller already renders as the poster still.
        assertNull(baseAndExtension("https://cdn/"))
        assertNull(baseAndExtension("https://cdn/noextension"))
        assertNull(baseAndExtension(""))
    }

    @Test
    fun `the query string never reaches the file name`() {
        val (base, ext) = baseAndExtension("https://cdn/widget_stack.gif?v=6a1f9c2b&x=1")!!
        assertEquals("widget_stack", base)
        assertEquals("gif", ext)
    }
}
