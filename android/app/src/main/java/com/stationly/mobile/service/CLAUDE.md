# Service — Agent context

Background services + Firebase plumbing. Three responsibilities:

1. **FCM message handling** — receive prediction payloads + status
   updates from the Syncer, persist them, and broadcast refresh
   intents to the UI surfaces.
2. **Notification dispatch** — turn any `NotificationPayload`
   (server-driven or client-generated) into a posted Android
   notification with the right channel / theming / deep link.
3. **FCM token registry** — push the device's FCM token to the
   backend so admin-driven UID/UIDs notifications can find this user.

## File layout

```
service/
├── FcmMessagingService.kt        FirebaseMessagingService subclass.
│                                 Routes incoming FCM by `type`:
│                                 prediction updates → SQL + broadcast,
│                                 line status updates → status-change
│                                 notification, dispatch-remote-
│                                 notification → NotificationDispatcher.
├── NotificationDispatcher.kt     Single entry point for posting ANY
│                                 Stationly notification. Channel,
│                                 priority, big-text / big-picture
│                                 style, severity glyph, large icon,
│                                 deep link, inline actions — all
│                                 derived from the payload.
├── StationlyNotificationChannels.kt  Channel definitions registered
│                                     on first notification post.
├── FcmTokenRegistrar.kt          Registers the device's FCM token
│                                 with the backend after the user
│                                 signs in. SharedPrefs-gated so we
│                                 don't re-POST identical tokens.
├── AuthLog.kt                    Tiny auth-event logger.
└── CLAUDE.md                     This file.
```

External integration points:
- `AndroidManifest.xml` declares `FcmMessagingService` with the
  `com.google.firebase.MESSAGING_EVENT` intent filter
- `StationlyApplication.onCreate` calls
  `FcmTokenRegistrar.ensureRegistered(this)` so the token is pushed
  on cold launch, and subscribes the install to the `stationly_all`
  topic
- `dream/StationlyDreamService` registers a broadcast receiver for
  `ACTION_DREAM_REFRESH`, fired by `FcmMessagingService` after every
  prediction-update payload is persisted
- `widget/DepartureWidgetProvider` is called via
  `updateWidgetContent` from `FcmMessagingService` after every
  prediction-update

## The notification pipeline

```
  Backend admin call           Client-side status change
  (NotificationService.send)   (FcmMessagingService.handleLineStatusUpdate)
            │                          │
            ▼                          ▼
  FCM data message            local NotificationPayload
   `notification_payload`              │
            │                          │
            ▼                          │
  FcmMessagingService                  │
   .dispatchRemoteNotification         │
            │                          │
            └──────────┬───────────────┘
                       ▼
              NotificationDispatcher.dispatch
                       │
                       ▼
              NotificationManagerCompat.notify(id, builder.build())
```

Every notification — admin push, status change, system event — goes
through `NotificationDispatcher.dispatch`. That means a single place
controls channel routing, severity colouring, priority mapping,
deep-link wiring, action buttons. Backend-driven UX changes don't
need APK releases.

## Severity colour cue

`NotificationDispatcher` prefixes the title with a coloured emoji
glyph based on `payload.severity`:

| `severity` | Glyph | Meaning |
|---|---|---|
| `"danger"` | 🔴 | Severe Delays / Service Closed / Suspended |
| `"warning"` | 🟠 | Minor Delays / Part Closure |
| `"success"` | 🟢 | Good Service (recovery) |
| `"info"` | 🔵 | Announcement / general update |
| `"neutral"` or null | (none) | System / generic |

**Why emoji and not `setColor()` or `ForegroundColorSpan`?** Material
You aggressively re-tints notification title text to the device
accent palette, squashing both `setColor()` and span-based foreground
colour overrides. Emoji glyph colours are baked into the system font
— the OS cannot re-paint them. Tested on Pixel 14: the only reliable
way to get colour into the title.

See `Stationly memory: notification_styling_quirks.md` for the full
investigation.

## Channels (`StationlyNotificationChannels`)

| Channel id | Label | Importance | Used for |
|---|---|---|---|
| `stationly_line_status` | Line Status | HIGH | Status changes |
| `stationly_announcement` | Announcements | DEFAULT | Admin pushes |
| `stationly_system` | System | LOW | App updates, account |
| `stationly_promo` | Promotions | LOW | Marketing |

`NotificationChannelIds.defaultFor(type)` picks the channel based on
the payload `type`. Backend can override per-payload via
`payload.channel`.

## Status-change auto-notifications

`FcmMessagingService.handleLineStatusUpdate` diffs the previous and
new `statusSeverityDescription` for the user's selected lines. Fires
a notification on **significant transitions only**:

- `wasGood`: line previously had "Good Service" → now something else
- `isGood`: line previously had a disruption → now "Good Service"
- `enteredSevere`: any other transition INTO the severe states
  (`Severe Delays`, `Service Closed`, `Part Suspended`, `Suspended`,
  `Planned Closure`)

Other transitions (e.g. Minor Delays → Reduced Service) are dropped
to avoid notification fatigue.

Severity is derived locally from the new status:
- `Good Service` → `success`
- Severe set → `danger`
- Minor Delays / Part Closure / Reduced Service → `warning`
- else → `neutral`

## FCM token registration

`FcmTokenRegistrar` is responsible for getting the device's FCM token
to the backend so admin-driven UID audiences can find this user.

Flow:
1. **`ensureRegistered(context)`** — called from
   `StationlyApplication.onCreate`. Reads SharedPrefs for the last
   registered `(uid, token)` pair. If both match the current values,
   no-op. Otherwise fetches a fresh token and posts.
2. **`registerIfAuthenticated(token)`** — called from
   `onNewToken(token)` in `FcmMessagingService`. POSTs to
   `/api/v1/user/fcm/register` with the user's Firebase ID token in
   the Authorization header.
3. Backend persists at `users/{uid}/fcm_tokens/{token}` in Firestore.
   `set(merge: true)` so re-registration is idempotent.

The SharedPrefs cache means cold launches don't hit the network if
nothing has changed — common case is "user opened the app, token is
the same as last time, skip".

## Architectural invariants (do not break)

**1. Every notification goes through `NotificationDispatcher`.**
Do not call `NotificationManagerCompat.notify` directly from
anywhere else. The dispatcher owns channel resolution, severity
glyphs, deep-link wiring, big-style attachments, action mapping.
Bypassing it means an inconsistent UX (no severity glyph, wrong
channel, missing deep link).

**2. FCM token register is opportunistic, not blocking.**
Failures (no network, backend down, user not yet authenticated)
silently no-op. The token will be re-tried on next app launch.
Don't add retry loops; the operation isn't critical-path.

**3. Status-change notifications NEVER fire on first launch.**
`handleLineStatusUpdate` checks `prevStatus == null` and returns
early. The user has to OPT IN by experiencing the line being fine
first; otherwise their first impression of the app is
"Stationly thinks your line is broken" which is a bad UX.

**4. `dispatchRemoteNotification` always parses
`notification_payload`.**
If the FCM message has a `notification_payload` field, dispatch.
Don't conflate with the prediction-update `payload` field used by
the Syncer — those are mutually exclusive routing keys.

**5. Material You title-colour overrides cannot be defeated.**
Don't reintroduce `setColor()` or `ForegroundColorSpan` for severity
colouring. The emoji glyph is the durable answer. If you want a new
colour cue, add a new glyph.

## Common gotchas

- **POST_NOTIFICATIONS permission (API 33+).** The
  `NotificationPermissionEffect` composable handles the runtime
  prompt; the dispatcher silently no-ops if the permission isn't
  granted (we'd rather miss a notification than crash).
- **`stationly_all` topic subscribe-once tracking.** Subscribing to
  the same FCM topic is idempotent on the FCM side, but we track in
  SharedPrefs anyway to avoid the network call entirely on cold
  launches.
- **`onNewToken` can fire mid-session.** When FCM rotates the token,
  the dispatch path goes through `registerIfAuthenticated` again;
  the SharedPrefs cache update happens after a successful POST so
  a failed POST won't poison the cache.

## When you change something here

After modifying any file in this folder, run `graphify update .` from
the repo root to keep the project's knowledge graph in sync. The
graph at `graphify-out/` is what future agents read for architecture
context.
