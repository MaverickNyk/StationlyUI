package com.stationly.core.model.release

import kotlinx.serialization.Serializable

/**
 * Which builds are still allowed to run, and which should be nudged.
 *
 * Wire shape of `AppReleaseService.ReleasePolicy` in the backend, served at
 * `GET /sdui/app/release-policy`. Decoded with `ignoreUnknownKeys`, and every
 * field carries a default so a payload from an older or newer backend still
 * decodes — a policy that fails to decode entirely falls back to
 * [ReleasePolicyDefaults.POLICY], which gates nothing.
 *
 * ## Why a document rather than the two config keys that already existed
 * `app.minVersion` and `app.storeUrl` are in the home config and are read by
 * the Android binary that is already in the Play Store, so they can never be
 * removed or repurposed. They also cannot express the policy:
 *
 *  - one version number for two platforms that release independently
 *  - one threshold doing the work of two very different statements
 *  - a Play Store URL served to iPhones, because the home config has no
 *    platform branching
 *
 * ## Every default here is "do nothing"
 * A blank floor compares equal to any version, so an undecodable payload, an
 * unreachable backend and a first launch all resolve to [UpdateVerdict.Ok].
 * That is the only safe resting state: the cost of missing one stale client for
 * another launch is invisible, and the cost of blocking a current one is an app
 * that will not open with no user-side remedy.
 */
@Serializable
data class ReleasePolicy(
    /** Monotonic. Bumped by the backend on every edit. */
    val version: Int = 0,
    /**
     * Master switch for the BLOCKING gate only — nudges are unaffected.
     * Off means [PlatformRelease.minimumVersion] is ignored entirely, which is
     * how a mis-set floor is recovered from without waiting on a deploy.
     */
    val gateEnabled: Boolean = false,
    val ios: PlatformRelease = PlatformRelease(),
    val android: PlatformRelease = PlatformRelease(),
    /** Copy for both surfaces. Flat strings, platform-neutral. */
    val strings: Map<String, String> = emptyMap(),
) {
    /**
     * The blocking screen's words, resolved once at decision time.
     *
     * ## Why resolution happens here and not in the composable
     * The surfaces used to read `ReleaseGate.policy.strings` directly during
     * composition. That is a plain `var`, not snapshot state, so a document
     * adopted while a surface was on screen did not recompose it — the UI
     * showed whatever copy happened to be loaded when the verdict changed, and
     * only re-read it by accident on the next unrelated recomposition.
     *
     * Resolving into the verdict makes the surfaces a pure function of one
     * flow, and puts the compiled fallbacks in one place instead of duplicating
     * them at each `?:` in the UI.
     */
    fun blockedCopy(): UpdateCopy = UpdateCopy(
        title = text(KEY_BLOCKED_TITLE, "Time to update"),
        message = text(
            KEY_BLOCKED_MESSAGE,
            "This version of Stationly is no longer supported. Update to keep seeing live departures.",
        ),
        cta = text(KEY_BLOCKED_CTA, "Update Stationly"),
    )

    fun nudgeCopy(): UpdateCopy = UpdateCopy(
        title = text(KEY_NUDGE_TITLE, "New update available"),
        message = text(KEY_NUDGE_MESSAGE, "Update Stationly for the latest features and improvements."),
        cta = text(KEY_NUDGE_CTA, "Update Now"),
        dismiss = text(KEY_NUDGE_DISMISS, "Not now"),
    )

    /** Blank is treated as absent rather than as a request for an empty label —
     *  same posture as [com.stationly.core.config.RemoteConfig.text], and the
     *  reason matters more here: a blank title on a blocking screen is a screen
     *  that says nothing at all. */
    private fun text(key: String, fallback: String): String =
        strings[key]?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    companion object {
        const val KEY_BLOCKED_TITLE   = "update.blocked.title"
        const val KEY_BLOCKED_MESSAGE = "update.blocked.message"
        const val KEY_BLOCKED_CTA     = "update.blocked.cta"
        const val KEY_NUDGE_TITLE     = "update.nudge.title"
        const val KEY_NUDGE_MESSAGE   = "update.nudge.message"
        const val KEY_NUDGE_CTA       = "update.nudge.cta"
        const val KEY_NUDGE_DISMISS   = "update.nudge.dismiss"
    }
}

/** What a surface says. Resolved at decision time — see [ReleasePolicy.blockedCopy]. */
data class UpdateCopy(
    val title: String,
    val message: String,
    val cta: String,
    /** Only the nudge has a decline. Blank on the blocking screen, which by
     *  design has no second action. */
    val dismiss: String = "",
)

@Serializable
data class PlatformRelease(
    /**
     * Below this the client is BLOCKED, with no way past the screen.
     *
     * A statement about the BACKEND, not about features: it is raised when the
     * server genuinely cannot serve that build any more. Most users should never
     * see it once in the app's lifetime.
     */
    val minimumVersion: String = "",
    /** Below this a dismissible, rate-limited nudge is allowed. */
    val recommendedVersion: String = "",
    /**
     * Newest build that has FINISHED rolling out and is installable by anyone.
     *
     * Load-bearing, not informational: the client refuses to block against a
     * floor above this, because Apple's 7-day phased release means a floor set
     * to a build still rolling out shows "you must update" to someone whose App
     * Store has no Update button. The backend asserts the same invariant; the
     * client re-checks it because a client that trusts a bad document has no
     * way back.
     */
    val latestVersion: String = "",
    /** Scheme link: `itms-apps://` (iOS) or `market://` (Android). */
    val storeUrl: String = "",
    /**
     * https form. On iOS this is a universal link the App Store app claims, so
     * it is equally direct; on Android it is the only link that resolves on a
     * device without the Play Store. Not a lesser option — see the opener in
     * `UpdateSurfaces`.
     */
    val storeUrlWeb: String = "",
    /** Minimum days between two nudges. */
    val nudgeIntervalDays: Int = 14,
)

/** What should happen to this build, resolved from a policy plus local state. */
sealed interface UpdateVerdict {
    /** Nothing to say. The overwhelmingly common case, and the default. */
    data object Ok : UpdateVerdict

    /**
     * Blocked. Non-dismissible, and the only state that can stop the app being
     * used. [reason] names how the verdict was reached — a user who cannot open
     * the app is a support conversation, and "which of the two paths blocked
     * them" is the first question in it, with opposite fixes either way.
     */
    data class Blocked(
        val store: StoreLink,
        val minimumVersion: String,
        val reason: BlockReason,
        val copy: UpdateCopy,
    ) : UpdateVerdict

    /** A newer build exists. Dismissible, rate-limited, snoozable. */
    data class Nudge(
        val store: StoreLink,
        val toVersion: String,
        val copy: UpdateCopy,
    ) : UpdateVerdict
}

/**
 * How a block was reached. An enum rather than a string because the two are not
 * interchangeable and one of them is load-bearing: a [SERVER] block is a fact
 * the client must not talk itself out of, and `ReleaseGate.reevaluate` tests for
 * it on every pass. That comparison was a magic string in two files.
 */
enum class BlockReason {
    /** The backend refused a request with 426. The server has spoken. */
    SERVER,

    /** The client's own reading of the policy document. Advisory by comparison:
     *  it can be re-derived, and a newer document can clear it. */
    POLICY,
}

/**
 * Where "Update" goes. Callers try [deepLink] and fall back to [web] — see the
 * opener in `UpdateSurfaces` for why both are needed and which platform needs
 * which.
 */
@Serializable
data class StoreLink(val deepLink: String, val web: String) {
    /** True when neither link was configured, so "Update" has nowhere to go. */
    val isEmpty: Boolean get() = deepLink.isBlank() && web.isBlank()
}

object ReleasePolicyDefaults {
    /**
     * The compiled fallback: gates nothing, nudges nothing.
     *
     * Deliberately NOT a mirror of the server's document the way
     * `RefreshPolicyDefaults` mirrors its schedule. A refresh cadence has a
     * sensible compiled answer; a version floor does not, because the only
     * honest compiled answer to "is this build too old" is "nothing has said
     * so". Shipping a real floor here would mean a client that could block
     * itself while offline, forever, with no way to be told otherwise.
     */
    val POLICY = ReleasePolicy()
}
