# Notifications on iOS: handover

Read `LINE_ALERTS.md` first (what the product does and every `notify.*` rule).
This is what an iOS release needs. **Nothing here is built.** Written 2026-11-24
from the code as it stands; no iOS code was changed.

## The one thing to know

**Do not port Android's design. On iOS the BACKEND should decide.**

Android: a silent data push wakes the app, the app schedules a job ten minutes
later, the job checks the backend and posts. iOS gives none of those steps:

| Android relies on | On iOS |
|---|---|
| FCM topics `LineStatus_<mode>_<line>` | No FCM at all. APNs direct (`AppDelegate`). Those topics are deliberately Android-only (`PushEnvelope`) |
| A data push always runs our code | Silent pushes are throttled, need Background App Refresh, and do not reach a force-quit app. On our device, 2026-07-30: alerts arrived, `content-available` did not (`stationStreamServer.ts`) |
| WorkManager runs ten minutes later | `BGAppRefreshTask` runs when iOS chooses. Ten minutes cannot be promised |
| The app drops what the rules forbid | A visible push is shown by iOS. A Notification Service Extension can reword it, but not suppress it without Apple's filtering entitlement |

So anything that must NOT be shown has to be stopped before it is sent.
Implementing `LineAlertPlatform` in Swift would compile, work in the simulator
with the app open, and fail silently on real phones.

## What already exists

| Piece | Where | Use on iOS |
|---|---|---|
| Served policy, live edits, history | backend `notificationPolicyService.ts` | As is. The server reads its own policy, so edits apply at once with no refresh push |
| Status change hook, all lines | `server.ts` calls `DisruptionTriggerService.observe` | The place to start from |
| Line to accounts to devices | `UserWatchIndex.uidsForLines`, `DevicePushService` | The audience, at no Firestore read for the first hop |
| Visible APNs push, collapse id, expiry | `apnsService.ts` (`pushType: 'alert'`) | The transport. The type exists; `DevicePushService` only sends `widgets` and `background` today |
| Device registration | `DevicePushCoordinator.swift`, `devicePushController.ts` | Extend with the preferences below |
| Rules, as a spec | `core/.../notification/LineAlertRules.kt` | Port to TypeScript, with its tests |
| Level setting UI, captions, storage | `HomeSettingsScreen` `LineAlertsSetting`, `LineAlertPreferences` | Remove the Android check; then sync the choice |
| Event names | `ActivityEvents` (`notification.*`) | Same names, so both platforms can be compared |

Traps in what exists:
- `DisruptionTriggerService.isHealthy` is for widget refreshes. It treats
  "Special Service" and "Planned Closure" as disruption. **Do not reuse it for
  alerts**; use the served severities.
- Its state is in memory, per process. It resets on restart, and under PM2
  cluster mode every worker has its own copy. Alerts need shared state or users
  get duplicates.

## Design to build

### Backend
1. **Detect.** In the same hook, compare with the last status. First sighting
   never alerts. Apply `LineAlertRules.onChange`.
2. **Wait.** Store a pending check per line in Firestore, due after
   `notify.line.confirmAfterMs`, and claim it with a transaction, so one worker
   sends it and a restart does not lose it. Moving between two disruptions keeps
   the wait; newly disrupted restarts it.
3. **Confirm** against the current cached status. Cleared: drop silently.
4. **Per device:** level, mute, rollout, mode, cooldown, daily cap, quiet hours
   in THAT device's zone. Alert memory (last alert, severity, open, count today)
   is per device.
5. **Send** `alert`, `apns-collapse-id: line:<id>`, `thread-id: line-status`,
   `apns-expiration` from `notify.line.expireAfterMs`.
6. **Silent** (quiet hours, recovery): `interruption-level: passive`, no `sound`.
   `drop`: do not send. Normal: `active`. Not `time-sensitive`: it breaks through
   Focus, which is the opposite of what users asked for.
7. **Recovery** reuses the collapse id, so it replaces the alert. The server
   cannot see whether the user swiped it away, and replacing would bring it
   back. **Default to not sending recovery on iOS**; clear the delivered
   notification at next app open instead.
8. **Sent notifications** (`POST /admin/notifications/send`) go through FCM and
   reach no iPhone. Add an APNs leg that applies the `notify.remote.*` rules
   (as in `RemoteNotifications`) before sending.

### What the device must send
Add to the existing registration: alert level (null = served default), muted
lines with their end times, IANA zone, rollout bucket, notification permission.
Send on change and at each foreground. Version it: an old build that sends none
of these must never get line alerts.

### App (Swift and shared)
- Register a `UNNotificationCategory` with the Mute action, label from
  `notify.copy.muteAction`. Handle it in `didReceive response`, store the mute
  and tell the backend. `muteMs` 0: register no action.
- Tap: use the payload's deep link. Scheme differs (`stationly` /
  `stationly-staging`), and it must be the stop or hub id, not a bus pole. That
  was an Android bug, so test a bus alert.
- `willPresent` returns `.banner, .sound` for everything today. Respect passive.
- Remove expired and recovered delivered notifications at foreground: iOS has no
  equivalent of Android's `timeoutAfter`.
- Permission: do not prompt at launch. If refused, show the setting with a way to
  iOS Settings.
- Config refresh: home-config is fetched at app open, which is enough for the
  setting captions. `config_refresh` is an FCM push and will not arrive; the
  rules do not need it, because they live on the server.

### Served keys to add first
- `notify.line.rolloutPercent.ios`, default **0**, so iOS ships dark and is
  raised from the backend. Today's key covers every platform.
- `notify.line.enabled.ios`, so one platform can be stopped without the other.
- `notify.line.recovery.ios`, default `off` (see 7).

Add to `NOTIFICATION_POLICY_KEYS`, `NotificationPolicy`, both pinned tests and
`CONFIG_KEYS.md` together.

`minVersionCode` / `maxVersionCode` are ANDROID numbers. iOS builds number
separately, so never compare them.

## Owner decisions
1. A user with an Android phone and an iPhone gets both alerts. Fine, or one?
2. Recovery on iOS: none (recommended) or a quiet replacement?
3. Quiet hours clock: the device's zone, as on Android?
4. Privacy text: the backend will store mutes, level and zone per device. Check
   the App Store privacy answers before release.

## Order of work
1. Port `LineAlertRules` to TypeScript. Run the same cases in both languages, so
   the rules cannot drift apart.
2. Pending check and per-device memory in Firestore, idempotent across workers.
3. The APNs `alert` path in `DevicePushService`, with staging sends to one device.
4. Registration fields, setting, Mute, deep links.
5. Sent notifications over APNs.
6. Release at `rolloutPercent.ios` 0, then 10, watching opened against muted.

## Test before release (real iPhone, not the simulator)
- Alert with the app open, in the background, force-quit, and after a reboot.
- Cleared before the check; two disruptions in a row; restart the backend
  between detecting and sending; two workers, exactly one alert.
- Nightly closure and morning reopening: nothing.
- Quiet hours by the device's zone: no sound, no screen wake. Focus and Low Power
  Mode. Background App Refresh off.
- Level Off, Mute, sign out and in, delete account, second user on one phone: no
  alert meant for the previous account.
- Permission refused, then granted. Token rotation. Sandbox and production tokens.
- Tap: tube, overground, bus. Old build: no line alerts.
- Kill switch with a check already pending: nothing is sent.
- Cap, cooldown, rollout 0.

Method lessons from Android: measure sound rather than assuming it; a fake status
push cannot cause an alert, because the check reads the real status (serve
"Good Service" as alert-worthy to get a true one); capture logs on the phone, as
a sleeping Mac loses them.

Known before you start: the shared tests do not compile for iOS
(`compileTestKotlinIosSimulatorArm64`): 47 older test names contain commas.
Rename those first. The app itself compiles.
