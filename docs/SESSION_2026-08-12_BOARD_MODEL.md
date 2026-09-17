> **⚠️ SUPERSEDED IN PART.** A later session the same evening audited this work
> and found the write-volume fix (§3) did not hold, plus an account-identity bug
> that duplicated user documents. Read
> **`SESSION_2026-08-12_SYNC_AND_IDENTITY.md`** first — it is the current state
> of play and indexes what is still true here.

# Handover — Board model rewrite, hub ids, device-local settings

_Session 2026-08-12. Branch `ios-parity` (StationlyUI) + `dev_13Jul`
(stationly-backend). **iOS only — `git status --porcelain -- android/` → 0.**_

Durable design doc: `docs/USER_STATE_AND_ACTIVITY.md`. This file is the change
log, the evidence, and the state of play.

> **Supersedes `docs/SESSION_2026-08-11_USER_STATE.md`.** That session's schema
> (`SavedBoard` with `lines[].directions[]`) and its "sync every setting" rule
> are both **gone**. Read it only for the pre-existing bugs it records.

---

## 0. How the session went, in order

Three distinct pieces of work, each triggered by the user rejecting the previous
state. Worth reading in order — later decisions only make sense given earlier
ones.

1. **"I didn't like the user model"** → full rewrite of the board entity (§1).
2. **"Are we even storing the correct IDs?"** → the board id was a bus *pole*,
   not a hub. Found, proved, fixed (§2).
3. **"Any user action is immediately written to Firestore — that's gonna exhaust
   our limit"** → appearance settings moved off the backend entirely (§3).

Plus a regression hunt the user asked for explicitly (§4), which found two real
bugs I had introduced and one I had not.

---

## 1. The board entity, rewritten

### What was deleted
`SavedBoard`, `SavedLine`, `SavedDirection`, `StationPrefs`, `BoardDisplayPrefs`,
`UserPreferences`, `HomePreferences`, `DreamPreferences`,
`StationPrefsRepository`. No migration — the previous shapes only ever existed in
development, and the user confirmed a fresh start.

### What replaced them

```
Board            id (the HUB), name, addedAt
  ├ selections   [ naptanId, line, mode, direction, filter{…} ]
  ├ config       expanded, view, rowsPerPlatform, pin, position   ← @Transient
  └ widget       placed, families, observedAt                     ← @Transient
```

`core/src/commonMain/kotlin/model/user/Board.kt` — `Board`, `BoardSelection`,
`BoardFilter`, `BoardConfig`, `BoardView`, `BoardPin`, `WidgetPlacement`.
`HomeLayout` is alone in its own file.

### The four decisions inside it

**No line level.** The old schema nested `lines[].directions[]`, and the line
level held nothing but the line id. Every reader walked two loops to reach the
row it wanted, and the app's own model (`UserSelection`, prediction tables, topic
subscriptions) is flat at exactly this level. `Board.byLine()` derives the
grouping in one call.

**`mode`, `lines`, `naptanIds` are derived, never stored.** A stored copy is a
second record of one fact; the two drift the first time a hub gains a line of
another mode.

**The fetch naptan is on the SELECTION.** Invisible on rail (every direction
shares the station naptan), decisive on bus. Smithwood Close: route 39 inbound
departs pole `490008805N`, outbound `490012211N`. On the board it would serve
inbound departures on the outbound side of the road. Filters sit at the same
level, because a filter narrows one queue.

**`hideHero` → `BoardView`.** Three named states (`FULL`, `BOARD_ONLY`,
`NEXT_ONLY`). Two booleans can express "show neither", which is not a card
anyone wants; one enum cannot. The settings screen's Layout picker is now driven
off `BoardView.entries`, so a view added to the enum cannot be missing from the
UI. `BoardPreview` gained `withBoard`.

> **`NEXT_ONLY` was removed in §12.** Two states remain, and the plumbing that
> existed only to hide the board — `showsBoard`, the card's `showBoard`,
> `BoardPreview.withBoard` — went with it.

**`HomePreferences.order` → `BoardConfig.position`.** The old list of ids had to
be reconciled with reality on every read — it could name boards that no longer
exist and miss ones that do. A rank on the board is created, moved and deleted
with it. `UNPOSITIONED = -1` sorts last (a board added since the last reorder
goes to the bottom, not the top); ties fall back to `addedAt`.

### Tests
`core/src/commonTest/kotlin/user/BoardTest.kt` — **15 tests, 0 failures**.
Covers the Smithwood Close pole split, multi-line hub, round trip, filter
isolation per direction, inactive filter with no resolved ids, pre-hub rows,
config carried per board, `rowCap` clamping, `UNPOSITIONED` ordering, default
view, pin survival, `isUsable`, `naptanIds` dedup, stable `addedAt`, and
`BoardSelection.key == UserSelection.boardKey`.

---

## 2. The hub id — the board id was a bus pole

### What the user spotted
The stored Firestore doc read `id: "490012211N"` — which is *also*
`selections[1].naptanId`. The hub and a pole were the same string.

### Root cause
`/stations/search` grouped the poles correctly but returned the **representative
member's naptan** as `id` (`stationController.ts:366`), discarding the group key
and `members`. Worse: the representative is chosen **by distance to the caller**
(`dataCacheService.groupStations`).

**Proved empirically.** The user's saved board said `490012211N`; a search from a
different position returned `490008805N`. Same stop, two ids → two cards, two
config rows, and a login on a new device that cannot match them.

### The right id
TfL's StopArea, from `stationNaptan`: **`490G00008805`** for both Smithwood
poles. `icsCode` (`1008805`) groups the same stops but is an Interchange Scheme
number, not a naptan — fine for grouping, useless to hand to a client or to TfL.

⚠️ **Hub ids are not derivable from member codes.** Smithwood's hub happens to
contain `8805`, but Sobell Centre is `490G00013695` over children `490008368N/S`.
It must come from the data.

> **False lead, recorded so nobody repeats it.** I first concluded the data had
> no group keys at all, from `data/stationly.sqlite`. That file is a **stale Aug-2
> dev snapshot**. `LocalDbService.upsertStation` stores the whole doc in
> `raw_data`, so the absence was the snapshot's age, not a schema gap. Firestore
> has `stationNaptan`. **Check live staging, not the local sqlite.**

### Fixes (`stationly-backend`)
- `DataCacheService.getGroupKey` → `stationNaptan || icsCode || commonName || naptanId`
- new `DataCacheService.getHubId` — same chain, but never lets a bare
  `commonName` escape as an identity ("Park Road" ×36, "Church Road" ×22 would
  otherwise become one board naming 36 unrelated places)
- new `DataCacheService.stationsInGroup(id)` — accepts a hub id **or** any member
  naptan. Replaced **three** duplicated "find member → derive group → collect
  siblings" lookups (`resolveStation`, `getLinesByMode`, the directions list).
  All three matched on `naptanId` only, so all three would have silently found
  nothing the moment clients started sending a hub.
- `searchStations` (text, nearby and mode-only branches) return `getHubId(s)`
- `lineController` directions no longer adds the caller's id to the fetchable
  stop set unconditionally

### Rail was always correct
`940GZZLUASL` already *is* a StopArea. This was a bus-only defect.

---

## 3. Settings are device-local now — the write-volume fix

### The problem
Every appearance change was a debounced backend write: flipping Expanded, each
detent of the rows slider, every drag-reorder. The document being written is the
one **every login reads**. Appearance is the highest-frequency, lowest-value
state in the app.

### The split, by consequence

| State | Where | Why |
|---|---|---|
| Boards, lines, directions, filters | Backend | The subscription registry is derived from them — a station missing from it is never polled, so losing a board loses departures. Rare. |
| expanded, view, rowsPerPlatform, pin, position, homeLayout, screensaver | **Device, per account** | Appearance. Every touch, worth nothing to another device. |

### Mechanism
New `StorageManager.saveDurable/loadDurable` — a store `clearAll()` does **not**
touch:

| Platform | Backing |
|---|---|
| iOS | the App Group suite (the trick `DreamPrefsBackend` already used) |
| Android | a separate prefs file, `stationly_durable_prefs` |
| wasmJs | localStorage under a `durable_` prefix |

`UserSettings` keys are namespaced `base::uid` (`board_configs_v2`,
`home_layout_v2`, `widget_boards_v1`; `anon` before sign-in).

- Same person, same device → the arrangement they left
- Different person → their own, never the previous user's
- **New device → defaults.** The accepted cost, stated in the doc.

⚠️ `UserSettings.reset()` **must not wipe disk** — it drops the in-memory copy
and re-reads for the incoming uid. The in-memory drop is equally mandatory: the
store is a process-wide `object` whose `ensureLoaded` returns early, so skipping
it makes the next user inherit the previous layout *and save it under their own
id*.

### Removed from the wire
`Board.config` and `Board.widget` are `@Transient`. Deleted client-side:
`syncPreferences`, `SyncPreferencesRequest`, `UserProfileResponse.preferences`,
`UserStateRepository.preferencesChanged/pushPreferences`,
`UserStateSync.restore()` and the screensaver bridging. Deleted server-side:
`POST /user/sync/preferences`, `UserController.syncPreferences`,
`UserService.syncPreferences`, the `UserPreferences` type,
`MAX_PREFERENCES_BYTES`, `SavedBoard.config`, and `UserSyncReason.'preferences'`.

> **Updated in the follow-up pass (§12).** `UserProfile.preferences` was kept as
> `Record<string, unknown>` "so an existing document still decodes". It is now
> **gone entirely** — off the TypeScript interface, out of the swagger schema,
> and actively deleted from stored documents by `DROP_LEGACY_PREFERENCES`, which
> rides on the `syncBoards` / `syncStations` writes at **zero** extra cost. It
> stays on `PROTECTED_PROFILE_FIELDS` as the guard that stops a client
> resurrecting it through the profile sync.

### Logout resets the widget
`cleanupAll()` → `widgetManager.clearWidgetData()` → `wipe(d)`, the widget's
designed empty state. A signed-out account must not leave a live departure board
on a home screen.

On re-login the boards restore under the **same hub ids** (§2), so a widget still
configured for that station relights itself.

> **Wrong on two counts; corrected in §12.** This claimed
> `UserSettings.rememberWidgetBoards()` ran *before* `clearWidgetData()` because
> "once the widget data is cleared there is nothing left to derive it from". It
> ran **after** (`ProfileViewModel` :246 then :254), which did not matter — it
> read the in-memory probe map, not the App Group. And nothing ever read what it
> stored. The whole thing is **deleted**: the relight above happens on its own,
> via `refreshAllBoards()`.

### Nightly flush
`ActivityBridge.uploadActivity()` (the existing `BGProcessingTask`) now also
calls `UserStateSync.flushNow()`. Normally a no-op — but it covers the one case
nothing else would: the app killed inside the debounce window, or a push that
failed offline. Without it the local list is right, the account's is stale, and
nobody notices, because the next push only fires on the next *edit*.

---

## 4. Bugs found and fixed

### Mine, introduced this session

**1. A hub id at the predictions endpoint returned a fake-empty board.**
`GET /stations/predictions/490G00008805` → **HTTP 200**, `name: "Unknown
Station"`, **0 departures** (measured). TfL does not 404 a StopArea. That
directly contradicts the contract the controller's own comment states — an empty
board is a *claim* ("no trains here") and is indistinguishable from a quiet stop
at 3am, so the board would sit empty forever with nothing logged.
Fixed in `fetchPredictions`: a single-member group resolves exactly; a multi-pole
group is **refused** rather than guessed, because the poles are opposite sides of
one road and serving the wrong one is worse than serving nothing.

**2. `/stations/resolve` had no cold-start guard.**
Every other station route checks `DataCacheService.getIsReady()` and falls back to
Firestore. This one did not, and got away with it because `resolveStation` echoes
its *input* when it finds no group — and the input used to be a stop id, so the
failure path was accidentally correct. Once the input became a hub, an unloaded
cache returned `{"naptanId":"490G00008805"}` with a **200**: not an error the
caller can detect, but a confident wrong answer saved as a board's fetch key.
**The window opens on every deploy.** Now 503s when the cache is cold, and 404s
when the answer would not be a real stop.

> Both existed because I changed what an id *means* without auditing every
> consumer of it. The user pushing back on "is it only when the network fails?"
> is what surfaced #2.

### Not mine, found on the way
`resolveStation` returned the *representative id* when no direction matched,
which for a hub is unfetchable. Now returns a member (`siblings[0]`).

### From the previous session, still in this tree
Five pre-existing fixes are still uncommitted and are documented in
`SESSION_2026-08-11_USER_STATE.md` §3: iOS cross-device sync never ran
(`UserSyncBridge.currentUid` read the App Group instead of standard defaults),
the subscription registry could never remove a station (`merge:true` deep-merges
maps — needs `FieldValue.delete()`), `deviceId` died on every reinstall (ghost
sessions), `lastSeen` never advanced, and `/user/sync/profile` wrote arbitrary
body fields.

---

## 5. Files touched

### StationlyUI — 41 modified, 0 under `android/`

**New**
```
core/src/commonMain/kotlin/model/user/Board.kt
core/src/commonMain/kotlin/model/user/HomeLayout.kt
core/src/commonMain/kotlin/repository/UserSettings.kt
core/src/commonTest/kotlin/user/BoardTest.kt
```
(plus, from the previous session and still uncommitted: `core/activity/*`,
`UserStateRepository.kt`, `composeApp/sync/UserStateSync.kt`,
`ActivityBridge.kt`, `sqldelight/migrations/1.sqm`,
`ActivityUploadScheduler.swift`, `DeviceIdentityStore.swift`)

**Deleted**
```
core/src/commonMain/kotlin/model/user/SavedBoard.kt
core/src/commonMain/kotlin/util/BoardDisplayPrefs.kt
core/src/commonMain/kotlin/model/user/UserPreferences.kt
composeApp/src/commonMain/kotlin/com/stationly/app/ui/util/StationPrefs.kt
core/src/commonTest/kotlin/user/SavedBoardTest.kt
```

**Renamed across the tree:** `BoardDisplayPrefs` → `BoardConfig`;
`com.stationly.core.util.BoardPin` → `com.stationly.core.model.user.BoardPin`.

**Rewired:** `SummaryViewModel`/`SummaryScreen` (`boardConfigs`,
`UserSettings.ordered`), `components/Board.kt` (`showBoard` param, board block
wrapped in `if (showBoard)`), `StationSettingsViewModel`/`Screen`
(`setExpanded`/`setView`/`updateConfig`), `HomeSettingsScreen`
(`UserSettings.reorder`), `LoginViewModel`, `UserSyncBridge`, `ProfileViewModel`,
`UserSyncRepository.reconcileBoards`, `Platform.ios.kt` `boardPrefs()`,
`MultiLineBoardProcessor`, `platform/Platform.kt` (+ android/ios/wasm impls).

**Swift:** `AppGroupKeys.swift` (`placements` key), `AppGroupStorage.swift`
(`WidgetPlacementStamp`, `notePlacement`, `read/writePlacementStamps`),
`StationlyWidget.swift` (stamp on every timeline build),
`HomeStateProbe.swift` (`describe()` reconciles stamps against
`getCurrentConfigurations`, plus its own minimal `Placement: Codable` and a
duplicated `widget_placements` literal — `AppGroupKeys` is widget-target only).

### stationly-backend — 10 modified, branch `dev_13Jul`
`dataCacheService.ts` (getGroupKey/getHubId/stationsInGroup/resolveStation),
`stationController.ts` (three search branches, resolve guards, fetchPredictions
guard), `lineController.ts` (two lookups), `userService.ts` (board schema,
effectiveStationIds, deriveBoardsFromLegacy, sanitiseBoards, preferences
removal), `userController.ts`, `apiRoutes.ts`, `userSyncNotifier.ts`,
`server.ts` (swagger), plus the previous session's `subscriptionService.ts`,
`rateLimitMiddleware.ts`, `userActivityService.ts`.

---

## 6. Evidence — measured on live staging, not reasoned

```
search "Smithwood Close"   before: 490008805N (pole)   after: 490G00008805
lines at hub                                            39, 639
resolve 39  inbound/outbound                            490008805N / 490012211N
resolve 639 inbound/outbound                            490008805N / 490012211N
resolve with a POLE id (back-compat)                    unchanged
resolve "NOTASTOP123"      before: echoed itself back   after: 404
predictions 490G00008805   before: 200 "Unknown Station" 0 deps   after: 404
predictions 490008805N / 490012211N                     200, 5 departures each
predictions 940GZZLUASL                                 200, 22 departures
predictions 940GZZLUKSX                                 200, 133 departures

grouping partition unchanged after the getGroupKey reorder:
  "Park Road"        33 rows before → 33 after; 31/33 now carry 490G… ids
  "Camden Park Road" / "Colin Park Road"  still SEPARATE (not name-merged)
  King's Cross 1 · Stratford 1 · Oxford Circus 1 · Southfields 1
  Bank 2 · Paddington 2   (genuinely distinct stations)
```

Kotlin: `:core:testDebugUnitTest` green (BoardTest 15/0/0),
`:android:app:compileStagingDebugKotlin --rerun-tasks` **BUILD SUCCESSFUL**
(forced — an earlier run reported a false UP-TO-DATE, so always force it),
`git status --porcelain -- android/` → **0**.

---

## 7. Android regression analysis (the user asked explicitly)

Android depends on `:core` (`android/app/build.gradle.kts:111`), so the model
rewrite matters. It compiles because Android never referenced the deleted types —
they were all on the iOS path.

Android's route through the changed endpoints:
`/stations/search` (now returns hub ids) → `/lines/mode/:mode?station=` →
`/lines/:lineId/route?station=` → **`/stations/resolve`** → `/stations/predictions/:pole`.

**The protection:** `android/…/selection/SelectionViewModel.kt:481` calls
`resolveStation` *before* saving, stores `station = resolvedId`, and leaves
`parentStationId` unset. So Android only ever persists **pole naptans** — the hub
is transient, used for lookups that all now accept it.

**Residual exposure:** `SduiApiService.resolveStation` catches its own errors and
falls back to the id it was handed. On a genuine network/5xx/429 failure that is
now a hub, so the board 404s instead of working. It fails **visibly** (retryable)
rather than silently. The user accepted this: *"Android flow would likely go and
call resolve station before setting up any board completely."*

**Blast radius: production Android is untouched.** Only staging was deployed;
production is a separate deployment (a probe with the staging key 403s).

---

## 8. Deploy state

| | State |
|---|---|
| **Backend** | Deployed to **staging** 4× this session (`bash .scripts/staging_deploy.sh`). **Production NOT deployed.** |
| **iOS app** | Built and launched on Nick's iPhone 11 (`AB7B04C8-F9D6-5C05-8388-5767BC96C059`), scheme `iosApp Staging`. |
| **Android** | Not built for release, not deployed. Zero files changed. |
| **Git** | **Nothing committed.** Both repos are dirty working trees. |

### Build recipe (the framework step is not optional)
```bash
# Kotlin first — xcodebuild ships a STALE framework otherwise, and builds green
./gradlew :composeApp:assembleComposeAppDebugXCFramework \
          :composeApp:assembleIosArm64MainResources      # ~4-5 min

DD=iosApp/build/DD
xcodebuild -project iosApp/iosApp.xcodeproj -scheme "iosApp Staging" \
  -destination 'id=AB7B04C8-F9D6-5C05-8388-5767BC96C059' \
  -derivedDataPath "$DD" -allowProvisioningUpdates build
xcrun devicectl device install app --device AB7B04C8-F9D6-5C05-8388-5767BC96C059 \
  "$DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device process launch --terminate-existing \
  --device AB7B04C8-F9D6-5C05-8388-5767BC96C059 com.stationly.mobile
```

### Debug recipes used
```bash
# App defaults (uid, token, settings keys)
xcrun devicectl device copy from --device <UDID> --domain-type appDataContainer \
  --domain-identifier com.stationly.mobile \
  --source Library/Preferences/com.stationly.mobile.plist --destination <out>

# App Group (widget board data, placements, device id)
xcrun devicectl device copy from --device <UDID> \
  --domain-type appGroupDataContainer --domain-identifier group.com.stationly.shared \
  --source Library/Preferences --destination <out>

# Staging API (key is in the App Group as widget_api_key)
curl -H "X-Stationly-Key: <key>" "https://staging-api.stationly.co.uk/api/v1/..."
```

---

## 9. Verified vs NOT verified

**Verified**
- Every endpoint above, against live staging, before/after
- Board grouping partition unchanged after the `getGroupKey` reorder
- Kotlin compiles for iOS + Android; core tests green
- App installs and launches; no Stationly crash logs

**NOT verified — the next agent should do these first**
1. **A settings change produces zero network traffic.** This is the whole point
   of §3 and is untested. Toggle Expanded, drag the rows slider, reorder the
   home screen — nothing should hit the wire.
2. **Logout → login restores the arrangement** on the same device, and a
   *different* account gets its own.
3. **A board written by the new client** — confirm the stored doc has
   `id: 490G00008805`, four selections, and **no `config` key**.
4. **Widget resets on logout** and relights on re-login.
5. **The nightly `BGProcessingTask`** flushing boards (hard to force; the
   foreground stale-path is the practical proxy).
6. **`@EncodeDefault` is moot now** — config never reaches the wire, so the
   `config: {}` concern raised earlier is dropped, not pending.

---

## 10. Open items / next up

1. **Commit.** Both trees are dirty and carry three sessions of work.
2. **Before the production backend deploy:** a small Android change so the
   `resolveStation` failure path falls back to a *member* id rather than the hub
   (or refuses to save the board). §7.
3. **Production deploy** carries the previous session's five pre-existing fixes
   too — re-read `SESSION_2026-08-11_USER_STATE.md` §3 before shipping.
4. **Android adoption of the board model** (when ready): point it at `boards`,
   drop `cleanupAll()` from the save path, set `parentStationId`, adopt
   `UserSettings` (the durable-storage actual is already written), wire
   `ActivityLog.record` call sites, schedule `ActivityUploader.flush` from
   WorkManager.
5. **Widget placement is best-effort.** Two same-size widgets on one station are
   indistinguishable; a just-added widget reports `family|` (no station) until
   its first timeline build. Documented in `WidgetPlacement`.
6. **`core:iosSimulatorArm64Test` still does not compile** — pre-existing
   backtick test names containing commas, which Kotlin/Native rejects.
   `:core:testDebugUnitTest` is the suite that runs.

---

## 11. Traps for the next agent

- **`data/stationly.sqlite` is a stale dev snapshot.** Verify station data
  against live staging. Trusting it cost an hour and a wrong conclusion.
- **Force Gradle.** `:core:compileKotlinIosArm64` reported a false UP-TO-DATE
  after real edits. Use `--rerun-tasks` when a clean result matters.
- **Rows equal to the defaults are pruned from `UserSettings`.** Always read via
  `configOf(id)`, never off the `configs` map — an absent row is a configured
  default, not a missing answer. Flipping a default is a one-way change.
- **`AppGroupKeys`/`AppGroupStorage` are widget-target only.** The app target
  cannot import them; `HomeStateProbe` keeps its own copies of the key literal
  and the stamp struct.
- **A hub id is not a fetch key.** It must never reach the predictions path or
  the subscription registry. `effectiveStationIds` walks
  `selections[].naptanId`, never `board.id`.
- **`zsh` does not word-split unquoted parameters.** `perl -pi -e '…' $files`
  silently passed the whole newline-joined list as one filename. Use
  `grep -rl … | xargs`.

---

## 12. Follow-up pass — the write amplification was never actually fixed

_Same day, after an audit of the above against the code. Both trees still
uncommitted._

§3 moved appearance off the wire and declared the write problem solved. It was
not: `UserStateRepository.flushNow()` pushed **unconditionally**, and it runs on
every backgrounding (`iOSApp.swift`) and again nightly (`ActivityBridge`). So the
cost simply moved from "per settings tap" to "per app close" — twenty opens a day
was twenty `POST /user/sync/boards`, each a Firestore read + write, each firing a
`user.sync` fan-out that every device answers with another profile read.

### The same push could delete every board on the account
`pushBoards()` sent whatever `currentBoards()` returned, stamped
`Clock.System.now()`, which always wins the server's LWW guard. The login path
calls `sqlStorage.clearAllData()` (`UserSyncRepository.kt:45`) **before**
`restoreBoards` repopulates. Background the app inside that window — a slow
network on the login loader is enough — and the device posted `boards: []`.
Nothing refused it: the controller checked only `Array.isArray`, and
`sanitiseBoards([])` returns `[]`. Account cleared, then propagated to every other
device by the next reconcile. The same shape applies if the `runCatching`-wrapped
`setupStation` calls inside `restoreBoards` fail.

### Fixes
| | |
|---|---|
| **`BoardPushGate`** (new, `core/repository/`) | Counts user changes against the last the server accepted. A flush with nothing pending costs nothing. An edit arriving mid-request is not acknowledged away (the request clears only the revision it carried). A failed push stays pending, so the nightly flush genuinely retries it — it used to "recover" only because the next flush pushed regardless. **17 tests.** |
| **`allowEmpty`** on `/user/sync/boards` | An empty list is refused when the stored one is non-empty, unless the client says a USER emptied it. Defaults to false, so a client that predates the field cannot clear an account by omission. Server answers `200 {applied:false, reason:'empty_rejected'}` and logs it — a client must not retry a destructive write. Call sites pass the live `selections.isEmpty()`, never a literal. |
| **`deleteAccount()` teardown** | It never called `resetForNewSession()`. A queued push from the deleted account could still fire, the next user on the device inherited their arrangement, and their per-uid durable rows sat on disk forever. New `UserStateSync.forgetAccount(uid)` + `StorageManager.removeDurable`. |
| **`rememberedWidgetBoards` deleted** | Written, never read — grepped across Kotlin and Swift. The widget relights anyway, because restoring a board runs `setupStation` → `completeSetupAsync` → `updateWidget` → `refreshAllBoards()`, which repopulates every station's App Group entry. Dead state promising a feature that already worked by another route. |
| **wasm durable storage** | `clearAll()`/`clearCache()` called `localStorage.clear()`, wiping the `durable_` prefix — the one contract iOS and Android both keep. Now enumerated and preserved. |
| **`preferences` erased** | Still on the `UserProfile` interface and still advertised in the swagger schema as "account-scoped client settings, stored verbatim" — i.e. the public API still told the next client to sync settings there. Removed from both, plus `DROP_LEGACY_PREFERENCES` sweeps it out of stored documents on writes that already happen (zero extra writes). Kept on `PROTECTED_PROFILE_FIELDS`: that is a DENY list, and it is what stops a client posting settings back through `/user/sync/profile`. |
| **`BoardView.NEXT_ONLY` removed** | The hero-only layout, dropped on request. With it went every line that existed to hide the board: `BoardView.showsBoard`, the card's `showBoard` param and its `if` wrapper, `BoardPreview.withBoard` and its `else` branch, the picker's third tile. Two states remain and only the hero varies. A stored `view: "NEXT_ONLY"` decodes to `FULL` via `coerceInputValues` — tested. |

Two stale comments corrected: `StationSettingsViewModel.updateConfig` still
claimed a config write marked the board list dirty and pushed it, and
`getUserProfile` still described returning "the settings blob".

### Verified
`:core:testDebugUnitTest` **193 tests, 0 failures** (BoardPushGate 17/0/0,
BoardTest 17/0/0).
`:composeApp:compileKotlinIosArm64`, `:android:app:compileStagingDebugKotlin
--rerun-tasks`, backend `tsc --noEmit` — all clean. Built, installed and launched
on Nick's iPhone 11 (`iosApp Staging`); main app + widget extension both running.
`git status --porcelain -- android/` still **0**.

⚠️ **`:core:compileKotlinWasmJs` does not build**, and did not before this pass
either: `app.cash.sqldelight:{runtime,coroutines-extensions}:2.0.2` publish no
wasmJs variant. A dependency-resolution failure, not a code error — the wasm
`StorageManager` edits above are sound but unverified by a compiler.

### NOT verified on device
1. A backgrounding with no board edits produces **zero** network traffic. This is
   the whole point of the gate.
2. `allowEmpty` end to end — **staging is not deployed**, so the server still
   ignores the field. The client-side gate is what closes the data-loss path; the
   server guard is the backstop and is inert until deploy.
3. Logout → login restores the arrangement; a different account gets its own.
4. Account deletion leaves no durable rows behind.
