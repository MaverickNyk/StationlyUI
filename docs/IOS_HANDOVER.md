# iOS — consolidated handover

**Branch:** `ios-parity` · **Last updated:** 2026-08-05
**Status:** builds clean, deployed and running on a physical iPhone 11 against
**staging**. All four compile gates green (§6).

This is the **one document to open first**. Everything else in `docs/` is a
deep-dive appendix; §7 says which one to reach for and when. Nothing here
duplicates them — where a topic has a dedicated doc, this file gives the
one-paragraph version and a pointer.

---

## 1. What this branch is

iOS is a **Compose Multiplatform port of the Android app**, not a separate
product. The guiding rule for every decision on this branch: *match Android's
behaviour unless the platform makes that impossible, and when it does, write
down why.* Deliberate divergences are listed in §4 — each one is a case where
copying Android exactly was not an option.

### Module topology — read this before worrying about Android regressions

```
:core          ← shared KMP (commonMain / androidMain / iosMain)
:android:app   → depends on :core ONLY
:composeApp    → depends on :core   (com.android.library; nothing consumes its android target)
:web           → standalone
```

**`:android:app` does not depend on `:composeApp`.** Every screen, ViewModel
and UI file under `composeApp/` is therefore *structurally incapable* of
affecting the shipped Android app, however much it lives in `commonMain`. The
only shared-code surface that can reach Android is **`core/commonMain`**.

`composeApp`'s `androidTarget` is kept compiling purely as a build-verification
canary (hence `HomePromoPlatform.android.kt`, whose actuals only have to
exist). Do not mistake it for a shipping target.

This is the fact that makes the whole branch safe, and it is worth re-checking
if the dependency graph ever changes.

---

## 2. Current state, by area

| Area | State |
|---|---|
| **Auth** | Email/password, Google, **Sign in with Apple** (live since the paid team landed), email verification, reset deep links. Keychain-restored sessions self-heal their identity keys. |
| **Home / board** | Full Android parity: dot-matrix board, per-minute tick, pull-to-refresh, promos, offline banner, SDUI strings with hardcoded fallbacks. |
| **Live departures** | **WebSocket stream**, iOS-only. Replaces REST polling for predictions + line status. See §3. |
| **Widget** | Full-bleed board, interactive platform paging, interactive refresh button, live clock, per-minute tick, departed-row retention. |
| **Dream / screensaver** | Full port as an in-app route (iOS has no system screensaver slot). SDUI-driven. |
| **Push** | APNs + FCM live on the paid team. `aps-environment` tracks the build config. |
| **Signing** | Stationly Limited org team **`7T7D5LLYSL`**. App Group **`group.com.stationly.shared`**. |

### Not done / known gaps

1. **Nothing on this branch is committed** beyond the commits already listed in
   `git log master..HEAD`. The working tree carries two sessions of work
   (home promos + live stream) — see §5 for the suggested commit split.
2. **No automated tests anywhere on the iOS side.** `LiveStreamManager`'s
   reconnect/backoff and force-resubscribe logic are the highest-value targets;
   `WidgetData.ticked` retention and `isVersionBelow` are the easiest.
3. **Stream tested with 1 station + 1 line only.** The 25-subscription cap and
   `unknown_station` handling are implemented but unexercised.
4. **Prod nginx** needs the stream `location` block verified before pointing
   production builds at it. Staging is verified.
5. **Owner-side console steps** (APNs `.p8` upload to Firebase, Apple provider
   enable) are outside this repo — see `IOS_PARITY_GAP_ANALYSIS.md`.
6. **App Store URL is a placeholder.** `ProfileScreen.APP_STORE_URL` 404s until
   a real listing exists; swap in the `itms-apps://…?action=write-review` deep
   link then.

---

## 3. The live departure stream (the big architectural change)

iOS no longer calls REST for departures or line status — both arrive over
`wss://…/api/v1/stream`. Full protocol/ops detail lives in
**`IOS_LIVE_STREAM.md`**; the essentials:

**The single most important design point:** inbound frames are handed to the
*exact same* `ProcessFcmPayloadUseCase` methods FCM pushes already used.
Parsing, SQLite writes, widget refresh and `FreshDataNotifier` are unchanged —
only the transport differs. **Do not add a parallel write path.**

Second: `StreamBackedTflApiService` implements the existing `TflApiService`
interface, so `DepartureRepository` and every caller were left untouched; their
`getPredictions()`/`getLineStatuses()` calls simply resolve over the socket.

**Android safety.** Both `core/commonMain` touches are `expect`/`actual` seams
whose Android actual is a no-op or the pre-existing code verbatim:

| Seam | Android actual |
|---|---|
| `expect object LiveStream` | three empty no-ops |
| `expect fun createTflApiService` | `TflApiServiceImpl(httpClient)` — byte-identical to the old inline construction |

The ktor websockets dependency is scoped to **`iosMain` only**.

**Three on-device bugs are already fixed here — do not regress them.** They are
written up in `IOS_LIVE_STREAM.md` §4: the lenient-JSON flags for line frames,
the `lastUpdated` timestamp that made a live stream look like 30s polling, and
the ~10s pull-to-refresh. The third one has a trap: **pull-to-refresh
deliberately reuses a healthy socket** rather than reconnecting. The `expect`
declaration in `core/commonMain/platform/Platform.kt` used to document the
opposite; that has been corrected, but if you see "force-close and reopen"
anywhere, it is stale.

---

## 4. Deliberate divergences from Android

Each of these is a case where copying Android was impossible, not a shortcut.
Do not "fix" them without deciding to change the product on purpose.

| Divergence | Why |
|---|---|
| **Widget promo has no CTA button** | No iOS API adds a widget on the user's behalf. Android already handles this exact case — it passes `cta = null` when `isRequestPinAppWidgetSupported` is false — so this is Android's own fallback branch, not an invention. The instruction moved into the subtitle. |
| **Widget-installed detection via Swift probe** | `WidgetCenter` is Swift-only (no ObjC interface), so Kotlin/Native cannot see it. `HomeStateProbe.swift` writes the answer to the App Group; `hasHomeScreenWidget()` returns `null` when un-probed so the promo never flashes at someone who already has one. |
| **Dream promo = "has ever run it"** | iOS has no system screensaver slot to be chosen for; the dream is an in-app route. `DreamSettings.hasEverStarted()` is the honest analogue of Android reading `Settings.Secure.screensaver_components`. |
| **"Enable" opens the app's Settings page** | iOS has no per-app notifications deep link like Android's `ACTION_APP_NOTIFICATION_SETTINGS`. |
| **Notification prompt fires from `SummaryScreen`, not launch** | iOS gives exactly one chance per install and a denial is permanent. Android asks from the first authenticated screen; iOS now matches. `AppDelegate` only re-registers for APNs when already authorized. |
| **Widget paging via interactive header** | WidgetKit cannot scroll. See `IOS_WIDGET_DESIGN.md`. |
| **Widget refreshes itself over REST** | The extension is a separate process that can reach neither KMP nor the SQLite file. Accepted gap: its refresh reaches the widget but not the app's store; the app re-syncs on next foreground. |
| **iOS-specific pull-to-refresh indicator + haptic** | `IosActivityRefreshIndicator` (a real `UIActivityIndicatorView`, spokes that never rotate) and a haptic latched at the trigger threshold. Android keeps its amber ring and its on-release haptic, both untouched. |
| **`lastUpdated` reads the real sync time on iOS only** | Android keeps `now`. See §3 / `IOS_LIVE_STREAM.md` §4.2 — an "ago" value climbing past 30s is now *correct* behaviour. |

### Checked and confirmed NOT gaps — don't "fix" these

- **Profile provider chip shows a generic mail icon for Apple users.** Android
  does the same (`if (provider == "Google") AlternateEmail else Email`, no Apple
  branch). Adding an Apple glyph would *invent* a divergence.
- **`WelcomeEmptyState` / `FeatureChip`** (~100 lines in Android's
  `EmptyStates.kt`) have no iOS counterpart because they are **dead code on
  Android** — nothing references them.
- **`hideWidgetPromoForSession()`** exists on Android but not iOS: it is only
  reachable from Android's "Add" CTA, which iOS structurally cannot have.

---

## 5. Uncommitted work on this branch

Two sessions' worth, plus this session's cleanup. Suggested commit split:

**a) Home promos + parity fixes (2026-07-25)**
`SummaryScreen.kt`, `SummaryViewModel.kt`, `HomePromoPlatform.{kt,ios,android}`,
`NotificationPermissionEffect.kt`, `VersionCompare.kt`, `HomeStateProbe.swift`,
`AppNavigation.kt`, `DreamSettings*.kt`, `ProfileScreen.kt`, `LoginScreen.kt`,
`AppDelegate.swift`, `project.yml`, entitlements, app-group rename.

Closed gaps: the **force-update gate was dead code** (`_forceUpdate` was
declared but never assigned, so `UpdateNudgeDialog` was unreachable on iOS
however low the installed version); the missing Profile "Rate Stationly" row;
notification-prompt timing; ~300 lines of Android home surface iOS never had.

**b) Live departure stream (2026-08-01/02)**
`LiveStreamManager.kt`, `LiveStream.{ios,android}.kt`,
`StreamBackedTflApiService.kt`, `TflApiServiceFactory.{ios,android}.kt`,
`LiveStreamBridge.kt`, `Platform.kt`, `NetworkModule.kt`, `core/build.gradle.kts`,
`Platform.ios.kt`, `iOSApp.swift`, `IOS_LIVE_STREAM.md`.

**c) Backend-sync and teardown fixes** (bundled with (a) chronologically but
independent — these are the highest-risk-if-lost changes):
- `SelectionViewModel` — **the board was never reaching the backend.**
  `syncStations` now runs *before* cleanup, and `clearBoardsPreservingIdentity()`
  replaces `cleanupAll()` on the board-swap path. `cleanupAll()` calls
  `storageManager.clearAll()`, which on iOS is
  `removePersistentDomainForName(bundleId)` — it wipes the whole standard
  NSUserDefaults domain including `firebase_user_uid` / `firebase_auth_token`.
  So one board change left the app with no uid and no bearer token, and every
  auth-gated call in between failed silently. The helper is exactly
  `cleanupAll()` minus that one wipe. **Android is unaffected** — its
  `clearAll()` only clears a SharedPrefs file and its identity lives in
  `FirebaseAuth.currentUser`.
- `ProfileViewModel` — backend teardown (`logOut`, FCM token unregister) now
  runs *before* `signOut()`, because both are auth-gated and read a token that
  `signOut()` clears.
- `FcmTokenRegistrar` — keychain-restored sessions and rotated tokens never
  reached the backend. Now driven from `SummaryViewModel` init + every
  foreground, cheap when unchanged (one string compare, no network).

**d) Review pass (2026-08-02)** — see §6.

**e) Multi-line board + home-screen fit (2026-08-03)** — see §6b.
`core/util/{MultiLineBoardProcessor,LineStatusRanker,LineShortNames}.kt`,
`core/src/commonTest/**`, `Board.kt`, `SummaryScreen.kt`, `SummaryViewModel.kt`,
`ExploreSection.kt`, `Models.kt`, `StationlyFormatters.kt`,
`BOARD_AND_DREAM_UI.md`. Includes the SDUI-path removal — read §6b before
committing, it is the one item that changes product capability.

**f) Collapsible stations + per-station settings (2026-08-04/05)** — see §6c.
`ui/station/**` (new: `StationSettingsScreen`, `StationSettingsViewModel`,
`HomeSettingsScreen`), `ui/util/StationPrefs.kt` (new),
`core/util/BoardLabels.kt` (new, tested),
`core/usecase/SyncSubscribedStationsUseCase.kt` (new),
`MultiLineBoardProcessor.kt`, `Board.kt`, `SummaryScreen.kt`,
`SummaryViewModel.kt`, `SelectionScreen.kt`, `SelectionViewModel.kt`,
`AppNavigation.kt`, `BOARD_AND_DREAM_UI.md` §10–§15.

---

## 6. This session: review, fixes, verification

A full read of every changed file. Findings, all fixed:

### Correctness

1. **`LiveStreamManager.openIfNeeded()` had a check-then-act race across two
   lock acquisitions.** Two coroutines could both observe "no live job" and
   both launch a `runConnection`; the second overwrote `socketJob`, leaving the
   first as an orphan that no `closeCurrentSession` could cancel and that
   reconnected forever. Easy to hit on a cold start, where `notifyForeground()`
   and the board's first `ensureStation` fire within milliseconds on different
   coroutines. **This is the most likely root cause of the reconnect churn
   `IOS_LIVE_STREAM.md` §7.2 flagged as never root-caused.** Test and launch
   are now inside one `withLock`.
2. **`ensureStation`/`ensureLine` leaked pending awaiters on timeout.** A
   `CompletableDeferred` has no parent job, so `withTimeout` did not discard it;
   the entry outlived the call and every subsequent timeout on the same id
   appended another. Now removed in a `finally`, with the key dropped once its
   last awaiter is gone.
3. **The widget's "Refresh failed, tap to retry" glyph lied.** The 15s debounce
   window was claimed before the network call and held on failure, so every
   retry tap inside it silently returned `.debounced`. A short
   `failedRetrySeconds = 3` window now applies while the failed flag is set —
   honest retry, still bounded against spam-tapping a broken backend.
4. **Double FCM token POST on every login.** `LoginViewModel` registered inline
   without seeding `FcmTokenRegistrar`'s cache, so `SummaryViewModel.init`
   re-POSTed the identical token seconds later. Login now goes through the
   registrar, passing `uid` explicitly to avoid racing the Swift AuthBridge's
   write of `firebase_user_uid`.
5. **Stale contract doc.** `expect fun notifyPullToRefresh`'s KDoc said
   "force-close and reopen the connection" — the exact behaviour that was
   removed to fix the ~10s pull. A future reader restoring it would have
   reintroduced the bug. Corrected, along with the same wording in
   `SummaryViewModel.refreshAll()`.

### Dead code / cost removed

- Two unused imports (`kotlinx.cinterop.cValue`, `NSProcessInfo`) in
  `Platform.ios.kt`.
- **Two SQLite queries per push** (`getAllSelections` + `getLastUpdatedTimestamp`)
  that existed only to build a trace string, in the push handler's hot path.
- `hideWidgetPromoForSession()` — unreachable on iOS (see §4); replaced with a
  doc note so it doesn't read as a missing port.
- Stray blank line / dangling comma in the `Board(...)` call.

### Consolidation

- **The App Group literal was copy-pasted into 18 places.** The 2026-07-25
  rename had to find every one, and a missed copy does not fail the build — it
  silently opens an empty suite, which looks exactly like "the data was never
  written". Now: one Kotlin constant (`IosAppGroup.ID` in `core/iosMain`,
  which `composeApp/iosMain` reads) and one per Swift target
  (`AppGroupID.swift` × 2 — separate compilation units cannot share one).
  Only those three declarations plus the two `project.yml` entitlements now
  carry the literal.
- `LiveStreamManager`'s tuning magic numbers are named constants
  (`ENSURE_TIMEOUT_MS`, `HEARTBEAT_MS`, `RECONNECT_*`).
- `PushTraceSwift` was living inside `HomeStateProbe.swift` **wearing
  `HomeStateProbe`'s doc comment** — two unrelated types, merged docs. Split
  into its own file, both correctly documented.
- Widget deployment target said `16.0` at target level and `17.0` in build
  settings (only the latter took effect). Aligned to 17.0, with the reason
  recorded.

### Verification

```bash
# iOS
./gradlew :core:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64
# Android no-regression proof
./gradlew :core:compileDebugKotlinAndroid :android:app:compileProdDebugKotlin
```

All four green. Full device build + install + launch on the iPhone 11 also
green (§8 pipeline).

---

## 6b. Session 2026-08-03: multi-line board rebuild + home-screen fit

The home screen was reworked end to end for the multi-line world. Presentation
rules live in **core** (testable, shared) and Compose only renders them.

### New core files (all pure functions, all covered by tests)
| File | Owns |
|---|---|
| `core/util/MultiLineBoardProcessor.kt` | Grouping departures into platform/stop blocks, row limits, header text |
| `core/util/LineStatusRanker.kt` | Which line's status the one strip shows, rotation order, traffic-light tone |
| `core/util/LineShortNames.kt` | Short line labels (`H&C`, `Picc.`) — **stopgap**, see below |

### What changed on the board
- **One board is one station.** Rail groups by **platform across every tracked
  line**; buses group by **pole naptan** (`UserSelection.station`). The old
  per-line "Circle · Inbound" strip is gone — the platform header carries it
  ("Northern Platform 8 Southbound", "Bus 39 Stop W").
- **Two separate row limits**, previously conflated: `MAX_ROWS_PER_PLATFORM = 3`
  is a per-platform ceiling; `MIN_BOARD_ROWS = 3` is a floor for the **whole**
  board. Three platforms with two departures each get six rows and no padding.
- **Unassigned platforms sort last** regardless of time — you cannot go and stand
  on one.
- **One status strip**, rotating worst-first (§7 of `BOARD_AND_DREAM_UI.md`).
- **Per-line hero** switched by the line pills, splitting in two when both
  directions are tracked. Pills carry traffic-light status dots.
- **Home fits one viewport**: `LazyColumn` → measured `Column(verticalScroll)`;
  the board's cap is what is left after chrome and Network are measured.

### Read `docs/BOARD_AND_DREAM_UI.md` §5–§9 before touching any of this.
It records the reasoning, including several rules that are counter-intuitive
until you have hit them (why not `LazyColumn`; why `stopLetter` is the wrong bus
key; why "Inbound" is never shown).

### Decisions that need YOUR sign-off

1. **SDUI no longer drives the home board.** `buildBoardLines`, the `BoardLine`
   model, `BoardSection.sduiPayload` and the ViewModel's `_sduiPayloads` /
   `loadSduiTemplateForSelection` are **deleted**. They were unreachable: a
   per-line SDUI template carries its own headers and rows for one line, which
   cannot express a board merged across lines by platform. `SduiWidgetPayload`
   and `GlobalBoardProcessor.bindSduiTemplate` remain in core, untouched, so the
   capability can be rebuilt against a merged-board template — but as of this
   commit the home board is **client-composed**. Revert this commit to restore.
2. **`LineShortNames` is a hardcoded client map.** Line naming is backend-owned
   everywhere else, so this WILL drift (the whole Overground fleet was renamed in
   2024). Intended end state: a `shortName` on the line API, with this map as the
   fallback for older payloads. Proposed names are in the file; `H&C` and `W&C`
   deliberately match TfL's own signage rather than inventing abbreviations.
3. **`"Bus"` is the one hardcoded user-facing word** in `MultiLineBoardProcessor`
   (`BUS_PREFIX`). It belongs in `homeConfig` beside `board.status_label`.
4. **New homeConfig keys** the backend may want to serve:
   `board.hero.no_departures`, `board.hero.min_label`,
   `explore.fares.{peak,offpeak}.subtitle_short`. The old
   `explore.fares.*.subtitle_prefix` keys are now **unused** — a backend still
   serving the long form would have re-wrapped the card to two lines, which is
   why the key changed rather than the default.

### Still open
- **Backend subscriptions were NOT reviewed.** Raised repeatedly and deferred
  every time. `StationLifecycleUseCase` still subscribes/unsubscribes per
  selection with the `remaining` guard; the ask was to confirm line-status
  subscriptions are one-per-line rather than one-per-board. **This is the top
  item.**
- Two unlettered bus poles at one hub both render `Bus 39` — the grouping is
  correct but the labels are ambiguous. Needs a per-naptan stop name from the
  backend (TfL has `indicator`/`towards`), or a `towards X` fallback.
- Promo cards lost `Modifier.animateItem()` in the lazy→eager conversion.
  Dismissals are instant rather than animated. `animateContentSize` would
  reintroduce height churn, so it was left out deliberately.

### Tests (new)
`core/src/commonTest/kotlin/util/` — 46 tests, all passing:
`MultiLineBoardProcessorTest`, `LineStatusRankerTest`, `LineShortNamesTest`,
`BoardIdentityAndEtaTest`. Every case is a bug that actually reached the device
during this session.

**Run them with `./gradlew :core:testDebugUnitTest`** (JVM). `:core:allTests`
fails on an unrelated pre-existing `wasmJs` test-dependency resolution problem,
and `:core:iosSimulatorArm64Test` needs a simulator SDK this machine does not
have. Kotlin/Native also rejects **commas inside backtick test names** — use an
em dash.

## 6c. Session 2026-08-04/05: collapsible stations, per-station settings

The home screen went from a scroll of full-height boards to a list of stations
of which one is open. Everything in this section is **iOS-only** — Android's
home is untouched, and `:composeApp`'s android target is consumed by nothing
(§1 topology).

### What changed
- **The station header moved OUT of the dot-matrix panel** onto the card, above
  the line pills, so the pills and the hero are finally labelled. No chevron —
  the whole header row is the control.
- **Cards collapse** to a header plus one **leg per platform/pole** (the soonest
  departure each way, `MultiLineBoardProcessor.collapsedLegs`, tested).
- **Two per-station settings** (`StationPrefs`, local-only, never synced):
  `pinned` (sorts to the top, industry meaning of "pin") and `openByDefault`
  (expands on every launch). Both are marked in the header with different icons.
  `hideHero` hides the countdown for that station.
- **`StationSettingsScreen`** owns one station: layout picker (two DRAWN
  previews of the real card), the two position toggles, lines grouped by line
  with a row per direction, and delete. **`HomeSettingsScreen`** (gear in the
  top bar) owns the cross-station settings.
- **The top bar's pencil is gone.** It opened the add flow, which does not edit.
  Now `+` alone, with the gear to its right.
- **Nested scroll ownership** (`boardScrollOwnership`): one drag belongs to one
  scroller, decided once per gesture, so the board no longer slides the page
  away when you reach the last departure.

Full reasoning: `BOARD_AND_DREAM_UI.md` §10–§15. **Read it before changing any
of this** — several rules there are counter-intuitive until you have hit them.

### Bugs found in the review pass (all fixed)
1. **Deleting a board from the settings screen never told the backend.**
   `discardStation` is LOCAL teardown only; the sync lived inline in the home
   VM's delete path and did not come along. A cloud restore or a second device
   would have resurrected the board. Now one shared
   `SyncSubscribedStationsUseCase`.
2. **`SelectionViewModel.openForStation` could lose its seeded station.** A
   nearby/search refresh replaces `dropdownData["station"]` wholesale, and
   `saveSelection` reads the display name from that list with `?: stationId` —
   adding a line while editing such a station would have saved a board named
   `940GZZLUKSX`. Held in `editStationOption` and re-inserted now.
3. **A Kotlin/Native SIGSEGV on opening the settings screen.** A property
   declared BELOW `init` was written by the coroutine `init` launches —
   `viewModelScope` is `Main.immediate`, so that coroutine runs synchronously
   during construction. See the boxed note in `BOARD_AND_DREAM_UI.md` §14,
   including how to pull the crash report off the device.

### Removed
`SummaryViewModel.deleteSelection` / `deleteStation` / `deleteSelectionInternal`
and `isDeletingBoard` (~96 lines, unreachable once delete moved to the settings
screen), the home screen's delete `LoadingOverlay`, `BoardDeleteDialog`,
`BoardDeleteBullet`, the board's filter captions, and `MULTI_BOARD_FRACTION` /
`NEXT_CARD_PEEK`.

### Tests
`core:testDebugUnitTest` — **62 green**, of which 16 are new:
7 in `MultiLineBoardProcessorTest` (collapsed legs) and 9 in the new
`BoardLabelsTest` (how a board names itself on the settings screen).

### Still open
- **Backend line-status subscriptions were STILL not reviewed** (§6b's top
  item, deferred again). One-per-line vs one-per-board.
- Two unlettered bus poles at one hub still render the same label.
- `HomeSettingsScreen`'s bulk actions write a preferences row per station; that
  is fine at today's station counts but is a full-map rewrite each time.
- Nothing here is covered by UI tests — `:composeApp` has no test source set,
  which is why `BoardLabels` was moved into `:core` to be testable at all.

## 7. Which doc to open

| Doc | Reach for it when |
|---|---|
| **`IOS_HANDOVER.md`** (this) | Starting a session. Always first. |
| `IOS_BUILD_AND_HANDOFF.md` | Build/deploy mechanics, Xcode setup, per-session history. The long one. |
| `IOS_PARITY_GAP_ANALYSIS.md` | "Does iOS have X yet?" Component-by-component sweep vs Android. |
| `IOS_LIVE_STREAM.md` | Anything touching the WebSocket, its protocol or its three fixed bugs. |
| `IOS_WIDGET_DESIGN.md` | Widget layout, the archiver pitfall, paging decision. |
| `IOS_PARITY_PLAN.md` | Original plan + environment/config setup. |
| `IOS_INFRA_AUDIT.md` | Security / testing / a11y / architecture audit findings. |
| `BOARD_AND_DREAM_UI.md` | The home screen, the board, collapsing/pinning stations, per-station settings. §10–§15 are the current layout. |
| `BOARD_DOTMATRIX_FONT.md` | Board typography — **the board uses no special font**, and DotGothic16 was tried and reverted for parity. |

---

## 8. Build + deploy (device, staging)

⚠️ **You must rebuild the XCFramework after any Kotlin edit.** Xcode links a
prebuilt artifact, so editing Kotlin and running only `xcodebuild` silently
ships stale code. This has cost real time more than once.

```bash
# 1. Regenerate the project (required after any project.yml change)
cd iosApp && ./xcodegen.sh && cd ..

# 2. Kotlin framework + Compose resources  (~6-9 min)
./gradlew :composeApp:assembleComposeAppDebugXCFramework \
          :composeApp:assembleIosArm64MainResources

# 3. Build, install, launch
DD=iosApp/build/DD
xcodebuild -project iosApp/iosApp.xcodeproj -scheme "iosApp Staging" \
  -destination 'id=00008030-001E0D9C3EFB802E' -derivedDataPath "$DD" \
  -allowProvisioningUpdates build
xcrun devicectl device install app --device 00008030-001E0D9C3EFB802E \
  "$DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device process launch --terminate-existing \
  --device 00008030-001E0D9C3EFB802E com.stationly.mobile
```

`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` does **not** work
from a plain shell — it needs Xcode's env vars.

### On-device debugging: the push/stream trace

`log stream` cannot target a device on recent macOS, and
`devicectl … --console` does not capture `print()` from a Compose/KMP process,
so on-device behaviour was effectively unobservable. Both sides of the app
write a bounded ring buffer to the App Group instead:

```bash
xcrun devicectl device copy from --device 00008030-001E0D9C3EFB802E \
  --domain-type appGroupDataContainer \
  --domain-identifier group.com.stationly.shared --source / --destination /tmp/pull
plutil -convert xml1 -o /tmp/ag.xml \
  /tmp/pull/Library/Preferences/group.com.stationly.shared.plist
python3 -c "import plistlib;print(*plistlib.load(open('/tmp/ag.xml','rb')).get('push_trace',[]),sep='\n')"
```

Keys: `stream:ready`, `stream:subscribe … force=`, `stream:update station=`/`line=`,
`stream:refresh reusing live socket`, `stream:reconnect backoff=`,
`stream:error code=`, `stream:decodeError`, `stream:heartbeatFailed`,
`stream:EXCEPTION`, `kmp:*`, `apns:*`, `bgRefresh=`. The widget extension keeps
its own `widget_refresh_trace`.

---

## 9. Next steps, in priority order

1. **Review backend subscriptions for multi-line** (§6b "Still open"). Deferred
   through the whole 2026-08-03 session and never looked at.
2. **Commit the working tree** using the §5 split. It is the single biggest
   risk on this branch — two sessions of unversioned work.
3. **QA the promos and the stream on device**: widget promo appears only with
   no widget installed; dream promo retires after one run; notification banner
   tracks the Settings toggle; force-update dialog fires against a raised
   `app.minVersion`.
4. **Re-check reconnect churn** now that the `openIfNeeded` race is fixed — the
   trace should show no `stream:reconnect` bursts in steady state. If it still
   churns, the foreground/background-cycling hypothesis in
   `IOS_LIVE_STREAM.md` §7.2 is back on the table.
5. **Exercise the stream past one station** — the 25-cap and `unknown_station`
   paths are unexercised.
6. **More tests**: `LiveStreamManager` reconnect/backoff and force-resubscribe.
7. **Verify prod nginx** before pointing production builds at the stream.
