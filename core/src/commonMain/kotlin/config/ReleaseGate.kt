package com.stationly.core.config

import com.stationly.core.model.release.BlockReason
import com.stationly.core.model.release.PlatformRelease
import com.stationly.core.model.release.ReleasePolicy
import com.stationly.core.model.release.ReleasePolicyDefaults
import com.stationly.core.model.release.StoreLink
import com.stationly.core.model.release.UpdateCopy
import com.stationly.core.model.release.UpdateVerdict
import com.stationly.core.platform.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * Whether this build should be blocked, nudged, or left alone.
 *
 * ## Where the decision lives, and why it is not in a ViewModel
 * The old check ran inside `SummaryViewModel`, after a successful home-config
 * fetch, and rendered a dialog owned by `SummaryScreen`. That gave it three
 * limits that between them made it unable to do the job:
 *
 *  - it could only fire on the home screen, so a launch into a board, a widget
 *    tap or a deep link never reached it;
 *  - it re-evaluated on every composition of that screen and its dismissal was
 *    a `remember`, so "Maybe Later" lasted until the user navigated away;
 *  - it had no memory at all, so the only two behaviours available were "every
 *    time" and "never".
 *
 * The verdict is a property of the INSTALL, not of a screen. It is resolved
 * here, held in one flow, and rendered at the app root above every destination.
 *
 * ## Two inputs, and the second one cannot be missed
 *  1. **The policy document**, fetched from `/sdui/app/release-policy` and
 *     cached. Cheap, works offline off the cache, and covers the nudge.
 *  2. **A 426 from any endpoint**, via [onUpgradeRequired]. This is the one the
 *     hard gate actually rests on: it reaches every route, cannot be served
 *     from a stale cache, and does not ask a build the server has already
 *     concluded is broken to correctly police itself.
 *
 * A 426 therefore OVERRIDES the document. If the server says it will not serve
 * this build, no cached policy gets to disagree.
 *
 * ## Everything unknown resolves to Ok
 * Undecodable payload, unreachable backend, first launch, a platform the
 * document does not name, a version string that will not parse — all [UpdateVerdict.Ok].
 * The two failure directions do not cost the same. Letting a stale client
 * through for one more launch is invisible and self-corrects on the next
 * request. Blocking a current client is an app that will not open, in front of
 * someone who has no action available that fixes it.
 */
object ReleaseGate {

    /** Where the last successful policy document is cached. */
    const val POLICY_CACHE_KEY = "release_policy_cache"

    /** Epoch ms of the last nudge actually shown. Absent = never. */
    const val NUDGE_LAST_SHOWN_KEY = "update_nudge_last_shown_ms"

    /** The version the user dismissed a nudge for. Never nudged for again. */
    const val NUDGE_SNOOZED_VERSION_KEY = "update_nudge_snoozed_version"

    /** Epoch ms this install was first seen. Anchors the post-install grace. */
    const val FIRST_SEEN_KEY = "app_first_seen_ms"

    /**
     * How long after a fresh install to say nothing about updating.
     *
     * Someone who installed the app an hour ago does not need to be told to
     * update it, and on iOS they are frequently mid-phased-release: the store
     * gave them what it had, which can be a build below `recommendedVersion`
     * through no choice of theirs. Nudging there is asking them to fix
     * something they cannot.
     *
     * The BLOCKING gate has no such grace. A build the backend refuses to serve
     * is refused however recently it was installed, and the user needs to be
     * told why rather than watching requests fail.
     */
    const val POST_INSTALL_GRACE_MS = 3L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    private val _verdict = MutableStateFlow<UpdateVerdict>(UpdateVerdict.Ok)

    /**
     * The verdict in force. Collected once, at the app root.
     *
     * Starts [UpdateVerdict.Ok] and stays there until something resolves
     * otherwise, so nothing is ever blocked on a cold launch before the first
     * answer arrives.
     */
    val verdict: StateFlow<UpdateVerdict> = _verdict.asStateFlow()

    /** The policy last adopted, for anything that wants the raw copy strings. */
    var policy: ReleasePolicy = ReleasePolicyDefaults.POLICY
        private set

    /**
     * Adopt a freshly fetched document, cache it, and re-resolve.
     *
     * The cache write happens before the evaluation so a payload that somehow
     * makes evaluation throw is still available to the next launch, where it
     * can be reasoned about rather than lost.
     */
    suspend fun adopt(fetched: ReleasePolicy) {
        policy = fetched
        runCatching {
            Platform.storageManager.saveString(POLICY_CACHE_KEY, json.encodeToString(ReleasePolicy.serializer(), fetched))
        }
        reevaluate()
    }

    /** Adopt the last cached document, for launches that run before any fetch. */
    suspend fun loadFromCache() {
        val raw = runCatching { Platform.storageManager.loadString(POLICY_CACHE_KEY) }.getOrNull() ?: return
        val decoded = runCatching { json.decodeFromString(ReleasePolicy.serializer(), raw) }.getOrNull() ?: return
        policy = decoded
        reevaluate()
    }

    /**
     * The server refused this build outright (HTTP 426).
     *
     * Called from the Ktor guard in `NetworkModule`, off ANY endpoint. Wins
     * over whatever the cached document says, because the server's refusal is
     * the fact and the document is only a description of it.
     *
     * The links come from the 426 body when it carries them, so a client that
     * has never successfully fetched the policy still gets a working Update
     * button. They fall back to the cached document, and if BOTH are empty the
     * block is DROPPED — see [blockedOrOk].
     */
    fun onUpgradeRequired(rejection: UpgradeRejection) {
        val release = releaseForThisPlatform()
        val store = StoreLink(
            deepLink = rejection.storeUrl ?: release.storeUrl,
            web = rejection.storeUrlWeb ?: release.storeUrlWeb,
        )
        // The 426 carries its own copy, and it is preferred over the cached
        // document's: a client blocked by a server it has never successfully
        // fetched a policy from would otherwise draw compiled fallbacks, and
        // the whole point of putting the words in the rejection is that the
        // screen is correct without a second call. Falls back per-field, so a
        // partial body still contributes what it has.
        val cached = policy.blockedCopy()
        _verdict.value = blockedOrOk(
            store = store,
            minimumVersion = rejection.minimumVersion ?: release.minimumVersion,
            reason = BlockReason.SERVER,
            copy = UpdateCopy(
                title = rejection.title ?: cached.title,
                message = rejection.message ?: cached.message,
                cta = rejection.cta ?: cached.cta,
            ),
        )
    }

    /**
     * The useful parts of a 426 body.
     *
     * A struct rather than five nullable parameters because the call site is a
     * Ktor plugin parsing untrusted JSON, and a positional call of five
     * same-typed nullables is one transposition away from showing the user a
     * store URL where the title should be. Every field is null when absent,
     * blank, or the wrong JSON type — never an empty string, so the fallbacks
     * downstream are a single `?:` each.
     */
    data class UpgradeRejection(
        val storeUrl: String? = null,
        val storeUrlWeb: String? = null,
        val minimumVersion: String? = null,
        val title: String? = null,
        val message: String? = null,
        val cta: String? = null,
    )

    /** Re-resolve from the current document and local nudge state. */
    suspend fun reevaluate() {
        // A 426 is a fact about this session that no document supersedes. Once
        // blocked by the server, only a new launch clears it.
        if ((_verdict.value as? UpdateVerdict.Blocked)?.reason == BlockReason.SERVER) return

        val installed = Platform.appVersion()
        val release = releaseForThisPlatform()
        val store = StoreLink(release.storeUrl, release.storeUrlWeb)

        if (shouldBlock(policy, release, installed)) {
            _verdict.value = blockedOrOk(
                store, release.minimumVersion, BlockReason.POLICY, policy.blockedCopy(),
            )
            return
        }

        val now = Clock.System.now().toEpochMilliseconds()
        val state = loadNudgeState(now)
        _verdict.value =
            if (shouldNudge(release, installed, state, now)) {
                UpdateVerdict.Nudge(store, release.recommendedVersion, policy.nudgeCopy())
            } else {
                UpdateVerdict.Ok
            }
    }

    /**
     * A block is only issued when the user can actually act on it.
     *
     * ## Why an empty store link cancels the block
     * The blocking screen's entire content is one button. Showing it with a
     * button that goes nowhere produces the precise outcome the whole design
     * exists to prevent: an app that will not open and offers no way out. A
     * missing store URL is a configuration mistake, and the correct response to
     * a configuration mistake is to keep serving the user, not to strand them.
     *
     * This is the last of three independent guards against a lockout — the
     * backend's `assertSafe`, the phased-release check in [shouldBlock], and
     * this. They are deliberately redundant: each covers a different way the
     * configuration can be wrong, and none of them can be verified in
     * production without shipping the failure they prevent.
     */
    private fun blockedOrOk(
        store: StoreLink,
        minimumVersion: String,
        reason: BlockReason,
        copy: UpdateCopy,
    ): UpdateVerdict =
        if (store.isEmpty) UpdateVerdict.Ok
        else UpdateVerdict.Blocked(store, minimumVersion, reason, copy)

    /**
     * Should this build be blocked by the DOCUMENT? (A 426 is decided elsewhere.)
     *
     * Pure and internal so the matrix can be tested — none of these branches can
     * be produced against a correct backend, which is exactly why they need to
     * be asserted somewhere.
     */
    internal fun shouldBlock(policy: ReleasePolicy, release: PlatformRelease, installed: String): Boolean {
        if (!policy.gateEnabled) return false
        if (release.minimumVersion.isBlank()) return false

        // The phased-release trap, re-checked on the client.
        //
        // The backend asserts this at boot, so a document that fails it should
        // never reach a device. It is re-checked here because the consequence
        // is asymmetric: if the assertion is ever bypassed — a hand-edited
        // deploy, a rollback to an older server, a cached document from one —
        // the failure is every user locked out at once, and a client that
        // trusted the document has no way back. A floor above what anyone can
        // install is not a policy, it is a mistake, and it is cheap to refuse.
        if (release.latestVersion.isNotBlank() &&
            isVersionBelow(release.latestVersion, release.minimumVersion)
        ) return false

        return isVersionBelow(installed, release.minimumVersion)
    }

    /**
     * Should a nudge be shown right now?
     *
     * Four independent reasons not to, and they compose: the user must be below
     * the recommendation, past the post-install grace, outside the interval
     * since the last nudge, and not already have dismissed this exact version.
     */
    internal fun shouldNudge(
        release: PlatformRelease,
        installed: String,
        state: NudgeState,
        now: Long,
    ): Boolean {
        if (release.recommendedVersion.isBlank()) return false
        if (!isVersionBelow(installed, release.recommendedVersion)) return false

        // Never nudge someone toward a build that does not exist yet.
        if (release.latestVersion.isNotBlank() &&
            isVersionBelow(release.latestVersion, release.recommendedVersion)
        ) return false

        // Freshly installed. See POST_INSTALL_GRACE_MS.
        if (now - state.firstSeenAtMs < POST_INSTALL_GRACE_MS) return false

        // Already said no to this one. A dismissal is an answer about a
        // VERSION, not a timer: re-asking about the same build once the
        // interval lapses is how a nudge becomes nagging. A newer
        // recommendation is a new question and does get asked.
        if (state.snoozedVersion.isNotBlank() &&
            !isVersionBelow(state.snoozedVersion, release.recommendedVersion)
        ) return false

        // Rate limit. `nudgeIntervalDays` is clamped rather than trusted: a
        // backend typo of 0 would nudge on every single evaluation.
        val intervalDays = release.nudgeIntervalDays.coerceIn(1, 365)
        val intervalMs = intervalDays.toLong() * 24 * 60 * 60 * 1000
        if (state.lastShownAtMs > 0 && now - state.lastShownAtMs < intervalMs) return false

        return true
    }

    /**
     * The user dismissed a nudge. Records the version so it is never re-asked,
     * and stamps the interval clock.
     */
    suspend fun snoozeNudge() {
        // Only a NUDGE can be snoozed, and the guard is not defensive padding.
        // A 426 can land while the dialog is on screen — it is a background
        // request on any endpoint — which flips the verdict to Blocked with the
        // dialog still composed. The user's tap then arrives against a verdict
        // it was never about, and an unconditional `= Ok` here would clear a
        // server-issued block with a "Not now" the user aimed at something else.
        val nudge = _verdict.value as? UpdateVerdict.Nudge ?: return

        val now = Clock.System.now().toEpochMilliseconds()
        runCatching {
            Platform.storageManager.saveString(NUDGE_LAST_SHOWN_KEY, now.toString())
            if (nudge.toVersion.isNotBlank()) {
                Platform.storageManager.saveString(NUDGE_SNOOZED_VERSION_KEY, nudge.toVersion)
            }
        }
        // Re-read rather than assigning blind: the same race can resolve between
        // the guard above and here, and the write is what mattered.
        if (_verdict.value is UpdateVerdict.Nudge) _verdict.value = UpdateVerdict.Ok
    }

    /**
     * The user tapped Update. Treated as a dismissal for rate-limiting.
     *
     * Deliberately the same as a snooze rather than "clear everything": the app
     * cannot observe whether the App Store visit ended in an install. Assuming
     * it did and re-nudging on the next launch when it did not is the one
     * outcome that reads as broken, so the safe reading is that this question
     * has been asked and answered for now. If they did not update and the
     * recommendation later moves on, the newer version asks again.
     */
    suspend fun acknowledgeNudge() = snoozeNudge()

    internal data class NudgeState(
        val lastShownAtMs: Long,
        val snoozedVersion: String,
        val firstSeenAtMs: Long,
    )

    /**
     * Reads local nudge state, stamping the install time on first call.
     *
     * The stamp is written here rather than at app startup so nothing has to
     * remember to call it, and it is written on READ because the first read is
     * necessarily at or after the first launch. An install that predates this
     * code stamps on its next launch and gets one grace period it did not
     * strictly need — which is three quiet days, not a defect.
     */
    private suspend fun loadNudgeState(now: Long): NudgeState {
        val store = Platform.storageManager
        val firstSeen = runCatching { store.loadString(FIRST_SEEN_KEY)?.toLongOrNull() }.getOrNull()
        if (firstSeen == null) {
            runCatching { store.saveString(FIRST_SEEN_KEY, now.toString()) }
        }
        return NudgeState(
            lastShownAtMs = runCatching { store.loadString(NUDGE_LAST_SHOWN_KEY)?.toLongOrNull() }.getOrNull() ?: 0L,
            snoozedVersion = runCatching { store.loadString(NUDGE_SNOOZED_VERSION_KEY) }.getOrNull().orEmpty(),
            firstSeenAtMs = firstSeen ?: now,
        )
    }

    /**
     * This device's half of the document.
     *
     * An unrecognised platform gets an empty [PlatformRelease], whose blank
     * floors block and nudge nothing — the same "unknown resolves to Ok"
     * posture the server takes with an unreadable client header.
     */
    private fun releaseForThisPlatform(): PlatformRelease =
        when (Platform.getPlatformName().lowercase()) {
            "ios" -> policy.ios
            "android" -> policy.android
            else -> PlatformRelease()
        }
}

/**
 * Is [installed] older than [minimum]?
 *
 * Dotted-segment comparison with a lenient parse: non-numeric segments are
 * dropped and missing segments read as 0, so "1.2" and "1.2.0" compare equal
 * and Android's "-staging" suffix does not read as an older build.
 *
 * ## This must not drift from the backend
 * `compareVersions` in `stationly-backend/src/services/appReleaseService.ts` is
 * the same algorithm, and the two evaluate the same document — the server on
 * the request path, the client offline off its cache. A disagreement between
 * them shows up as a gate that appears and disappears depending on
 * connectivity, which is close to undiagnosable from the outside. Both sides
 * carry tests for the cases that separate a correct implementation from a
 * plausible one: numeric-per-segment ordering (1.10 is newer than 1.9), missing
 * segments, and non-numeric suffixes.
 *
 * Permissive on purpose in one direction only: a malformed value from the
 * backend must never lock users behind an update they cannot satisfy.
 */
fun isVersionBelow(installed: String, minimum: String): Boolean {
    fun parse(v: String) = v.trim().split(".").mapNotNull { it.toIntOrNull() }
    val ins = parse(installed)
    val min = parse(minimum)
    for (i in 0 until maxOf(ins.size, min.size)) {
        val a = ins.getOrElse(i) { 0 }
        val b = min.getOrElse(i) { 0 }
        if (a < b) return true
        if (a > b) return false
    }
    return false
}
