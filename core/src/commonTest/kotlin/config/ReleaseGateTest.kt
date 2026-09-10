package com.stationly.core.config

import com.stationly.core.model.release.PlatformRelease
import com.stationly.core.model.release.ReleasePolicy
import com.stationly.core.service.parseUpgradeRejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When this build is blocked and when it is nudged.
 *
 * Every interesting case here is one that cannot be produced against a correct
 * backend, which is precisely why it needs asserting somewhere. A version floor
 * decides whether the app opens at all, and both of its failure directions are
 * silent from the outside: a gate that never fires looks exactly like a healthy
 * fleet, and a gate that fires wrongly looks exactly like an outage.
 *
 * The blocking tests are deliberately weighted toward the ways a block should
 * be REFUSED. Missing a stale client for one more launch costs nothing and
 * self-corrects; blocking a current one is an app that will not open in front
 * of somebody with no action available to them.
 */
class ReleaseGateTest {

    private val day = 24L * 60 * 60 * 1000

    private fun release(
        min: String = "",
        recommended: String = "",
        latest: String = "",
        intervalDays: Int = 14,
    ) = PlatformRelease(
        minimumVersion = min,
        recommendedVersion = recommended,
        latestVersion = latest,
        storeUrl = "itms-apps://apps.apple.com/app/id1",
        storeUrlWeb = "https://apps.apple.com/app/id1",
        nudgeIntervalDays = intervalDays,
    )

    private fun policy(gateEnabled: Boolean = true) = ReleasePolicy(gateEnabled = gateEnabled)

    /** Long past the post-install grace, so it never confounds a nudge case. */
    private val now = 90L * day

    private fun nudgeState(
        lastShown: Long = 0L,
        snoozed: String = "",
        firstSeen: Long = 0L,
    ) = ReleaseGate.NudgeState(lastShown, snoozed, firstSeen)

    // ── Version comparison ───────────────────────────────────────────────
    //
    // The same algorithm runs in the backend (`compareVersions`), and the two
    // evaluate the same document — the server on the request path, the client
    // offline off its cache. A disagreement shows up as a gate that appears and
    // disappears with connectivity, so both sides assert the cases that
    // separate a correct implementation from a plausible one.

    @Test
    fun `missing segments read as zero so 1_2 and 1_2_0 are the same version`() {
        assertFalse(isVersionBelow("1.2", "1.2.0"))
        assertFalse(isVersionBelow("1.2.0", "1.2"))
    }

    @Test
    fun `ordering is numeric per segment rather than lexicographic`() {
        // The one a string comparison gets wrong: "1.10" sorts before "1.9".
        assertFalse(isVersionBelow("1.10.0", "1.9.0"))
        assertTrue(isVersionBelow("1.9.0", "1.10.0"))
    }

    @Test
    fun `a staging suffix does not read as an older build`() {
        // Android appends "-staging" to versionName. Parsing that as a lower
        // version would gate every staging build against its own floor.
        assertFalse(isVersionBelow("1.0-staging", "1.0"))
    }

    @Test
    fun `an unparseable version compares as zero and is below any real floor`() {
        assertTrue(isVersionBelow("", "1.0"))
        assertTrue(isVersionBelow("not-a-version", "1.0"))
    }

    // ── Blocking: the cases where a block is refused ─────────────────────

    @Test
    fun `an empty document blocks nothing`() {
        assertFalse(ReleaseGate.shouldBlock(policy(), PlatformRelease(), installed = "0.1"))
    }

    @Test
    fun `gateEnabled false is an instant kill switch for the floor`() {
        // The recovery path for a mis-set floor that does not need a code change.
        assertFalse(
            ReleaseGate.shouldBlock(policy(gateEnabled = false), release(min = "9.9", latest = "9.9"), "1.0"),
        )
    }

    @Test
    fun `a floor above the newest installable build is refused`() {
        // The phased-release trap. Apple rolls an update out over 7 days, so a
        // floor set to a build still rolling out tells a user to update and then
        // hands them a store page with no Update button.
        assertFalse(
            ReleaseGate.shouldBlock(policy(), release(min = "2.0", latest = "1.5"), installed = "1.0"),
        )
    }

    @Test
    fun `a client exactly at the floor is not blocked`() {
        // The floor is inclusive. Off by one here blocks the very build that
        // was shipped to satisfy it.
        assertFalse(ReleaseGate.shouldBlock(policy(), release(min = "2.0", latest = "2.0"), "2.0"))
    }

    @Test
    fun `a client above the floor is not blocked`() {
        assertFalse(ReleaseGate.shouldBlock(policy(), release(min = "2.0", latest = "3.0"), "2.5"))
    }

    // ── Blocking: the case where it fires ────────────────────────────────

    @Test
    fun `a client below the floor is blocked`() {
        assertTrue(ReleaseGate.shouldBlock(policy(), release(min = "2.0", latest = "2.0"), "1.9"))
    }

    // ── Nudging ──────────────────────────────────────────────────────────

    @Test
    fun `no recommendation means no nudge`() {
        assertFalse(ReleaseGate.shouldNudge(release(), "1.0", nudgeState(), now))
    }

    @Test
    fun `a client below the recommendation is nudged once`() {
        assertTrue(
            ReleaseGate.shouldNudge(release(recommended = "2.0", latest = "2.0"), "1.0", nudgeState(), now),
        )
    }

    @Test
    fun `a client at or above the recommendation is never nudged`() {
        val r = release(recommended = "2.0", latest = "2.0")
        assertFalse(ReleaseGate.shouldNudge(r, "2.0", nudgeState(), now))
        assertFalse(ReleaseGate.shouldNudge(r, "2.1", nudgeState(), now))
    }

    @Test
    fun `a fresh install is left alone for the grace period`() {
        // Someone who installed an hour ago cannot act on "please update", and
        // during a phased release the store may have handed them a build below
        // the recommendation through no choice of theirs.
        val r = release(recommended = "2.0", latest = "2.0")
        val installedNow = ReleaseGate.POST_INSTALL_GRACE_MS / 2
        assertFalse(ReleaseGate.shouldNudge(r, "1.0", nudgeState(firstSeen = 0L), installedNow))
    }

    @Test
    fun `the grace period lapses and the nudge becomes available`() {
        val r = release(recommended = "2.0", latest = "2.0")
        val after = ReleaseGate.POST_INSTALL_GRACE_MS + 1
        assertTrue(ReleaseGate.shouldNudge(r, "1.0", nudgeState(firstSeen = 0L), after))
    }

    @Test
    fun `a nudge inside the interval is suppressed`() {
        val r = release(recommended = "2.0", latest = "2.0", intervalDays = 14)
        assertFalse(ReleaseGate.shouldNudge(r, "1.0", nudgeState(lastShown = now - 13 * day), now))
    }

    @Test
    fun `a nudge past the interval is allowed again`() {
        val r = release(recommended = "2.0", latest = "2.0", intervalDays = 14)
        assertTrue(ReleaseGate.shouldNudge(r, "1.0", nudgeState(lastShown = now - 15 * day), now))
    }

    @Test
    fun `an interval of zero is clamped rather than trusted`() {
        // A backend typo of 0 would otherwise nudge on every evaluation, which
        // is the behaviour this whole mechanism exists to replace.
        val r = release(recommended = "2.0", latest = "2.0", intervalDays = 0)
        assertFalse(ReleaseGate.shouldNudge(r, "1.0", nudgeState(lastShown = now - 1000), now))
    }

    @Test
    fun `a dismissed version is never asked about again even after the interval`() {
        // A dismissal is an answer about a VERSION, not a timer. Re-asking the
        // same question in a fortnight is how a nudge becomes nagging.
        val r = release(recommended = "2.0", latest = "2.0", intervalDays = 14)
        assertFalse(
            ReleaseGate.shouldNudge(r, "1.0", nudgeState(lastShown = now - 100 * day, snoozed = "2.0"), now),
        )
    }

    @Test
    fun `a newer recommendation is a new question and is asked`() {
        val r = release(recommended = "3.0", latest = "3.0", intervalDays = 14)
        assertTrue(
            ReleaseGate.shouldNudge(r, "1.0", nudgeState(lastShown = now - 100 * day, snoozed = "2.0"), now),
        )
    }

    @Test
    fun `a recommendation above the newest installable build is not nudged`() {
        // Same trap as the floor: a nudge toward something nobody can install
        // never goes away however many times the user taps Update.
        val r = release(recommended = "3.0", latest = "2.0")
        assertFalse(ReleaseGate.shouldNudge(r, "1.0", nudgeState(), now))
    }

    // ── The 426 body ─────────────────────────────────────────────────────
    //
    // Parsed from untrusted JSON on the response path, and it is the ONLY input
    // to the hard gate that cannot be missed. Every case here is a malformed
    // body, because a correct backend never sends one and the parser therefore
    // has no other way of being exercised.

    @Test
    fun `a full rejection body yields every field`() {
        val r = parseUpgradeRejection(
            """{"code":"client_too_old","title":"T","message":"M","cta":"C",""" +
                """"minimumVersion":"2.0","storeUrl":"itms-apps://x","storeUrlWeb":"https://y"}""",
        )
        assertEquals("T", r.title)
        assertEquals("M", r.message)
        assertEquals("C", r.cta)
        assertEquals("2.0", r.minimumVersion)
        assertEquals("itms-apps://x", r.storeUrl)
        assertEquals("https://y", r.storeUrlWeb)
    }

    @Test
    fun `a body that is absent or not JSON yields nulls rather than throwing`() {
        // The status is the fact; the body only supplies words and links. A
        // parser that threw here would drop the one signal that cannot be
        // missed, so an unreadable body must still produce a usable rejection.
        for (body in listOf(null, "", "not json", "[]", "null")) {
            val r = parseUpgradeRejection(body)
            assertNull(r.title, "body ${'$'}body must not produce a title")
            assertNull(r.storeUrl)
        }
    }

    @Test
    fun `blank and wrongly-typed fields collapse to null so a fallback can run`() {
        // A blank title would otherwise render a blocking screen that says
        // nothing, and a numeric storeUrl would render a button going nowhere.
        val r = parseUpgradeRejection(
            """{"title":"   ","storeUrl":123,"cta":null,"minimumVersion":"2.0"}""",
        )
        assertNull(r.title)
        assertNull(r.storeUrl)
        assertNull(r.cta)
        assertEquals("2.0", r.minimumVersion, "the readable field still survives")
    }

    // ── Copy resolution ──────────────────────────────────────────────────

    @Test
    fun `copy falls back per key so a partial payload is not a blank screen`() {
        val policy = ReleasePolicy(strings = mapOf(ReleasePolicy.KEY_BLOCKED_TITLE to "Custom"))
        val copy = policy.blockedCopy()
        assertEquals("Custom", copy.title)
        assertTrue(copy.message.isNotBlank(), "an unset key keeps its compiled fallback")
        assertTrue(copy.cta.isNotBlank())
    }

    @Test
    fun `a blank served string is treated as absent rather than as empty copy`() {
        val policy = ReleasePolicy(strings = mapOf(ReleasePolicy.KEY_BLOCKED_CTA to "   "))
        assertTrue(policy.blockedCopy().cta.isNotBlank())
    }
}
