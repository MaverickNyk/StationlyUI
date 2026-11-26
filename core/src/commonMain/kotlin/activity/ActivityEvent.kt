package com.stationly.core.activity

import kotlinx.serialization.Serializable

/**
 * One thing the user did, recorded locally and uploaded later in a batch.
 *
 * ## Not a log line, and not a metric
 * This is the user's own activity trail: what they opened, what they added,
 * what they changed. It exists so the product can answer questions about how
 * the app is actually used — which surfaces earn their place, whether a widget
 * survives its first week, where a session ends — and so a support question
 * about one account has an answer.
 *
 * It is deliberately NOT the diagnostic trace (`PushTrace`), which is
 * high-volume, device-local and thrown away. Anything logged here is stored per
 * user, so the bar for adding an event is "would we act on this", not "might
 * this be interesting once".
 *
 * ## Why every event carries an [id]
 * Uploads are idempotent by construction: the server appends with a set union
 * keyed on this id, so a batch whose response was lost can be resent without
 * storing anything twice. That only works because the id makes two events
 * distinct even when their name and timestamp are identical — which two rapid
 * taps genuinely produce, and which a (name, timestamp) key would silently
 * collapse into one.
 */
@Serializable
data class ActivityEvent(
    /** Unique per event, generated at the moment it happened. See above. */
    val id: String,
    /** See [ActivityEvents] for the vocabulary. */
    val name: String,
    /**
     * DEVICE clock when the event happened, epoch millis.
     *
     * Every consumer has to read this as historical: a nightly flush means the
     * gap between this and the upload is routinely hours and can be days. The
     * server records its own arrival time separately, so a device with a badly
     * wrong clock can be spotted rather than silently believed.
     */
    val t: Long,
    /**
     * Event detail — flat scalars only, and the server drops anything else.
     *
     * Kept as strings on the wire rather than a typed payload per event,
     * because the alternative is a sealed hierarchy that both platforms and the
     * backend have to agree on before a single new event can ship. The cost is
     * that nothing here is type-checked; the mitigation is that nothing here is
     * ever read back BY the app.
     */
    val props: Map<String, String> = emptyMap(),
)

/** Body of `POST /user/activity/batch`. */
@Serializable
data class ActivityBatchRequest(
    val deviceId: String,
    val platform: String,
    val appVersion: String? = null,
    val events: List<ActivityEvent>,
)

/** What the server did with a batch — see `SduiApiService.uploadActivity`. */
enum class ActivityUploadOutcome {
    /** Stored. The caller deletes its local copy. */
    Accepted,

    /**
     * Refused as malformed. The caller ALSO deletes its local copy: the same
     * bytes will be refused again, and a batch that is retried forever blocks
     * every event queued behind it.
     */
    Rejected,

    /** Transient — offline, rate-limited, expired token, server error. Keep. */
    Retry,
}

/**
 * The event vocabulary, in one place so both platforms spell it the same way.
 *
 * Dotted `subject.verb`, past tense, because that is what makes a list of them
 * readable in the order they happened. A name is a permanent commitment: it is
 * what a stored event says forever, so renaming one splits its history in two.
 * Add a new name rather than repairing an old one.
 */
object ActivityEvents {

    // ── App lifecycle ───────────────────────────────────────────────────────
    /** Props: `cold` ("true"/"false"). */
    const val APP_OPENED = "app.opened"
    /** Props: `seconds` — how long the session in the foreground lasted. */
    const val APP_BACKGROUNDED = "app.backgrounded"

    // ── Auth + sync ─────────────────────────────────────────────────────────
    /** Props: `provider` (google | apple | email). */
    const val AUTH_LOGGED_IN = "auth.logged_in"
    const val AUTH_LOGGED_OUT = "auth.logged_out"
    const val AUTH_ACCOUNT_DELETED = "auth.account_deleted"

    /**
     * Props: `path`, `status`, `account_gone`. A session ended by the NETWORK
     * layer rather than by the person using the app.
     *
     * The tripwire for the class of bug where the app signs itself out and
     * nobody can say why. Until this existed a forced logout left no evidence at
     * all — the trail simply stopped, indistinguishable from a user who put
     * their phone down — and diagnosing one meant reasoning about which of
     * several equally plausible paths had fired. The three props name the
     * request, its status, and whether the SERVER said the account was gone, so
     * the answer is a row rather than a hypothesis.
     *
     * `account_gone = false` here should not happen: [NetworkModule.forceLogout]
     * is only called on a labelled 401. If it ever appears, something learned to
     * end sessions without the server's verdict, and that is the bug.
     */
    const val AUTH_FORCED_LOGOUT = "auth.forced_logout"

    /**
     * Props: `path`, `retried`. A 401 that was NOT allowed to end the session.
     *
     * The counterpart to [AUTH_FORCED_LOGOUT], and it earns its place by being
     * the only positive evidence that the guard is working. A regression that
     * restored the old sign-out-on-any-401 behaviour would show up as these rows
     * disappearing and forced logouts appearing in their place — a shape that is
     * visible in the data, where "the app stopped logging people out" on its own
     * is not.
     */
    const val AUTH_401_SURVIVED = "auth.401_survived"

    /**
     * Props: `path`, `min`, `installed`. The backend refused this build (426).
     *
     * The one row that explains an app showing a blocking update screen. It
     * matters because the alternative account of that screen — a mis-set floor
     * in the served policy — looks identical from the outside, and the two have
     * opposite fixes: a real 426 means the deployed gate is doing its job, while
     * a block with no row behind it means the client blocked ITSELF off a
     * document, and the floor is what needs correcting.
     *
     * `installed` is recorded alongside `min` because "which build was refused"
     * is the first question in any support conversation that starts with "the
     * app tells me to update and the App Store has nothing".
     */
    const val APP_UPGRADE_REQUIRED = "app.upgrade_required"

    /** Props: `to` — the version nudged toward. One row per nudge SHOWN, not
     *  per evaluation, so the rate limiter's behaviour is visible in the data. */
    const val APP_UPDATE_NUDGED = "app.update_nudged"
    /** Props: `reason` — which `user.sync` push arrived. */
    const val SYNC_RECONCILED = "sync.reconciled"
    /** Props: `stage`, `reason`. The one event that exists for diagnosis. */
    const val SYNC_FAILED = "sync.failed"
    /** Props: `boards` — how many were restored. */
    const val SYNC_RESTORED = "sync.restored"

    /** Boards the account held that did not fit under the station limit; see `BoardQuota.fit`. */
    const val SYNC_OVER_LIMIT = "sync.over_limit"

    // ── Boards ──────────────────────────────────────────────────────────────
    /** Props: `station`, `line`, `mode`, `direction`. */
    const val BOARD_ADDED = "board.added"
    const val BOARD_DELETED = "board.deleted"
    /** Props: `station`, `filter` (ALL | DESTINATIONS | VIA), `count`. */
    const val BOARD_FILTERED = "board.filtered"

    // ── Settings ────────────────────────────────────────────────────────────
    /** Props: `layout` (LIST | CAROUSEL). */
    const val SETTINGS_HOME_LAYOUT = "settings.home_layout"
    /** Props: `count` — how many stations are in the new order. */
    const val SETTINGS_HOME_REORDERED = "settings.home_reordered"
    /** Props: `station`, `key`, `value`. */
    const val SETTINGS_STATION_CHANGED = "settings.station_changed"
    /** Props: `key`, `value`. */
    const val SETTINGS_DREAM_CHANGED = "settings.dream_changed"

    // ── Widget ──────────────────────────────────────────────────────────────
    /**
     * Props: `family` (systemSmall | systemMedium | …), `station`.
     *
     * On iOS these are DERIVED, not observed. WidgetKit gives no callback when
     * a widget is placed or removed — the only thing available is a snapshot of
     * the current configurations, so add and remove are inferred by diffing
     * that snapshot against the previous one on each foreground. The
     * consequence to remember when reading the data: a widget added AND removed
     * between two app opens leaves no trace at all, and both events carry the
     * timestamp of the app open that noticed, not of the user's action.
     */
    const val WIDGET_ADDED = "widget.added"
    const val WIDGET_REMOVED = "widget.removed"
    /** Props: `count` — total widgets installed, emitted when it changes. */
    const val WIDGET_COUNT = "widget.count"

    /**
     * The widget guide was opened. Props: `count`, widgets already placed.
     *
     * The pair is the whole point of recording it. Opens with `count = 0` are
     * people trying to get their first widget, and the share of them followed by
     * a [WIDGET_ADDED] is the only measure of whether the guide works. Opens
     * with a count above zero are a different question being asked, usually why
     * the board looks stale, and should not be averaged in with the first.
     */
    // ── Notifications ───────────────────────────────────────────────────────
    // What makes the served policy tunable on evidence: how many are shown,
    // opened and muted, and why the rest were not shown. `type` and `reason`
    // only, plus the line for a line alert. Never the text.
    const val NOTIFICATION_POSTED = "notification.posted"
    const val NOTIFICATION_OPENED = "notification.opened"
    const val NOTIFICATION_SUPPRESSED = "notification.suppressed"
    const val NOTIFICATION_MUTED = "notification.muted"

    const val WIDGET_GUIDE_OPENED = "widget.guide_opened"

    // ── Support ─────────────────────────────────────────────────────────────
    // The funnel from being asked to paying, one row per step, so "nobody has
    // ever contributed" can be answered with WHERE people stop: never asked,
    // asked and declined, opened the amounts and left, tapped and the store
    // refused, or paid and our server never credited it.
    // `stationly-backend/src/scripts/support_funnel.cjs` reads these names; keep
    // the two in step. Never the price paid beyond the tier, never anything a
    // store returns about the person.

    /** The banner was raised. Props: `trigger` (board_added | on_open). */
    const val SUPPORT_ASKED = "support.asked"
    /** "Maybe later" on the banner. Props: `trigger`. */
    const val SUPPORT_ASK_DISMISSED = "support.ask_dismissed"
    /** The amounts sheet opened. Props: `from` (banner | profile | whats_new), `progress` (true | false). */
    const val SUPPORT_SHEET_OPENED = "support.sheet_opened"
    /** The amounts sheet closed without a thank-you. */
    const val SUPPORT_SHEET_CLOSED = "support.sheet_closed"
    /** An amount was tapped. Props: `tier`, `started` (true | false, whether a store sheet opened). */
    const val SUPPORT_TIER_TAPPED = "support.tier_tapped"
    /**
     * What the store said. Props: `result` (purchased | pending | cancelled |
     * unavailable | failed | already_owned), `product`, `code` on failures.
     * Android only: iOS's StoreKit flow lives in Swift and is left as it is.
     */
    const val SUPPORT_PURCHASE = "support.purchase"
    /**
     * What OUR server said about a store purchase. Props: `outcome`, `product`.
     * The row that catches a payment Google took and we never credited, which
     * Google then refunds: the shape of the 2026-11-24 bug.
     */
    const val SUPPORT_VERIFIED = "support.verified"
    /** The thank-you played. Props: `tier` when known. */
    const val SUPPORT_THANKS_SHOWN = "support.thanks_shown"

    // ── What's new (Android v1 → 2.0, once) ─────────────────────────────────
    const val WHATS_NEW_SHOWN = "whats_new.shown"
    /** Props: `via` (button | back). */
    const val WHATS_NEW_DISMISSED = "whats_new.dismissed"
    const val WHATS_NEW_SUPPORT_TAPPED = "whats_new.support_tapped"
}
