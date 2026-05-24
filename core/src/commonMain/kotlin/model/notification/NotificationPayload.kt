package com.stationly.core.model.notification

import kotlinx.serialization.Serializable

/**
 * Wire format for any user-facing notification posted by the Stationly
 * backend (or generated locally by the client in response to a sync
 * event). One shape covers status changes, admin announcements,
 * marketing pushes, and system notices — exactly like SDUI for layouts.
 *
 * Backend sends a JSON blob with this shape in an FCM `data` field
 * named `notification_payload`. The Android client deserialises it,
 * routes by [type] to a builder, and posts the resulting Android
 * notification. Nothing about the visual/audio/grouping behaviour
 * needs an APK change — backend tweaks `priority`, `color`, etc.
 * and the next message ships the new behaviour.
 *
 * **Why this exists rather than using FCM's built-in `notification`
 * field?** FCM `notification` payloads are auto-handled by the system
 * when the app is backgrounded — bypassing our code path entirely, so
 * we can't customise channel/colour/actions/grouping/deep-linking.
 * `data` payloads always hit `onMessageReceived`, giving us full
 * control regardless of app state.
 */
@Serializable
data class NotificationPayload(
    /**
     * Routing key. Drives the channel selection, default colour, and
     * deep-link target if not explicitly set on the payload.
     *
     * Current taxonomy:
     *   - `line_status_change` — a line we're subscribed to changed status
     *   - `announcement`       — admin push (new features, blog posts, etc.)
     *   - `system`             — app updates, account events, critical info
     *   - `promo`              — marketing campaigns
     *
     * Unknown types fall through to `announcement` channel so new
     * server-side types never get silently dropped.
     */
    val type: String,

    /** Bold first line of the notification. Required, max ~50 chars to
     *  fit Android's collapsed notification ribbon. */
    val title: String,

    /** Body text. Falls back to expanded BigTextStyle if it overflows. */
    val body: String,

    /**
     * Severity bucket — drives the title colour at render time:
     *   - `"danger"`  → red    (Severe Delays / Service Closed / Suspended)
     *   - `"warning"` → amber  (Minor Delays / Part Closure)
     *   - `"success"` → green  (Good Service / recovery)
     *   - `"info"`    → brand  (announcements, marketing)
     *   - `"neutral"` / null → default text colour (system / generic)
     *
     * Drives semantics, not line identity. The line is conveyed in the
     * title text itself ("Piccadilly · …"). Backend can change the
     * mapping or add new severities by extending [SeverityColors] in
     * the dispatcher; payload values it doesn't recognise fall back
     * to neutral.
     */
    val severity: String? = null,

    /** Short context line shown ABOVE the body — Android renders this
     *  as the subText / setSubText slot. Good for "Piccadilly Line · now"
     *  context that complements but doesn't replace title. */
    val subtitle: String? = null,

    /** Expanded-view summary string. Shown by BigPicture / Inbox styles
     *  under the expanded content. */
    val summary: String? = null,

    /** Visual style of the expanded view:
     *   - `"bigText"`   (default) — multi-line body. Always safe.
     *   - `"bigPicture"` — large image fills expanded view; uses [imageUrl].
     *   - `"inbox"`     — multi-line "list" style; uses [summary] + [actions].
     *
     *  Unknown values fall through to BigText. */
    val style: String? = null,

    /**
     * Optional URL for the notification large-icon (the disc on the
     * right of the collapsed chip). Status-change notifications
     * auto-populate this with the affected line's roundel so the user
     * recognises the line at a glance before reading the title.
     * Backend served from `/icons/lines/<lineId>.png`.
     */
    val largeIconUrl: String? = null,

    /** Channel ID to post to. Optional; resolver picks a sensible
     *  default based on [type] if absent. */
    val channel: String? = null,

    /** Priority hint. Mapped to NotificationCompat.PRIORITY_*. */
    val priority: String = "high",

    /** Theme tint for the notification chip — usually the line colour
     *  for status changes, brand amber for everything else. Hex string
     *  (`#RRGGBB`); ignored if unparseable. */
    val color: String? = null,

    /** Optional remote image rendered as BigPictureStyle. Used for
     *  rich promo / announcement notifications. URL must be HTTPS. */
    val imageUrl: String? = null,

    /** Where tapping the notification lands the user. Standard
     *  `stationly://` deep-link scheme; null means open the app to
     *  whatever screen it last left. */
    val deepLink: String? = null,

    /** Optional inline action buttons (Material "Reply", "Mark read"
     *  style). Max 3 — Android collapses beyond that. */
    val actions: List<NotificationAction> = emptyList(),

    /** Notifications with the same group key are bundled by Android
     *  into a summary. Use for high-frequency types — e.g. all line
     *  status changes for the same user share one group. */
    val groupKey: String? = null,

    /** Stable id used for `NotificationManagerCompat.notify(id, ...)`.
     *  Same id → notification updates in place instead of stacking. If
     *  null, the client hashes [type] + [title] for a deterministic id. */
    val notificationId: Int? = null,

    /** Type-specific extras. The line_status_change handler reads
     *  [lineId], [lineName], [previousStatus], [newStatus] to build
     *  a richer "Good Service → Severe Delays" arrow line. */
    val lineId: String? = null,
    val lineName: String? = null,
    val previousStatus: String? = null,
    val newStatus: String? = null,
)

/** Inline action button (e.g. "View board", "Mute line"). */
@Serializable
data class NotificationAction(
    val label: String,
    val deepLink: String,
)

/**
 * Canonical channel identifiers. Backend can override per-payload
 * via [NotificationPayload.channel], but these are the defaults the
 * app pre-registers on first launch.
 */
object NotificationChannelIds {
    const val LINE_STATUS  = "stationly_line_status"
    const val ANNOUNCEMENT = "stationly_announcement"
    const val SYSTEM       = "stationly_system"
    const val PROMO        = "stationly_promo"

    /** Default channel for the given [NotificationPayload.type]. */
    fun defaultFor(type: String): String = when (type) {
        "line_status_change" -> LINE_STATUS
        "announcement"        -> ANNOUNCEMENT
        "system"              -> SYSTEM
        "promo"               -> PROMO
        else                  -> ANNOUNCEMENT
    }
}
