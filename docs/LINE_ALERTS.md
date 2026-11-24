# Notifications: served, quiet by default, shared across platforms

Covers line alerts (decided on the phone) AND notifications the backend sends.
Every rule is served as a `notify.*` key and can be changed live, with no app
release, deploy or restart.

## Why (the v1 complaint)

"A notification every day." v1's rule was compiled in: any move off or back to
Good Service alerted and "Service Closed" was severe, so every line made a loud
pair every night; plus bus roadworks and Minor Delays flapping, with no cooldown
or quiet hours. It could not be changed without a release. Live v1 users keep it
until they update. iOS shows no line alerts.

## How a line alert is decided

`core/.../notification/`: `LineAlertRules` (pure), `LineAlertCoordinator`
(memory, wording, wiring), `LineAlertPreferences` (the user's level, rollout place).

1. **A status push arrives** (`onStatusChange`). Compared with the last PUSH, not
   the status table: the widget's own refresh (every unlock) writes that too and
   used to hide changes. Alert-worthy? A re-check is scheduled
   `confirmAfterMs` later. Becoming disrupted starts the wait; moving between two
   disruptions keeps it. Uses the cached policy: a push handler may be ended at
   any moment. First sighting never alerts.
2. **The re-check** (`confirm`), a job that needs a network. Reads the status from
   the BACKEND (status pushes are sent once per change and live 90 s, so the
   stored copy can be hours wrong). No answer = no alert, tried again. Then
   cooldown, daily cap, quiet hours, same-severity.
3. **How it ends.** Recovery updates OUR alert quietly (or per `recovery`), only
   if it is still showing. Closing for the night, Off, the kill switch, a muted
   line: ours is taken down without a word. It also expires by itself.

Blocked by the user (app or channel)? Not shown, so nothing is spent or counted.
A muted line (Mute button) and a device outside `rolloutPercent` count as Off.

## Notifications the backend sends

`RemoteNotifications` (core) applies expiry, app-version range, kill switch,
stopped types, daily cap and quiet hours to everything sent; `exemptTypes` skip
the last four. In-place actions are stripped from anything sent.
Sending: `POST /admin/notifications/send`; `ttlSeconds` (default 24 h) sets both
how long FCM tries and `expiresAtMs`. Audience `line` now goes to
`LineStatus_<mode>_<line>`, the topic phones subscribe to (it used to omit the
mode and reach nobody).

## Changing a rule

`GET /admin/notifications/policy` lists every rule, its value, source and limits.

```
PUT /admin/notifications/policy
{ "set": { "notify.line.maxPerDay": "2" }, "reason": "why", "by": "who", "push": true }
```

Validated, all or nothing, kept with history (`app_config/notification_policy`),
followed by every worker. `null` removes an override. `push` tells phones to
refetch within `notify.config.jitterMs`. Order: live, then environment
(`NOTIFY_LINE_MAX_PER_DAY`), then default. Per-mode severities:
`notify.line.alertSeverities.bus`.

**Emergency:** set `notify.line.enabled` (or `notify.remote.enabled`) to `"false"`
with `"push": true`.

| Key | Default | What it does |
|---|---|---|
| `notify.line.enabled` | true | Kill switch for every line alert |
| `notify.line.rolloutPercent` | 100 | Share of devices that alert at all; a device keeps its place |
| `notify.line.defaultLevel` | severe | Level of a user who never chose one |
| `notify.line.alertSeverities` | Severe Delays,Suspended,Part Suspended | Severities that alert at "Severe only" |
| `notify.line.allSeverities` | Severe Delays,Suspended,Part Suspended,Minor Delays,Reduced Service,Part Closure | Severities that alert at "All changes" |
| `notify.line.modes` | tube,overground,elizabeth-line,dlr,tram | Modes that may alert |
| `notify.line.confirmAfterMs` | 600000 | A disruption must still be there this long after it appeared |
| `notify.line.confirmRetries` | 5 | Tries to learn the status at the re-check before giving up |
| `notify.line.cooldownMs` | 7200000 | At most one alert per line in this window |
| `notify.line.maxPerDay` | 3 | Line alerts per device per day, all lines |
| `notify.line.recovery` | silent | When a line recovers: update quietly, notify, or just clear |
| `notify.line.expireAfterMs` | 14400000 | An alert removes itself after this long (0 never) |
| `notify.line.muteMs` | 43200000 | What the Mute button on an alert does (0 hides it) |
| `notify.quiet.start` | 22:30 | Quiet hours start |
| `notify.quiet.end` | 06:30 | Quiet hours end (same as start: no quiet hours) |
| `notify.quiet.mode` | silent | During quiet hours: no sound, or nothing at all |
| `notify.quiet.zone` | device | Whose clock: the phone's, or a named zone |
| `notify.remote.enabled` | true | Kill switch for sent notifications (exempt types still arrive) |
| `notify.remote.disabledTypes` | - | Types the app drops on arrival, to stop a campaign already sent |
| `notify.remote.maxPerDay` | 3 | Sent notifications per device per day |
| `notify.remote.exemptTypes` | system | Types never capped, quietened or switched off |
| `notify.config.maxAgeMs` | 21600000 | Background refetch when the phone's copy is older than this |
| `notify.config.jitterMs` | 120000 | A refresh push is spread over this long |
| `notify.copy.alertTitle` | {line} · {severity} | Alert title |
| `notify.copy.alertBody` | {reason} | Alert body when TfL gives a reason |
| `notify.copy.alertBodyNoReason` | Tap to see your board. | Alert body otherwise |
| `notify.copy.recoveredTitle` | {line} · {severity} | Recovery title |
| `notify.copy.recoveredBody` | Easing off. Earlier: {previous}. | Recovery body, still some disruption |
| `notify.copy.recoveredBodyGood` | Back to normal. Earlier: {previous}. | Recovery body, Good Service |
| `notify.copy.muteAction` | Mute for 12 hours | Mute button; keep in step with notify.line.muteMs |
| `notify.copy.levelSevere` | Only severe delays and suspensions on your lines. Quiet overnight. | Caption under Severe |
| `notify.copy.levelAll` | Minor delays and part closures too. Quiet overnight. | Caption under All |
| `notify.copy.levelOff` | No line alerts. Your boards still show every change. | Caption under Off |

App defaults equal backend defaults (`NotificationPolicyTest`), so losing or
deploying the backend changes nothing.

## Measuring it

`notification.posted`, `.opened`, `.muted`, `.suppressed` (with the reason:
recheck, blocked, cap, quiet, type, off, expired, version) go through the existing
activity log. Type, reason and line only, never the text.

## iOS

Not built. **Do not port the push and re-check flow: iOS cannot run it reliably.**
The backend must decide and send a visible APNs push, using these same rules and
keys. Design, traps, order of work and test list: `IOS_NOTIFICATIONS.md`.

## Verified (2026-11-23/24, Pixel 10 Pro Fold, staging)

Sound is Android's `notification_alert` (buzz / beep). **Earlier runs used
`NOTIFICATION_INTERRUPTION`, which is not sound**; that also explains the "one
unexplained loud notification" reported then. 63 unit tests (core), 8 backend,
plus the cases below on a phone. A true positive is made by serving "Good
Service" as alert-worthy, so the re-check reads a real status from the backend.

**Overnight, real data, default settings (00:27 to 07:20):** Piccadilly and
Mildmay went to Service Closed at 06:45:41. Pushes arrived, both statuses were
stored, the stale config refetched itself at 06:47:11, nothing was shown or
sounded. Outside quiet hours on the phone's clock, so it was the severity rule.
From the phone's records: the Mac slept and its logs were lost. Not Doze
(charging, screen on), and it ended before the morning reopening.

Not covered: iOS; a genuine severe disruption from start to end; the Firestore
write and listener behind live edits (stubbed, so nothing was written to the
shared staging database); signing out and in; a reboot between push and re-check.
