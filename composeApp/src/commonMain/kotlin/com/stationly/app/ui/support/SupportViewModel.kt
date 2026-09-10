package com.stationly.app.ui.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.core.config.BoardPolicyStore
import com.stationly.core.model.sdui.SupportMoneyView
import com.stationly.core.service.NetworkModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * What every support surface reads, and the only place that decides anything.
 *
 * The banner, the profile card, the badge and the thank-you are four views of
 * one small state: what the server says the card should say, what this device
 * remembers about being asked, and whether either of the two moments — a board
 * just added, a checkout just returned — is currently live.
 *
 * Held at the app root ([com.stationly.app.App]) rather than per screen. The
 * thank-you has to survive the user tapping through to Profile mid-celebration,
 * and the banner's "already asked in this session" must not reset because the
 * home screen recomposed.
 */
data class SupportUiState(
    val config: SupportMoneyConfig = SupportMoneyConfigDefaults.FALLBACK,
    /**
     * The whole home-config string map.
     *
     * Carried alongside the parsed card because the two halves of this feature
     * are served differently: the card is one JSON string, and the banner plus
     * the handful of labels the card payload has no field for are flat
     * `home.promo.support_money.*` / `support_money.*` keys. Every surface reads
     * its copy from here with a compiled-in fallback, so any of it can be
     * rewritten with a redeploy.
     */
    val strings: Map<String, String> = emptyMap(),
    val local: SupportState = SupportState(),
    /**
     * What the SERVER says about this account's contributions, or null before
     * the first successful fetch.
     *
     * The authority on [isSupporter]. Null is not "not a supporter" — it is "we
     * have not asked yet" — which is why [isSupporter] falls back to the
     * device's optimistic window rather than to false while it is null.
     */
    val server: SupportMoneyView? = null,
    /** The banner is on screen right now. */
    val bannerVisible: Boolean = false,
    /** The tier sheet is open. */
    val sheetVisible: Boolean = false,
    /** A thank-you is playing; the amount it is celebrating (0 = no figure). */
    val thanksAmountMinor: Int? = null,
) {
    /** Show the Supporter mark? See [isSupporterAt] for the rule and why. */
    val isSupporter: Boolean get() = isSupporterAt(nowMs())

    /**
     * [isSupporter] against a caller-supplied clock.
     *
     * Exists so the rule is one function rather than a property the tests have
     * to re-implement. A test that spells out its own copy of "server says yes,
     * or the device paid recently" is pinning a rule the app might not be
     * running, which is the one thing a regression test must never do.
     */
    fun isSupporterAt(nowMs: Long): Boolean =
        server?.isActiveSupporter == true || local.contributedOptimistically(nowMs)

    /**
     * Has the server actually answered for this account yet?
     *
     * `server == null` means "not asked", NOT "not a supporter", and the
     * difference decides whether it is safe to interrupt somebody. See
     * [SupportViewModel.evaluateBanner].
     */
    val serverAnswered: Boolean get() = server != null

    /**
     * How many times this person has contributed.
     *
     * The server's count when we have it, because it spans every device; this
     * device's own until then. Powers copy ("you've done this 3 times") and
     * nothing else — there is no history, here or anywhere.
     */
    val contributionCount: Int get() = server?.count ?: local.contributionCount

    /**
     * Show the support card on Profile at all?
     *
     * Reads the clock ONCE and reuses it. Each of these is read during
     * composition, and three independent `Clock.System.now()` calls in one pass
     * can straddle the optimistic window's expiry and disagree with each other
     * about whether the same person is a supporter.
     */
    val showProfileCard: Boolean
        get() = nowMs().let { now -> config.isPayable || isSupporterAt(now) }

    // ── The contextual banner's server knobs ─────────────────────────────
    //
    // Served as flat `home.promo.support_money.*` keys and, until this was
    // wired, ignored by the client: the operator could switch the banner off,
    // or move its thresholds, and nothing on the device changed. A knob that
    // does nothing is worse than no knob, because it is trusted.
    //
    // Separate from the card's own `enabled` on purpose — that governs whether
    // support exists at all (the profile card included); this governs only the
    // unprompted ask, so the permanent surface can stay while the interruption
    // is switched off.

    /** May the banner be raised at all? Defaults to the card's own switch. */
    val bannerEnabled: Boolean
        get() = strings["home.promo.support_money.show"]?.equals("true", ignoreCase = true) ?: config.enabled

    /** Boards the user must have before the banner is allowed. */
    val minBoards: Int get() = strings["home.promo.support_money.min_boards"]?.toIntOrNull() ?: 1

    /** Distinct days the app must have been opened before the banner is allowed. */
    val minDays: Int get() = strings["home.promo.support_money.min_days"]?.toIntOrNull() ?: 1
}

internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Ordinary screen entries refetch at most this often. An account CHANGE ignores it. */
private val fetchMinIntervalMs: Long
    get() = BoardPolicyStore.current.supportFetchIntervalMs

/** How long to wait for the auth bridge to publish a uid before deciding nobody is signed in. */
private const val UID_WAIT_TIMEOUT_MS = 3_000L
private const val UID_POLL_STEP_MS = 150L

class SupportViewModel(
    private val uidProvider: () -> String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupportUiState())
    val uiState: StateFlow<SupportUiState> = _uiState.asStateFlow()

    /**
     * A board was added but the banner has not been decided yet.
     *
     * The moment is raised by NAVIGATION and the card arrives from the NETWORK,
     * and on a cold launch the first can beat the second. Dropping the moment
     * because the config had not landed yet meant the banner never appeared on
     * the run where the user did the very thing it waits for — and the next run
     * would not raise it again. Held here instead, and re-evaluated the instant
     * the config arrives.
     *
     * Not part of [SupportUiState]: nothing renders it, and putting it there
     * would recompose every collector twice per board.
     */
    private var pendingBoardCount: Int? = null

    /** The account the state currently describes. Null until auth restores. */
    private var boundUid: String? = null

    /** When the profile was last fetched, so ordinary screen entries do not spam it. */
    private var lastFetchMs = 0L

    /** Serialises [syncAccount]; see the note inside it for what races without this. */
    private val syncMutex = Mutex()

    init {
        syncAccount()
    }

    /**
     * Make sure the state describes whoever is signed in RIGHT NOW, and that the
     * server's answer is not stale.
     *
     * ## The four ways this used to be wrong
     * All four produced the same symptom, which is why they hid behind each
     * other: a Supporter badge that appeared for people who had never given
     * anything and could not be made to go away.
     *
     * 1. **A successful "no contributions" was indistinguishable from a failed
     *    request.** The fetch read `getUserProfile(uid).supportMoney` and bailed
     *    on null. But the backend OMITS that field entirely for an account that
     *    has never contributed, so deleting the rows in Firestore returned a
     *    perfectly good response that the client threw away, leaving the old
     *    `true` in place. Once set, the badge could never be cleared by the
     *    server. That is the main one.
     * 2. **The fetch happened once, at construction, and raced auth restore.**
     *    `uidProvider()` is backed by the Swift auth bridge, which publishes the
     *    uid slightly after launch. A null read returned early and nothing ever
     *    asked again, so on a cold start the server's answer was often never
     *    fetched at all and the device's optimistic window was the only thing
     *    deciding.
     * 3. **Nothing reacted to the account changing.** This view model lives at
     *    the app root and survives sign-out, so `server` kept the previous
     *    account's answer across a sign-out, a sign-in, and a sign-in as somebody
     *    else entirely.
     * 4. **The device record was not scoped to an account** — see
     *    [SupportStore.bindAccount].
     *
     * @param force skip the debounce, for moments where something is KNOWN to
     *   have changed rather than merely suspected, such as a checkout return.
     */
    fun syncAccount(force: Boolean = false) {
        viewModelScope.launch {
            // Serialised, and this is not belt-and-braces. Home and Profile each
            // call `onHomeConfig` from their own `LaunchedEffect`, so two of
            // these run within milliseconds of each other on any navigation
            // between them. Unserialised, both suspend inside `awaitUid`, both
            // then read `boundUid`, both decide the account changed, and both
            // rebind and refetch — and the debounce cannot stop them, because it
            // is read and stamped either side of a suspension point. The exact
            // shape `UserSyncBridge.reconcileOnForeground` documents for the
            // same reason.
            syncMutex.withLock {
                val uid = resolveUid()

                if (uid != boundUid) {
                    boundUid = uid
                    // ONE emission for the whole rebind. It used to be two, and
                    // every collector recomposed twice per account change for a
                    // state nobody was ever meant to see in between.
                    //
                    // `server = null` in the SAME emission that rebinds is what
                    // stops a badge belonging to the previous account surviving
                    // a single frame into the next one.
                    val bound = SupportStore.bindAccount(uid)
                    val local = if (uid != null) {
                        // The active-day count belongs to the ACCOUNT, and this
                        // is the first moment we know which one that is. Not
                        // counted for a signed-out session: `min_days` measures
                        // whether Stationly became routine for a PERSON.
                        SupportStore.recordActiveDay(nowMs())
                    } else {
                        bound
                    }
                    _uiState.value = _uiState.value.copy(server = null, local = local)
                } else if (!force && nowMs() - lastFetchMs < fetchMinIntervalMs) {
                    return@withLock
                }

                if (uid == null) return@withLock
                // Stamped BEFORE the request, on purpose. A failing backend must
                // not turn every screen entry into another attempt; one a minute
                // is the right rate for a cosmetic badge either way.
                lastFetchMs = nowMs()

                // The distinction the old code lost, and the bug that made a
                // badge impossible to clear. A failed REQUEST leaves the previous
                // answer alone, because a timeout is not evidence about anybody's
                // contributions. A successful request carrying no `supportMoney`
                // IS evidence: the backend omits that field entirely for an
                // account with no contributions, so the empty view is the
                // server's way of saying "none", and it must be recorded as one.
                val profile = runCatching { NetworkModule.sduiApi.getUserProfile(uid) }.getOrNull()
                    ?: return@withLock
                val view = profile.supportMoney ?: SupportMoneyView()
                if (view != _uiState.value.server) {
                    _uiState.value = _uiState.value.copy(server = view)
                }
                // A board added before the answer landed is still waiting to be
                // judged. `evaluateBanner` now holds the moment until the server
                // has spoken, so this is the callback that releases it — the
                // same role `onHomeConfig` plays for the card.
                evaluateBanner()
            }
        }
    }

    /**
     * The uid, waiting for auth to restore only when it might still be coming.
     *
     * The wait exists because the Swift auth bridge publishes the uid a moment
     * after launch, and a single read at construction loses that race. It must
     * NOT be paid every time: `syncAccount` runs on every entry to Home and
     * Profile, and an unconditional poll would spend three seconds of a held
     * mutex on each one for a signed-out user, serialising every later call
     * behind it.
     *
     * So it only waits while the answer could still change: before anything has
     * ever been bound. Once an account is known, a null read means they signed
     * out, which is immediate and true.
     */
    private suspend fun resolveUid(): String? {
        currentUid()?.let { return it }
        if (boundUid != null) return null
        var waited = 0L
        while (waited < UID_WAIT_TIMEOUT_MS) {
            delay(UID_POLL_STEP_MS)
            waited += UID_POLL_STEP_MS
            currentUid()?.let { return it }
        }
        return null
    }

    private fun currentUid(): String? = uidProvider()?.takeIf { it.isNotBlank() }

    /**
     * Feed the card in from whichever screen fetched home-config first.
     *
     * Both the home screen and Profile fetch that map independently, so this is
     * called from both and has to be idempotent. Re-parsing an identical payload
     * would churn the state flow and, through it, recompose every collector — so
     * an unchanged config is dropped rather than re-applied.
     */
    fun onHomeConfig(homeConfig: Map<String, String>) {
        // Every entry to Home or Profile lands here, which makes it the cheapest
        // seam in the app for "has anything about this account changed". The
        // debounce inside holds that to one profile read a minute; an account
        // CHANGE ignores the debounce and applies immediately.
        syncAccount()
        if (homeConfig.isEmpty()) return
        val parsed = SupportMoneyConfigDefaults.parse(homeConfig)
        if (parsed == _uiState.value.config && homeConfig == _uiState.value.strings) return
        _uiState.value = _uiState.value.copy(config = parsed, strings = homeConfig)
        // A board added before the card landed is still waiting to be judged.
        evaluateBanner()
    }

    // ── The banner ────────────────────────────────────────────────────────

    /**
     * A board was just added and the user is landing on the home screen.
     *
     * @param boardCount how many boards they now have, which the server's
     *   `min_boards` threshold is measured against.
     */
    fun onBoardAdded(boardCount: Int) {
        pendingBoardCount = boardCount
        evaluateBanner()
    }

    /**
     * The single place that decides whether the banner may be raised.
     *
     * Called from both directions — a board arriving, and the config arriving —
     * because either can be the last piece. Every reason not to ask is checked
     * here, before any timer starts, so the delay in front of the banner is
     * never spent on one that was never going to show.
     *
     * Three outcomes, and the difference between the last two matters: SHOW,
     * NOT YET (the config has not arrived, so keep waiting), and NO (a settled
     * answer, so forget the moment rather than re-asking on the next config
     * emission).
     */
    private fun evaluateBanner() {
        val boardCount = pendingBoardCount ?: return
        val state = _uiState.value

        // NOT YET — two things can still be in flight, and neither is an
        // answer. No card means we cannot know whether asking is even enabled;
        // no server response means we cannot know whether this person has
        // already paid. Keep the moment in both cases; `onHomeConfig` and
        // `syncAccount` each call back when their half arrives.
        if (state.strings.isEmpty()) return
        if (!state.serverAnswered) return

        // NO — settled answers. Clear the moment so a later config emission
        // does not re-litigate a decision already made.
        pendingBoardCount = null
        if (!state.config.isPayable) return
        if (!state.bannerEnabled) return
        if (boardCount < state.minBoards) return
        if (state.local.activeDays < state.minDays) return
        // Never ask an existing supporter, and never ask before we KNOW whether
        // they are one. `server == null` is "not asked yet", not "no": raising
        // the banner on it means the one person who has already paid is the one
        // most likely to be interrupted, because a cold launch reaches the home
        // screen before the profile request comes back. Waiting costs nothing —
        // `onHomeConfig` re-evaluates the moment the answer lands.
        if (state.isSupporter) return
        if (!state.local.mayAskAgain(nowMs())) return
        if (state.bannerVisible || state.sheetVisible || state.thanksAmountMinor != null) return

        _uiState.value = state.copy(bannerVisible = true)
    }

    /**
     * "Maybe later", and now the only way the banner ever leaves.
     *
     * Quiet for [SupportStore.SKIP_DAYS] days. Note what is NOT here: no
     * counter, no escalation, no second chance later in the same session. The
     * user answered the question.
     *
     * There used to be a second, silent exit: the banner timed out after nine
     * seconds and that was deliberately NOT treated as a decline, on the
     * grounds that someone who looked away had not said no. That reasoning was
     * sound and the shape it defended was not, because in practice the timeout
     * fired on people who were still reading. The banner waits now, so every
     * exit is a real answer and every real answer is worth the full window.
     */
    fun onBannerDismissed() {
        _uiState.value = _uiState.value.copy(bannerVisible = false)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(local = SupportStore.recordSkip(nowMs()))
        }
    }

    // ── The sheet ─────────────────────────────────────────────────────────

    fun openSheet() {
        performHaptic(HapticType.TAP)
        _uiState.value = _uiState.value.copy(sheetVisible = true, bannerVisible = false)
    }

    fun closeSheet() {
        _uiState.value = _uiState.value.copy(sheetVisible = false)
    }

    /**
     * Open checkout for one tier.
     *
     * Returns whether anything opened, so the sheet can stay put and say
     * something rather than closing on a tap that did nothing. The two ways this
     * fails are both real: an operator who has not wired that tier's link yet
     * (fall back to the custom-amount link), and a signed-out user (nothing to
     * attribute the payment to, so we refuse rather than take money we cannot
     * credit).
     */
    fun payTier(tier: SupportTier?): Boolean {
        val cfg = _uiState.value.config
        val template = tier?.checkoutUrl?.takeIf { it.isNotBlank() } ?: cfg.cta.urlOneoff
        val url = checkoutUrlFor(template, uidProvider()) ?: return false
        performHaptic(HapticType.TAP)
        openCheckout(url)
        // The sheet is left OPEN behind Safari on purpose. If the user swipes
        // the payment sheet away without paying, they land back on the tiers
        // they were choosing from, not on a home screen that has forgotten what
        // they were doing.
        return true
    }

    // ── The return ────────────────────────────────────────────────────────

    /**
     * A checkout came back. Record it, then celebrate.
     *
     * Recorded FIRST, and only then shown: the write is what makes the badge
     * survive the user backgrounding the app mid-animation, and what stops the
     * same deep link — which iOS can deliver more than once — from being counted
     * as two contributions.
     */
    fun onCheckoutReturned(thanks: SupportReturn.Thanks) {
        viewModelScope.launch {
            val local = SupportStore.recordContribution(
                amountMinor = thanks.amountMinor,
                sessionId = thanks.sessionId,
                nowMs = nowMs(),
            )
            _uiState.value = _uiState.value.copy(
                local = local,
                sheetVisible = false,
                bannerVisible = false,
                thanksAmountMinor = thanks.amountMinor,
            )
            // The celebration haptics are NOT fired here. They belong to the
            // overlay, which owns the confetti clock: a buzz that lands before
            // the first piece is drawn reads as a notification, and the point of
            // the thing is that it lands WITH the burst. See
            // `SupportThanksOverlay`.
            //
            // Ask the server to confirm what the device has just assumed. It
            // will usually lose the race with its own webhook and answer "not a
            // supporter", which costs nothing — the optimistic window is
            // covering the badge either way, and the next refresh wins. What it
            // buys is the case where the webhook was ALREADY processed while the
            // browser was still redirecting, which is common enough on a fast
            // connection to be worth one request.
            syncAccount(force = true)
        }
    }

    /** The thank-you has been seen. It plays once, and never again. */
    fun onThanksDismissed() {
        _uiState.value = _uiState.value.copy(thanksAmountMinor = null)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(local = SupportStore.clearPendingThanks())
        }
    }
}

/**
 * The one [SupportViewModel], reachable from any screen.
 *
 * Provided at the app root. A `viewModel {}` call inside Profile and another
 * inside the home screen would produce two instances with two copies of the
 * local state, and the first thing to break would be the quietest: the profile
 * card would keep offering to open a sheet the home screen had already decided
 * not to show.
 *
 * Null default rather than a throwing one — a preview or a test that renders a
 * screen without the root provider should render it without support surfaces,
 * not crash.
 */
val LocalSupport = androidx.compose.runtime.compositionLocalOf<SupportViewModel?> { null }
