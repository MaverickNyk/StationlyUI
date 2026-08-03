# Multi-line boards, departure filters & station hubs — design + handover

**Date:** 2026-08-02 · **reviewed 2026-08-03** (findings 14–19, §8b, §9 correction)
**Status:** implemented on iOS, compiles on all targets, installed and **running
on device (iPhone 11, Debug Staging) — not QA'd.** No functional testing has been
done: the app launches and stays up, nothing beyond that is verified.
**Session scope:** the **selection flow**. The home screen and the departure
board itself were only touched where the new data model forced it — see §11.

> ### 🚨 THE BACKEND IS STILL UNCOMMITTED, AND STAGING RUNS IT
>
> | Repo | Branch | State |
> |---|---|---|
> | `StationlyUI` | `ios-parity` | ✅ committed 2026-08-03 |
> | `stationly-backend` | `dev_13Jul` | 🚨 `src/controllers/lineController.ts` **modified, uncommitted** |
>
> `.scripts/staging_deploy.sh` rsyncs the **local build**, not a git ref. A fresh
> clone will not reproduce staging, and **the next deploy from a clean checkout
> silently reverts `upcomingStops`** — every via-filter on every device then stops
> resolving. **Commit `lineController.ts`.** The client half is now committed, so
> this is the only remaining way for the feature to silently break.

---

# PART 1 — DESIGN

## 1. The problem

Three problems, solved together because they share a data model.

1. **One board per station.** Saving a board wiped every other. A user could
   track exactly one line, one direction, one station.
2. **No way to narrow a board.** At a junction, half the departures are going the
   wrong way and there was no way to say so.
3. **Bus was quietly wrong.** A bus "station" in our list is a hub; each physical
   pole has its own naptan. We stored the hub for every direction, so an outbound
   board served **inbound** departures.

## 2. Core model — two ids, not one

Everything follows from this.

| Field | Meaning |
|---|---|
| `station` | the naptan we **fetch from** — the resolved pole for this exact `(line, direction)` |
| `parentStationId` | the hub the user picked |
| `groupingId` | `parentStationId.ifBlank { station }` — **always use this to group cards** |

Verified against live staging at Smithwood Close:

```
route 39  inbound  → 490008805N     ← the hub id itself
route 39  outbound → 490012211N     ← a different pole
route 639 inbound  → 490008805N     ← same pole as 39 inbound
```

Two failure modes this avoids:

- Store the **hub** for both directions → outbound board shows inbound
  departures. (This was live.)
- Resolve per direction but group by `station` → one bus stop splits into a card
  per pole, all with the same name. (This is exactly why `resolveStation` was
  disabled during the multi-line tube work.)

**Row identity is `(station, line, direction)`.** Many rows → one naptan (routes
39 and 639 share a pole). Never the reverse. A *card* spans many naptans; a *row*
is always exactly one.

## 3. Filters: intent vs resolution

| Field | Meaning |
|---|---|
| `filterMode` | `ALL` \| `DESTINATIONS` \| `VIA` |
| `viaStationIds` / `viaStationNames` | **intent** — what the user tapped |
| `destinationIds` | **resolution** — the allow-list actually matched |
| `routeResolvedAt` | when the resolution was computed |

Kept apart so a stale allow-list — engineering works, branch closures — can be
recomputed without re-asking the user.

### 3.1 Everything compiles to one allow-list

```
ALL          → empty (no filtering)
DESTINATIONS → exactly the termini ticked (EXACT match)
VIA          → the stop, PLUS every stop beyond it, on every branch reaching it
```

### 3.2 Why VIA stores the downstream closure

**The most important decision in the feature.** TfL turns trains short
constantly — Northfields, Acton Town, Rayners Lane, Arnos Grove. A terminus-only
allow-list (`{Heathrow, Uxbridge}`) would **hide** a Piccadilly train showing
"Northfields", even though it calls at Green Park.

Anything beyond the via stop can only be reached *through* it, so membership of
the downstream set literally answers "does this train pass my stop".

Verified live — Piccadilly southbound @ King's Cross, via Green Park:

| Probe | Result |
|---|---|
| Acton Town / Northfields / Rayners Lane | **MATCH** — short-terminating |
| Heathrow T5 / Uxbridge | MATCH |
| Holborn | excluded (terminates before) |
| Cockfosters | excluded (opposite direction) |

District @ Earl's Court via Turnham Green: Wimbledon **excluded** (branch
diverges first); Richmond / Ealing Broadway / Chiswick Park match.

### 3.3 Match on id, never on name

Route sequence says `"Hammersmith (Dist&Picc Line)"`; a prediction says
`"Hammersmith"`. Also `Heathrow Terminal 5` vs `Heathrow T5`. Name matching fails
precisely on the short-terminating services this exists to catch — hence the
backend change (§6).

### 3.4 Filter at WRITE time, never at read time

Rows are written **once per stream frame** but read on every recomposition, every
one-second countdown tick, every widget rebuild, every DREAM refresh.

So `SyncPredictionsUseCase` materialises the id set once per frame and persists a
**boolean per row** (`matchesFilter`). Reads filter and sort in SQL:

```sql
WHERE stationId=? AND lineId=? AND direction=? AND matchesFilter = 1
ORDER BY targetEpochMs IS NULL, targetEpochMs
```

`NULLS LAST` matters — a row whose ISO timestamp failed to parse would otherwise
sort to the head of the board.

**The property worth preserving:** any future filter can be arbitrarily expensive
to *resolve* and still cost rendering nothing, because the storage contract is
one boolean.

### 3.5 Fail open, three ways

1. Empty allow-list = no filter.
2. Unknown/blank `destId` = **shown** (`Check Front of Train`, depot moves).
3. Filter matches nothing → falls back to the unfiltered list, captioned
   `NO MATCHES · SHOWING ALL`.

Excluded rows are **persisted, not dropped** — they are what the fallback shows
and what lets `reapplyFilter` apply a change offline. The per-platform cap of 8
is applied to matching and excluded rows **separately**: one shared budget would
let eight Uxbridge departures fill it and render a Heathrow-filtered board empty.

## 4. Subscriptions & teardown

**Subscribe the DISTINCT resolved pole**, not per board. At Smithwood Close 39
inbound and 639 inbound share `490008805N` — one topic, one FCM wake, two boards
updated. `completeSetupAsync(selection, subscribeTopics = false)` lets a batch
caller subscribe once.

**Teardown refcounts on `station` (the pole), never on the hub:**

```kotlin
if (remaining.none { it.station == selection.station }) add("Station_${selection.station}")
```

Refcount on the hub and deleting one route silences every other route there.
`remaining` must come from the **repository**, not a VM mirror, and deletes must
be **sequential** — concurrent deletes each see the other as "still using" a
topic and leak it.

**Staggered arrivals are already safe.** Writes are keyed `(station, line,
direction)`, each section owns a `SyncStatusEntity` stamp, the card footer takes
`sections.maxOf { lastUpdated }` (`Board.kt:289`). **Do not build message
coalescing** — latency and backend coupling for a problem that doesn't exist.

## 5. Route tree

`core/util/RouteTree.kt` groups destination runs by shared prefix, recursively.

The earlier model took **one** common prefix across every terminus. On Piccadilly
southbound from King's Cross, Uxbridge leaves at Acton Town, so the trunk stopped
there and Heathrow T4/T5 rendered as two full-length parallel branches
duplicating ~9 shared stops.

```
root  15 stops  Russell Square … Acton Town
  ├─ Uxbridge            14 stops
  └─ branch               8 stops  South Ealing … Hatton Cross
       ├─ Heathrow T5      2 stops
       └─ Heathrow T4      1 stop
```

**Network-wide: 20 of 21 line/directions have zero duplicated stops.**

| | |
|---|---|
| No split | victoria, circle, jubilee, bakerloo, hammersmith-city, waterloo-city |
| Splits cleanly | central (2), northern inbound (2), piccadilly (3, depth 2), district inbound (4, depth 2), metropolitan inbound (4, **depth 3**) |
| **Duplicates** | **northern outbound** |

**Northern outbound is a known limitation.** Bank and Charing Cross **rejoin** at
Camden Town — a DAG, not a tree. Duplicated: Euston, Camden Town, Kentish Town,
Archway, Highgate, East Finchley, Finchley Central, Tufnell Park. **Filtering is
unaffected** (`BoardFilterResolver` walks raw runs, not the tree). Display only.

`elizabeth` / `dlr` were **not successfully tested** — the assessment used tube
naptans that don't serve them.

## 6. Backend change (deployed to staging, uncommitted)

`src/controllers/lineController.ts`,
`GET /api/v1/lines/{lineId}/route?station={naptan}&mode={mode}`.

Added `upcomingStops: [{id, name}]` on destinations and on the direction trunk,
plus `originStationId`. `upcomingStations` unchanged.

- `upcomingStops` is **index-aligned** with `upcomingStations` by construction:
  `stopsFor()` is the primitive, `namesFor()` derives from it. Never build them
  separately — unresolvable stops are dropped, so parallel construction
  misaligns them by one at each.
- `commonPrefix()` compares on **naptan id**, not display name.

**Why additive rather than `/v2`:** every JSON parser in `StationlyUI` sets
`ignoreUnknownKeys = true`, and `NetworkModule.json` has had it **since the
commit that created the file** (`36e80c5`). No shipped build on either platform
can choke on a new key. `/v2` is only needed for a type change, rename or removal.

## 7. Mode-awareness (bus)

Buses have no compass direction. The backend sends `directionName` as the literal
word **`"Towards"`** with `label` = `"Towards Gordon Cottages"`, so rendering
`directionName` alone produced a card titled "Towards" above "towards Gordon
Cottages".

**Rule:** when `directionName` is blank or the bare word "Towards", title from
`label` and suppress the duplicate second line.

Hardcoded `"trains"` was found in **three** places. Anything user-facing must
derive from `vehicleNounPlural(mode)` / `stopNounSingular(mode)` (now `internal`
in `SelectionScreen.kt`).

---

# PART 2 — CODE REVIEW FINDINGS

A full re-read of the diff produced these. All fixed unless stated.

### Fixed in this pass

1. **Filter edits were silently discarded.** `saveSelection` re-ran
   `buildSelection` for *unchanged* rows, which re-called `resolveStation`.
   `updateSelectionInPlace` matches on `station`, so any different result — a
   real route change, or the hub fallback after a network failure — missed the
   match and dropped the edit with no error. Now passes `knownStation =
   existing.station` and skips the resolve. Also removes one network round trip
   per untouched board per save.
2. **The topic "dedupe" was additive.** I subscribed the distinct set and then
   `completeSetupAsync` re-subscribed per board — a net *increase*. Added
   `subscribeTopics: Boolean = true`.
3. **Terminus chip on a stopless leaf.** A destination whose whole run is a
   prefix of its siblings' becomes a leaf with no stops; its chip rendered a
   column early and could never be tapped. Guarded.
4. **Second restore path missed `parentStationId`** (`UserSyncRepository.kt:51`),
   so a full profile restore regrouped bus boards per pole.

### Fixed earlier in the session

5. **Widget sorted by the formatted ETA string** — `"1 min" < "10 min" < "2 min"
   < "Due"` then `take(3)`. Wrong three trains, wrong order. **Pre-existing; also
   affected Android.**
6. **Widget filtering had no fail-open** — would have blanked silently.
7. **`destId` was dropped** between payload and storage; nothing could match.
8. **Repo-init race** — prefill read an empty mirror, then the save diffed
   against an empty baseline and re-added existing rows. `repoReady` join added.
9. **`deleteStation` matched the wrong key** — `SummaryScreen` passes the group
   key, the filter used `station`. On bus that orphaned one direction: still
   streaming, no card.
10. **Cloud restore dropped a direction** — keyed `id|line` with no direction, so
    two directions on one naptan collided.
11. **Two teardown paths ignored survivors** (`ProfileViewModel`, cloud restore),
    silencing boards that shared a topic.
12. **Accordion discarded work** — tapping a collapsed line unticked it, losing
    its directions and filter.
13. Dead code removed: `availableViaStops`, `setMatchesFilter`,
    `isFilterHidingAll`.

### Fixed in the review pass (2026-08-03)

A second full read of the diff against this document. Everything below is
applied; `:core`, `:composeApp` (iosSimulatorArm64) and `:android:app`
(stagingDebug) all compile.

14. **`deleteSelection` never matched.** `SqlStorage.deleteSelection` normalised
    `direction` into a local and then passed the **raw** value to the query,
    while `saveSelection` stores `direction.lowercase()`. Any caller with a
    non-lowercase direction deleted the board's predictions and left the
    selection row behind — a card that returns on next launch with no data.
    Latent today only because every direction id happens to arrive lowercase.
15. **`RouteGraph` was not actually deleted** — item 13 claimed it, but
    `core/util/RouteGraph.kt` was still in the tree, unreferenced (99 lines,
    superseded by `RouteTree`). Removed.
16. **The widget's filter did not fail open on a blank `destId`.**
    `FormatDeparturesUseCase.filterByDestinations` used a plain `in` test, so
    `Check Front of Train` and depot moves were dropped on the widget while the
    board still showed them (`SqlStorage.matchesFilter` fails open on blank).
    Now calls the shared helper, so the two cannot disagree.
17. **`saveSelection` carried a comment saying the opposite of the code.** It
    still read "NOTE: deliberately NOT calling `sduiService.resolveStation`"
    with a rationale for storing the picked hub as `station` — that was true
    before hubs and is now exactly wrong (`buildSelection` resolves, and the
    two-id split is what makes resolving safe). §10 tells the Android port to
    copy `buildSelection` exactly, so this comment was on a direct path to
    reintroducing the inbound-on-the-outbound-board bug.
18. `clearPredictionsForLine` documented a caller it does not have — it is
    unused, kept for the Android port. Comment corrected rather than the query
    removed.
19. **Search scrolled the route map to the wrong place.** `ViaStopPicker`
    recomputes the picker's column geometry to scroll a search hit into view,
    and its comment says keeping both off the shared `ROUTE_STOP_W` /
    `ROUTE_ORIGIN_W` constants is what stops them drifting — but it omitted
    `ROUTE_EDGE_GUTTER`, which lives INSIDE the scrolling content, so every hit
    landed a gutter's width off. The constant is now `internal` and documented
    as part of the coordinate system rather than as padding.
20. **The multi-line delete dialog could not scroll.** Material3 does not scroll
    the `text` slot, and at the 8-row cap the dialog stacks eight removable
    lines plus header and footnote — the last line clipped off an iPhone 11,
    and a line you cannot see is a line you cannot remove.
21. Orphaned KDoc in `Board.kt` (the `filterTag` block had drifted onto
    `isShowingUnfilteredFallback`); unused `val vehicle` in `BoardFilterSheet`;
    `gradle.properties` cache note rewritten from "disabled to test build
    times" into the measured decision it now records.

### UI change requested during review

- **Route map endpoints are solid discs.** The origin ("You're here") and the
  last stop of every leaf are drawn filled rather than as hollow rings, so the
  two caps of the route read as endpoints and intermediate stops read as
  pass-throughs. The last stop of an *internal* node stays a ring — the line
  carries on through it. `RouteGraphPicker.StopNode(isEndpoint = …)`.

### Known-and-accepted

- **`resolveStation` returns a single id.** If a route ever serves two poles at
  one hub in one direction, the signature can't express it and we'd silently get
  half the departures. **Confirm with the backend.**
- **`buildSelection` resolves sequentially** — 8 new rows is 8 round trips before
  anything persists. Acceptable behind the saving overlay; parallelise with
  `async`/`awaitAll` if it becomes noticeable.
- **Northern outbound** duplication (§5).
- **Stop NAMES are stored comma-separated.** `viaStationName` (and the
  pre-existing `destinations`) are `joinToString(",")` into one TEXT column, so
  a stop name containing a comma splits into two entries on read and
  **misaligns `viaStationNames` from `viaStationIds`** — the index-alignment §3
  relies on. Display-only: ids carry no commas, so filtering is unaffected; the
  damage is a wrong or truncated name in the `VIA X` board tag and in the
  restored sheet chip. TfL `commonName`s rarely contain commas, which is why
  this is accepted rather than fixed — but it is a real corruption, and JSON in
  those columns is the fix if a report ever comes in.

---

# PART 3 — ANDROID

## 8. What Android has today

Android depends on `:core` (`android/app/build.gradle.kts:111`) but its selection
UI is entirely separate: `android/app/src/main/java/com/stationly/mobile/ui/selection/`.
It is **single line, single direction, single board**, and does not have filters,
the accordion, the route map, or hubs.

**Android already gets, for free:** all schema columns (inert), the
`SyncPredictionsUseCase` filter step (a no-op with an empty allow-list), the
widget ETA sort fix, and the `UserSyncRepository` direction-key fix.

### 8b. 🚨 Android's home screen collides on multi-board data

`android/.../ui/summary/SummaryViewModel.kt` keys its state maps on
`selection.station` alone:

```kotlin
currentMap[selection.station] = dbPreds
```

`composeApp` moved to `station_line_direction` for exactly this reason. Android
did not, because it is single-board — **but it does not have to be multi-board
to hit this.** `UserSyncRepository.syncUserAndGetSavedStations` restores *every*
cloud station into local SQL at login, so an account with iOS multi-line boards
puts several selections on one Android device the moment the user signs in.
Two directions at one station then land on the same key and the second one
loaded silently overwrites the first's departures, SDUI payload and timestamp.

Reachable **as soon as iOS ships**, without anyone porting anything. Fix with
the same `boardKey(station, line, direction)` helper `composeApp` uses — note
`SummaryScreen` reads these maps too, so both change together.

## 9. 🚨 Cross-platform data loss — STILL OPEN, and the fix below was wrong

`android/.../ui/selection/SelectionViewModel.kt`:

```kotlin
sduiService.syncStations(user.uid, listOf(SubscribedStation(...)))   // ONE element
```

`syncStations` is a **full REPLACE**. On a shared account, **saving one board on
Android wipes every other board from the cloud**, including all iOS multi-line
boards. The next iOS re-login restores only that one.

Harmless while both platforms were single-board. iOS is not any more.

### ⚠️ "Post the full list" is necessary but NOT sufficient

The earlier version of this section prescribed posting the full list. That is
half a fix, and shipping it would have looked like a whole one. **Six lines
earlier, Android's save calls `stationLifecycleUseCase.cleanupAll()`**, which is
`selectionRepository.clearAll()` + `sqlStorage.clearAllData()` +
`storageManager.clearAll()`. By the time the sync runs there IS no other board
on the device — so "the full list" is still one element and the cloud is still
wiped.

Android's save is **destructive by design**: one board, and saving replaces it.
That is the actual root cause, and no change confined to the sync call can fix
it.

**What has been done** (2026-08-03): the call now posts
`Platform.sqlStorage.getAllSelections()` with `parentStationId`, rather than a
hand-built single element. Strictly better, and it is the shape the port needs
— but it does **not** close this bug.

**What is still required, in order:**

1. Android's save must stop calling `cleanupAll()` and become additive (§10.2).
   Until then, one Android save destroys every iOS board on the account.
2. Only then does posting the full list mean anything.

Alternatively, make `syncStations` additive server-side, which fixes it for both
platforms at once and is the smaller change — **that is probably the right call
given Android's UI port is not scheduled.**

**Until one of those lands, do not sign into a shared account on both platforms.**

## 10. Porting the flow to Android

The hard parts are already shared. `:core` holds `RouteTree`,
`BoardFilterResolver`, the schema, `SqlStorage`, `StationLifecycleUseCase` and
`UserSyncRepository`. **Android needs no new business logic** — only UI and a
ViewModel.

Order to do it in:

1. **Fix §9 first.** Everything else is pointless while Android can erase the
   cloud.
2. **ViewModel state.** Mirror `composeApp`'s `SelectionViewModel`:
   `_linePicks: Map<String, Set<String>>`, `_existingPicks` (baseline for the
   diff), `_boardFilters`, `_expandedLine`, `repoReady`. The diffing `saveSelection`
   — added / removed / unchanged — ports as-is; it is pure Kotlin over `:core`.
   **Copy `buildSelection` exactly**, including `knownStation`.
3. **Selection UI.** `SelectionScreen.kt` is Compose already, so the accordion,
   `DirChoiceCard`, `BoardFilterSheet` and `RouteGraphPicker` are close to
   copy-paste. They depend only on Compose + `:core`. Watch: `performHaptic` and
   `LocalSoftwareKeyboardController` are platform-shimmed in `composeApp`.
4. **Board rendering.** Android's summary must group by `groupingId` and render a
   section per row. This is the biggest genuinely new piece — Android's board
   assumes one selection.
5. **Widget & DREAM.** Both read `sqlStorage.getPredictions`, which is already
   filtered and ordered, so they inherit filters with no change. Verify the
   Android widget's primary-board choice with several boards.

**Rather than porting twice, consider moving Android onto `composeApp`.** The two
selection screens are already near-identical Compose; the divergence is the cost
being paid here. Out of scope for this session, but it is the decision that would
stop this recurring.

---

## 11. ⚠️ NOT REVIEWED THIS SESSION

**This session was the selection flow.** The following were touched only where
the data model forced it, and have **not** had a design pass:

- **Home screen / `SummaryScreen`** — changed only to group by `groupingId`.
  Card ordering, the hero row across several lines, empty and error states, and
  the delete dialog with many sections are all unreviewed.
- **The departure board (`Board.kt`)** — changed only to add filter tags and the
  fail-open caption. Section density, scrolling with 8 sections, per-section
  status strips, and the dot-matrix layout at that size are unreviewed.
- **Widget & DREAM** — inherit filtering via `getPredictions` but were not
  exercised with a multi-line card.

## 12. Not done / verify first

1. **No QA on device.** Compilation and API-level verification only. No test
   suite was run — coverage was not surveyed.
2. **🚨 Android still wipes cloud boards** — §9. Highest priority, and **still
   open**: the previously-prescribed fix (post the full list) has been applied
   and does not close it, because `cleanupAll()` empties the device first.
   Making `syncStations` additive server-side is the smaller real fix.
2b. **🚨 Android's home screen collides on multi-board data** — §8b. Triggered by
   an iOS user signing into Android, not by any Android port.
3. **Filters do NOT survive a cloud round-trip.** `SubscribedStation` carries no
   filter fields and both restore paths set `destinationIds = emptyList()`, so a
   logout/login **silently drops every filter**. Fails open, so it is not
   dangerous — but it is silent loss of user configuration. Needs fields on
   `SubscribedStation` **and** backend persistence.
4. **Backend must persist `parentStationId`** — until it does, a restore regroups
   bus boards per pole.
5. **The 24h dropdown cache hides the via filter.** Payloads cached before the
   backend change have no `upcomingStops`, so the option renders disabled.
   **Most likely thing to make the feature look broken.**
6. **Keyboard behaviour not verified on device.**
7. **Typography pass not started.**
8. **Board-save failures are silent** — `uiState.error` is only rendered by the
   backend-offline overlay.
9. **`UserSelection.direction` stores the option id** (`"inbound"`), so the board
   reads `PICCADILLY · INBOUND`, not `SOUTHBOUND`. Needs another column.
10. **Bus search is unusable** — "Oxford Circus" returns six near-identical
    entries with only `id` and `label`; no indicator, no routes served.
11. **Northern outbound duplication**; **`elizabeth`/`dlr` untested** — §5.

---

## 13. Build & deploy

`iosApp/project.yml` links a **prebuilt XCFramework** and there is **no Gradle
build phase in the Xcode project.** `xcodebuild` will link a *stale* framework and
report BUILD SUCCEEDED, so Kotlin changes silently never reach the device.

```bash
./gradlew :composeApp:assembleComposeAppDebugXCFramework   # MANDATORY

cd iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration "Debug Staging" \
  -destination 'platform=iOS,id=AB7B04C8-F9D6-5C05-8388-5767BC96C059' \
  -derivedDataPath build/DD -allowProvisioningUpdates build

xcrun devicectl device uninstall app --device <UDID> com.stationly.mobile   # schema is breaking
xcrun devicectl device install app --device <UDID> \
  "build/DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device process launch --device <UDID> --terminate-existing com.stationly.mobile

# Verify — the widget extension keeps running even when the app has died
xcrun devicectl device info processes --device <UDID> | grep "iosApp.app/iosApp"
```

- Configurations are `Debug Staging` / `Debug Production` / `Release *`. There is
  **no plain `Debug`** — it fails with a misleading `FirebaseAuth` module error.
- A locked phone fails install with `BSErrorCodeDescription = Locked`.
- **The schema is breaking and there is no `migrations/` directory**
  (`core/build.gradle.kts:104`). Installing over an existing install **crashes**.
  Always uninstall first. If the app has shipped by the time you read this, add
  `N.sqm` files.

### Build times — `kotlin.native.cacheKind`

`gradle.properties` had `kotlin.native.cacheKind=none`, added incidentally in
`8a20501` (an env-variants commit that never mentions caching). It disabled the
Kotlin/Native compilation cache, recompiling all of Compose on every link.

| | before | after |
|---|---|---|
| device link, incremental | 180s | **58s** |
| XCFramework, incremental | ~360–420s | **70–100s** |

**The first build after enabling is slower** — 502s to build the arm64 cache,
then 519s while the simulator cache was still cold. Only the third build shows
the real number. Wiping `~/.konan` or `build/` pays it again.

---

## 14. Decisions worth not re-litigating

- **Single-section cards render exactly as before.** Extra chrome appears only
  when `sections.size > 1`.
- **8-row cap per station.** Technical, not monetisation: each row is a stream
  subscription plus a card section that must stay readable on an iPhone 11.
  Surfaced via the summary bar because `uiState.error` is invisible here.
- **The line step is an accordion.** Collapsed lines summarise their own picks so
  the user can see their answers without reopening each.
- **`DESTINATIONS` is exact; `VIA` is the downstream closure.** Different
  questions — do not unify.
- **One pick per branch, multi-pick across branches.** Two stops on one branch is
  meaningless (anything reaching C already passed B). Same-branch is tested by
  containment of reachable-terminus sets, which nest along a path and are
  disjoint across a split.
- **Search focuses, it does not select.** Finding and choosing are separate.
- **The map's gutters scroll with the content**, so it is inset at rest but runs
  off both edges when dragged.
- **Never call `cleanupAll()` / `storageManager.clearAll()` from a board-editing
  path on iOS.** It is `removePersistentDomainForName(bundleId)` and wipes
  `firebase_user_uid` / `firebase_auth_token`, silently breaking every auth-gated
  call. `LoginViewModel` carries the same warning.
