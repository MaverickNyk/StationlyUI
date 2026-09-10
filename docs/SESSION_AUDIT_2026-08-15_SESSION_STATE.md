# Audit — session lifecycle, device state, and what actually crosses devices

_2026-08-15. Branch `ios-parity`. A read of every store the app writes, what
each session boundary does to it, and where the promises and the code disagree.
No behaviour changed by this document; it is an assessment._

---

## 0. Read this first

Three questions were asked: what resets at logout and what looks stale; what
happens when two different accounts use one device; and whether the backend
holds enough for a new device to come back to the same state.

The short answers:

1. **Cross-account isolation is sound.** Two accounts on one device do not
   inherit each other's anything. This is the part that was designed carefully
   and it holds up — §3.
2. **The device→account link is never broken on the server.** iOS's logout
   unregisters nothing, because the call it makes is a no-op on this platform.
   This is the most serious finding — §2.1.
3. **Cross-device restore is boards and filters only.** Arrangement, layout and
   screensaver do not travel, by an explicit and documented decision — but that
   decision is not what the product owner expected, so it is stated plainly in
   §4 rather than buried.

Nine findings. Two high, three medium, four low.

| # | Where | Class | Severity |
|---|---|---|---|
| 1 | `ProfileViewModel.signOut` / `deleteAccount` | push tokens never unregistered on iOS | **high** |
| 2 | `DevicePushCoordinator.register` | device registry keeps the signed-out uid | **high** |
| 3 | `DreamSettings` | not per-account; reset at logout, so the returning user loses it | medium |
| 4 | `DreamSettings.onChanged` | dead hook, and the doc says it syncs | medium |
| 5 | `Board.config` `@Transient` | arrangement does not cross devices | medium |
| 6 | `UserStateSync.restoreBoards` | sorts on a `config` that is always default | low |
| 7 | App Group | orphaned keys nothing reads or cleans | low |
| 8 | `IosWidgetManager.wipe` | `widget_placements` survives logout | low |
| 9 | `app_theme` | wiped at logout with the rest of the domain | low |

---

## 1. The state surface

Every store the app writes, and what each boundary does to it.

| Store | Keyed by | Logout | Delete account | Survives? |
|---|---|---|---|---|
| Firebase Keychain session | — | `Auth.signOut()` | `Auth.signOut()` | no |
| NSUserDefaults **standard** (token, identity, caches, `recent_stations`, predictions, line status, theme, `app_theme`) | — | `clearAll()` = `removePersistentDomainForName` | same | **no** |
| SQLite `UserSelectionEntity`, `PredictionEntity`, `LineStatusEntity` | — | `clearAllData()` | same | no |
| SQLite `ActivityEventEntity` | uid stamped per row | **kept** | kept | yes, deliberately |
| App Group `board_configs_v2::<uid>`, `home_layout_v2::<uid>` | **uid** | kept | `forgetAccount(uid)` removes | yes |
| App Group dream prefs (`layout`, `theme`, `clock_style`, `station_id`) | **nothing** | reset to defaults | reset to defaults | keys yes, values no |
| App Group `stationly_device_id` | — | kept | kept | yes, deliberately |
| App Group `widget_*` boards/stations | — | `clearWidgetData()` wipes + raises `widget_signed_out` | same | no |
| App Group `widget_placements`, `widget_push_token`, `widget_api_*` | — | **kept** | kept | yes — see §5.2 |
| Backend user doc (`boards`, `stations`, profile) | uid | kept | deleted | yes |
| Backend device registry | deviceId | **not updated** | not updated | yes — see §2 |

The split of what is *account* state and what is *device* state is deliberate
and documented in `UserSettings` and `USER_STATE_AND_ACTIVITY.md`. The finding
is not that the split is wrong; it is that two entries in the last column are
not what anybody intended.

---

## 2. High severity

### 2.1 iOS never unregisters its push tokens at logout

`ProfileViewModel.signOut` does this, and `deleteAccount` does the same:

```kotlin
val token = Platform.notificationManager.registerDevice()
if (token.isNotBlank()) sduiApi.unregisterFcmToken(token)
```

On iOS, `IosNotificationManager.registerDevice()` is:

```kotlin
override suspend fun registerDevice(): String = ""
```

It returns the empty string by design — FCM was removed from iOS and the device
is addressed by its APNs tokens instead, which are registered from Swift. So the
guard never passes and **the unregister call has never executed on iOS.** It is
not a bug in the guard; the shared teardown was written for Android's token
model and iOS silently opted out of it by returning nothing.

Two tokens are affected: the app's APNs token and the widget extension's
WidgetKit push token, both held in the backend device registry against
`stationly_device_id` — an id that deliberately survives logout so the device
keeps one identity.

**Impact.** After a sign-out the backend still believes this device belongs to
that account:

- Disruption and widget pushes for the departed user's stations keep being
  delivered. They are now inert on the device (the widget renders the
  signed-out board and refuses to fetch — shipped today), but they still wake
  the phone and still cost sends.
- uid-targeted `user.sync` pushes still route here. The `deleted`-push guard
  hardened today drops them, which is the only reason this is not worse.
- On a **shared or resold device**, a stranger's station disruptions keep
  arriving until somebody signs in.
- For `deleteAccount` the miss is sharper. Its own comment explains that tokens
  must be dropped *before* the account goes, because Firestore keeps
  subcollections when a parent document is removed — so an un-unregistered
  token lingers and can still resolve for uid-targeted sends. That protection
  is a no-op on iOS, which is the platform the comment was written on.

**Fix.** A Swift-side deregistration posted at logout, mirroring
`DevicePushCoordinator.register()` — it already has the deviceId, the base URL
and the API key, and the endpoint is not auth-gated. Needs one backend decision:
whether `/api/v1/device/register` gains a delete verb or `POST /user/logout`
(which already receives `deviceId`) clears the tokens server-side.

### 2.2 The device registry keeps the signed-out uid

Distinct from 2.1, and it survives fixing it.

`DevicePushCoordinator.register()` runs on every foreground and attaches a
Bearer only when one exists:

```swift
// The endpoint is not auth-gated (a signed-out device still runs widgets and
// still wants disruption pushes), so this is additive: no token simply means
// no uid, not a rejected registration.
if let idToken = …, !idToken.isEmpty {
    request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
}
```

"Additive" is the right call for a signed-out device that still shows widgets.
But it means **omitting the token cannot clear a uid the registry already
holds.** The mapping is only ever overwritten — by the next sign-in — and never
removed.

**Impact.** A device that logs out and never logs back in stays attributed to
that account indefinitely. Combined with 2.1 the device is both *addressable*
and *attributed*, which is the pair that makes a wrong push possible.

**Fix.** Needs the backend contract checked first: does `POST /user/logout`
with `deviceId` already clear the device→uid mapping, or only end the
subscription session? The client comment for that call only claims the latter
("decrements this station's subscription count and flips `loggedIn=false`"). If
it does not clear the mapping, either it should, or `register()` should send an
explicit `uid: null` when signed out rather than omitting the header.

---

## 3. Two accounts on one device — the part that works

Sequence: **A signs in → uses the app → signs out → B signs in.**

| Concern | Handled? | By what |
|---|---|---|
| B sees A's boards | ✅ | `syncUserAndGetSavedStations` wipes SQL, restores from B's cloud profile |
| B sees A's boards from the in-memory cache | ✅ | `selectionRepository.initialize()` re-seeds after the direct SQL write |
| B inherits A's arrangement | ✅ | `board_configs_v2::<uid>` — B loads `::B`, gets defaults |
| B inherits A's arrangement *from memory* | ✅ | `UserSettings.reset()` drops process-wide state; the `object` hazard is called out explicitly |
| B's first edit saves A's layout under B's id | ✅ | same — `reset()` runs before the restore |
| A's debounced push fires under B's credentials | ✅ | `UserStateRepository.reset()` cancels `boardsJob` |
| A's last edit lost by the wipe | ✅ | `flushNow()` before teardown, 3 s cap |
| A's activity filed under B | ✅ | uid stamped at `persist()` time, `activityBatch(uid)` filters uploads |
| Login window posts `boards: []` and wipes A's account everywhere | ✅ | `BoardPushGate.allowEmpty`, passed the live `selections.isEmpty()` |
| A comes back later and gets their arrangement | ✅ | `::A` rows were kept, not deleted |
| Deleted account leaves rows nothing can reach | ✅ | `forgetAccount(uid)` + `removeDurable` |

**No leakage was found in this scenario.** The per-uid namespacing plus the
in-memory drop is the right shape, and the failure modes that matter are each
addressed with a comment explaining why. The one gap is §3.1.

### 3.1 The exception — screensaver settings

`DreamSettings` keys are flat: `layout`, `theme`, `clock_style`, `station_id`.
No uid namespace. So they cannot be kept per account, and the only way to stop B
inheriting A's screensaver is to destroy it — which is what
`UserStateSync.clearDreamSettings()` does at every logout.

That is correct for isolation and wrong for the promise beside it. The same
person signing back in on the same device gets their board arrangement back and
their screensaver reset to `CLOCK_AND_BOARD` / `SYSTEM` / `DIGITAL` / no
station override. `USER_STATE_AND_ACTIVITY.md` says "sign out and back in as the
same person on the same device → the arrangement they left"; for this one store
that is not true.

**Fix.** Namespace the four keys by uid exactly as `UserSettings` does, then
delete `clearDreamSettings()` from the logout path and keep it only in
`forgetAccount`. `dream_ever_started` stays device-level — it is a fact about the
phone, not the account.

---

## 4. What actually crosses devices

The direct answer, because the expectation and the implementation differ.

**The entire user-sync API surface** is `syncProfile`, `syncStations`,
`syncBoards`, `getUserProfile`, `logOut`, `deleteAccount`, `registerFcmToken`,
`unregisterFcmToken`. There is no preferences or settings endpoint.

| Signing in on a NEW device restores | |
|---|---|
| Stations tracked | ✅ |
| Lines per station | ✅ |
| Directions | ✅ |
| Destination / via filters | ✅ |
| Station names, bus pole naptans | ✅ |
| Profile name, email, photo | ✅ |
| Expanded / collapsed | ❌ default |
| View mode (full / board-only / …) | ❌ default |
| Rows per platform | ❌ default |
| Pinned platform | ❌ default |
| Drag order | ❌ falls back to `addedAt` |
| Home layout (list vs carousel) | ❌ default |
| Screensaver settings | ❌ default |
| Theme (light/dark/system) | ❌ default |

The mechanism is `@Transient` on `Board.config` and `Board.widget`, so they are
excluded from the wire payload as well as from the local board list. The
reasoning is recorded in `Board.kt` and `UserSettings`, and it is a real
argument: appearance is the highest-frequency, lowest-value state in the app,
and syncing it puts a Firestore write behind every detent of a slider, on the
document every login reads.

**This is a deliberate trade, not a defect.** It is listed here because it was
described to me as "we store everything on the backend so a new device comes
back to the same state", and that is not what the code does.

If the promise is wanted, the cost is bounded and the shape already exists: a
single `preferences` blob on the user document, pushed through the same
`BoardPushGate` debounce that boards use (2.5 s, coalesced, gated on an actual
change) rather than per-touch. That was the thing the original decision was
right to refuse; it is not what a debounced blob would cost. `UserSettings`
already has the serialiser, `applyRemote` already exists on `DreamSettings` to
suppress echo, and `restoreBoards` is already the place it would be applied.

---

## 5. Low severity — staleness and hygiene

### 5.1 `restoreBoards` sorts on a `config` that is always default

```kotlin
boards.sortedWith(compareBy({ it.config.sortKey }, { it.addedAt }))
```

`config` is `@Transient`, so every board deserialised from the profile carries
`BoardConfig()` and therefore `position = UNPOSITIONED` and
`sortKey = Int.MAX_VALUE`. The first comparator is constant across the list and
the sort is entirely by `addedAt`. The comment above it says "`config.position`
overrides this wherever they have actually dragged something", which cannot
happen on this path.

Harmless in effect — the home screen re-sorts from local `UserSettings`, which
does have the positions — but the code states something untrue about itself, and
the same expression appears in `buildBoards()` where it *is* live.

### 5.2 Orphaned App Group keys

Pulled from the test device today:

- `widget_boards_v1::<uid>` and `widget_boards_v1::anon` — written by
  `rememberWidgetBoards()`, a feature deleted as "written and never read". The
  writer is gone; the data is not, and nothing cleans it.
- `widget_page__primary`, `widget_page_at__primary`, `widget_page_dir__primary`
  — `forgetStations` removes paging keys only for ids listed in
  `widget_stations`, and the legacy unconfigured-widget id `_primary` is never
  in that list, so these survive every wipe.

A few bytes each. Worth clearing because they are exactly the kind of residue
that misleads the next person debugging a session problem — the `::anon` bucket
cost real time in today's diagnosis before it turned out to be from a dead
feature.

### 5.3 `widget_placements` survives logout

`wipe()` removes the boards, the station directory and the legacy keys, but not
`widget_placements`. After a sign-out it still lists the previous user's
stations against their widget families.

Contained: `trackedBoards()` reads `widget_stations` (wiped), not placements, so
the device registration does not report them; and `HomeStateProbe` reconciles
against the authoritative count on the next foreground. But it is stale by
default rather than by accident, and it is the one widget key the sign-out
misses.

### 5.4 Theme resets at logout

`@AppStorage("app_theme")` lives in the standard domain, which `clearAll()`
removes wholesale. A user who chose dark mode is back on "system" after signing
out. Arguably device-level state that should sit in the App Group beside the
device id — the same argument already made for `stationly_device_id` and the
dream prefs.

### 5.5 Residue from the identity-wipe bug

Events recorded while `firebase_user_uid` was missing carry `uid = ''`.
`ActivityUploader` requires a signed-in uid and batches with
`activityBatch(uid, …)`, so those rows can never be uploaded; they age out via
the `MAX_QUEUED_EVENTS` trim. ~30 such rows on the test device. The source is
fixed; no action needed beyond knowing why the table has them.

---

## 6. Suggested order of work

> **§2.1 and §2.2 are DEFERRED** by decision on 2026-08-15 — to be picked up as
> their own piece of work. Recorded here rather than dropped: they are the only
> findings with a privacy dimension, and §2.2 cannot be closed from the client
> alone. Note that the user-visible exposure is narrower than the severity
> suggests — `register()` re-posts an empty station list on the next foreground,
> so the window is "signed out and never opened again".

1. ~~§2.1 + §2.2~~ — deferred, see above.
2. **§3.1** — namespace the dream keys by uid. Small, self-contained, and
   removes a stated promise the code breaks.
3. **§4** — a product decision before it is an engineering one. If the
   cross-device promise is wanted, a debounced `preferences` blob is the shape;
   if not, `USER_STATE_AND_ACTIVITY.md` should say so where someone setting
   expectations will read it.
4. **§5.1, §5.2, §5.3, §5.4** — hygiene, batchable into one pass.
