package com.stationly.core.model.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The vocabulary the backend uses to tell a device something changed.
 *
 * ## Two kinds of traffic, and only one of them belongs here
 * Stationly pushes fall into two categories that must not be confused:
 *
 *  1. **High-frequency data fanout** — the `Station_{naptan}` topic (roughly
 *     once a minute) and `LineStatus_{mode}_{line}` (roughly every ten). These
 *     carry departures and drive Android's widget directly. They are FCM
 *     topics, they are Android-only, and they must STAY Android-only: iOS meters
 *     widget reloads at ~40–70 a day, so a per-minute push would exhaust the
 *     quota before the morning was out and leave the widget throttled — looking,
 *     to a user, exactly like a broken widget. iOS gets its freshness from the
 *     adaptive schedule instead (`RefreshPolicy`), and the web client from its
 *     live stream.
 *
 *  2. **Low-frequency control signals** — "your account changed", "refetch
 *     now", "here is a new schedule". These are small, rare, and mean the same
 *     thing on every platform. **That is what this file is.**
 *
 * The distinction is the whole design. Category 1 is per-platform by necessity;
 * category 2 has no reason to be, and used to be anyway — `user_sync` was an
 * FCM-shaped concept that simply did not reach iOS, so an iPhone stayed on stale
 * account state until its next cold launch while an Android phone updated in
 * seconds.
 *
 * ## One vocabulary, several transports
 * A [PushEnvelope] is delivered by whatever a platform actually uses — FCM data
 * message on Android, APNs on iOS, the live stream on web — but the CONTENT is
 * this model, decoded by shared code. Adding a signal means adding it here
 * once, not three times with three spellings.
 *
 * Unknown [type] values decode to [PushSignal.Unknown] rather than throwing, so
 * a backend can introduce a signal before every client understands it.
 */
@Serializable
data class PushEnvelope(
    /** The signal, as the wire spells it. Use [signal] to interpret. */
    val type: String = "",
    /**
     * The account this was minted for.
     *
     * Checked against the signed-in user before acting: a device token outlives
     * a session, so a push can arrive on a phone that has since signed in as
     * somebody else. Acting on it would reconcile — or log out — the wrong
     * account. Android has always done this check; it is stated here so every
     * platform inherits the reasoning rather than rediscovering it.
     */
    val uid: String? = null,
    /** Qualifies the signal — see [UserSyncReason] for `user.sync`. */
    val reason: String? = null,
    /** Station grouping ids this concerns, where the signal is scoped. */
    val stations: List<String> = emptyList(),
    /** `boost.start`: which tier to promote to. */
    val tierId: String? = null,
    /** `boost.start`: requested duration. The CLIENT caps this at the policy's
     *  own ceiling, so a sender cannot pin a device into a dense tier. */
    val minutes: Int? = null,
    /** The policy version the backend believes is current, so a client can tell
     *  whether its cached copy is stale without fetching to find out. */
    val policyVersion: Int? = null,
    /** Send time, epoch millis. Lets a client drop a push delayed so long by
     *  store-and-forward that acting on it would be misleading. */
    @SerialName("ts") val sentAtEpochMs: Long? = null,
) {
    val signal: PushSignal get() = PushSignal.from(type)
}

/**
 * Every control signal, and which platforms care.
 *
 * Deliberately NOT an enum on the wire: [PushEnvelope.type] stays a String so
 * an unrecognised signal is inert rather than a decode failure. This is the
 * interpretation of that string.
 */
sealed interface PushSignal {

    /**
     * The user's server-side state changed — stations added or removed, profile
     * edited, or the account deleted. Every device signed into that account
     * reconciles.
     *
     * **All platforms.** This is the cross-device consistency guarantee: change
     * something on one device and the others follow within seconds instead of
     * at their next cold launch. It was Android-only when it was an FCM concept;
     * it is not one any more.
     *
     * See [UserSyncReason] for the qualifier, and note `Deleted` is the one
     * signal that logs a user out — hence the uid check on [PushEnvelope.uid].
     */
    data object UserSync : PushSignal

    /** Refetch departures now and redraw. Scoped by
     *  [PushEnvelope.stations] when it concerns particular boards — a closure,
     *  an incident. iOS today; Android has its per-minute topic already. */
    data object WidgetRefresh : PushSignal

    /** The refresh schedule changed. Refetch [RefreshPolicy] and republish,
     *  even though the TTL has not expired. This is what lets a schedule edit
     *  reach a device whose app is not running. */
    data object PolicyUpdate : PushSignal

    /** Temporarily promote to a denser refresh tier — a match, a festival, an
     *  incident. Self-expiring on the device; see `BoostSpec`. */
    data object BoostStart : PushSignal

    /** End a boost early. An optimisation only: a boost expires on its own
     *  absolute deadline, so losing this push is harmless by construction. */
    data object BoostStop : PushSignal

    /** A signal this client does not know. Carried so it can be traced, and
     *  ignored so an older client is never broken by a newer backend. */
    data class Unknown(val type: String) : PushSignal

    companion object {
        const val TYPE_USER_SYNC = "user.sync"
        const val TYPE_WIDGET_REFRESH = "widget.refresh"
        const val TYPE_POLICY_UPDATE = "policy.update"
        const val TYPE_BOOST_START = "boost.start"
        const val TYPE_BOOST_STOP = "boost.stop"

        /**
         * `user_sync` is the pre-existing Android spelling, still sent by
         * older backends and still understood here. New signals use the
         * dotted form; this alias is what lets the two coexist while
         * Android's FCM path migrates.
         */
        const val TYPE_USER_SYNC_LEGACY = "user_sync"

        fun from(type: String): PushSignal = when (type) {
            TYPE_USER_SYNC, TYPE_USER_SYNC_LEGACY -> UserSync
            TYPE_WIDGET_REFRESH -> WidgetRefresh
            TYPE_POLICY_UPDATE -> PolicyUpdate
            TYPE_BOOST_START -> BoostStart
            TYPE_BOOST_STOP -> BoostStop
            else -> Unknown(type)
        }
    }
}

/** Why a [PushSignal.UserSync] was sent. */
object UserSyncReason {
    /** The saved-stations list changed. */
    const val STATIONS = "stations"
    /** Display name or another profile field changed. */
    const val PROFILE = "profile"
    /** The account was deleted — the client force-logs-out. */
    const val DELETED = "deleted"
}
