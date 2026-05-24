package com.stationly.mobile.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.stationly.core.model.notification.NotificationChannelIds
import com.stationly.core.model.notification.NotificationPayload
import com.stationly.mobile.MainActivity
import com.stationly.mobile.R
import java.net.URL

/**
 * Single entry point for posting any Stationly notification. Given a
 * [NotificationPayload] (whether sourced from a backend FCM message or
 * generated client-side after detecting a line-status transition),
 * builds the Android [NotificationCompat] and shows it.
 *
 * The dispatcher is intentionally dumb about WHY a notification is
 * being posted — that decision belongs to the caller. Once the
 * payload is constructed, every type goes through the same pipeline:
 *   1. Pick the channel (payload override → type default).
 *   2. Map priority/colour to NotificationCompat values.
 *   3. Build the content intent from the deep link.
 *   4. Attach BigPictureStyle if [NotificationPayload.imageUrl] is set
 *      (best-effort — network failures fall through silently).
 *   5. Attach inline action buttons.
 *   6. Choose a stable notification id (payload override → hash).
 *   7. Post via [NotificationManagerCompat].
 *
 * This means the backend can evolve the notification UX entirely
 * through payload tweaks. Want richer "view board" buttons? Add an
 * action. Want to demote promos to silent? Lower the priority. No
 * APK release needed.
 *
 * NOTE: Posting requires the user has granted POST_NOTIFICATIONS
 * (API 33+). Caller is responsible for prompting — the dispatcher
 * silently no-ops if the permission isn't granted, which is the
 * correct behaviour (we'd rather miss a notification than crash).
 */
object NotificationDispatcher {

    private const val TAG = "NotifDispatcher"

    fun dispatch(context: Context, payload: NotificationPayload) {
        // Channel: payload override wins; else type default. Ensure the
        // channel exists (cheap on re-entry).
        StationlyNotificationChannels.ensureCreated(context)
        val channelId = payload.channel ?: NotificationChannelIds.defaultFor(payload.type)

        val priority = when (payload.priority.lowercase()) {
            "max"     -> NotificationCompat.PRIORITY_MAX
            "high"    -> NotificationCompat.PRIORITY_HIGH
            "default" -> NotificationCompat.PRIORITY_DEFAULT
            "low"     -> NotificationCompat.PRIORITY_LOW
            "min"     -> NotificationCompat.PRIORITY_MIN
            else      -> NotificationCompat.PRIORITY_HIGH
        }

        val contentIntent = buildContentIntent(context, payload.deepLink)

        // Severity cue is rendered as a small coloured dot emoji
        // glyph in front of the title (e.g. "🔴 Piccadilly · Severe
        // Delays"). The user glances at the shade and reads the
        // emotional weight in a single beat:
        //   - 🔴 danger   — something's wrong, you may need to reroute
        //   - 🟠 warning  — minor issue, expect small delay
        //   - 🟢 success  — recovery / good news
        //   - 🔵 info     — announcement / general update
        //   - (no glyph)  — neutral / system
        //
        // Why a glyph rather than a ForegroundColorSpan on the title
        // text? Material You aggressively re-tints notification title
        // text to its own accent palette, squashing both `setColor()`
        // and span-based foreground colour overrides. Emoji glyph
        // colours are baked into the font and the OS *can't* recolour
        // them — making the dot the only reliable cross-OEM way to
        // carry severity into the chip's primary visual area.
        val severityGlyph = SeverityGlyphs.glyphFor(payload.severity)
        val styledTitle: CharSequence = if (severityGlyph != null) {
            "$severityGlyph  ${payload.title}"
        } else {
            payload.title
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.stationly_logo)
            .setContentTitle(styledTitle)
            .setContentText(payload.body)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        payload.subtitle?.takeIf { it.isNotBlank() }?.let { builder.setSubText(it) }

        payload.color?.let { hex ->
            runCatching { android.graphics.Color.parseColor(hex) }
                .onSuccess { builder.setColor(it).color = it }
        }

        payload.groupKey?.let { builder.setGroup(it) }

        // Large icon — the disc on the right of the chip. For
        // line_status_change notifications the payload normally carries
        // the line's roundel URL; for everything else it's blank and
        // Android falls back to the smallIcon's tint.
        payload.largeIconUrl
            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?.let { url ->
                runCatching {
                    URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                }.onSuccess { bitmap ->
                    if (bitmap != null) builder.setLargeIcon(bitmap)
                }.onFailure { Log.w(TAG, "largeIcon fetch failed for $url", it) }
            }

        // Inline actions — max 3 to match Android's collapsed view.
        payload.actions.take(3).forEach { action ->
            val actionIntent = buildContentIntent(context, action.deepLink, requestCodeSalt = action.label.hashCode())
            builder.addAction(0, action.label, actionIntent)
        }

        // Expanded-view style. Default = BigText (multi-line body) which
        // is the right behaviour for status changes / announcements.
        // BigPicture is opt-in via payload.style + imageUrl; Inbox is
        // for digest-style notifications listing multiple items.
        val resolvedStyle: NotificationCompat.Style? = when (payload.style?.lowercase()) {
            "bigpicture" -> {
                val image = payload.imageUrl
                    ?.takeIf { it.startsWith("https://", ignoreCase = true) }
                    ?.let { url ->
                        runCatching {
                            URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                        }.onFailure { Log.w(TAG, "bigPicture image fetch failed", it) }
                            .getOrNull()
                    }
                if (image != null) {
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(image)
                        .setSummaryText(payload.summary ?: payload.body)
                        // Setting bigLargeIcon to null hides the largeIcon
                        // in the expanded view (which otherwise covers the
                        // top-right of the big picture). Standard pattern.
                        .bigLargeIcon(null as android.graphics.Bitmap?)
                } else {
                    // Image unavailable — degrade gracefully to BigText.
                    NotificationCompat.BigTextStyle().bigText(payload.body)
                }
            }
            "inbox" -> {
                // For Inbox style we render `actions` labels as the
                // list rows so the payload's existing `actions` field
                // doubles as content. Fall back to summary text below.
                val style = NotificationCompat.InboxStyle()
                payload.actions.forEach { style.addLine(it.label) }
                payload.summary?.let { style.setSummaryText(it) }
                style
            }
            else -> NotificationCompat.BigTextStyle().bigText(payload.body)
                .also { s -> payload.summary?.let { s.setSummaryText(it) } }
        }
        resolvedStyle?.let { builder.setStyle(it) }

        val id = payload.notificationId ?: (payload.type + "::" + payload.title).hashCode()

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.d(TAG, "Notifications disabled — skipping post id=$id type=${payload.type}")
            return
        }

        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (sec: SecurityException) {
            // Missing POST_NOTIFICATIONS (API 33+). Silent no-op.
            Log.d(TAG, "POST_NOTIFICATIONS not granted, skipping", sec)
        }
    }

    /**
     * Severity → glyph. Centralised here so backend changes (adding a
     * new bucket, swapping emoji) only need a Dispatcher patch, never
     * a payload schema bump. Unrecognised values return null so the
     * title renders without a prefix, which is the right behaviour
     * for "system" / "neutral" notifications.
     */
    private object SeverityGlyphs {
        private val map: Map<String, String> = mapOf(
            "danger"  to "🔴", // 🔴 red circle
            "warning" to "🟠", // 🟠 orange circle
            "success" to "🟢", // 🟢 green circle
            "info"    to "🔵", // 🔵 blue circle
            // "neutral" intentionally absent → no glyph → bare title
        )

        fun glyphFor(severity: String?): String? =
            severity?.lowercase()?.let(map::get)
    }

    /**
     * Build a PendingIntent that opens MainActivity (single-task) and
     * carries the deep link as the intent's data URI. MainActivity's
     * existing `handleDeepLink` will route based on scheme/host.
     */
    private fun buildContentIntent(
        context: Context,
        deepLink: String?,
        requestCodeSalt: Int = 0,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            deepLink?.let { data = Uri.parse(it) }
        }
        val requestCode = (deepLink.orEmpty() + requestCodeSalt).hashCode()
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
