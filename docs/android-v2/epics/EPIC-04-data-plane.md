# EPIC-04 — Data plane · 18 pts

**Goal.** Feed the v2 board model from FCM, and stop a v1 device deleting a v2
device's work.

**This is where Android is stronger than iOS, not weaker.** FCM
`Station_{naptan}` fan-out updates the app, the widget and the dream with no
polling and no budget. iOS gave that up and built a three-engine refresh-budget
system to approximate it. **Do not port that machinery.**
`LiveStream.android.kt` is 12 lines of deliberate no-ops and
`RefreshBudgetStore.android.kt` returns `null` on purpose. Both are correct.
Leave them.

**Exit.** One push updates every board that subscribes to that station, each with
its own direction and filter; and an account with a v1 and a v2 device converges
instead of destroying itself.

---

## AV2-4.1 — FCM at v2 · `L` · Review (S012)

**Depends on:** AV2-3.5 **Reads:** [`GAP_ANALYSIS.md`](../analysis/GAP_ANALYSIS.md) §3.4
**Files:** `android/app/src/main/java/com/stationly/mobile/service/FcmMessagingService.kt`

`FcmMessagingService` is 400+ lines written against single-selection
assumptions. `ProcessPredictionsUseCase` already exists in `commonMain` and its
KDoc names this service as its caller.

### Tasks
- [x] **a.** **Characterize first** — this is `DIVERGENT`. Build a table of
      cases: v1 payload in, SQL writes out. Make it pass against the current
      code before changing anything. *(9 cases,
      `core/src/androidUnitTest/.../fcm/PredictionSyncCharacterizationTest.kt`,
      against a real in-memory SQLite. It found a defect — see the findings.)*
- [ ] **b.** Route ingestion through `ProcessPredictionsUseCase`. *(NOT done, and
      deliberately — see "why (b) is still open" below. Doing it today would
      regress the widget.)*
- [x] **c.** Direction scoping. *(Already correct before this story. Pinned by
      the characterization suite so it stays that way.)*
- [x] **d.** Per-board fan-out. *(Already correct. What was missing was the fan-
      out to the APP, which is the story's headline finding.)*
- [x] **e.** `matchesFilter` precomputed at ingest. *(Already correct. Pinned.)*
- [ ] **f.** **Restore the foreground reconcile that AV2-3.5 deleted.** v1's
      `MainActivity.onResume` called `UserSyncCoordinator.reconcile(this)` — the
      fallback for a device that was offline when a `user_sync` push went out.
      The host that replaced it does not, and `reconcile` is now called from
      nowhere. Coherent rather than merely deferred, because the push it backs
      up does not reach the v2 board model either until this story lands: fix
      both halves together, or the fallback is a fallback for nothing. Put it in
      the shared UI's resume hook (`SummaryScreen`'s `ON_RESUME` observer, which
      already exists and already reloads from SQLite) rather than back in the
      Activity — iOS needs the same thing and the host is not the place for it.

### Acceptance criteria
- [x] The characterization table still passes, or each change is justified.
- [x] A device that was offline for a `user_sync` push catches up when the app
      is next brought to the foreground. *(Verified on the Pixel:
      `D/UserSync: Reconcile complete: 1 station(s)` on every foreground.)*
- [x] A push to a station a user tracks twice (two directions) updates both, and
      neither clears the other. *(Test; not reproducible on the device, which
      tracks one direction.)*
- [x] A filtered board admits only matching rows, and an empty filter result
      falls back to the unfiltered list with a note rather than an empty card.
      *(Two tests — the fail-open is the reason excluded rows are persisted.)*

### Findings

#### An FCM push wrote fresh departures and the open board did not move

**The story's premise was stale, and the real gap was somewhere else.** The task
list describes `FcmMessagingService` as "400+ lines written against
single-selection assumptions" needing direction scoping, per-board fan-out and a
`matchesFilter` precompute. All three were already done, in
`SyncPredictionsUseCase`, which both platforms call — the shared use case
absorbed them on `ios-parity` before this branch was cut. Tasks (c), (d) and (e)
were pinned rather than written.

What was actually broken is a level up, and it arrived with AV2-3.5. The fan-out
told the app a board had changed by writing a `SharedPreferences` key shaped
`predictions_<station>_<line>`, which v1's `SummaryViewModel` watched with an
`OnSharedPreferenceChangeListener`. The cutover deleted that view model. Nothing
in the app has registered a preference listener since — the write kept happening,
to a key with no reader.

Meanwhile the shared `SummaryViewModel`, which is the home screen now, collects
`com.stationly.core.util.FreshDataNotifier.events`, and **no Android code path
emitted to it**. So a push wrote to SQLite and the visible board sat there until
its own 30-second poll or the next resume. No error, no log line, and the symptom
— a board that feels a bit behind — is indistinguishable from a slow network.
iOS was unaffected throughout: it reaches the same SQLite through
`ProcessPredictionsUseCase`, which emits.

Fixed in `mobile/util/FreshDataNotifier`, which is the one place every write path
already funnelled through. The single `notify(context, stationId, lineId)` became
three scoped entry points — `notifyPredictions`, `notifyLineStatus`, `notifyAll`
— because the shared flow carries WHAT changed and a station push is not a line
push; one call could only have emitted `FreshData.All` for everything, reloading
every board on the phone on every push. The dead SharedPreferences ping is gone.

**And it now logs.** `D/FreshData: fresh data → Station(stationId=…)`. That line
is the other half of the fix: a fan-out to a key nobody reads produces no error,
no warning and no trace, which is exactly how this survived a cutover and three
device passes.

Verified on the Pixel 7 Pro, from real backend pushes:

```
13:36:21.900 D/FreshData: fresh data → Station(stationId=910GHACKNYW)
13:37:52.067 D/FreshData: fresh data → Station(stationId=910GHACKNYW)
```

#### Android signed people out with no explanation, and the receiving end already existed

Logged on AV2-3.5 as AV2-4.4 task (e); **closed here** because it sits in the
same file as task (f) and leaving it another story would have been leaving a
silent sign-out in the app for no reason.

`UserSyncCoordinator.forceLogout` wrote `pending_account_removed` into a private
prefs file and v1's `MainActivity` read it back and raised a Toast. The cutover
deleted that Activity, so an account deleted from another device returned this
one to the login screen with nothing said — the most alarming thing an app can do
silently. The shared login screen has carried the whole receiving end all along
(`LoginViewModel` reads `UserStateSync.ACCOUNT_REMOVED_FLAG` from durable storage
and renders `strings.accountRemoved`); the only writer was iOS's `UserSyncBridge`.
Android now writes the same key, and the private prefs file and its read-and-clear
helper are deleted. Two files for one flag is how the two stop agreeing.

#### Duplicate boards render duplicate departures — found on the device

The Pixel's home screen showed the same 2-minute Stratford departure **twice,
side by side**, in the hero strip of one card. Three `UserSelectionEntity` rows
for Hackney Wick / Mildmay / inbound: two carrying `parentStationId`, one blank.
The screen groups CARDS by hub, so they collapse into one card, and then the hero
renders one entry per selection.

Nothing in the schema prevents it — the primary key is an AUTOINCREMENT `id` and
`insertSelection` is a plain `INSERT` — so any path that saves without clearing
appends. Fixed on the READ side in `SqlStorage.getAllSelections`, with two rules
that both matter: the group keeps the POSITION of its first row, because
`selectAllSelections` orders by `id` and three surfaces read "your first station"
off that order; and the kept ROW is the one carrying a `parentStationId`, because
blank means "same as station" and a bus stop restored without it renders one stop
as several identically-named cards.

**Where the duplicates come from is not settled, and the evidence points at the
cloud.** They came back after a restore, with the ids jumped from 63/64/65 to
118/119/120 and the identical 2-with-hub-plus-1-blank shape. A local double-insert
would not reproduce that shape twice; `syncUserAndGetSavedStations` clears the
table and re-inserts `profile.stations.toUserSelections()`, so three rows in means
three entries in the cloud `stations` array — one of them written by something
that dropped the hub. **AV2-4.3** owns that list (and moves Android off it), so
the server-side duplicate belongs there. The read-side repair is right either way:
it helps every user who already has duplicates on disk, including from the
AV2-3.1 era when a v1 door and a v2 door were both writing this table.

Worth knowing what it cost before the fix, straight from the log — one push,
three identical sync-and-persist passes:

```
13:34:52.159 D/FreshData: fresh data → Station(stationId=910GHACKNYW)
13:34:52.223 D/FreshData: fresh data → Station(stationId=910GHACKNYW)
13:34:52.235 D/FreshData: fresh data → Station(stationId=910GHACKNYW)
```

#### DEFECT — the prediction primary key collapsed two real trains · ✅ FIXED S016 (Q7)

Found by the characterization suite, pinned by a test that asserts the WRONG
behaviour on purpose so the fix has something to flip.

```sql
PRIMARY KEY (stationId, lineId, direction, destination, platform, eta)
```

`eta` there is the FORMATTED STRING, and `insertPrediction` is
`INSERT OR REPLACE`. Two trains to the same destination on the same platform 40
seconds apart both format as `"1 min"`, so the second overwrites the first: the
rider is shown one train where two are coming, and the board is a row short.

`SyncPredictionsUseCase` already dedupes on `targetEpochMs` precisely so the
second survives, and its comment says the row is kept "without losing the row from
SQL". It is lost from SQL anyway, one layer down. The Kotlin fix is defeated by
the schema.

**Fixed in S016**, three sessions after it was raised, and it cost what AV2-4.1
predicted: `2.sqm` drops and recreates the table, because everything in it is a
cache FCM refills within seconds of launch.

The key is now
`(stationId, lineId, direction, destination, platform, eta, targetEpochMs)` —
both columns, not a swap. When the timestamp parsed, `targetEpochMs` discriminates
and `eta` is derived from it; when it did not, `eta` is the only thing left, which
is the same fallback the Kotlin dedupe uses (`targetEpochMs ?: eta`). The `.sq`
carries the reasoning and the one nuance: SQLite treats NULLs as distinct in a
unique index, so two unparseable rows do not collapse here — they never arrive,
because `distinctBy` removes them first and `savePredictions` clears the board's
rows before every insert.

**Turning the pinned test green turned a neighbouring one red, and that was the
real find.** `the same train twice in one payload is collapsed` had been passing
**because of** this defect: its fixture called `pred(…, in3min)` twice, and
`in3min` is a `get()` that reads the clock on each access — so the two rows were
milliseconds apart and were never the same train. The old key rounded both to
"4 min" and collapsed them. A fixture that re-derives a value per use cannot
express "the same thing twice".

#### Two `Station_*` topics are subscribed for boards that no longer exist

The device is subscribed to `Station_940GZZDLBNK` and `Station_940GZZLUKSX` and
has no selection for either — pushes arrive, match nothing, and are dropped. Not
harmful, but it is push traffic and backend fan-out for a phone that stopped
caring. **AV2-4.2** owns the topic lifecycle; noting it here because the evidence
is easy to reproduce (`run-as com.stationly.mobile cat
shared_prefs/StationlyPrefs.xml`, then grep `Station_`).

### Why (b) is still open

Routing Android's ingestion through `ProcessPredictionsUseCase` would unify the
two platforms on one path, which is the right destination. Doing it today would
regress the widget, so it waits for EPIC-05.

The two paths now differ in exactly two things. `ProcessPredictionsUseCase` calls
`departureRepository.processPredictionsPayload` to keep an in-memory flow warm for
live collectors — that flow exists for iOS's WebSocket stream, which Android
deliberately does not have (`LiveStream.android.kt` is twelve lines of no-ops, and
correctly so). And it refreshes the widget for **the primary selection only**,
where Android's `DepartureWidgetProvider.updateFromStorage` redraws every widget
instance. Android's is the better behaviour and it is the one AV2-5.1 builds on:
adopting the shared path first would mean shipping a single-widget refresh into a
story whose entire point is per-instance binding.

Do (b) after AV2-5.1, when `ProcessPredictionsUseCase` can be given the
per-instance refresh and both platforms gain from it.

### Handoff notes — S012, 2026-09-06

**Gate GREEN.** `:core` 403 → 418, `:android:app` 23 → 26. `commonMain` untouched, so no XCFramework run was needed; `:core` changed
(`SqlStorage.getAllSelections`) and iOS gets the dedupe too, which it wants for
the same reason.

**Verified on a Pixel 7 Pro**, staging debug: one launcher icon
(`com.stationly.mobile/.MainActivity`), no crash, board renders, real pushes fan
out to the app, the foreground reconcile runs, and the duplicate hero departure is
gone. Screenshots before and after are in the session scratchpad.

**What still needs a phone**, and could not be reached from this device:
- A **two-direction board**. This account tracks one direction of one line, so the
  headline acceptance criterion is covered by test only. Add a second direction at
  any tube station and confirm both halves tick.
- The **`user_sync` deleted push** end to end — sign in on two devices, delete the
  account on one, and check the other lands on login *with the notice*. The writer
  is new here and only the write side is proven.
- The **screensaver and the widget** still receive the fan-out. They were never
  broken and the code path is unchanged, but they share the function that changed
  shape.

---

## AV2-4.2 — Topic lifecycle · `L` · Review (S016)

**Depends on:** AV2-4.1 **Files:** `StationLifecycleUseCase` callers, `FirebaseAuthManager`, `AndroidNotificationManager`

Topic shapes are **unchanged** between v1 and v2 — `Station_{naptan}` and
`LineStatus_{mode}_{line}` — so there is no rename to migrate. Two things do
change: who emits them, and how many.

### Tasks
- [x] **a.** Move subscription onto `StationLifecycleUseCase` (`commonMain`).
      *(`topicsFor` / `topicsToRelease` / `subscribeTopicsFor` / `reconcileTopics`
      — the vocabulary and every emission now start here.)*
- [x] **b.** Delete v1's parallel paths in `FirebaseAuthManager` and
      `SelectionViewModel`. *(Both gone. `SelectionViewModel`'s was in
      `commonMain` and spelled the topic names itself, four lines above a call
      that spelled them correctly.)*
- [x] **c.** Diff against the persisted `StationlyPrefs.fcm_topics` set and
      subscribe only the difference. *(`TopicLedger.plan`, and the diff runs in
      both directions — see the findings for why one direction alone would have
      been a new silent bug.)*
- [x] **d.** Keep `stationly_all` and its `subscribed_all_topic` guard. *(Kept,
      and given the one event that invalidates it. `BroadcastTopic`.)*

### Acceptance criteria
- [x] Topics are emitted from exactly one place. *(`StationLifecycleUseCase` for
      every board topic; `BroadcastTopic` for the one topic that is not a board.
      Nothing else reaches `FirebaseMessaging.subscribeToTopic`.)*
- [x] Removing one board of two at a shared station does **not** unsubscribe the
      station topic the other still needs. *(Was already true. It is now pinned
      against the SHIPPING rule: `V1GoldenTest` used to re-implement it in the
      test file, so the v1 fixtures were asserting a restatement.)*
- [x] Upgrade is a diff, not a flood. *(An upgrading device arrives with its
      ledger already written under the same key by the same class, so the first
      reconcile has nothing to do. `TopicLedgerTest`.)*

### Findings

#### The ledger only ever grew, and the story's own task (c) would have weaponised it

`AndroidNotificationManager.subscribeToTopics` added to `StationlyPrefs.fcm_topics`
and `unsubscribeFromTopics` **did not remove from it**. Harmless while nothing
read it — it is only a list — and a live bug the moment anything diffs against
it, which is exactly what task (c) asks for. A topic subscribed, removed, then
re-added would read as "already subscribed", get skipped, and that board would
never receive another push. No error, no log; it looks like a quiet line.

So the ledger had to become honest before the diff could be added, and both
halves shipped in the same change. The ledger is now maintained on every path,
and dropped from **only on success** — a failed unsubscribe leaves a live
subscription whose ledger row is the only thing that will ever find it again.

#### The reconcile has to run in both directions, and an empty list is not an answer

`TopicLedger.plan(ledger, desired)` subscribes what is missing and releases what
is stale. Releasing alone would have been the obvious reading of task (c), and
it would have missed the case where the ledger is BEHIND reality — a board added
on another device, or a subscription lost with a rotated token.

The dangerous input is the empty one. `getAllSelections()` answers empty during a
login restore, in the gap between a wipe and its refill, and before the database
has opened — and each of those means "I do not know", not "this user has no
boards". Reading it as a delete would unsubscribe a working device in the middle
of signing in. So an empty `desired` changes nothing, and the two operations that
genuinely mean it (`unsubscribeFromTopics`, `clearAllTopics`) say so explicitly.
Same rule as `Board.isUsable`, for the same reason, and it is the first test in
`TopicLedgerTest`.

#### A token rotation silently dropped `stationly_all`, forever

Found while doing task (d) rather than looked for. FCM topic subscriptions belong
to the **token**, not to the app — which is why `onNewToken` re-subscribes the
board topics. `stationly_all` was not in that list: it is subscribed once per
install by `StationlyApplication`, guarded by a `subscribed_all_topic` boolean
that stays `true` forever after. So a rotated token left the device unsubscribed
from the global broadcast topic, with a flag saying it was subscribed and nothing
anywhere to notice. Every `audience: {type:"all"}` push, gone, for that install.

The guard is still right — it is what keeps cold launch off the network. It just
needed the one event that invalidates it to lower it. `BroadcastTopic` now owns
the topic name, the guard key, and both paths; it was spelled out in two files
by the time this session had finished adding the second one.

#### `onNewToken` re-subscribed the LEDGER, which carries a leak across the one event that would have ended it

It now re-derives from the selections. On a healthy device that is the same set;
when it differs it is because a subscription outlived its board, and a rotation
is the one moment those genuinely disappear on their own.

#### `cleanupAll` was protecting a mechanism that no longer exists

Its comment said topic collection must happen before `clearAll()` "so the
unsubscription queue written by `unsubscribeFromTopics()` is not immediately
wiped" — a queue iOS wrote into `NSUserDefaults` for a Swift bridge to flush.
That bridge is gone (iOS does not link FirebaseMessaging any more; its topics are
live-stream subscriptions). Meanwhile the ordering cost Android the thing it
does have: the ledger lives in the prefs file `storageManager.clearAll()` wipes,
so by the time the old code asked, any subscription not derivable from the
selections was already unnameable.

Now `clearAllTopics()` runs first, off the platform's own record. Same set on a
healthy device, strictly larger on one that has been running for a year — and the
rows that differ are exactly the ones that must not be left on a phone somebody
else is about to sign into. `FirebaseAuthManager.logout` had its own copy of the
derive-from-selections loop (and a fourth copy of the topic names); it calls the
same one line now.

### What is NOT covered by a test

`AndroidNotificationManager`'s ledger IO — the `SharedPreferences` read/write
around `TopicLedger.plan` — has no test, because `:core`'s `androidUnitTest` has
no Robolectric and `getSharedPreferences` throws in a plain JVM test. The rules
are all pure and all tested; what is untested is a ten-line wrapper. Adding
Robolectric to the gate for it is a bigger change than the risk justifies, but
it is the reason the ledger discipline is written down in the class KDoc rather
than left to be inferred.

### Handoff notes — S016, 2026-09-11

**The reconcile runs on every foreground** (`MainActivity.onResume` →
`UserSyncCoordinator.reconcileTopics()`), deliberately NOT inside the profile
reconcile next to it: that one costs a Firestore read and is debounced to 15
minutes, this one costs a prefs read and answers a different question. It also
runs at the tail of a successful profile reconcile, where the board list may have
just been rewritten from the cloud.

**On the device, this is verifiable in one command.** The two stale topics
AV2-4.1 found should now disappear on the next app open:

```bash
adb shell run-as com.stationly.mobile cat shared_prefs/StationlyPrefs.xml | grep -A20 fcm_topics
adb logcat -s NotificationManager:D BroadcastTopic:D   # "reconcile: +0 -2 (ledger 6 → 4)"
```

**Not yet done, and it belongs to AV2-4.3:** the desired set is derived from
`UserSelectionEntity`. Once Android believes `boards`, it should be derived from
the same list the boards array folds to — `Board.toSelections()` — or the two
will disagree the first time a board exists in the cloud that has no local row.

---

## AV2-4.3 — Cloud state dual-write · `L` · Review (S016)

**Depends on:** AV2-4.2 **Reads:** [`MIGRATION.md`](../analysis/MIGRATION.md) §2 · **Risk R2, and R2 is proven**

`syncStations` is a **full replace**: it diffs old ids against new to inc/dec
subscription counts, so a one-element post deletes every other board on the
account. This already happened — it is why `SelectionViewModel` on this branch
reads the full list out of SQLite before posting.

> ### Owner direction, 2026-09-06 (S013)
> **"Align with the backend — we would be using the `boards` array now, after
> iOS has developed it."** Task (b) below was already the plan; this confirms it
> and settles the order: `boards` is authoritative on Android too, and
> `stations` becomes the dual-write for the transition window, not the source of
> truth.
>
> **AV2-4.1 found the evidence for why this matters, and it is stronger than the
> plan assumed.** The Pixel's home screen was rendering the same departure twice
> off three `UserSelectionEntity` rows for one board — and they came BACK after a
> restore, with the identical two-with-hub-plus-one-blank shape. A restore is
> `profile.stations.toUserSelections()` written verbatim, so three rows on the
> device means three entries in the cloud **`stations`** array, one of them
> written by something that dropped the hub. Moving Android to `boards` removes
> the source rather than the symptom. The read-side dedupe shipped in AV2-4.1 is
> the repair for devices that already have them; it is not the fix.
>
> Note what does NOT change: the two lists stay two lists on the wire, and the
> subscription registry keeps reading their UNION. "Adopt `boards`" is about
> which one Android believes, not about collapsing them.

### Tasks
- [x] **a.** **Verify on the backend** that `syncStations` does not touch
      `boards` and `syncBoards` does not touch `stations`. *(Read, and both hold
      — see the findings. The duplicate `stations` question is answered too, and
      the answer is not the one the story expected.)*
- [x] **b.** Adopt `/user/sync/boards` as the authoritative write. *(The WRITE
      was already boards, since the cutover. The **read** was not, and that was a
      live bug that deleted boards — the headline finding.)*
- [x] **c.** **Dual-write** the same list flattened to `SubscribedStation` via
      `/user/sync/stations`, for the transition window. *(In
      `UserStateRepository.pushBoards`, after an ACCEPTED boards write and only
      then — that ordering is the whole safety argument.)*
- [x] **d.** First-run fold-up: `boards` wins if non-empty; else fold `stations`;
      else fall back to the local rows. *(`effectiveBoards()`, one definition,
      replacing three. Rung three lives where local state is visible.)*

### Acceptance criteria
- [x] A v1 save on a shared account no longer prunes v2 boards. *(Structural:
      `syncStations` cannot reach `boards`, verified in the backend source, and
      the subscription registry diffs the UNION so a v1 write releases nothing a
      v2 board still holds.)*
- [x] A user who has never signed in keeps their boards through the upgrade.
      *(`reconcileBoards` returns early when `boardsUpdatedAt == 0` and this
      device holds boards; the migration preserved the rows; nothing on the
      reconcile path can now delete them.)*
- [x] The dual-write has a documented retirement condition (Q2), not an open end.
      *(Named at both the call site and on `SduiApiService.syncStations`: a
      remaining-v1-install count, not a date.)*

### Findings

#### Android was writing one list and reading the other, and the reconcile deleted the difference

**This is the bug of the story, and it was not the one the story describes.** The
task list reads as a migration: adopt the boards write, dual-write the legacy
array, fold on first run. But the write had already moved — AV2-3.5 made the
shared `SelectionViewModel` the Android save path, and that calls
`UserStateSync.boardsChanged()`, which posts `/user/sync/boards`. What did not
move was the READ: `UserSyncCoordinator.reconcile` still called
`UserSyncRepository.reconcile`, the legacy diff against `profile.stations`.

The backend never derives one array from the other on write — verified in
`UserService`: `syncStations` writes `stations`, `syncBoards` writes `boards`,
and only a **missing** `boards` is derived, on read. So since the cutover, every
board saved on Android left no trace in the array its own next foreground
compared against. That reconcile removes any local selection absent from the
cloud list:

```kotlin
local.filter { key(it) !in cloudKeys }.forEach { lifecycle.discardStation(it, …) }
```

On an account whose `stations` array was empty — every account created on v2, and
any account whose v1 device never saved — **that is every board the user has,
deleted within fifteen minutes of adding it**, with no error and nothing on
screen. It reads exactly like the app forgetting.

It survived four device passes because the test account's `stations` array
happened to already describe the boards the device held: one station, saved by v1
before the cutover, unchanged since. Nothing in the sessions before this one
added a board and then waited.

Android now calls `reconcileBoards`, the same path iOS runs. Three things come
with it: filters and the resolved hub are restored (the flat list carries
neither), the `boardsUpdatedAt == 0` guard stops a never-written account deleting
a device's boards, and the revision gate means an unchanged account costs zero
Firestore reads per foreground instead of one.

The legacy `UserSyncRepository.reconcile` is **deleted**, not deprecated. Left in
place it would be a correctly-named, obviously-useful function that quietly
destroys the user's boards — the same shape as `updateWidgetContent` in AV2-5.1,
removed for the same reason.

#### The duplicate `stations` rows were not the backend appending

AV2-4.1 suspected a server-side append. It is not: `syncStations` is
`userRef.update({ stations })`, a straight replace of whatever the client sends.
The duplicates came back after a **restore**, and the restore is where they were
made — `syncUserAndGetSavedStations` wiped SQLite and then wrote
`profile.stations.toUserSelections()` verbatim, so three entries in the cloud
array became three rows, every time, forever.

And the array could hold a board the user had already deleted on v2, because
nothing was updating it. So the restore's own fallback was reviving deleted
boards on each sign-in. It now restores from `effectiveBoards()` — the board list
where there is one — which is the same rule the mid-session reconcile and the
board setup use, and used to be decided separately in all three places.

#### The dual-write borrows its guards from the write it follows

`/user/sync/stations` has **no staleness check and no empty guard**: it stores
exactly what it is handed. `/user/sync/boards` has both — it rejects a write
whose `clientUpdatedAt` is at or before the stored one, and refuses to empty a
non-empty list without `allowEmpty`. So the projection is written **after** the
boards write and only when the server reports `applied == true`. A stale replay
and an unjustified empty list are both refused before the legacy line is reached,
and the content written is content the server has just agreed to keep.

Writing it first — which is tempting, because then the boards response carries
the revision that accounts for both writes — would have meant a client with a
momentarily empty database wiping the legacy array while the guarded endpoint
protected the real one.

#### Two writes are two revision bumps, and the stamp has to be the second one

The echo-suppression stamp (`LocalRevStore`) now takes the rev from the LEGACY
response when there was one, because that is the later of the two writes.
Stamping the boards rev would leave the device one revision behind its own write
and cost it exactly the fetch the stamp exists to avoid.

`SduiApiService.syncStations` returned `Boolean` and threw the rev away. It now
returns the same `SyncStateResponse` the boards write does — no wire change, the
endpoint has always sent `{ success, count, rev }`.

The cost that remains is on the OTHER devices: one board change is two `user.sync`
fan-outs, so each of them reads the profile twice instead of once. Board changes
are rare (a few per session, debounced, and gated by `BoardPushGate`), and this
ends when the dual-write does — Q2.

### Handoff notes — S016, 2026-09-11

**Not verified on hardware.** Everything here is reasoned from the two sources
plus unit tests, and the failure it fixes is a timing one: add a board, wait for
a foreground reconcile, see whether it survives. That is the four-minute device
check this needs and did not get:

```
1. Add a board on the Pixel. Confirm it appears.
2. adb shell am force-stop com.stationly.mobile ; reopen (cold start always reconciles)
3. The board is still there, and `D/UserSync: Reconcile complete: N board(s), rev R`
   names the board count — not `N station(s)`, which was the legacy path.
4. adb logcat -s UserSync:D  — a second open within 15 min should say
   "Reconcile skipped — account unchanged (rev gate)".
```

**One behaviour deliberately changed beyond the story.**
`FreshDataNotifier.notifyAll` used to fire on every reconcile; it now fires only
when the reconcile actually read a profile. Every foreground used to reload every
board on the phone to discover that nothing had changed. The screen's own
`ON_RESUME` reload covers the ordinary case.

**What AV2-4.4 inherits:** the topic reconcile added in AV2-4.2 derives its
desired set from `UserSelectionEntity`. That is now correct by construction —
`reconcileBoards` writes those rows from the board list — but it is worth knowing
the two are coupled: a board that exists in the cloud and fails to set up locally
is also a topic this device will not subscribe to.

---

## AV2-4.4 — Sessions and activity · `M` · Review (S016)

**Depends on:** AV2-4.3 **Files:** device registration, WorkManager

### Tasks
- [x] **a.** Device registration and session records. *(Already running, from
      the shared `SummaryViewModel.registerDeviceSession()` plus the login path,
      both carrying the real `DeviceIdentity`. The story named `/device/register`
      and that is the wrong endpoint for Android — see the findings.)*
- [x] **b.** `stateRev` read budget wiring. *(Arrived with AV2-4.3:
      `reconcileBoards` carries the gate, `pushBoards` stamps it, and the FCM
      push's `rev` is now threaded through so a push-triggered reconcile skips
      the `GET /user/state/rev` round trip entirely.)*
- [x] **c.** Activity trail upload on WorkManager. *(`ActivityUploadWorker` —
      nightly, plus the foreground staleness net. Android had NO driver at all;
      see the findings.)*
- [x] **d.** `ActivityEventEntity` must survive `clearAllData()`. *(It already
      did, by omission. Now pinned by a test, because "survives by omission" is
      broken by ADDING a line, which is what the next person adding a table
      does.)*
- [x] **e.** **Write `ACCOUNT_REMOVED_FLAG` from Android.** *(Closed early in
      AV2-4.1 — it lived in the same file as that story's task (f).)*

### Acceptance criteria
- [x] An Android device appears in the device list. *(Code path verified end to
      end; not re-checked on hardware this session — AV2-3.2's device pass
      confirmed the id survives a crash, force-stop, update and sign-out.)*
- [x] Deleting the account from another device returns this one to login **with
      the account-removed notice**, not silently. *(AV2-4.1. Still needs the
      two-device check.)*
- [x] Logging out releases its subscriptions — no ghost sessions. *(`/user/logout`
      carries the device id, and the id can no longer be minted twice — see the
      findings. Topics are released off the ledger now, AV2-4.2.)*
- [x] The activity queue survives a logout and uploads under the next uid.
      *(`ActivityQueueSurvivalTest`, both sides of the session boundary.)*

### Findings

#### Android has never uploaded a single activity event

`ActivityUploader` is shared, complete, and has been since before this branch:
batching, the three-outcome response handling, the queue cap, the staleness
fallback. What it has never had on Android is anything to **call** it. iOS drives
it from `ActivityUploadScheduler` (a `BGProcessingTask` at 03:00 on a charger)
and from `uploadActivityIfStale()` on foreground. Android had neither — so every
event `ActivityLog.record` wrote went into `ActivityEventEntity` and stayed
there, filling to the cap and dropping its oldest rows, on every Android install
since the trail shipped.

Nothing failed. There is no error path for a queue nobody drains.

`ActivityUploadWorker` is the driver: a `PeriodicWorkRequest`, 24 hours with a
six-hour flex window, `CONNECTED` and battery-not-low, first run aimed at 03:00
local. `KEEP` rather than `REPLACE` on a unique name, because `REPLACE` on every
cold start pushes the next run a full period into the future *every time the user
opens the app* — the schedule would exist and never fire.

Not charging-only. iOS requires a charger because its task type does; requiring
one on Android would mean a phone that only ever charges in the morning never
reports at all.

#### The story named an iOS endpoint

Task (a) says "session records against `/device/register`". That route registers
**APNs tokens** — the app's and the widget extension's — onto
`users/{uid}/devices/{deviceId}`, and it exists because iOS addresses a device by
APNs token. Android does not have one. Its push address is the FCM token, posted
by `FcmTokenRegistrar` to `/user/fcm/register`, and its device SESSION record
comes from `syncProfile(deviceId, deviceInfo)`, which the cutover already put on
both the login path and the home screen's first authenticated moment.

So the task was done before it was read, by a route the story does not mention.
Worth writing down rather than silently ticking: the next person to look for
Android's device registration will otherwise go looking at `/device/register`
and find nothing.

#### Two objects could mint the device id, and one of them ran during logout

`DeviceIdProvider` (`:android:app`) and `DeviceIdentity` (`:composeApp`) read the
same preferences file and the same key — `V1V2StorageContractTest` existed
precisely to keep them agreeing — but **both could also generate one**. The
generator is the dangerous half: this id is how the backend tells devices apart
in its `sessions` map, and a station's subscription is released only when the
last device signs out. An id that changes leaves a session no logout can ever
clear, holding subscriptions for a device that does not exist. iOS lost two days
to exactly this.

The surviving caller of the old one was `FirebaseAuthManager.logout`, which runs
while the app is being torn down, with `apply()` where the other uses `commit()`.
`DeviceIdProvider` is deleted; the contract test is now one-sided against the
names on disk, which is the shape it already uses for the notification flag.

#### Android could lose a board change by being swiped away

Board pushes are debounced 2.5 seconds so that ticking four lines at one station
is one write. iOS flushes on scene-phase-leaving-active; Android flushed nowhere,
so a user who added a board and immediately swiped the app away kept it locally
and never told the account — recovering only on their next edit, and until then
their other device simply does not have it.

`MainActivity.onStop` now flushes, on the app-level scope rather than the
Activity's, because the point is to outlive the thing that triggered it. Free
when nothing is pending: `BoardPushGate` makes a flush with an empty gate cost no
request at all, which is what lets it sit on a hook that also fires when the
widget configuration Activity opens in front of the app.

### Handoff notes — S016, 2026-09-11

**What needs a device**, and none of it is reachable from adb alone:
- Two phones, to see the account-removed notice arrive (AV2-4.1's writer, this
  story's criterion).
- `adb shell dumpsys jobscheduler | grep stationly` to confirm the periodic work
  is registered after a cold start. The run itself is overnight; force it with
  `adb shell am broadcast -a "androidx.work.diagnostics.REQUEST_DIAGNOSTICS" -p com.stationly.mobile`
  to dump WorkManager's view, or trust the foreground staleness net.
- The device list on another signed-in client, to see this phone appear.

**The activity trail is now a live wire that has never carried current.** The
first nightly run on a real device will upload a queue that may have been
accumulating for months, in up to three batches of 200. The uploader caps it
(`MAX_BATCHES_PER_FLUSH`), so a long backlog drains over several nights rather
than in one request — that is deliberate, and it is worth knowing before somebody
reads the first night's numbers as "the trail is broken".
