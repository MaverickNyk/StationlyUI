package com.stationly.app.ui.support

import com.stationly.core.platform.Platform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the DEVICE remembers. Not who is a supporter — the server decides that.
 *
 * ## The split, and which half moved
 * This used to be the source of truth for the badge: a contribution was written
 * here with a thirty-day `untilMs`, and every surface asked this file whether to
 * show the mark. That was a workaround for not being able to read the server's
 * answer, and it had the consequence the handoff notes recorded — a contribution
 * the server knew about did not light up the badge, and the badge did not cross
 * devices.
 *
 * The server now answers it: `supportMoney.isActiveSupporter` on the profile
 * response, computed against a window only the server knows. So what is left
 * here is only what is genuinely about THIS PHONE:
 *
 *  - **Has this person been asked, and did they say not now.** Device-local by
 *    nature: it is about an interaction, not an account. On the account it would
 *    mean asking again on every new device the moment they signed in.
 *  - **The optimistic gap.** Between the browser returning and the Stripe
 *    webhook landing there are a few seconds in which the user has paid and the
 *    server does not know yet. [SupportState.contributedOptimistically] covers
 *    exactly that gap and nothing more — see [OPTIMISTIC_WINDOW_MS].
 *  - **How many distinct days the app has been opened**, which the banner's
 *    `min_days` threshold is measured against.
 *
 * Nothing here can grant anything the user did not pay for. The worst a tampered
 * value achieves is a cosmetic badge on one phone for ten minutes, which is why
 * this is not worth defending.
 *
 * ## Stored via [Platform.storageManager], not the database
 * Key/value, like the appearance and refresh-policy settings, for a reason
 * beyond convenience: the app is pre-launch and its SQLDelight schema is
 * deliberately migration-free, so adding a table here would buy a migration
 * obligation for four numbers.
 *
 * Durable storage — this survives sign-out. Losing it on logout would forget
 * that we already asked, and the first sign-in after would ask again.
 */
@Serializable
data class SupportState(
    /**
     * Contributions made FROM THIS DEVICE, newest last, capped.
     *
     * Kept only for the optimistic window and for recognising the same checkout
     * returning twice. The account's real history lives on the server, is never
     * sent here in full, and is not rendered as a list anywhere — there is no
     * history screen, by design.
     */
    val contributions: List<SupportEntry> = emptyList(),
    /**
     * Do not raise the banner before this instant. Set when the user declines
     * ([SupportStore.SKIP_DAYS]) and when they contribute
     * ([SupportStore.QUIET_AFTER_CONTRIBUTION_DAYS]).
     */
    val quietUntilMs: Long = 0L,
    /** A contribution has returned but its thank-you has not been played yet. */
    val thanksPendingAmountMinor: Int = 0,
    /** Guards the once-only thank-you against a relaunch mid-celebration. */
    val thanksPendingId: String = "",
    /** Distinct days the app has been opened, capped. Feeds `min_days`. */
    val activeDays: Int = 0,
    /** The last day counted, as a whole UTC day number. */
    val lastActiveDay: Long = 0L,
)

@Serializable
data class SupportEntry(
    val atMs: Long = 0L,
    val amountMinor: Int = 0,
    /**
     * The Stripe checkout session this came from, used only to recognise the
     * same return arriving twice. Stored because iOS can and does deliver a
     * deep link more than once — a relaunch from the same URL, or the browser
     * bouncing back after the app was already resumed — and each delivery would
     * otherwise be counted as another coffee.
     */
    val sessionId: String = "",
)

object SupportStore {

    /**
     * How long "not now" lasts.
     *
     * Three days, from the owner's spec, and it is the number that decides
     * whether this feature is a nudge or a nag. The banner is only ever raised
     * after adding a board, so three days is not three days of silence — it is
     * at least three days AND another board, which in practice is much longer.
     */
    const val SKIP_DAYS = 3

    /**
     * How long a contribution silences the unprompted ask.
     *
     * A client-side NAG POLICY, not a badge decision, which is why it is a
     * constant here rather than a number fetched from the server. It matches the
     * badge window today; if the two ever drift, the cost is that someone is
     * asked a few days early or late, and the banner is additionally suppressed
     * outright while the server says they are an active supporter
     * (`SupportViewModel.evaluateBanner`). The badge itself is never decided
     * from this.
     */
    const val QUIET_AFTER_CONTRIBUTION_DAYS = 30

    /**
     * How long a just-paid contribution is believed WITHOUT the server agreeing.
     *
     * The browser returns and the thank-you plays before Stripe's webhook has
     * necessarily reached the backend, so for a few seconds the user has paid
     * and the server does not know. Ten minutes covers that gap with enormous
     * margin, and it bounds the damage in the case that matters: if the payment
     * never actually landed — a return URL pasted by hand, a checkout that
     * failed after redirecting — the mark disappears in ten minutes instead of
     * standing for a month on the strength of a URL.
     *
     * This is the ONLY thing on the device that can show the badge, and it can
     * only ever show it early, never longer.
     */
    internal const val OPTIMISTIC_WINDOW_MS = 600_000L

    private const val KEY = "support_money_state_v3"

    /**
     * The account this record belongs to, or null when nobody is signed in.
     *
     * ## Why the record is scoped at all
     * It is DURABLE storage, so it survives sign-out on purpose: "we already
     * asked this person and they said not now" must not be forgotten the moment
     * they sign out and back in. Unscoped, that same durability leaked the other
     * way — a contribution made on this device was visible to whoever signed in
     * next, including a brand new account, because the optimistic window reads
     * the newest row without asking whose it is.
     *
     * Scoping keeps both properties: each account keeps its own quiet window,
     * and nobody inherits anybody else's.
     *
     * ## Why this is safe here and was not for the theme
     * `AppSettings` deliberately does NOT scope by uid, because the theme is read
     * at theme-host start — before auth restores — so a scoped read found nothing
     * and reset to system on every launch. Nothing here is read that early, and
     * the honest answer for "no uid yet" is an empty record: not a supporter, and
     * do not raise a banner at somebody we cannot even name.
     */
    private var boundUid: String? = null

    /**
     * Point the store at whoever is signed in now, and return their record.
     *
     * **Binding and reading are one step, under the lock**, and they have to be.
     * Separately, a contribution landing between the two would be written under
     * the account that was bound a microsecond earlier: the deep link that
     * carries a checkout return arrives on the main thread from a platform
     * callback and does not wait for anybody's sign-in to settle. Returning the
     * state also removes the only reason a caller had to follow this with a
     * `load()` of its own.
     *
     * Idempotent, so a caller may rebind on every account check rather than
     * having to work out first whether anything changed.
     */
    suspend fun bindAccount(uid: String?): SupportState = mutex.withLock {
        boundUid = uid?.takeIf { it.isNotBlank() }
        return load()
    }

    /** The storage key for the bound account. Signed out reads and writes its own slot. */
    private fun key(): String = "$KEY:${boundUid ?: "_signed_out"}"

    /**
     * How close two returns have to be before the second is treated as the same
     * payment arriving twice.
     *
     * The session id is the precise guard, but it is only present if the
     * operator templated `{CHECKOUT_SESSION_ID}` into the Payment Link's success
     * URL — and on the first device test they had not, so one £4 payment was
     * recorded SEVEN times as the user tapped the bounce page's button. A
     * correctness rule that depends on a dashboard setting being right is not a
     * rule. Nobody contributes twice inside two minutes; a repeated deep link
     * does it in seconds.
     */
    internal const val SAME_PAYMENT_WINDOW_MS = 120_000L
    private const val DAY_MS = 86_400_000L

    /** Keep the record small; nothing reads past the most recent entry. */
    private const val MAX_ENTRIES = 20

    /**
     * The day counter stops here. It is only ever compared against a small
     * threshold, so counting past it buys nothing and an unbounded integer in a
     * record that lives forever is a slow-motion overflow.
     */
    private const val MAX_ACTIVE_DAYS = 999

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Every mutation is read-modify-write against one key, so they have to be
     * serialised.
     *
     * Not theoretical: the device trace showed ONE payment arriving as seven
     * deep links inside six seconds, each launching its own coroutine into
     * [recordContribution]. Without this, two of them can both read the
     * pre-write state and the second's save silently discards the first's — and
     * the read-side dedupe cannot help, because it read the same stale state.
     */
    private val mutex = Mutex()

    suspend fun load(): SupportState {
        val raw = runCatching { Platform.storageManager.loadDurable(key()) }.getOrNull()
            ?: return SupportState()
        return runCatching { json.decodeFromString<SupportState>(raw) }.getOrElse { SupportState() }
    }

    private suspend fun save(state: SupportState) {
        runCatching {
            Platform.storageManager.saveDurable(key(), json.encodeToString(SupportState.serializer(), state))
        }
    }

    /**
     * Record a contribution that has just come back from checkout.
     *
     * Optimistic: this runs on the deep link, before the Stripe webhook has
     * necessarily reached the backend. That is the right trade — the user has
     * just paid and is watching the screen, and making them wait on a webhook
     * round-trip to see their own thank-you would be a worse lie than showing
     * it a few seconds before the server agrees. The server write is the
     * authority; this is what makes the badge immediate and offline-proof.
     */
    suspend fun recordContribution(
        amountMinor: Int,
        sessionId: String,
        nowMs: Long,
    ): SupportState = mutex.withLock {
        val current = load()
        if (current.isSamePaymentAgain(sessionId, amountMinor, nowMs)) return current
        val entry = SupportEntry(atMs = nowMs, amountMinor = amountMinor, sessionId = sessionId)
        val next = current.copy(
            contributions = (current.contributions + entry).takeLast(MAX_ENTRIES),
            // A contribution buys silence for about as long as the badge lasts.
            // Asking someone for money nineteen days after they gave you some is
            // the single fastest way to make a thank-you read as a subscription
            // prompt.
            quietUntilMs = maxOf(current.quietUntilMs, nowMs + QUIET_AFTER_CONTRIBUTION_DAYS * DAY_MS),
            thanksPendingAmountMinor = amountMinor,
            thanksPendingId = sessionId,
        )
        save(next)
        return next
    }

    /** The user chose "maybe later". Quiet for [SKIP_DAYS], then eligible again. */
    suspend fun recordSkip(nowMs: Long): SupportState = mutex.withLock {
        val current = load()
        val next = current.copy(quietUntilMs = maxOf(current.quietUntilMs, nowMs + SKIP_DAYS * DAY_MS))
        save(next)
        return next
    }

    /** The thank-you has played. It never plays again for that contribution. */
    suspend fun clearPendingThanks(): SupportState = mutex.withLock {
        val current = load()
        val next = current.copy(thanksPendingAmountMinor = 0, thanksPendingId = "")
        save(next)
        return next
    }

    /**
     * Note that the app has been opened today, and return the updated record.
     *
     * This is what makes the server's `home.promo.support_money.min_days`
     * threshold mean anything: "three distinct days" is a measure of whether
     * Stationly has become part of someone's routine, and asking a stranger for
     * money on the day they installed it is the difference between a nudge and a
     * cold call. Counted in whole UTC days — a boundary an hour either side of
     * local midnight is not worth a timezone database here.
     */
    suspend fun recordActiveDay(nowMs: Long): SupportState = mutex.withLock {
        val current = load()
        val today = nowMs / DAY_MS
        if (current.lastActiveDay == today) return current
        val next = current.copy(
            lastActiveDay = today,
            activeDays = (current.activeDays + 1).coerceAtMost(MAX_ACTIVE_DAYS),
        )
        save(next)
        return next
    }

    /** Wipe — used by the debug menu and by a full local reset. */
    suspend fun clear() {
        save(SupportState())
    }
}

/**
 * Is this return the same payment we have already recorded?
 *
 * Two guards, and the second is the one that actually holds in production. An
 * exact session-id match is definitive when Stripe sends one. When it does not,
 * an identical amount landing within [SupportStore.SAME_PAYMENT_WINDOW_MS] of
 * the last recorded contribution is a redelivery, not a second coffee.
 */
internal fun SupportState.isSamePaymentAgain(sessionId: String, amountMinor: Int, nowMs: Long): Boolean {
    if (sessionId.isNotBlank() && contributions.any { it.sessionId == sessionId }) return true
    val last = contributions.maxByOrNull { it.atMs } ?: return false
    return last.amountMinor == amountMinor && nowMs - last.atMs < SupportStore.SAME_PAYMENT_WINDOW_MS
}

/** The most recent contribution made from this device, or null. */
val SupportState.lastContribution: SupportEntry?
    get() = contributions.maxByOrNull { it.atMs }

/** How many contributions this DEVICE has seen. The account's own count comes from the server. */
val SupportState.contributionCount: Int
    get() = contributions.size

/**
 * Did this device just take a payment the server has not confirmed yet?
 *
 * The one and only reason the device may show the Supporter mark on its own.
 * True for [SupportStore.OPTIMISTIC_WINDOW_MS] after a checkout returns, and
 * false forever after — by which time `supportMoney.isActiveSupporter` from the
 * profile response has either taken over or revealed that nothing was paid.
 *
 * Replaces an `isSupporter` that read a stored thirty-day `untilMs`. That made
 * the device an authority on a question it could not actually answer: it could
 * be talked into a month-long badge by a pasted URL, and it disagreed with the
 * server about accounts whose contribution came from another phone.
 */
fun SupportState.contributedOptimistically(nowMs: Long): Boolean {
    val last = lastContribution ?: return false
    return nowMs - last.atMs in 0 until SupportStore.OPTIMISTIC_WINDOW_MS
}

/**
 * May the banner be raised at all?
 *
 * One question, one place. Both suppression windows — the three days after a
 * decline and the badge's length after a contribution — are folded into
 * `quietUntilMs` on the way in, so this stays a single comparison rather than a
 * pile of conditions that later code has to remember to keep in step.
 */
fun SupportState.mayAskAgain(nowMs: Long): Boolean = nowMs >= quietUntilMs
