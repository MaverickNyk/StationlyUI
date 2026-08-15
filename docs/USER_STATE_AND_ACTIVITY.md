# User state sync & the activity trail

_Introduced 2026-08-10, reshaped 2026-08-12. Branch `ios-parity`. Spans
`StationlyUI` and `stationly-backend`. Touch points: `Board`, `UserSettings`,
`UserStateRepository`, `UserStateSync`, `UserSyncRepository.reconcileBoards`,
`ActivityLog`, `ActivityUploader`, `ActivityUploadScheduler`, `UserService`,
`DataCacheService`._

_Change log for the 2026-08-12 pass: `SESSION_2026-08-12_BOARD_MODEL.md`._

Two promises:

1. **Sign in anywhere and get your boards back** — every line, every direction,
   every filter. *Not* the arrangement: appearance is device-local, kept per
   account, and restored when the same person signs back in on the same device.
   See §2b for why that reverses an earlier decision.
2. **Know what people actually do in the app**, without paying for it per action.

---

## 1. Why there are TWO board lists

`users/{uid}` holds both:

| Field | Written by | Shape |
|---|---|---|
| `stations` | **Android only** | `SubscribedStation[]` — flat: id, name, line, mode, direction |
| `boards` | **iOS only** (Android later) | `Board[]` — one per STATION, selections and config under it |

They are separate because the CLIENTS disagree about what a list means, not
because the shape needed changing.

Android is a one-board app. `SelectionViewModel` calls `cleanupAll()` before
saving, so setting up a board wipes every other one, and it then posts the whole
(now single-element) list to `/user/sync/stations` — **a full replace**. On an
account shared with an iPhone that silently deleted every board added on iOS,
and iOS's next reconcile removed them locally too. Widening `SubscribedStation`
would not have fixed that: the loss is in the replace.

> **The Android index-0 question, answered.** Android's login path calls
> `syncUserAndGetSavedStations` (which writes every cloud station into SQLite),
> then immediately `cleanupAll()` — wiping them — and sets up
> `stations.firstOrNull()`. So Android *already* takes index 0 and ignores the
> rest. A multi-station cloud list could never have broken Android login. The
> hazard was always the write direction, not the read.

---

## 2. The board entity

```
Board            id = the HUB, name, addedAt
  ├ selections   [ naptanId, line, mode, direction, filter ]
  └ config       expanded, view, rowsPerPlatform, pin, position
```

`Board.id` is the hub (`UserSelection.groupingId`) — the same thing the home
screen draws one card per, the settings screen edits one of, and a widget is
configured with.

### The fetch naptan hangs off the SELECTION
On rail every direction shares the station's naptan so the distinction never
shows; on bus it is the whole problem — Smithwood Close is hub `490012211N`,
route 39 inbound departing from pole `490008805N` and outbound from
`490012211N`. Putting the naptan on the board serves inbound departures on the
outbound side of the road. Filters sit at the same level, because a destination
filter narrows one queue of trains.

### There is no line level
Selections are flat under the board, each carrying its own `line` and `mode`.
The previous shape nested `lines[].directions[]`, and the line level held
nothing but the line id — every reader had to walk two loops to reach the row it
wanted, and the app's own model (`UserSelection`, the prediction tables, the
topic subscriptions) is flat at exactly this level. `Board.byLine()` is one call
for anything that renders per line.

`mode`, `lines` and `naptanIds` are **derived** on `Board`. A stored copy of any
of them is a second record of one fact, and the two drift the first time a hub
gains a line of another mode.

### The configuration is ON the board
`expanded`, `view`, `rowsPerPlatform`, `pin` and `position` used to live in
`preferences.stations[hubId]` — a second structure keyed by the same id as the
board list. Two records of one entity can disagree, and did: deleting a board
left its settings behind (so every delete path had to remember a `forget()`
call), and restoring one restored a card with default arrangement until a
separate blob arrived. On the board, a board's settings are created, restored
and deleted with it, atomically.

`BoardView` replaced a `hideHero` boolean with named states — `FULL` (hero +
board) and `BOARD_ONLY`. A third, `NEXT_ONLY` (hero only), was offered and then
removed: the board is what the card is FOR, so a view that hides it was never
worth the code that supported it. That is now structural rather than a rule —
nothing left in the card can hide the board.

A stored config still naming `NEXT_ONLY` decodes to `FULL`, because
`UserSettings` reads with `coerceInputValues` and the property has a default. The
fallback shows more than was asked rather than less, which is the only safe
direction on a screen whose purpose is departures.

`position` replaced `HomePreferences.order`, a list of ids kept beside the boards
it ranked. That list had to be reconciled with reality on every read — it could
name boards that no longer exist and miss ones that do. A rank on the board is
created, moved and deleted with it. `UNPOSITIONED` (-1) sorts last, so a board
added since the last reorder appears at the bottom rather than jumping to the
top; ties fall back to `addedAt`.

### `Board.widget` is observed, not configured
Whether a board is on a home-screen widget is device-local and `@Transient` —
off the wire and out of the local store. It is true on the phone and false on
the iPad, so last-write-wins across devices would make it flap rather than
converge. See §5.

### The union that must not be split
The subscription registry (`metadata/subscribed_stations`) reads the **union**
of both lists — `UserService.effectiveStationIds`. A station absent from it is
never polled by the Syncer, so the board renders permanently empty while the
client-side topic subscription still succeeds: the device listens to a topic
nobody publishes to.

⚠️ The union must walk `selections[].naptanId`, **not** `board.id` — the board id
is the hub, which on a bus stop is not a pole the Syncer can fetch from at all.

It is also **deduplicated**, where the old loops counted per entry (three lines
at one station contributed +3). Deduplicating is the safe direction to migrate:
a session incremented under the old code and decremented under this one is
decremented *fewer* times, which merely keeps a station polled a little longer.
The reverse would take a live station away from other users.

### Convergence
- `boards` absent → `getUserProfile` derives it from `stations` at **read time**,
  never written back. An existing user's board survives their first iOS login
  without a write ever touching Android's array.
- `boardsUpdatedAt == 0` means no client has ever written a real list. In that
  state `reconcileBoards` **refuses to delete anything** when the device has
  local boards, and the client pushes instead.
- A board with no selections is `!isUsable` and is treated as **absent**, which
  is what a superseded shape decodes to. Same bail.
- Either bail is followed by a **push**, in `UserSyncBridge` and in
  `restoreBoards`. Without it the device is stuck: it keeps its boards (the bail
  protects them) but never publishes them, so the account never converges and
  the user's next device restores the old shape. One push resolves it — the
  write makes the list usable and the branch stops firing.

---

## 2b. What syncs, and what deliberately does not

The split is drawn by **consequence**, not by convenience.

| State | Where it lives | Why |
|---|---|---|
| Boards, lines, directions, filters | **Backend** (`users/{uid}.boards`) | The subscription registry is derived from them. A station missing from it is never polled, so losing a board loses departures. Changes are rare. |
| Expanded, view, rowsPerPlatform, pin, position, home layout, screensaver | **Device**, per account | Appearance. Changes on every touch, and is worth nothing to any device but this one. |

### This reverses the earlier decision, on purpose
An earlier version synced everything, so that signing in anywhere restored the
app exactly as it was left. That is a nicer promise and it costs too much. A
settings screen writes on every touch: flipping Expanded once, or dragging the
rows slider from 2 to 5, put backend writes on the wire — and the document being
written is the one **every login reads**. Appearance is the highest-frequency,
lowest-value state in the app, which makes it the worst possible thing to spend a
write quota on.

The debounce (2.5 s) hid the worst of it and did not fix it: it collapsed a
slider drag into one write, but every distinct change was still a round trip.

### The cost, stated plainly
**A brand-new device gets default appearance.** The boards come back — every
line, every direction, every filter — but arranged as the app arranges them out
of the box, not as the user had them on their old phone.

That is recoverable in seconds by the user and invisible to anyone else, which
is the opposite of a lost board. It is the trade being made.

### Device-local does NOT mean forgotten at logout
Every settings key is namespaced by uid and written through
`StorageManager.saveDurable`, which survives the logout wipe of the app's own
defaults (iOS: the App Group suite; Android: a separate prefs file). So:

- Sign out and back in as the **same person on the same device** → the
  arrangement they left.
- Sign in as **somebody else** → their own settings, never the previous user's.
- Sign in on a **new device** → defaults.
- **Delete and reinstall the app** → defaults. iOS destroys the App Group
  container with the last app in the group (see `DeviceIdentityStore`), and it
  is the App Group that holds `board_configs_v2::<uid>` and `home_layout_v2::<uid>`.

That last case is the one worth knowing about, because it does not look like any
of the others from the user's side. The Firebase session lives in the Keychain
and **outlives the app**, so a reinstall comes back silently signed in, restores
every board correctly from the cloud, and presents them arranged as though the
user had never touched them. Nobody signed out; the app simply forgot. Reinstalls
are ordinary — storage pressure, troubleshooting — so this is the common way the
arrangement is lost, not the new-phone case.

Accepted, not overlooked. See "What does NOT cross devices" below for the
decision and what it would cost to change.

Nothing kept there identifies anybody. "Three rows per platform, board first" is
not personal data, which is what makes it safe to leave behind.

#### The exception: screensaver settings are not per-account
`DreamSettings` keys (`layout`, `theme`, `clock_style`, `station_id`) are **flat
— no uid namespace**. They therefore cannot be kept per account, and the only
way to stop the next user inheriting them is to destroy them:
`UserStateSync.clearDreamSettings()` resets all four to defaults on every logout.

So the promise above does not hold for this one store. The same person signing
back in on the same device gets their board arrangement and a **reset
screensaver**. `dream_ever_started` is deliberately kept — it is a fact about the
device, not the account.

`UserSettings.reset()` drops the in-memory copy and re-reads for whoever is
signed in now. The in-memory drop is not optional: the store is a process-wide
`object` and `ensureLoaded` returns early once loaded, so without it the next
user inherits the previous one's arrangement **and saves it under their own id**.

### Boards still push, and still debounce
Board changes are rare — a few per session — so the debounce is no longer
load-bearing for the quota. It stays because a single edit is still a *burst*:
adding a station with four lines ticks four boxes and saves once. `flushNow()`
covers backgrounding and logout, where the timer may not survive.

### Nothing is sent unless something changed
`flushNow()` runs on **every** backgrounding and once a night, so it must be free
when there is nothing to say — otherwise the write problem this section describes
is not solved, only relocated from "per settings tap" to "per app close".

`BoardPushGate` counts user changes against the last the server accepted:

- nothing pending → no request at all;
- an edit made *while* a request is in flight is not acknowledged away, because
  the response clears only the revision that request carried;
- a failed push stays pending, so the nightly flush actually retries it.

It also carries `allowEmpty`, the permission a full-replace endpoint needs before
storing an **empty** list over a non-empty one. Without it the login path is a
data-loss window: it wipes local SQL before restoring, so a device backgrounded
inside it posts `boards: []` stamped with a clock that always wins the LWW guard,
and the account is cleared on every device. Only a user action — deleting the
last board, unticking the last row — hands out that permission, and call sites
pass the live `selections.isEmpty()` rather than a literal.

A **nightly flush** rides on the existing `BGProcessingTask` (§4). Normally a
no-op — but it covers the case nothing else would: the app killed inside the
debounce window, or a push that failed offline. Without it the local list is
right, the account's is stale, and nothing notices until the next *edit*.

### Every mutation, and where it goes
| Change | Pushes to backend? |
|---|---|
| Board added, lines edited, filter changed | **yes** — `boardsChanged()` |
| Board or station deleted | **yes** — plus `UserSettings.forget` |
| Expanded / view / rows / pin | no — disk only |
| Drag reorder, home layout | no — disk only |
| Screensaver settings | no — disk only |
| Widget added / removed | no — observed, device-local |

Deleting a board's last selection also calls `UserSettings.forget`, or re-adding
the station silently restores the arrangement of the one the user removed and the
orphan row keeps a `position` that reorders what is left.

### Logout resets the widget
`cleanupAll()` calls `widgetManager.clearWidgetData()`, which wipes the App Group
to the widget's designed empty state. A signed-out account must not leave a live
departure board on someone's home screen.

The widget relights **on its own** at the next sign-in, and needs nothing stored
to do it: the widget's station is chosen in the SYSTEM's widget editor and that
configuration is untouched by logout, so once `restoreBoards` runs,
`setupStation` → `completeSetupAsync` → `updateWidget` → `refreshAllBoards()`
repopulates every station's App Group entry and the widget finds its board again.

> An earlier version persisted `rememberWidgetBoards()` for this. It was written
> and never read — nothing anywhere consumed it — and it could not have helped:
> the only case it covers is a widget whose station did NOT come back as a board,
> and there is nothing to show for such a widget anyway. Deleted.

#### …and the wipe alone stopped being enough (2026-08-15)

The paragraph above was true when the widget could only be *given* data. It
stopped being true when the extension learned to go and get its own:
`DepartureBoardProvider.timeline` now fetches whenever what it holds is older
than `staleAfterSeconds` (120 s), over the same REST path the refresh button
uses — authenticated by `widget_api_key`, **not** by the user's token — for a
station named in an AppIntent configuration that nothing on the app side can
read or erase.

So the wipe held for about two minutes. Then the board came back, fully live,
for an account that had signed out. Reported from device, and it is the exact
failure this section's first paragraph forbids.

Deleting data cannot fix this, because the widget can always re-derive it. The
sign-out has to be **stated**, in the one place both processes can see:

- `widget_signed_out`, **raised** by `AuthBridge.logout()` and by
  `IosWidgetManager.clearWidgetData` (reached only from `cleanupAll()`), and
  **lowered** by `AuthBridge.persistUserIdentity`.
- The extension checks it in `AppGroupStorage.readWidgetData` (renders
  `WidgetData.signedOut` — "Sign in to see your board"), in the timeline's
  staleness branch, and in `WidgetRefreshService.refresh` (the door the refresh
  button and the WidgetKit push handler come through).
- It is **not** the same as "no stations". A signed-in user who deletes their
  last board wipes to the same empty App Group and must keep the refresh path
  they have — so `refreshAllBoards`'s own `wipe()` never raises it.

The relight above is unchanged and still needs nothing stored — but the flag is
**not** lowered on it. Both edges belong to `AuthBridge`, the only side that
knows whether there is a session:

- raised in `logout()` as well as in `clearWidgetData`, because the two halves of
  a sign-out run in opposite orders (`ProfileViewModel.signOut` signs out of
  Firebase *before* `cleanupAll()`; `signOutForAccountDeletion` *after*), so
  either alone can be undone by whatever ran last;
- lowered in `persistUserIdentity`, which runs on sign-in, on every keychain
  restore and on every token refresh. A board write cannot stand in for it in
  either direction: a request still in flight at sign-out can produce one with
  nobody signed in, and a user who signs back in with **no boards saved** never
  reaches one — which would leave their widget telling them to sign in while
  they are signed in.

### Account deletion is not logout
Everything device-local is kept per uid *because the same person is coming back*.
A deleted account has no same person, so `UserStateSync.forgetAccount(uid)`
removes those rows outright via `StorageManager.removeDurable` — the one thing
`clearAll()` deliberately cannot reach. It also cancels any push the deleted
account had queued and drops the in-memory copy, without which the next user to
sign in on that device inherits the deleted user's arrangement.

## 2b. What does NOT cross devices

**Read this before promising anything about a new device.** The account carries
what the user TRACKS. How it LOOKS is this device's business, and does not
travel — not to a new phone, not to a second device, and not across a reinstall
of the same app.

| Signing in elsewhere restores | |
|---|---|
| Stations, lines, directions | ✅ |
| Destination / via filters | ✅ |
| Station names, bus pole naptans | ✅ |
| Profile name, email, photo | ✅ |
| Expanded / collapsed | ❌ default |
| View mode | ❌ default |
| Rows per platform | ❌ default |
| Pinned platform | ❌ default |
| Drag order | ❌ falls back to `addedAt` |
| Home layout (list / carousel) | ❌ default |
| Screensaver settings | ❌ default |
| Theme (light / dark / system) | ❌ default |

The mechanism is `@Transient` on `Board.config` and `Board.widget`, which keeps
them out of the wire payload as well as the local board list. **There is no
preferences endpoint** — the whole user-sync surface is `syncProfile`,
`syncStations`, `syncBoards`, `getUserProfile`, `logOut`, `deleteAccount`,
`registerFcmToken`, `unregisterFcmToken`.

### Why, and what changing it would cost

Appearance is the highest-frequency and lowest-value state in the app. Syncing it
as originally written put a backend write behind every tap of a toggle and every
detent of a slider — on the one document every login reads. That is the right
thing to have refused.

It is worth being precise that this reasoning does **not** rule out syncing
appearance at all; it rules out syncing it *per touch*. A single `preferences`
blob pushed through the debounce boards already use (`BoardPushGate`, 2.5 s,
coalesced, and gated on an actual change so an unchanged app sends nothing)
would cost roughly what a board edit costs, which is rare. The pieces exist:
`UserSettings` has the serialiser, `DreamSettings.applyRemote` already suppresses
the echo a remote apply would otherwise push straight back, and `restoreBoards`
is where it would be applied.

**Reviewed and deliberately declined on 2026-08-15.** The behaviour stands; this
section exists so the promise matches it. If that decision is revisited, the
audit in `SESSION_AUDIT_2026-08-15_SESSION_STATE.md` §4 has the full shape.

---

## 3. Login restore

`LoginViewModel.syncUserAndSetupData`, all awaited before the loader clears:

1. `/user/sync/profile` — registers the device session, wipes local SQL/cache.
2. `UserStateSync.resetForNewSession()` — drops the *previous* user's in-memory
   settings and re-reads the incoming user's from disk. Without it, signing in as
   somebody else in the same process leaves their layout in memory, and the new
   user's first change saves that arrangement under **their** id.
3. `restoreBoards` — **every** board, in `position` order, filters included.
   The arrangement is already in memory (step 2 re-read it for this account), so
   a card is never briefly drawn at the defaults and then corrected.

---

## 4. The activity trail

Local-first queue → batched upload. A user who opens the app forty times a day
costs **one Firestore write**, not forty.

- **Queue:** `ActivityEventEntity` in SQLite, capped at 5 000 (oldest dropped).
  Deliberately **not** cleared by `clearAllData()` — the events worth having most
  are the ones around an auth change, and a queue emptied by the very event it is
  recording can never report it.
- **`uid` is stamped at enqueue**, not at upload: an event belongs to whoever was
  signed in when it happened.
- **Upload:** `users/{uid}/activity/{YYYY-MM-DD}_{deviceId}`, appended with
  `arrayUnion`. One document per device per day — contention-free, and idempotent
  because every event carries a client-generated id **and** is a pure function of
  what the client sent. An earlier version stamped each event with the server's
  arrival time, which made every retry a distinct object and turned the dedup into
  an append (measured: a repeated 2-event batch left 4 rows). There is no
  per-event `receivedAt` and no `count` field for the same reason — the
  document's `updatedAt` and `events.length` carry both facts.
- **Three outcomes, never two:** accepted → delete; **400 → also delete** (the
  same bytes will be refused again, and a poisoned batch at the head of a FIFO
  queue blocks everything behind it); anything else → keep.

### Scheduling on iOS
A **third** background budget, on purpose:

| Task | Type | For |
|---|---|---|
| `com.stationly.mobile.widgetrefresh` | `BGAppRefreshTask` | keeping the widget fresh |
| `com.stationly.mobile.activityupload` | `BGProcessingTask` | the nightly upload |

`BGProcessingTask` runs when the device is idle and on power, with a long slice.
Sharing the refresh task would have made every widget wake carry a network
upload it does not need, and given deadline-free work a slice measured in
seconds.

**Nightly is an intent, never a promise** — iOS may run it late or not at all.
`ActivityBridge.uploadActivityIfStale()` on foreground is the net: it fires only
once the oldest queued event is >26 h old, so on a healthy device it reads one
integer from SQLite and returns.

### Widget add/remove is DERIVED
WidgetKit has no placement callback. `HomeStateProbe` reads
`getCurrentConfigurations()` on every foreground and hands the snapshot to
`ActivityBridge.widgetsObserved`, which diffs it. Two unavoidable consequences
when reading the data: a widget added **and** removed between two app opens
leaves no trace, and every event carries the timestamp of the app open that
noticed, not of the user's action. A failed probe reports nothing — recording it
would look like the user removing every widget at once.

The station a widget is pinned to is **not** in the event: `StationConfiguration`
is compiled into the widget target and is not visible to the app. The descriptor
keeps a trailing `|` so it can be added later without splitting the event's
history.

---

## 5. Database migrations now exist

Adding `ActivityEventEntity` is the first change that needed one.
`Schema.create` runs **only on an empty database**, so a new table declared in
the `.sq` alone reaches fresh installs and nothing else — failing with
"no such table" on precisely the devices that have used the app longest.

Earlier changes were all additive-with-a-default on existing tables, which old
rows survive, which is why the omission went unnoticed. Schema is now **version
2** via `migrations/1.sqm`. Every future change needs one.

---

## 6. Two pre-existing bugs this work uncovered

Both were found on device / on staging, not by reading.

**1. Cross-device sync never ran on iOS.** `UserSyncBridge.currentUid()` read
the uid from the **App Group** suite while `AuthBridge` writes every identity key
to `UserDefaults.standard`. Nothing had ever written a uid to the App Group, so
it returned null, `handle()` bailed on its first line, and every `user.sync` push
reconciled nothing. Verified: the push arrived and was traced correctly, and a
dump of the App Group container had no uid key at all. It fails in the safe
direction, which is why it survived — a reconcile that never runs looks exactly
like a reconcile with nothing to do.

**2. A station could never leave the subscription registry.**
`SubscriptionService.updateCount` rebuilt `stationCounts`, `delete`d the key at
zero, and wrote with `set(…, { merge: true })`. Merge **deep-merges map fields**,
so a key absent from the payload is preserved — the delete was invisible to
Firestore. Measured on staging: adding a board took the registry 99 → 100;
removing it left 100 with `count: 1` still stored. The Syncer therefore keeps
polling TfL for stations nobody watches, and the registry grows monotonically.
Now written as a single field path with `FieldValue.delete()`, which is the one
construct merge honours as "remove this".

## 7. Known gaps

- **A new device gets default appearance.** Boards and filters come back; the
  arrangement does not. Deliberate — see §2b.
- **The activity `props` map is untyped** on both sides of the wire. Nothing
  validates a key or a value; nothing in the app ever reads one back.
- **Android emits no events and reads no v2 state.** The infrastructure is in
  `commonMain` and ready — `ActivityLog.record` call sites and a WorkManager
  job scheduling `ActivityUploader.flush` are all that is missing.
- **Device clocks order everything.** A phone an hour behind loses writes it
  should win. Tolerable for settings; deliberately not used for anything with
  real consequence.
- **`core:iosSimulatorArm64Test` does not compile** — pre-existing backtick test
  names containing commas, which Kotlin/Native rejects. `:core:testDebugUnitTest`
  is the suite that runs.
