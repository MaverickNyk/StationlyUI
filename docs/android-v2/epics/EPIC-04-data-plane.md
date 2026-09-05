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

## AV2-4.1 — FCM at v2 · `L` · Backlog

**Depends on:** AV2-3.5 **Reads:** [`GAP_ANALYSIS.md`](../analysis/GAP_ANALYSIS.md) §3.4
**Files:** `android/app/src/main/java/com/stationly/mobile/service/FcmMessagingService.kt`

`FcmMessagingService` is 400+ lines written against single-selection
assumptions. `ProcessPredictionsUseCase` already exists in `commonMain` and its
KDoc names this service as its caller.

### Tasks
- [ ] **a.** **Characterize first** — this is `DIVERGENT`. Build a table of
      cases: v1 payload in, SQL writes out. Make it pass against the current
      code before changing anything.
- [ ] **b.** Route ingestion through `ProcessPredictionsUseCase`.
- [ ] **c.** Direction scoping. A station's eastbound and westbound boards are
      independent; `savePredictions` deletes by station+line+direction, and
      without the direction one direction's sync wipes the other's rows.
- [ ] **d.** Per-board fan-out: one `Station_{naptan}` push may feed several
      boards, on several lines, in several directions, with different filters.
- [ ] **e.** `matchesFilter` precomputed at ingest — once per push rather than on
      every recomposition and every one-second countdown tick.

### Acceptance criteria
- [ ] The characterization table still passes, or each change is justified.
- [ ] A push to a station a user tracks twice (two directions) updates both, and
      neither clears the other.
- [ ] A filtered board admits only matching rows, and an empty filter result
      falls back to the unfiltered list with a note rather than an empty card.

### Handoff notes
_(none yet)_

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

### Acceptance criteria
- [ ] An Android device appears in the device list.
- [ ] Logging out releases its subscriptions — no ghost sessions.
- [ ] The activity queue survives a logout and uploads under the next uid.

### Handoff notes
_(none yet)_
