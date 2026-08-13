# Handover — User state sync, activity trail, board schema

> ## ⚠️ SUPERSEDED — read `SESSION_2026-08-12_BOARD_MODEL.md` first
>
> Two things in this document are **no longer true**:
>
> 1. **The board schema.** `SavedBoard` with `lines[].directions[]` was replaced
>    on 2026-08-12 by `Board` with flat `selections[]` and the config on the
>    board. `SavedBoard`/`SavedLine`/`SavedDirection` no longer exist.
> 2. **"Every setting is account state."** Reversed. Appearance is DEVICE-LOCAL
>    per account now; only boards and filters reach the backend. `preferences`,
>    `/user/sync/preferences` and `UserPreferences` are gone.
>
> What is still accurate and worth reading: **§3, the five pre-existing bugs**,
> all of which are still fixed in this tree and still uncommitted.

_Session 2026-08-10 → 11. Branch `ios-parity` (StationlyUI) + `dev_13Jul`
(stationly-backend). **iOS only — zero files changed under `android/`.**_

Companion reference: `docs/USER_STATE_AND_ACTIVITY.md` (the durable design doc).
This file is the change log and the state-of-play.

---

## 1. What shipped

| Area | Outcome |
|---|---|
| **Board schema v2** | One board per **station**, lines nested under it, directions under those. iOS-only field `boards`; Android keeps `stations`. |
| **Settings sync** | Account-scoped `preferences` blob, last-write-wins, debounced 2.5 s. |
| **Login restore** | Boards (with filters) + settings applied before the loader clears. |
| **Activity trail** | Local SQLite queue → nightly `BGProcessingTask` + stale-foreground fallback. |
| **5 bugs fixed** | 4 pre-existing, 1 mine. See §3. |

---

## 2. The board schema, and why it changed twice

### Two lists, not one
`users/{uid}` carries both:

- `stations` — **Android only.** Untouched.
- `boards` — **iOS only** (Android later). Richer, nested.

They are separate because the *clients* disagree about what a list means.
Android's `SelectionViewModel` calls `cleanupAll()` **before** saving, so the
"full list" it posts to the full-replace `/user/sync/stations` is always a single
board. On a shared account that deleted every board added on iOS, and iOS's next
reconcile removed them locally too. Widening the schema would not have helped —
the loss is in the *replace*.

> **The index-0 question:** Android was always safe reading index 0. Its login
> calls `syncUserAndGetSavedStations`, then `cleanupAll()` (wiping what core just
> restored), then sets up `stations.firstOrNull()`. The hazard was the write
> direction, never the read.

### One board per STATION
v2 first shipped **flat** — one row per (station, line, direction), a shape
inherited from the legacy array rather than chosen. Eleven rows for three
stations. Every reader had to regroup before it could do anything, the station's
name and hub were repeated on every row and could disagree, and there was
nowhere to put a fact about the station.

It is now nested, matching what the app already believes everywhere else — the
home screen draws one card per `groupingId`, a widget is configured with one
station, `StationPrefs` is keyed by grouping id.

```
SavedBoard   id = the HUB (groupingId), name, addedAt
  └ SavedLine      line, mode
      └ SavedDirection   direction, naptanId, filterMode, destinationIds,
                         viaStationIds, viaStationNames, routeResolvedAt
```

**The fetch naptan lives on the DIRECTION.** On rail every direction shares the
station's naptan, so the distinction never shows. On bus it is the whole problem:

> Smithwood Close — hub `490012211N`, route 39 **inbound** from pole
> `490008805N`, **outbound** from `490012211N`.

Hanging the naptan off the board serves inbound departures on the outbound side
of the road. Off the line, same failure one level down. Filters sit beside it for
the same reason: "only Heathrow services" is a statement about one queue of
trains.

`mode` is on the **line**, and `SavedBoard.mode` is derived — a stored copy would
drift the first time a hub gained a line of another mode.

### The union that must not be split
`UserService.effectiveStationIds` reads **both** lists and, for v2, walks
`lines[].directions[].naptanId`. A board's `id` is the hub and may not be a stop
the Syncer can fetch at all. Collecting `board.id` there would leave every bus
board permanently empty while the client's topic subscription still succeeded —
the quietest possible failure. Also **deduplicated**, which is the safe migration
direction: fewer decrements leaves a station polled slightly too long; more takes
a live station away from other users.

---

## 3. Bugs fixed

### Pre-existing — found by testing, not reading

**1. iOS cross-device sync never ran. At all.**
`UserSyncBridge.currentUid()` read the uid from the **App Group** suite while
`AuthBridge` writes every identity key to `UserDefaults.standard`. A dump of the
App Group container had no uid key. So `handle()` bailed on its first line and
every `user.sync` push reconciled nothing. Verified on device: push arrived,
traced, routed correctly, did nothing. *Fails in the safe direction, which is why
it survived — a reconcile that never runs looks like one with nothing to do.*

**2. A station could never leave the subscription registry.**
`SubscriptionService.updateCount` rebuilt `stationCounts`, `delete`d the key at
zero, and wrote with `set(…, { merge: true })` — which **deep-merges map
fields**, preserving keys absent from the payload. Measured on staging: adding a
board took the registry 99 → 100; removing it left 100 with `count: 1`, through
repeated polls. The Syncer therefore polls TfL forever for stations nobody
watches. Now a single field path with `FieldValue.delete()`.

**3. `deviceId` died on every app reinstall → ghost sessions.**
Stored in the App Group, which iOS destroys with the app. Firebase's session
lives in the Keychain and *survives*, so a reinstall returns silently signed in
under a new device id. Nine sessions for one phone (six from one debug day).
Because `loggedIn` means "≥1 session" and `/user/logout` removes only the calling
device, **logout could never release the user's stations.** Fixed by
`DeviceIdentityStore.swift` (Keychain-backed, restores into the App Group before
Kotlin reads it).

**4. `lastSeen` never advanced, so the 90-day TTL measured the wrong thing.**
The write-elision in `startSession` meant `lastSeen` was frozen at the install's
first launch — so a device in *daily use* would be pruned as abandoned at 90
days, while a ghost looked permanently fresh. Added `SESSION_REFRESH_MS` (24 h),
and opened the short-circuit in `createOrUpdateUser` that would otherwise cancel
the refresh.

**5. `/user/sync/profile` wrote arbitrary body fields.**
The controller spread `...other` into the Firestore update. A body carrying
`boards: []` would have wiped every saved board through a display-name endpoint.
`PROTECTED_PROFILE_FIELDS` guard added.

### Mine, caught before it mattered

**6. Activity uploads were not idempotent.**
I stamped each event with the server's `receivedAt`, which made every retry a
*distinct object* — so `arrayUnion` appended instead of deduping. Measured: a
repeated 2-event batch left **4** rows. Removed `receivedAt`; also removed
`count`, which `FieldValue.increment` inflated on any retry. An event is now a
pure function of what the client sent.

---

## 4. Review pass — correctness, performance, cleanliness

| Fix | Why it mattered |
|---|---|
| `ActivityUploader` held the log mutex **across the HTTP request** | Every `ActivityLog.record()` blocked for the duration of an upload — on a path whose entire promise is never making a caller wait. Now: read under lock → release → upload → re-acquire to delete. Safe because enqueue only *adds* rows and the delete is by id (idempotent). |
| Concurrent flushes | Added a `tryLock` in-flight guard — a background task firing during a foreground flush would send the same batch twice. `tryLock`, not `lock`: the second caller wants skipping, not queueing. |
| `ActivityLog` event-id **data race** | `++counter` from arbitrary threads could mint the *same* id twice — and a duplicate id is worse than none, since the server's set-union would silently swallow the second event as a replay. Replaced with 96 bits of randomness and no shared state. |
| `UserStateRepository.addedAt` **data race** | A plain map mutated from the login path and the debounced push concurrently. Now under the same mutex as the push. |
| `reconcileBoards` could delete every local board | A backend serving flat rows decodes to boards with no directions → no cloud selections → mass delete. Added `SavedBoard.isUsable` and two narrow guards. |
| `SqlStorage.clearAllData()` | Verified it names its tables explicitly, so `ActivityEventEntity` survives login/logout by construction — the events worth most are the ones *around* an auth change. |
| Settings leaked across users | The prefs stores are process-wide objects, so `cleanupAll()` wiping disk was invisible to an already-loaded repository. The next user would inherit the previous one's arrangement **and upload it to their own account.** `resetForNewSession()` at both boundaries. |
| DRY | `StationPrefs`/`HomeLayout` moved into `core`, deleting the `StoredStationPrefs` shim in `Platform.ios.kt` — a silent-drift copy (rename a field and the widget quietly reverts to defaults). `SyncSubscribedStationsUseCase` deleted; superseded and its last caller removed. |
| Dream settings | All writes funnel through one `write()` so a new setting cannot forget to sync. `applyRemote` suppresses the echo that would otherwise ping-pong between devices. |

**Known, accepted, not fixed:** `StationPrefsRepository` exposes
`StateFlow<Map<…>>`, so one station's toggle replaces the whole map and
re-emits to every observer. Compose skips unchanged children, and splitting into
per-station flows is a larger refactor than the symptom justifies.

---

## 5. Schema migrations now exist

`core/src/commonMain/sqldelight/com/stationly/db/migrations/1.sqm` → schema
**version 2**. Every earlier change was additive-with-a-default on an existing
table, which old rows survive — so the absence of migrations went unnoticed.
`Schema.create` runs **only on an empty database**, so the new
`ActivityEventEntity` would have thrown "no such table" on precisely the devices
that had used the app longest. **Every future schema change needs a new `.sqm`.**

---

## 6. State of play

**Deployed & verified**
- App built, installed, running on Nick's iPhone (`iosApp Staging`).
- All endpoints exercised against live staging with a real token: profile read,
  boards write, preferences write, LWW rejection on both, activity batch,
  idempotency, filter/via round-trip, registry add **and** remove.
- 8 core unit tests for the board grouping (Smithwood Close, multi-line hub,
  filter isolation, pre-hub rows, round trip, stable `addedAt`, usability).
- Android: `git diff --name-only -- android/` → **0**, and
  `:android:app:compileStagingDebugKotlin` clean.

**⚠️ Backend NOT redeployed.** Six fixes are local only:
`arrayUnion` idempotency · subscription-registry delete · activity purge on
account deletion · session refresh · nested board schema · protected profile
fields.

Until then the live backend serves **flat** boards. Verified the new client
handles that safely: it filters them as unusable, falls back to the 11 legacy
stations, and `reconcileBoards` bails rather than deleting. Device confirmed
intact (3 stations) after deploy.

**Data cleaned**
- Test artefacts removed from the live account (flat `boards`, `boardsUpdatedAt`,
  `preferences`, synthetic activity docs); registry back to 99.
- Ghost sessions on `PNKQJrFfBwaZ…` cut 9 → 1 (`loggedIn` stayed true, no
  subscription counts moved).
- `xCmB23WzldRii8DWmBPI6R8MBd12` still has **5 sessions** — deliberately left.
  All are inside the 90-day TTL, and age alone cannot tell a ghost from a real
  device. They will resolve once that account runs a build with the Keychain fix.

---

## 7. Next up

1. **Redeploy the backend**, then re-read the profile: 11 flat boards should
   become **3 nested** (Smithwood Close, Arsenal, King's Cross).
2. **Verify the Keychain fix** — note the device id, delete the app, reinstall,
   confirm no tenth session. Best done *after* changing one setting in the app so
   `preferences` exists server-side and the settings-restore path is proven too.
3. **End-to-end from the UI** — not yet done. Change a setting on the phone and
   watch it land; log out and back in and watch everything return. The endpoint
   layer beneath it is proven; the UI-driven path is not.
4. **Android adoption** (when ready): point it at `boards`, drop `cleanupAll()`
   from the save path, wire `ActivityLog.record` call sites, schedule
   `ActivityUploader.flush` from WorkManager. The infrastructure is already in
   `commonMain`.
