package config

import com.stationly.core.config.BoardPolicy
import com.stationly.core.config.BoardPolicyStore
import com.stationly.core.config.SduiConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Warming the served config on the surfaces that have no screen above them.
 *
 * ## The gap, and why it was invisible
 * Everything about how a board behaves is served: how long a departed train
 * stays on screen and what it says while it does, how deep SQL is written, when
 * a timestamp starts going amber, which line status counts as the worst. Until
 * something calls `SduiConfig.refresh`, all of it is the values that were
 * compiled — a safe answer, not a correct one.
 *
 * On iOS the paths with no UI already warm themselves. On Android the paths with
 * no UI are the ones that run MOST: a home-screen widget redrawn by a push
 * several times a minute in a process where no Activity need ever have started,
 * the prediction sync that writes what that widget draws, and the screensaver.
 * All three ran on compiled rules while the app beside them ran on served ones,
 * and nothing on any screen said which you were looking at.
 */
class SduiWarmUpTest {

    @BeforeTest
    fun reset() {
        SduiConfig.resetForTest()
        BoardPolicyStore.resetForTest()
    }

    @AfterTest
    fun tidy() = reset()

    @Test
    fun `nothing has been adopted on a cold process`() {
        assertFalse(SduiConfig.adopted)
    }

    @Test
    fun `adopting a map records that something was adopted`() {
        SduiConfig.refresh(mapOf(BoardPolicy.KEY_LABEL to "Left"))
        assertTrue(SduiConfig.adopted)
        assertEquals("Left", BoardPolicyStore.current.departedLabel)
    }

    /**
     * An empty map is a fetch that came back with nothing, not a config that
     * says nothing. Adopting it would replace served values with defaults and
     * then claim a config had been adopted, so the warm-up would skip the cache
     * that still held the real one.
     */
    @Test
    fun `an empty map adopts nothing and claims nothing`() {
        SduiConfig.refresh(emptyMap())
        assertFalse(SduiConfig.adopted)
        assertEquals(
            BoardPolicy.DEFAULT.departedLabel,
            BoardPolicyStore.current.departedLabel,
        )
    }

    /**
     * **The one that makes the warm-up safe to call anywhere.** A widget push
     * and the app opening race constantly, and the app's fetch is the fresher
     * of the two. If the warm-up ran afterwards it would put the cached payload
     * back on top — a config that went backwards because a widget was redrawn.
     */
    @Test
    fun `warming up after a live fetch does not put the cache back on top`() = runTest {
        SduiConfig.refresh(mapOf(BoardPolicy.KEY_LABEL to "Went"))

        // Calling it IS the assertion. `ensureLoaded` reaches device storage the
        // moment the guard lets it through, and there is no device here — so a
        // missing guard shows up as a throw rather than as a wrong value, which
        // is the strongest form this can take in a common test.
        SduiConfig.ensureLoaded()

        assertTrue(SduiConfig.adopted)
        assertEquals("Went", BoardPolicyStore.current.departedLabel)
    }
}
