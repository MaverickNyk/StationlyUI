# Route branches and rejoins — the "Through" map and filter

**Status (2026-08-19): Phase A and Phase B both in the working tree and on the
test phone. Backend deployed to staging.** Read §1 and §6 and you have enough to
resume. What is left is listed in §5.

Full analysis with diagrams and measurements:
https://claude.ai/code/artifact/37078557-ce26-4722-8d2b-9a975de79fc4

---

## 1. The problem in six lines

Tube lines split **and rejoin**. The Northern line divides at Camden Town into a
Charing Cross branch and a Bank branch and merges again at Kennington. Both run
to Morden, so both report `destinationNaptanId = 940GZZLUMDN`.

Two consequences, both live before this change:

1. **The backend deleted a branch.** Destination chips were keyed on terminus and
   same-terminus runs collapsed to the longest (`matching[0]`), so "Morden via
   Charing Cross" never reached the app. On the Central line the deleted run was
   the Woodford arm of the Hainault loop, which made **Chigwell, Grange Hill and
   Roding Valley unfindable on the map entirely**.
2. **The filter could not decide.** "Trains through X" resolved to a set of
   destination naptan ids and matched `destId` against it. After a rejoin the
   same destination is reachable both through X and around it, so a "via Bank"
   board showed Charing Cross trains and a "via Charing Cross" board hid half the
   Morden service.

**Scale (measured, all 20 rail lines + trams + buses, 1,726 origin/direction
pairs):** 86 wrong, 77 with a deleted branch, 31 drawing a station twice. Only
Northern (35), Central (30), Metropolitan (12) and Circle (9) are affected.
Buses, trams, DLR, Elizabeth, Overground, District and Piccadilly are clean.

---

## 2. The design decision

**The map and the filter stop being the same object.**

| | built from | answers |
|---|---|---|
| **Topology** (drawing) | the pattern list | where the lines go |
| **Service patterns** (filtering) | the pattern list | which trains go which way |

Both derive from one source so they cannot disagree.

Two facts that make it work, both verified against live TfL data:

- **`towards` carries the branch.** `"Morden via Bank"` / `"Morden via CX"`,
  `"Hainault via Newbury Park"` / `"Grange Hill via Woodford"`. We already read
  this string and already print it on the board.
- **It only helps on TWO lines: Northern and Central.** The Metropolitan rejoins
  but TfL publishes no discriminator for its four Aldgate patterns (they differ
  only by Willesden Green), and the **Circle does not rejoin at all** — it is a
  spiral that calls at Edgware Road twice on one journey, which is a different
  problem. An earlier draft of this doc claimed four lines; it is two.
- **The graph needs no extra source.** `stopPointSequences[].prevBranchIds`
  states the rejoin outright, but once patterns stop being deleted the DAG falls
  out of the pattern list itself (share prefixes forward, tails backward).
  Deriving it from the patterns is safer — a separately-built graph could
  contradict the filter.

---

## 3. Hard constraints — read before editing

- **`android/app` depends on `:core`.** Android does NOT use `RouteTree`,
  `BoardFilterResolver`, `matchesFilter`, `BoardFilter` or `FilterMode`. It DOES
  use `UserSelection`, `SduiDropdownOption` and `destinationIds`. Everything in
  `core` must stay **additive with defaults**. Do not touch `android/`.
- **The `destinations[]` array in the route payload must stay byte-identical.**
  Android renders chips from it. `patterns[]` is additive beside it. There is a
  regression check for this in §6.
- **SQLDelight has no `verifyMigrations`.** `.sq` and `migrations/N.sqm` are kept
  identical BY HAND. Change one, change the other, same commit. Schema is now
  **v5** (`4.sqm` is the newest). Verify with the arity check in §6.
- **Fail open, always.** A null `viaKey`, an empty `allowedViaKeys`, an empty
  allow-list or a blank `destId` all mean "show the train". Showing an extra
  train costs a glance; hiding the needed one costs the journey.

---

## 4. Phase A — DONE (correctness). Files and what changed

### Backend (`stationly-backend`)

| File | Change |
|---|---|
| `src/utils/viaKey.ts` | **NEW.** `viaKeyOf` / `viaLabelOf` / `canonicalToken`. One module both sides use, so a route tagged `charingcross` and a prediction tagged `charing-cross` can never silently fail to match. Alias table has exactly one entry: `cx → charingcross`. |
| `src/controllers/lineController.ts` | `fetchSequences` now also returns `sequenceVias` (the "via X" half of each `orderedLineRoutes[].name`). New `RoutePattern` interface. New `patterns[]` built from **every distinct run**, deduped on the run not the terminus, emitted alongside the untouched `destinations[]`. |
| `src/utils/routeEncoding.ts` | `sequenceViasJson` as a **separate** Firestore field — folding it into `sequencesJson` would hand a mid-deploy older instance the wrong shape and silently disable station filtering. |
| `src/services/predictionCache/PredictionCache.ts` | **Stamps `viaKey` on every departure, at the single write-through.** Must stay here: there are TWO producers, and the Java Syncer's payloads are stored verbatim, so deriving it in a `PredictionSource` covered only ours — the field was present on unsubscribed stations and absent on subscribed ones. |
| `src/models/index.ts` | `PredictionItem.viaKey?: string`. **Omitted, not null**, when there is no branch: only Northern and Central ever produce a value, so a null would ride on every departure of every other line on every stream frame. |
| `docs/BRANCH_VIA_KEYS.md` | **NEW.** The whole `viaKey` contract in one place. |

Two non-obvious rules in the pattern builder, both found by testing against real
data — **do not remove them**:

1. **Labelled wins on dedup.** From Liverpool Street, `"Ealing Broadway ↔
   Hainault"` and `"West Ruislip ↔ Hainault via Newbury Park"` are the same run.
   Taking the first drops the discriminator, and an unlabelled Newbury Park
   pattern lets a "Hainault via Woodford" train match a filter on Gants Hill.
2. **The via must name a stop ON the run.** TfL names whole end-to-end routes, so
   a run starting north of the split inherits a via describing track behind you —
   northbound from Camden Town every pattern read "Edgware via Bank". Requiring
   the via to match a stop in the run is exact and self-validating.

### Core (`core/`) — all additive

| File | Change |
|---|---|
| `model/Models.kt` | `PredictionItem.viaKey`, `PredictionDisplay.viaKey`, `UserSelection.viaKeys` — all defaulted. |
| `model/sdui/SduiAppModels.kt` | **New** `SduiRoutePattern`; `SduiDropdownOption.patterns`. |
| `model/user/Board.kt` | `BoardFilter.viaKeys` + both conversion sites. |
| `util/RouteTree.kt` | `from()` prefers `patterns`, falls back to `destinations`. Leaves carry `patternId` + `viaKey`. `terminiFrom` → **`patternsFrom`** (terminus is no longer unique). New `viaKeysFrom`. |
| `util/BoardFilterResolver.kt` | `Resolution.viaKeys`. `resolveVia` walks patterns, collects tokens only from runs that REACH the stop, and returns empty when any chosen stop is served by every pattern (narrows nothing → don't risk hiding). |
| `repository/SqlStorage.kt` | `matchesFilter(destId, allowed, viaKey = null, allowedViaKeys = emptySet())`. Persists/reads `viaKey` on predictions and `viaKeys` on selections. `reapplyFilter` takes the tokens. |
| `usecase/SyncPredictionsUseCase.kt`, `FormatDeparturesUseCase.kt`, `StationLifecycleUseCase.kt` | Pass the tokens through. |
| `sqldelight/.../StationlyDatabase.sq` + `migrations/2.sqm` | `UserSelectionEntity.viaKeys TEXT NOT NULL DEFAULT ''`, `PredictionEntity.viaKey TEXT`. **`PredictionEntity.viaKey` is load-bearing**: `reapplyFilter` re-evaluates rows already on disk (narrowing a board does not re-fetch), so without it the new filter re-admits exactly the trains it exists to exclude. |
| `commonTest/kotlin/util/BranchFilterTest.kt` | **NEW.** 9 tests on the real Camden Town shape. All pass. |

### composeApp

| File | Change |
|---|---|
| `ui/selection/SelectionViewModel.kt` | `terminiFrom` → `patternsFrom`; saves `viaKeys = resolution.viaKeys`. |

### Build state

`:core` compiles for iOS + Android · `:core:testDebugUnitTest` green ·
`:composeApp:compileKotlinIosArm64` green · `:android:app:compileStagingDebugKotlin`
green · backend `npx tsc --noEmit` clean.

**Pre-existing and unrelated:** `:core:compileTestKotlinIosSimulatorArm64` fails —
Kotlin/Native rejects commas in test names in `BoardTest`, `BoardTickerTest`,
`MultiLineBoardProcessorTest`. Use `:core:testDebugUnitTest`. Don't add commas.

---

## 5. Phase B — DONE, plus what is still open

**Done since:** `RouteTree` is deleted. The map is built from `RouteGraph`
(segments, longest-path columns, greedy row packing), so a merge draws as a
merge. Selection was rebuilt around a consistent three-marker vocabulary, and a
terminus chip now stores a PATTERN id rather than a stop id.

### The selection model (this is the part that was confusing users)

A chip says "All Morden via Bank trains", which is about WHERE A TRAIN GOES. A
via stop is about A PLACE. They used to be the same mechanism: taking a branch
meant storing "via the first stop nothing else reaches", which put a tick on a
station in the middle of the branch that nobody had tapped.

Now:

| Intent | Stored as | Map shows |
|---|---|---|
| Trains through here | `viaStationIds` | tick on that stop, line beyond it filled |
| This whole service | `patternIds` (new column) | chip fills, no stop is ticked |

Three markers and no more: hollow ring (not included), ring with a filled centre
(included), filled disc with a tick (the thing you picked). **Do not add a
fourth.** Endpoint discs and junction interchange rings were both tried and both
removed — a marker changing shape reads as selection feedback to someone tapping
stations, and structure belongs in the geometry and the terminus chips.

### Three bugs found in review — do not reintroduce

1. **A branch is resolved from where it DIVERGES, not from the origin.** A
   pattern's stop list starts at the station you are standing at, so it includes
   the shared trunk. Taking all of it made "All Battersea trains" admit every
   "Kennington via CX" turn-back, because Kennington is on the Battersea run.
   Only stops past the longest prefix shared with a sibling count. Short workings
   further DOWN the branch still match, which is the point.
2. **A chip past a merge stands for SEVERAL services.** Oval to Morden is reached
   both via Bank and via Charing Cross, and the chip there says "Morden". It
   toggles all of them, all-or-nothing, or it fills for nothing and shows half
   the trains.
3. **Pattern NAMES are stored beside the ids** (`patternNames`, migration
   `4.sqm`). A pattern id is unreadable and a restored board otherwise read
   "via 940GZZLUMDN:bank" on the home screen.

`composeApp` now has a `commonTest` source set (it had none) covering the filter
summary, because that string is assembled from two kinds of pick and shipped
straight to the screen — it once shipped with its template escaped rather than
evaluated, reading "call at ${names[0]} & ${names[1]}".

### Gaps found in a later review pass

**Fixed:**

- **Cross-device sync was dropping the new filter fields.** The backend's
  `sanitiseBoards` rebuilds each filter from an explicit ALLOW-LIST, so
  `viaKeys` / `patternIds` / `patternNames` were silently stripped on every
  sync. The saving device kept working; every other device, and the same device
  after a reinstall, got the board back with half its filter gone. **Any new
  filter field must be added to `userService.ts:sanitiseBoards` as well as to
  the client model.**
- **Restore change-detection ignored them.** `UserSyncRepository` compared only
  `filterMode`, `destinationIds` and `viaStationIds`. Taking a whole branch
  changes `patternIds` and `viaKeys` and nothing else, so that edit synced into
  storage and was never re-applied to departures already on the device.

**The iOS widget did not filter at all — FIXED.** `WidgetRefreshService.swift`
has its own `PredictionsResponse.Pred` decoder, and it read only `displayName`,
`platform`, `eta` and `stopLetter`. It never decoded `destId`, so when the widget
refreshed ITSELF it showed every departure in the direction and ignored the
board's filter completely. Pre-existing — `destinationIds` was never applied
there either — but newly visible beside a correctly filtered board.

Pushed boards were always fine; KMP filters those before writing them. Only the
extension's own refresh path was affected.

The fix, and the shape to keep:

- `WidgetFeed` (KMP) and `BoardFeed` (Swift) now carry the board's **resolved**
  `destinationIds` + `viaKeys`. Resolved, never the intent — resolving needs
  route data the extension cannot fetch and should not know about.
- `BoardFeed.admits(destId:viaKey:)` is a **line-for-line mirror of
  `SqlStorage.matchesFilter`**, fail-open branches included. If you change one,
  change the other; the widget and the board it mirrors disagreeing is worse
  than either being wrong alone.
- The refresh falls open per feed the way `SqlStorage.getPredictions` does: if
  the filter matches nothing, show the unfiltered list. A widget has no empty
  state to explain itself with, so an empty one reads as "no trains at all".
- Both new fields decode with `decodeIfPresent`, so a board written by an older
  build still loads and simply does not filter.

### Known limitation, deliberate

Mixing a stop pick with a branch pick whose service carries no TfL branch label
(Battersea) drops branch narrowing entirely and fails open. A board stores ONE
flat (ids, tokens) pair, which cannot express a union of two different clauses.
Fixing it means a clause list, a new column and a change to the synced board
payload. Not worth it for that combination; the failure is in the safe
direction. Pinned by `mixing a stop and an unlabelled service fails open`.

## 5b. Still open (the map)

Phase A leaves the map **complete but not merged**: every branch is now drawn and
selectable, but where two branches rejoin the shared tail is still drawn twice
(31 origin/direction pairs, worst is Kennington northbound with 8 extra dots).
Nothing is missing or mis-filtered — this is presentation.

### B1. ~~`RouteGraph`~~ DONE — a node two parents can share

`RouteTree` is `stops` + `children`; a merge is unrepresentable. Keep the forward
prefix grouping (it is correct — the Finchley Central split groups properly) and
add a backward tail-merge.

**Node identity for drawing: `(stopId, occurrenceOrdinal)`, positioned at
`column = max over patterns of its index`.** Longest-path layering is what makes
a merge line up, and it reproduces the printed map on the case at hand: at Camden
Town southbound all three patterns put Kennington at index 9, so it collapses to
one node with three edges in and two out. `occurrenceOrdinal` handles the Circle,
where Edgware Road appears twice in one 37-stop sequence.

### B2. ~~The renderer~~ DONE

`RouteGraphPicker.place()` becomes layered: a node's column is the furthest any
parent can push it; rows assigned per track with merge nodes centred on their
feeders. The merge curve is the split S-curve already in `drawNode`, mirrored.

### B3. Euston vs Kennington — RESOLVED DIFFERENTLY TO THE ORIGINAL PLAN

The original plan was two linked markers at Euston. **That was wrong for this
surface.** A station is ONE node shared by every pattern calling there, so Euston
is where the branches meet and part again, and Kennington is where they meet and
stay together. Both get one marker.

The printed map splits Euston because it is drawing tunnels and platforms. This
map answers "which trains take me through here", and at Euston the answer is "all
of them" — two markers would offer a choice between two Eustons meaning the same
thing. `RouteGraph.repeatedStops` is only for a stop the route genuinely calls at
twice on one journey (the Circle's Edgware Road).

### B4. Platform labels on the origin's outgoing branches — NOT DONE

We cannot label downstream stops: TfL returns a platform only for the station you
query. But we can label the branches **leaving the origin**, which answers "which
platform do I wait on". Verified on live Northern data:

| Station | Southbound | Northbound | |
|---|---|---|---|
| Euston | Plat 2 = via CX · Plat 6 = via Bank | Plat 1 = via CX · Plat 3 = via Bank | clean |
| Kennington | Plat 2 = via CX · Plat 4 = via Bank | Plat 1 = via CX · Plat 3 = via Bank | clean |
| Camden Town | both branches on both platforms | both branches on both platforms | **noisy** |

Camden Town is a double junction and its platform reflects which branch the train
*arrived* from. **Show the label only when every observed arrival for that branch
agrees**, and show nothing at Camden Town rather than something wrong.

**Blocker found:** the selection sheet has no live arrivals for the origin, and
TfL returns a platform only for the station you query. So this needs the origin's
arrivals fetched into the sheet. Data is verified good at Euston and Kennington,
noisy at Camden Town.

### B5. Loose ends

- **Circle spiral.** Edgware Road at index 9 and 36 of one route. `b.indexOf(sid)`
  takes the first. Practical risk is narrow (needs a train short-turning at the
  first visit) but the map draws a bare duplicate with no "again" affordance.
  Needs occurrence-aware run extraction.
- **`routeResolvedAt` is written but never read.** No saved filter is ever
  re-resolved. Should re-resolve when the route payload behind it has moved on.
- **Night Tube is invisible.** `fetchSequences` hardcodes
  `serviceTypes: 'Regular'`.
- **Piccadilly Heathrow loop.** TfL models the one-way T4 loop as *westbound ends
  at T4*, with the continuation `T4 → T2&3 → east` living in the **outbound**
  sequence. Our data matches and the filter is right under that model. The one
  debatable case: at Hatton Cross westbound, "through Terminals 2 & 3" excludes
  T4 trains, which do reach T2&3 after T4. Deliberately left alone — splicing
  across directions would contradict the direction model everywhere else.
  Decide before touching it.

---

## 6. Verification recipes

**Backend locally** (needs nothing but the checked-in `.env` + SQLite):

```bash
cd stationly-backend && npx nodemon --quiet src/server.ts   # port 3000
K="X-Stationly-Key: f7d6c5b4-3a2b-1c0d-e9f8-a7b6c5d4e3f2"   # staging key, local.properties
curl -s -H "$K" "http://localhost:3000/api/v1/lines/northern/route?station=940GZZLUCTN&mode=tube" | python3 -m json.tool
```

Expected southbound: **3 patterns** — `940GZZLUMDN:bank` "Morden via Bank",
`940GZZLUMDN:charingcross` "Morden via Charing Cross", `940GZZBPSUST` "Battersea
Power Station" — and `destinations` still **2 chips**.

Expected northbound: 3 patterns with **`viaKey: null`** on all of them (the
Bank/CX split is behind you). If they read "Edgware via Bank", the
via-must-be-on-the-run rule has regressed.

**Android-safety regression** — the legacy half must be byte-identical:

```python
# a = old payload, b = new payload (same station)
legacy = lambda d: [{k:v for k,v in x.items() if k != 'patterns'} for x in d]
assert json.dumps(legacy(a), sort_keys=True) == json.dumps(legacy(b), sort_keys=True)
```

**Other stations worth checking:** Central `940GZZLULVT` outbound → 2 chips, 3
patterns, both Hainault arms labelled. Northern `940GZZLUKNG` outbound → 3 chips,
**6** patterns. Bus `39` at `490008805N` → 1 pattern each way, unchanged.

**Kotlin:**

```bash
./gradlew :core:testDebugUnitTest        # BranchFilterTest 16 + RouteGraphTest 11
./gradlew :composeApp:testDebugUnitTest  # BoardFilterSummaryTest 7
./gradlew :core:compileKotlinIosArm64 :composeApp:compileKotlinIosArm64
./gradlew :android:app:compileStagingDebugKotlin   # Android must stay green
```

Schema arity, since nothing checks it automatically:

```bash
grep -A2 "^insertSelection:" core/src/commonMain/sqldelight/com/stationly/db/StationlyDatabase.sq
# column count must equal the number of ? placeholders
```

**On device** — always the connected **iPhone 11**, never the simulator, and
`iosApp Staging` / `Debug Staging` only. Run
`assembleComposeAppDebugXCFramework` before `xcodebuild` or you ship stale
Kotlin. Pull the device DB to inspect saved filters:

```bash
xcrun devicectl device copy from --device <id> --domain-type appDataContainer \
  --domain-identifier com.stationly.mobile.staging \
  --source "Library/Application Support/databases/stationly.db" --destination ./stationly.db
sqlite3 -line stationly.db "select stationName,line,direction,filterMode,viaStationName,viaKeys from UserSelectionEntity where filterMode<>'ALL';"
```

**Not yet done on device:** as of 2026-08-18 all six saved boards on the test
phone are `filterMode = ALL`, so nothing has exercised the new column in anger.
Phase A needs one manual pass: save a "through Bank" board at Camden Town
southbound and confirm no `via CX` train appears.

---

## 7. TfL data facts worth not rediscovering

- **`/StopPoint/{id}/Arrivals?line=X` ignores `line`.** At a shared station you
  get every line's trains. Use `/Line/{lineId}/Arrivals/{stopPointId}`.
- **`towards` is not always "X via Y".** It can be `"Heathrow T123 + 5"`,
  `"Aldgate"`, `"Check Front of Train"`. The parser returns null for those, which
  is the honest answer.
- **Route-name vs arrival wording differ**: `"via Charing Cross"` in the route
  name, `"via CX"` in `towards`. That is what the alias table is for.
- **Piccadilly sequences use HUB ids** (`HUBHX5`) where predictions report `940G`
  ids. Our cached route data already carries 940G, so there is no live mismatch —
  but a rebuild from a fresh TfL fetch could reintroduce one. Worth an assertion.
- **The Metropolitan is genuinely undecidable.** Four patterns from
  Harrow-on-the-Hill all end at Aldgate and differ only in whether they call at
  Willesden Green; `towards` says just `"Aldgate"`. Those 12 origins fail open by
  design. Do not invent a discriminator for them.
