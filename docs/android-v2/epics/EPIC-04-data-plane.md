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

#### DEFECT NOT FIXED — the prediction primary key collapses two real trains

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

**Not fixed here, and this needs the owner.** The fix is `targetEpochMs` in the
key instead of `eta`, which is a schema change in a `.sq` shared with a build
going to TestFlight, in a story that does not own the schema, landing on top of
**Q5** — the open question about the migration already pending. The migration
itself is cheap and low-risk: `1.sqm` already clears `PredictionEntity`, because
cached departures are replaced within seconds of the next push, so `2.sqm` can
simply rebuild the table. Raised as **Q7**.

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

## AV2-4.2 — Topic lifecycle · `L` · Backlog

**Depends on:** AV2-4.1 **Files:** `StationLifecycleUseCase` callers, `FirebaseAuthManager`, `AndroidNotificationManager`

Topic shapes are **unchanged** between v1 and v2 — `Station_{naptan}` and
`LineStatus_{mode}_{line}` — so there is no rename to migrate. Two things do
change: who emits them, and how many.

### Tasks
- [ ] **a.** Move subscription onto `StationLifecycleUseCase` (`commonMain`).
- [ ] **b.** Delete v1's parallel paths in `FirebaseAuthManager` and
      `SelectionViewModel`. Two systems subscribing to the same topics is how a
      topic gets unsubscribed out from under a board that still needs it.
- [ ] **c.** Diff against the persisted `StationlyPrefs.fcm_topics` set and
      subscribe only the difference. Risk R6: on upgrade day every device would
      otherwise re-subscribe its entire set at once.
- [ ] **d.** Keep `stationly_all` and its `subscribed_all_topic` guard. It is how
      `audience: {type:"all"}` pushes fan out with zero Firestore reads.

### Acceptance criteria
- [ ] Topics are emitted from exactly one place.
- [ ] Removing one board of two at a shared station does **not** unsubscribe the
      station topic the other still needs.
- [ ] Upgrade is a diff, not a flood.

### Handoff notes
_(none yet)_

---

## AV2-4.3 — Cloud state dual-write · `L` · Backlog

**Depends on:** AV2-4.2 **Reads:** [`MIGRATION.md`](../analysis/MIGRATION.md) §2 · **Risk R2, and R2 is proven**

`syncStations` is a **full replace**: it diffs old ids against new to inc/dec
subscription counts, so a one-element post deletes every other board on the
account. This already happened — it is why `SelectionViewModel` on this branch
reads the full list out of SQLite before posting.

### Tasks
- [ ] **a.** **Verify on the backend** that `syncStations` does not touch
      `boards` and `syncBoards` does not touch `stations`. The whole plan rests
      on it and it has not been read. If it is false, stop and re-plan.
- [ ] **b.** Adopt `/user/sync/boards` as the authoritative write.
- [ ] **c.** **Dual-write** the same list flattened to `SubscribedStation` via
      `/user/sync/stations`, for the transition window. `Board.toSelections()`
      already produces exactly that shape (AV2-1.2 proves the fold is total).
      A v1 device on a shared account then sees a degraded-but-correct view
      instead of a stale or emptied one.
- [ ] **d.** First-run fold-up: `boards` wins if non-empty; else fold `stations`;
      else fall back to the local `UserSelectionEntity` rows the migration
      preserved. Rely on `Board.isUsable` — it already makes an empty board list
      read as ABSENT rather than as "delete everything".

### Acceptance criteria
- [ ] A v1 save on a shared account no longer prunes v2 boards.
- [ ] A user who has never signed in keeps their boards through the upgrade.
- [ ] The dual-write has a documented retirement condition (Q2), not an open end.

### Handoff notes
_(none yet)_

---

## AV2-4.4 — Sessions and activity · `M` · Backlog

**Depends on:** AV2-4.3 **Files:** device registration, WorkManager

### Tasks
- [ ] **a.** Device registration and session records against `/device/register`.
      Needs the real `DeviceIdentity` from AV2-3.2.
- [ ] **b.** `stateRev` read budget wiring — observed rev 0 means FETCH, and
      there are five bump sites, not three.
- [ ] **c.** Activity trail upload on WorkManager. `work-runtime-ktx` is already
      a dependency. iOS drives this from `ActivityUploadScheduler.swift`.
- [ ] **d.** `ActivityEventEntity` must survive `clearAllData()` — the events
      worth having most are the ones around an auth change, and a queue emptied
      by the event it is recording can never report it. `clearAllData` names its
      tables explicitly; keep it that way.
- [ ] **e.** **Write `ACCOUNT_REMOVED_FLAG` from Android.** The shared UI has the
      entire receiving end already — `UserStateSync.ACCOUNT_REMOVED_FLAG`, read
      by `LoginViewModel`, rendered by `LoginScreen` — and the only writer is
      `iosMain`'s `UserSyncBridge`. Android's writer was v1's
      `UserSyncCoordinator`, into storage that only v1's deleted `AppNavigation`
      read, so AV2-3.5 left Android silently without it. Today a user whose
      account is deleted from another device is returned to the login screen
      with no explanation at all. The flag is durable and common; this is a
      writer, not a feature.

### Acceptance criteria
- [ ] An Android device appears in the device list.
- [ ] Deleting the account from another device returns this one to login **with
      the account-removed notice**, not silently.
- [ ] Logging out releases its subscriptions — no ghost sessions.
- [ ] The activity queue survives a logout and uploads under the next uid.

### Handoff notes
_(none yet)_
