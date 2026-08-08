# iOS — consolidated handover

**Branch:** `ios-parity` · **Last updated:** 2026-08-08
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
| **Home / board** | Full Android parity: dot-matrix board, per-minute tick, pull-to-refresh, promos, offline banner, SDUI strings with hardcoded fallbacks. Plus iOS-only per-station **board arrangement** (order, depth, pin) — §6f(1), which the widget now honours too. |
| **Live departures** | **WebSocket stream**, iOS-only. Replaces REST polling for predictions + line status. See §3. |
| **Widget** | Full-bleed board, **one station per widget** (configured like the Weather widget), platform paging by arrows, interactive refresh, live clock, per-minute tick, departed-row retention. Renders KMP's own blocks and headers, so it groups and labels exactly like the home board — §6h(1). |
| **Dream / screensaver** | Full port as an in-app route (iOS has no system screensaver slot). SDUI-driven. |
| **Push** | APNs + FCM live on the paid team. `aps-environment` tracks the build config. |
| **Signing** | Stationly Limited org team **`7T7D5LLYSL`**. App Group **`group.com.stationly.shared`**. |

### Not done / known gaps

1. **The working tree is clean and everything is pushed** as of 2026-08-08
   (`138bdb1`). Every session listed in §5 is now a commit on `origin/ios-parity`.
   What is NOT done is device QA — see §9, which is the whole of the top of the
   list.
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

## 5. Branch history and uncommitted work

**Everything is committed** on `ios-parity` and pushed. The list below is the
shape of that history, kept because the commit messages are terse and these are
the entries that explain WHY:

- **(a) Home promos + parity fixes (2026-07-25)** — closed the dead force-update
  gate (`_forceUpdate` was declared but never assigned, so `UpdateNudgeDialog` was
  unreachable on iOS however low the installed version), the missing Profile
  "Rate Stationly" row, notification-prompt timing, and ~300 lines of Android home
  surface iOS never had.
- **(b) Live departure stream (2026-08-01/02)** — see `IOS_LIVE_STREAM.md`.
- **(c) Backend-sync and teardown fixes** — the highest-risk-if-lost changes.
  `syncStations` now runs *before* cleanup, and `clearBoardsPreservingIdentity()`
  replaces `cleanupAll()` on the board-swap path: `cleanupAll()` calls
  `storageManager.clearAll()`, which on iOS is
  `removePersistentDomainForName(bundleId)` and wipes the whole standard
  NSUserDefaults domain including `firebase_user_uid` / `firebase_auth_token`. One
  board change left the app with no uid and no bearer token and every auth-gated
  call in between failed silently. **Android is unaffected** — its `clearAll()`
  only clears a SharedPrefs file, and its identity lives in
  `FirebaseAuth.currentUser`. Also: `ProfileViewModel` runs backend teardown
  before `signOut()` (both are auth-gated and read a token `signOut()` clears),
  and `FcmTokenRegistrar` is driven from `SummaryViewModel` init + every
  foreground so keychain-restored sessions and rotated tokens reach the backend.
- **(d) Review pass (2026-08-02)** — §6.
- **(e) Multi-line board + home-screen fit (2026-08-03)** — §6b, commit `1226f64`
  and `22b8182`.
- **(f) Collapsible stations + per-station settings (2026-08-04/05)** — §6c,
  commit `983b927`.
- **(g) Home-screen polish + station ordering (2026-08-05)** — §6d, commit
  `3fb34f6`. Station cards became real containers, expand/collapse became one
  choreographed transition, pinning was replaced by an ordered list, and the
  profile stopped duplicating the station list. §6d also records four FAILED
  attempts at drag-to-reorder; they were superseded by (h) and are kept because
  they are why the working version is built the way it is.
- **(h) Carousel home + working drag-to-reorder (2026-08-06)** — §6e, commit
  `ab2fec7`.
- **(i) Board settings, multi-station widgets, platform arrows (2026-08-07/08)**
  — §6f, commit `138bdb1`.
- **(j) Refresh speed, one grouping rule, FCM rename (2026-08-08)** — §6g,
  commit `a15c57e`.
- **(k) Widget board parity, backend line short names, tap latency
  (2026-08-08)** — §6h, uncommitted at time of writing.

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
- **Two separate row limits**, previously conflated: a per-platform ceiling
  (`MAX_ROWS_PER_PLATFORM = 3` then; `BoardDisplayPrefs.rowCap`, user-set, since
  2026-08-07 — §6f) and `MIN_BOARD_ROWS = 3`, a floor for the **whole**
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

## 6d. Session 2026-08-05: home-screen polish, station ordering

All iOS-only. `:android:app` depends on `:core` alone and nothing consumes
`:composeApp`'s android target, so none of the UI work here can reach Android;
the two `core` changes are shared and are covered by tests.

### What changed

**Station cards are containers.** A raised surface with a hairline border and a
3dp accent rail across the top, so it is obvious where one station ends and the
next begins. Full write-up: `BOARD_AND_DREAM_UI.md` §16 — including
`CARD_CHROME_HEIGHT`, which must stay in the height budget or the
three-visible-rows floor silently shrinks.

**Expand/collapse is one choreographed transition.** A single `updateTransition`
drives the body, the legs, the chevron and the rail; the body's contents enter on
a stagger. Also §16.

**A state chevron in the card header,** rotating with that same transition. The
"opens by default" mark changed from `UnfoldMore` to `OpenInFull` to stop reading
as a duplicate of it, and the station settings switch now uses the same glyph.

**Pinning is gone; order is a list.** `StationPrefs.pinned` deleted,
`StationPrefsRepository.order` (`station_order_v1`) added, home settings grew a
**Your stations** list. Why a per-station pin could not work: §11 of
`BOARD_AND_DREAM_UI.md`.

**Home settings reorganised.** Appearance moved to the bottom and became one
segmented row (three icon+label segments) — a theme is set once and never thought
about again, while the station list is what people come here for. The entire
station-wide board section was removed: "open every station by default", "collapse
every station on launch" and "show the countdown everywhere" were each a second
way to set something that already has one home on the station's own screen.

**The profile no longer lists stations.** `StationCard`, `EmptyStationsCard`, the
delete-board dialog, and `ProfileViewModel.{loadStations,deleteStation,
syncStationsToBackend}` are gone, along with three `ProfileUiState` fields. It was
a read-only second copy of the home screen's stations whose only action was
delete — which meant a second implementation of "discard a board", i.e. a second
place for the survivor logic to be got wrong.

**Good Service shows its description again.** `LineStatusRanker.rotation` was
hard-coding `reason = ""` in the all-healthy branch and throwing away what the feed
sent, so the strip's marquee was dead on the state the board is in nearly all the
time. `BOARD_AND_DREAM_UI.md` §17.

**Haptics stopped stalling the main thread.** `performHaptic` allocated a new
`UIImpactFeedbackGenerator` and called `prepare()` on every call. `prepare()` wakes
the Taptic Engine and Apple's guidance is to call it *ahead* of the event, not as
part of it. Harmless for a button; visible as a hitch anywhere haptics fire in
quick succession. The generators are cached and re-armed after each use. **This
one is app-wide, not list-specific.**

**Double-navigation guard.** `AppNavigation.navigateFrom(origin, route)` pushes
only if the current route is the screen that asked. Two fast taps on a station row
used to push station settings twice, so backing out landed on it again.

### ⚠️ NOT WORKING: drag-to-reorder the station list

> **Superseded on 2026-08-06 — it works now.** See §6e and
> `BOARD_AND_DREAM_UI.md` §19. The missing piece was the second hypothesis
> below: the parent `verticalScroll` was consuming the move, and the fix is to
> claim the gesture on the `Initial` pass. The rest of this section is kept as
> the record of what was tried, because it is why the working version is built
> the way it is.

**Status at the time: abandoned, shipped as arrows instead.** The **Your stations**
list reorders with up/down buttons on each row (`StationOrderCard`). They are
plain `clickable`s with no gesture arbitration anywhere near them, and the rows
still animate past each other, so the feature works — but the user asked for
press-and-hold drag-and-drop and that is not what this is.

Do not treat this as untried. Four attempts, each fixing something real and each
still failing:

1. **Drag handle + `detectDragGestures`, list reordered on every swap.**
   Felt broken. Two causes found: `pointerInput` was keyed on the list, so the
   first swap tore the modifier down and cancelled the in-flight gesture; and the
   drag offset was an `Animatable` written through `launch { snapTo() }`, so the
   swap test on the next line read the offset from *before* the finger moved. The
   rows only got out of the way once the coroutines caught up, which in practice
   was on release.
2. **Long-press pickup, synchronous offset, animated slots.** Better, still not
   smooth. Rows were re-placed but the lift animation was read during composition,
   so every frame of it rebuilt the row's text and roundel.
3. **List frozen during the drag; positions derived from two indices.** This part
   was right and is worth keeping if anyone resumes: reordering the list reorders
   the *composables*, and the drop is jump-free only if every row already sits at
   the index the committed list is about to give it. (The algorithm: rows between
   the origin and the target step one place towards the origin; everything outside
   that span does not move. It was `DragReorder.slotFor`, removed with the rest —
   `ListReorder.moved` is what survives, and the two must always agree.)
4. **One detector on the container instead of per row.** The row-level detector
   could never have worked: a `PointerInputChange`'s position is in the
   coordinates of the node receiving it, so a row that moves to follow the finger
   sees a stationary finger, and `drag()` — which waits for a position change —
   delivers nothing. The symptom was exact: the row lifts (arming works, the
   shadow appears) and then will not move. Moving the detector to the container,
   which does not move, should have fixed it. **It did not, and I do not know
   why.** That is the honest state of it.

**Where to look next, in order:**
- Confirm whether `onStart` fires at all — put a log in `pickUp`. If the shadow
  appears but `from` is never set, the hold is arming somewhere else.
- Check whether the parent `verticalScroll` is consuming the move.
  `awaitDragOrCancellation` returns null the moment it sees an already-consumed
  change, which ends the drag silently and looks exactly like this. If so, the
  fix is to claim the gesture on the `Initial` pass once armed, or to disable the
  scroller while a row is held.
- Try `detectDragGesturesAfterLongPress` verbatim on the container first, with no
  custom hold and no press animation. If the stock detector also fails, the
  problem is arbitration with an ancestor and not this code.
- **Test in a release build before concluding anything.** Everything here was
  measured on a debug Compose/iOS build, which is meaningfully slower; some of the
  "not smooth" in attempts 1–3 may not exist in release.

### Bugs found and fixed on the way

- `StationPrefsRepository.setOrder` had to be observed, not just read —
  `SummaryViewModel.stationOrder` exists so a reorder in settings reaches the
  cards without waiting for something else to recompose them.
- The scrollbar showed on boards with nothing to scroll (`maxValue > 0` is true
  for a pixel of rounding overflow). Threshold is half a row now — §17.
- `Modifier.clickable` fires on release regardless of how long the press was, so
  a long press on a station row *also* opened its settings. Any future drag work
  must own the tap too, not sit next to a `clickable`.

### Removed

`StationPrefs.pinned`, `StationPrefsRepository.{clearPins,setOpenByDefaultForAll,
showHeroEverywhere}`, `StationSettingsViewModel.setPinned`, the profile's whole
station section (above), `HomeSettingsScreen.ChoiceRow`, and the bulk board
actions. Unused imports swept from every file touched.

### Tests

`core`: **68 green**, up from 62 — 5 new in `ListReorderTest` and 1 in
`LineStatusRankerTest` covering the Good Service reason. `:composeApp` still has no test source set,
which is why anything with a rule in it keeps moving to `core`.

### Gates

`:core:testDebugUnitTest`, `:composeApp:compileKotlinIosArm64`,
`:composeApp:compileDebugKotlinAndroid`, and a staging build installed on the
iPhone 11 — all green. **The arrows in the station list have not been verified on
device**; they were the last change made.

## 6e. Session 2026-08-06: carousel home, drag-to-reorder, copy pass

All iOS-only. `:android:app` depends on `:core` alone and nothing consumes
`:composeApp`'s android target, so none of the UI work here can reach Android;
the one `core` change (`ListReorder.slotOf`) is additive and covered by tests.

### New: the home screen has two layouts

`HomeLayout.LIST` (what shipped) or `HomeLayout.CAROUSEL`, one station per
swipeable page with dots. Chosen in home settings, which now opens with a
drawn picker for it; stored as `home_layout_v1` in `StationPrefsRepository`.
`BOARD_AND_DREAM_UI.md` §18 has the reasoning and the three things to know
before touching it — chiefly that the page height is fixed and comes from the
same budget a single open card gets in the list.

### New: drag to reorder actually works

The up/down arrows are gone; a row is held and dragged. **The fix the four
earlier attempts were missing is consuming the gesture on the `Initial` pointer
pass** — the ancestor `verticalScroll` claims drags on `Main`, and once it
consumes a move the child's drag ends silently, which is exactly the "lifts but
will not move" symptom §6d describes. Full write-up: `BOARD_AND_DREAM_UI.md`
§19. `ListReorder.slotOf` moved into core beside `moved`, with a test that walks
every from/to pair and asserts the two agree — that agreement is what makes the
drop jump-free.

**§6d below is now history, not current state.** Keep it: it is why this
attempt was built the way it was.

### Feedback fixes

| Ask | What changed |
|---|---|
| "Open by default" is unclear | Now an **Expanded / Collapsed** picker. `StationPrefs.openByDefault` renamed to `startExpanded`, with `@SerialName("openByDefault")` so nobody's stored preference resets. Hidden entirely in a carousel, where it would do nothing. |
| The expand glyph looks like fullscreen | It was `OpenInFull`, which *is* the fullscreen glyph everywhere else in iOS. Now `ExpandCircleDown` — the card's own chevron, badged. The settings picker uses the same glyph, rotated 180° for Collapsed, so the pair reads as exact opposites. |
| Theme button in the bottom-right corner | Removed, along with `ui/common/ThemeToggleButton.kt` (nothing else used it). Appearance lives in home settings, where the segmented row is now one line high instead of two. |
| Screensaver in Profile | Removed. It is a property of the home screen and home settings already has it; `profile.screensaver.title` / `.subtitle` are no longer read by anything. |
| The screensaver preview is blank | Rebuilt as a real board: signage card, station strip, departure rows with destinations and ETAs, status strip, and the clock where that layout actually puts it. The station NAME is the real one (that is what the picker below changes); the departures are fixed and fictional, for the same reason the station card's preview is. |
| Scrollbar only appears while scrolling | It was always drawn when scrollable, but at an effective alpha near 0.2. Now a lit amber rail that reads at rest and only brightens under the finger. Still absent when the board fits. |
| Strings read as AI-written | Em dashes are gone from every user-visible string in `composeApp` (the two left are a developer `error()` and a comment). Settings copy cut to a line: "Live departures while charging", "Open iOS notification settings", "Lines, directions and filters", "Only this board goes. The rest of $stationName stays." |
| Two icons on the station card | One. The chevron's ROTATION is the current state; a filled accent disc behind it is the mark that this station opens itself. They were two glyphs about the same axis, which reads as two controls. `BOARD_AND_DREAM_UI.md` §11. |
| "When the app opens" did not name the setting | Now **Default view**, with the caption saying when it applies. |
| The carousel overlapped and was not smooth | The page transform included `translationX = offset * width * 0.10`, which pulls each neighbour ~39pt toward the centre over an 18pt gutter — the next card's edge sitting on the current one. The edge `transformOrigin` compounded it. Both gone; scale about the centre pulls pages APART during a swipe. §18. |
| Carousel chips showed no line names | Chips are now roundel + station name on one line, line names underneath — the same reading order as the card and the list row. Coloured dots alone cannot tell Circle from Hammersmith & City, which at an interchange is the only difference between two chips. |

### Crash found and fixed during the device run

The first install aborted on the first frame, every launch: `SIGABRT`, and the
only useful frame in the `.ips` was `MetalRedrawer.draw`. The message is on the
console, not in the crash log — `xcrun devicectl device process launch --console`
gave `kotlin.RuntimeException: Can't wrap nullptr` and a Kotlin stack pointing at
`BoardScrollbar`.

Cause: the new gradient thumb divided by a `scroll.maxValue` that layout had just
set to 0 in the same frame, `coerceIn` passed the resulting NaN straight through
(every comparison against NaN is false), Skia returned a null shader for the
gradient, and Kotlin/Native aborted on the null. The solid fill it replaced had
been swallowing the same NaN silently. Written up in `BOARD_AND_DREAM_UI.md` §17
as a general rule, because any future draw lambda on this panel can hit it.

### Where the code lives now

The session added a carousel, a working drag, and a motion pass, and each of
those arrived as more code in files that were already the two longest in the
app. A consolidation pass at the end split them up and deleted the duplication
they had grown. **Start here when looking for any of it:**

| File | What it owns |
|---|---|
| `ui/common/ReorderBox.kt` | The hold-and-drag gesture, axis-agnostic and generic over the item type. The one place the five attempts' worth of hard-won detail lives. |
| `ui/common/SegmentedRow.kt` | Mutually-exclusive options with a selection that slides. Theme picker and expanded/collapsed picker. |
| `ui/common/SettingsUi.kt` | Section label, caption, card, divider, navigating row, picker tile — the furniture all three settings screens are built from. |
| `ui/common/MiniBoard.kt` | The departure board in miniature, plus the signage palette and the fictional departures every preview shows. |
| `ui/common/Press.kt` | `pressScale` / `pressHighlight` — iOS touch feedback, replacing Material's ripple. |
| `ui/station/StationOrder.kt` | What a STATION looks like while being dragged: the list row, the carousel chip, and the two containers. |
| `ui/summary/StationCarousel.kt` | The pager, its page transform, and the dots. |
| `ui/summary/HomeBoardBudget.kt` | The height arithmetic: `MIN_BOARD_HEIGHT`, `boardMaxHeight`, and every measurement they depend on. |

What that removed, in case it looks like something is missing:

- **Two copies of the settings furniture.** `HomeSettingsScreen` had
  `HomeSectionLabel`/`HomeCaption`/`HomeCard`/`HomeDivider`/`ActionRowSimple`;
  `StationSettingsScreen` had `SectionLabel`/`SectionCaption`/`SettingsCard`/
  `HairlineDivider`/`ActionRow`. Same six components under two sets of names,
  already drifting in padding and radius. Now one set in `SettingsUi.kt`. Home's
  rows gained the trailing chevron the station rows already had, which is
  correct — every one of them navigates.
- **Two copies of the mini board**, each with its own private spelling of the
  signage palette (`PreviewPanelBg`/`PreviewAmber`/`PreviewActiveRow` in one,
  `PreviewBoardBg`/`PreviewRowBg`/`PreviewAmber` in the other) and its own idea
  of what was on it. Two previews of the same board that disagree about its
  contents look like two products.
- **Two copies of the picker tile** (`LayoutTile`, `LayoutOption`) — animated
  border, press-scale, artwork, label — now `PickerTile`.
- **The drag, twice**, once per axis. `ReorderBox` takes `horizontal: Boolean`
  and everything else is shared. This one matters most: a bug fixed in one copy
  of a gesture that took five attempts would not have been fixed in the other.

`SummaryScreen` went 1441 → 1099 lines and `HomeSettingsScreen` 1068 → 421,
without anything being deleted that a user can see.

### Gates

`:core:testDebugUnitTest` **72 green** (was 68), `:composeApp:compileKotlinIosArm64`,
`:composeApp:compileDebugKotlinAndroid`, `:android:app:compileProdReleaseKotlin`,
and a staging build installed on the iPhone 11, launched, and confirmed still
running (no crash report newer than the launch).

### Not verified on device

Everything here is built and installed but **not QA'd by hand**. In priority
order: the drag (four attempts failed before this one — if a row lifts and will
not move, the Initial-pass claim is being beaten by something new, and
`IOS_HANDOVER.md` §6d lists what else to check); the carousel's page height on a
small screen with a promo showing; and whether the carousel's per-page haptic is
welcome or annoying in practice.

## 6f. Session 2026-08-07/08: board settings, multi-station widgets, platform arrows

Two features plus a review pass. The first is iOS-only app work; the second
changes the widget extension and `core/iosMain`, which Android does not compile.

**Android safety.** `core/commonMain` gains three files
(`BoardDisplayPrefs.kt`, additions to `MultiLineBoardProcessor`, tests) and
every one of them is **additive with a default**: `buildRows` and
`collapsedLegs` take `prefs: BoardDisplayPrefs = BoardDisplayPrefs()`, whose
defaults reproduce the previous behaviour exactly. Android calls neither — they
were written for the iOS multi-line board — but the defaults mean it could and
nothing would change. Everything else is `core/iosMain`, `composeApp` or Swift.

### (1) The user arranges their own departure board

Per station, on its settings screen: **Order** (time / platform / destination),
**Departures per platform** (2–5, default 3), and **Show first** (pin one
platform or one line to the top). Rules live in
`core/util/BoardDisplayPrefs.kt`, are applied by `MultiLineBoardProcessor`, and
are edited by `ui/station/BoardArrangement.kt`.

**The platform grouping is not one of the settings and must not become one.**
Everything under a header is one queue in one place you can walk to; a flat
re-sort of the whole board would leave the user reading the platform off every
row to know where to stand. So each control acts at exactly one level — block
order, or row order inside a block — and `BOARD_AND_DREAM_UI.md` §20b has the
table of which does which, plus the three rules most likely to be got wrong
later:

- **The cap picks WHICH trains; the sort only arranges them.** Applying the cap
  after a destination sort keeps the alphabetically-first destinations, which at
  Green Park means three Cockfosters trains an hour out and no sign of the one
  leaving now.
- **A pinned LINE promotes every platform it calls at**, because a line at an
  interchange is genuinely on more than one, and lifting its rows out of their
  blocks would put them under a header naming no place you can stand.
- **Unassigned platforms still sort last, above the pin.** A pin is a
  preference; "you cannot stand on a platform TfL has not allocated" is a fact.

`MultiLineBoardProcessor.MAX_ROWS_PER_PLATFORM` is gone — the ceiling is
`BoardDisplayPrefs.rowCap`, clamped on READ. `MIN_BOARD_ROWS` stays a constant
and is deliberately not user-facing.

There is **no drawn preview**, unlike the layout picker directly above it on the
same screen, and that is a decision rather than an omission: the real board is
one back-tap away, and the alternative (drawing one from the SQLite cache) would
put hours-old ETAs on a settings screen where they are indistinguishable from
live ones. §20b records what carries the meaning instead.

**Known gap: the widget does not see any of this.** `StationPrefs` lives in the
standard NSUserDefaults suite and the extension reads the App Group.

Tests: 12 new in `MultiLineBoardProcessorTest`, one per rule above (plus 4
more from the review pass — see §6f(3) and the gate count below).

### (2) One widget, one station — and arrows to page platforms

Full write-up: `IOS_WIDGET_DESIGN.md` **§5 and §6**. The short version:

- The widget is now configured per instance with a **station**, the iOS Weather
  model (`AppIntentConfiguration` + `StationEntity`/`StationEntityQuery`). It
  was a `StaticConfiguration` reading one set of flat keys, so every widget on
  the home screen showed the same board.
- KMP now writes **every** station to the App Group: `widget_stations` (the
  directory the picker reads) and `widget_board_<groupingId>` per station. The
  legacy flat keys are still written for the primary, which is what an
  unconfigured widget reads.
- A station's board is **merged across its lines**, the way the app's card
  merges it.
- The medium board's platform paging is now a **chevron at each end of the
  header**, dimmed when there is nothing that way, with the board pushed in the
  direction the arrow points. The page is clamped rather than modulo, and keyed
  per station.

Three things came out of using it on device, and each is a rule rather than a
tweak:

- **A dimmed control must still be a Button.** Drawn as plain content, the
  disabled arrow fell through to the widget's own tap target and LAUNCHED THE
  APP. Every non-interactive pixel of a widget belongs to that target. It is a
  Button in both states now and the clamp makes the tap a no-op.
- **Do not call `reloadTimelines` from an interactive intent** unless the DATA
  changed. WidgetKit re-renders the tapped widget from the timeline it already
  holds, which is immediate; the explicit reload threw that away and rebuilt all
  61 entries (re-ticking every departure) before the new page could draw. That
  rebuild was the entire lag between tap and movement.
- **The picker's chrome is Apple's; its rows are ours.** The list cannot be
  themed or inlined into the editor — it is the same sheet Weather's Location
  picker uses. Title, subtitle and image are the three things we control, so the
  image is the real roundel: `DisplayRepresentation.Image(data:)` takes a
  bitmap (verified in the SDK's `AppIntents.swiftinterface`), and
  `RoundelImage` falls through cached PNG → drawn roundel in the mode's colour →
  SF Symbol. The middle rung is not optional: the `/modes` PNGs are absent on a
  fresh install, which is exactly when someone is setting their widgets up.

**The trap this session actually hit:** Kotlin edits made after
`assembleComposeAppDebugXCFramework` has run are NOT in the build. Here it
presented as an empty station picker — the widget's Swift was correct and
shipping, `widget_stations` was simply never written, which looks exactly like a
broken `EntityQuery`. §8's warning is not decorative. `IOS_WIDGET_DESIGN.md` §5
has the one-liner that dumps the App Group to check.

### (3) Review pass

A full re-read of everything above. Findings, all fixed:

**Correctness**

1. **The disabled-arrow app launch** and **the paging lag**, above.
2. **`RoundelImage`'s cache was a data race.** `EntityQuery` methods are `async`
   and the system runs them off any executor; an unsynchronised static
   Dictionary mutated from two of them can crash while resizing, not merely lose
   a write. Behind an `NSLock` now, with the *build* outside the lock so one
   slow row cannot serialise the sheet.
3. **The pin picker could render with only a "Nothing" chip** — a station whose
   board has never loaded and which tracks one line has nothing to promote. The
   whole section is hidden in that case, and shown anyway if a pin is already
   set, so a setting in force is always reachable.
4. **Deleting a board left the picker offering its platforms.** The boards list
   re-emits from the repository; the CACHED reads (`towards`, `platforms`) did
   not. `deleteBoard` re-reads them now.

**Cost**

5. **The primary station's board was built twice per refresh** — once for its
   own key and once for the legacy keys — which is ~3 SQL queries per selection
   run twice on every stream frame. Built once, reused.
6. **The reload signal was bumped on every write.** That signal is what makes
   Swift call `WidgetCenter.reloadAllTimelines()`, so a push for a station no
   widget shows still asked WidgetKit to regenerate every timeline — and Apple
   meters those (~40–70/day). Writes are diffed (`putIfChanged`) and the signal
   is bumped once per pass, only if something moved.
7. **`pruneStaleBoards` called `dictionaryRepresentation()`**, which materialises
   the entire user-defaults domain, on every push. The station ids are read back
   out of the directory we already write, which is cheaper and more precise.

**Duplication**

8. **~20 raw App Group key literals across four Swift files**, with four keys
   spelled out in two files each. This is the same failure mode as the App Group
   ID rename in §6: a mistyped key reads `nil`, which is indistinguishable from
   "the app never wrote it", and the symptom lands on the home screen rather
   than in a compiler message. Now one `AppGroupKeys.swift` per target,
   mirroring the Kotlin object, with the two paging keys declared on both sides
   because KMP is the only side with an event for "this station is gone".
9. **A second line-naming map in Swift.** The picker prettified canonical ids
   ("hammersmith-city" → "Hammersmith & City") and disagreed with the app's own
   station settings screen. KMP resolves display names through
   `LineShortNames.displayName` before writing the directory; the extension has
   no line vocabulary at all.
10. **`mode == "bus"` written out at four call sites** — the board, the panel,
    the settings screen, its ViewModel — agreeing only by coincidence. One
    `MultiLineBoardProcessor.isBus(mode)`, beside the grouping rules that depend
    on it, tested.
11. **`WidgetRefreshService` kept its own aliases** for three keys that now live
    in `AppGroupKeys`. Deleted; one name per key.
12. **One key's name was nested inside another's prefix.** The paging state was
    `widget_board_page_<id>`, which sits under the `widget_board_` prefix a
    station's board uses: any prefix scan reads it as a station whose id begins
    "page_". Nothing scans by prefix today — that is the point. Renamed
    `widget_page_<id>` on both sides before something could.

**Naming and clarity**

13. `refreshFromPrimary` → **`refreshAllBoards`**: it stopped being about the
    primary the moment it wrote every station.
14. `updateWidget(state)`'s unused parameter now says WHY it is unused and that
    it must stay that way — it describes one board, and the method has to leave
    every station's board correct.
15. The refresh guard tested `feeds.first`, so a board whose first feed carried
    a blank naptan bailed even when the rest were fine. It tests the naptans it
    is about to fetch.

**Accessibility**

16. The pin chips carry `Role.RadioButton` + `selected`. Selection was conveyed
    by colour and weight alone, which VoiceOver cannot see. (The app's wider a11y
    gap is tracked in `IOS_INFRA_AUDIT.md`; this is one control, not a sweep.)

### Gates

`:core:testDebugUnitTest` **88 green** (was 72 at session start — 16 new),
`:composeApp:compileKotlinIosArm64`, `:composeApp:compileDebugKotlinAndroid`,
`:android:app:compileProdReleaseKotlin`, plus a staging build installed and
launched on the iPhone 11 with the App Group verified by hand (7 stations in
`widget_stations`, King's Cross merged across Circle + H&C with all four feeds).

### Known gaps, deliberately left

- **The widget cannot see the board-arrangement settings.** `StationPrefs` is in
  the standard NSUserDefaults suite; the extension reads the App Group. Moving
  it is a real option and is not free — it would put a per-station preferences
  blob on the hot write path.
- **No line prefixes on a widget row at a mixed platform**, unlike the app's
  board. The extension's own refresh re-derives rows from REST and cannot know
  which line each came from, so prefixes would appear on a push and vanish on a
  refresh tap.
- **Two widgets on the same station share a page counter.** WidgetKit exposes no
  per-instance identifier.
- **The in-extension refresh still cannot write SQLite**, so a refreshed board
  reaches the widget and not the app's store. Pre-existing; the app re-syncs on
  next foreground.
- **`widget_board_page`** (the old global page counter) and any
  `widget_board_page_<id>` from this session's first cut are orphaned in the App
  Group on devices that ran those builds. They are stale integers nothing reads;
  migration code on the hot path to delete them would cost more than it saves.
  A page counter carries no user intent, so losing one is invisible.
- **No Swift tests.** `:composeApp` has no test source set either, which is why
  everything with a rule in it keeps moving into `core`.

### Not verified on device

The station-settings controls (Order / Departures per platform / Show first)
are installed and running but have not been exercised by hand. On the widget
side, still unconfirmed: two widgets showing two different stations at once, the
gallery showing one tile per station (`recommendations()`), and search inside
the picker.

## 6g. Session 2026-08-08: refresh speed, one grouping rule, FCM rename

Three strands: making every refresh path fast, giving every board surface ONE
grouping implementation, and correcting names that claimed FCM was involved
where it is not. Plus three bugs found on device, two of them mine.

### (1) Every refresh path was doing far more work than it needed to

| Path | Before | Now |
|---|---|---|
| Home pull-to-refresh | serial loop over every SELECTION | concurrent fan-out over unique STOPS |
| Cold start | refreshed `selections.firstOrNull()` only | every stale board |
| Foreground | **no departure fetch at all** | stale boards refetched |
| Stream frame lands | reloaded ALL boards | only the boards it touched |
| Widget refresh button | one station, serial naptans | every installed widget, concurrent |

**`DepartureRepository.refreshBoards`** is the new fan-out and the reason the
first three rows changed. It deduplicates before it fetches, which matters more
than the concurrency: a rail station tracked in both directions is two
selections sharing one naptan, and the predictions endpoint answers with every
line and direction calling there — so the second call fetched a payload
identical to the first. Line status was worse: seven stations across five lines
asked fourteen times for five answers. Deduplicating turns ~2N requests into
(unique stops + unique lines); running them concurrently turns the remainder
into roughly ONE round trip instead of a sum. `onUpdated` fires per selection as
that stop lands, so boards repaint while their neighbours are still in flight.

**The cold-start line was one character of scope.** `refreshDataIfStale(
selections.firstOrNull())` meant everything after the FIRST board painted from
SQLite and waited for the 30 s poll — which only re-READS SQL — or for a stream
frame. Station two onwards could show departures minutes old with nothing
saying so.

**Foreground had no fetch at all.** `notifyForeground()` reopens the socket and
asks it for nothing. So returning from another app showed whatever was in
SQLite when you left, until a frame happened to arrive on a socket that had just
been reopened — i.e. exactly when it is slowest.

**`FreshDataNotifier` now names what changed.** It carried a bare `Unit`, so
every collector assumed the worst and reloaded everything it owned. Affordable
when the only trigger was an FCM push; not affordable against a live stream
emitting per station every few seconds — a phone tracking seven stations in two
directions did fourteen SQL reads plus fourteen board re-derivations **per
frame**, nearly all re-reading unchanged rows. `FreshData.Station` /
`.Line` / `.All`, with `All` kept as the honest fallback for an emitter that
cannot name its scope. Android has always been targeted (`predictions_<station>_
<line>` prefs pings); this is that precision in the shared notifier.

### (2) REST is now hedged behind the WebSocket

`StreamBackedTflApiService` served predictions and line status from the socket
alone. That is the right transport in steady state and the WRONG one at exactly
the moments a user is watching: a cold socket does a TCP connect, a TLS
handshake, an HTTP upgrade, a subscribe and a wait for the server's snapshot
before `ensureStation` returns, with a six-second ceiling. That is first launch,
and every return from background where the socket was reaped.

**Hedging, not racing.** Firing both every time would double request volume for
a warm socket that was going to win anyway. The stream goes first alone; REST is
asked only if the stream has not answered within `HEDGE_DELAY_MS` (250 ms) or
has already failed.

**Cancelling the loser does not unsubscribe** — this is what makes it safe
rather than merely fast. `ensureStation`'s `finally` discards only the AWAITER;
the subscription lives on the socket's own state. So when REST wins, the station
is still subscribed and the stream keeps pushing. REST buys the first answer;
the socket keeps doing the job it is good at.

Verified on device: `stream:hedge REST won station:490012211N` in the push trace.

### (3) One grouping implementation

`MultiLineBoardProcessor.buildGroups` now does the grouping, ordering, capping
and header text with no opinion about rendering; `buildRows` is a flattening of
it. This exists because the widget re-derived its own grouping and got wrong the
exact case this file warns about — it grouped buses by `platform`, so two poles
at one hub with no letters between them collapsed into a single block with both
directions interleaved. Confirmed in live App Group data: Smithwood Close, 7
predictions, **every one with `platform=""`**, across two naptans.

`buildGroups` takes a `rowCap` override because the widget needs RESERVES rather
than a display depth — it re-derives ETA labels every minute from a timeline
built once, so capping its payload at what fits leaves it nothing to shift into
view.

### (4) The FCM rename

`FcmPayload` → **`PredictionsPayload`**, `ProcessFcmPayloadUseCase` →
**`ProcessPredictionsUseCase`**, `FcmPayloadBridge` → **`PushPayloadBridge`**.

The DTO was never FCM-specific: the same JSON shape arrives from an FCM push, a
WebSocket frame and a REST response, and on iOS the socket is the board's real
source. The name asserted otherwise, which is how the question "why are we
relying on FCM here?" arises in the first place.

**Nothing FCM-specific was renamed or removed, on either platform.** Android's
`FcmMessagingService`, `onMessageReceived`, `onNewToken`, `subscribeToTopic`,
its manifest registration and the `fcm_topics` / `fcm_token` App Group keys are
all untouched — the Android diff is four lines, every one a type name. iOS keeps
`FcmTokenRegistrar`, `FCMBridge.swift` and the APNs→FCM path in `AppDelegate`,
which genuinely is FCM and genuinely is live.

### (5) Three bugs found on device

1. **SIGSEGV on every launch.** `staleRefreshLock` was declared BELOW `init`,
   and `init` launches a coroutine that reads it. `viewModelScope` is
   `Main.immediate`, so that coroutine runs synchronously during construction,
   before the property exists. Signal 11, crash report naming only
   `MetalRedrawer.draw`. **This is the second time this branch has paid for this
   exact trap** — `BOARD_AND_DREAM_UI.md` §14 documents the first, on the
   station settings screen. The declaration now carries a warning saying so.
2. **Pull-to-refresh spun for ever.** `LiveStreamManager.ensureStation` bounds
   itself with `withTimeout`, which throws `TimeoutCancellationException` — a
   **subclass of `CancellationException`**. The hedge's `catch (CancellationException) { throw e }`
   therefore re-threw a timed-out socket as "the hedge was cancelled", so it
   never counted as a failed attempt; with REST also failing, nothing completed
   the result. And because the await sat INSIDE `mutexFor(naptan).withLock`, the
   hung fetch held that station's mutex for the life of the process, so every
   later refresh for it blocked behind a lock that would never be released.
   Fixed three ways: catch `TimeoutCancellationException` explicitly before the
   cancellation clause (in `hedged` AND in `DepartureRepository.guarded`, which
   had the identical trap); replace the failure counter with a watchdog that
   joins both jobs and completes exceptionally if neither won — drawing the
   conclusion from the jobs themselves, so no new failure route can slip past;
   and a hard per-stop ceiling so a stall can never pin a spinner again.
3. **One station added six seconds to every refresh.** All Saints DLR
   (`940GZZDLALL`) sends `{"lines":{}, "name":null}` when it has nothing to
   report. `PredictionsPayload.name` was a non-null `String` with **no default**,
   and `coerceInputValues = true` (already set on both JSON configs) can only
   coerce null to a DEFAULT — with none, the whole payload was rejected. Every
   frame for that station failed to decode, `ensureStation` never resolved and
   burned its full six seconds, and because REST deserialises the SAME model the
   hedge could not rescue it. `name` and `lut` are defaulted now, as is
   `LineData.name`. **The lesson is `coerceInputValues` without a default does
   nothing** — the flag was there and looked like protection.

### (6) Review pass

- **`DepartureRepository.fetchPredictions` had zero callers.** Deleted.
- **`fetchInitialData` was a second implementation of "refresh a board"** — its
  own copy of the two fetches, the per-station lock and the error handling, and
  the shipping Android app runs it. Now delegates to `refreshBoards`. The cache
  fallback survives, expressed better: `refreshBoards` writes a fresh status to
  SQL before returning, so reading it back yields the new value on success and
  the cached one on failure, without needing to know which case it is in.
- **`loadPredictions(x); loadLineStatus(x)` at four call sites** → one
  `reloadBoard`. Four places to forget the second half, and forgetting it gives
  fresh trains under a stale status strip, which reads as a backend bug.
- **`buildBoard` stamped `lineShort` on bus rows** and relied on the RENDERER to
  suppress it, while `buildGroups` blanks it at the source. Two rules for one
  decision; the second copy is the one that gets forgotten.
- **Every pull subscribed twice.** `refreshAll` called `notifyPullToRefresh()`
  (blanket force-resubscribe of everything currently subscribed) and then the
  fan-out force-subscribed each stop through `ensureStation`. The server replays
  a cached snapshot per subscribe, so a pull cost double the frames — and the
  blanket call also resubscribed stations no longer on screen. Removed; a dead
  socket is still reconnected by `openIfNeeded` inside `ensureStation`.
- `RefreshBoardIntent` no longer reloads on top of the service's own reload: a
  DEBOUNCED tap, which deliberately does no work, was still asking WidgetKit to
  regenerate all 61 entries of every timeline.

### Gates

`:core:testDebugUnitTest` **92 green** (was 88 — 4 new in
`MultiLineBoardProcessorTest` covering the two-poles case, rail grouping across
lines, the bus no-prefix rule, and an agreement test that `buildRows` and
`buildGroups` never disagree), `:composeApp:compileKotlinIosArm64`,
`:composeApp:compileDebugKotlinAndroid`, `:android:app:compileProdReleaseKotlin`,
the `StationlyWidget` target, and a staging build installed and running on the
iPhone 11 with the App Group verified by hand.

### ⚠️ NOT FINISHED — ✅ ALL CLOSED IN §6h (kept for the reasoning)

**Widget board parity with the home board (the big one).** `buildGroups` is in
and tested, but `IosWidgetManager.buildBoard` still writes a flat list through
`GlobalBoardProcessor`, and the extension still re-derives grouping in
`WidgetViews.groupedByPlatform`. Until that is wired, three reported issues
remain live:

1. **Bus poles collapse into one page.** Smithwood Close tracked in both
   directions is two naptans and must be two pages the arrows step between; it
   renders as one block with both directions interleaved. Confirmed in device
   data (§3 above).
2. **No line name in the widget's platform header.** It shows
   `Platform 1 (Westbound) 6/6` where the home board shows
   `Northern Platform 1 Westbound`. `headerFor` already produces the right
   string; the widget never receives it. `WidgetData.platformHeader` also
   capitalises the RAW canonical id, so a single-line H&C station would render
   `Hammersmith-City`.
3. **Rows show "Gone" immediately after a refresh.** `ticked(keepAtLeast: 3)`
   backfills departed rows unconditionally. "Gone" should mean "this data is
   old, refresh me", so retention must key off the data's age at that entry's
   date — fresh payload, no backfill; old payload, hold the last known
   departures.

The intended shape is written up in §6f/§6g thinking: `buildBoard` emits
`buildGroups` output, the wire format carries an ordered group list with KMP's
own headers, the extension renders those groups and pages by group index, and
its REST refresh re-associates rows by group key and REUSES the KMP headers
rather than inventing any — so header text keeps one implementation.

**Also outstanding:**

- **Android smoke test needed.** `fetchInitialData` now runs through
  `refreshBoards`; it compiles and the logic is equivalent, but the add-a-station
  path was not exercised on an Android device.
- **The widget still cannot see `BoardDisplayPrefs`** (sort / rows-per-platform
  / pin). `StationPrefs` lives in the standard NSUserDefaults suite; the
  extension reads the App Group. Carried over from §6f.
- **Backend `shortName` on the lines API.** `LineShortNames` is a client map
  that has already drifted once (the 2024 Overground renames) and is documented
  as a stopgap. Owner's ask this session: serve a short name per line and always
  read from it, keeping the map as the fallback for older payloads.
- **Batched stream subscribe.** The fan-out issues N concurrent `ensureStation`
  calls, each sending its own subscribe frame. `requestSubscribe` already takes
  lists, so one frame would do. Frames, not latency — they pipeline.
- **The 30 s poll is now largely redundant on iOS** and still costs N SQL reads
  plus N board re-derivations. It is a safety net; measure before removing.
- **`WidgetRefreshService` mapper and `WidgetData.ticked` remain untested**, and
  neither `:composeApp` nor the widget target has a test source set.
- **Backend line-status subscriptions for multi-line** — one-per-line vs
  one-per-board. Deferred through six sessions now and still never looked at.

---

## 6h. Session 2026-08-08 (second): widget board parity, backend short names, tap latency

§6g's three "NOT FINISHED" widget issues are closed, the `LineShortNames`
stopgap now has a backend behind it, and the widget's tap path was profiled on
device rather than guessed at — which corrected two wrong theories, one of them
load-bearing.

### (1) The widget renders KMP's blocks instead of re-deriving them

`IosWidgetManager.buildBoard` emits `MultiLineBoardProcessor.buildGroups`, and
the wire format carries an ordered `WidgetGroup` list. That single change closes
all three reported issues, because all three were the same missing wire-up:

1. **Bus poles no longer collapse.** The pole a departure was fetched from is
   the bus group key and it exists only BEFORE the merge; a flat list cannot
   express it. Smithwood Close tracked both ways is now two pages the arrows step
   between — verified in device data (2 groups, one per naptan).
2. **Headers carry the line name.** `headerFor` already produced
   "H&C & Met. Platform 1 (Westbound)"; the widget simply never received it and
   rebuilt its own from `lineName.capitalized`, which is what rendered
   "Hammersmith-City".
3. **"Gone" means stale again.** `ticked(keepAtLeast:)` backfilled departed rows
   unconditionally, so a payload that legitimately contains already-departed
   trains resurrected them on the freshest data the app had. Retention now
   requires the payload to be at least `retentionMinAgeMs` (60s — the same
   threshold `LiveAgo` already calls fresh) old at that entry's date.

The extension's own REST refresh re-associates rows by group key and REUSES
KMP's headers and block order rather than inventing any; a key it has never seen
falls back to the backend's raw platform label, which is verbatim backend text
rather than a second header implementation.

**Two bugs found on the way:**

- **`buildGroups` ignored its own `rowCap` override** — the body used
  `prefs.rowCap`, so the widget silently got 3 rows however many it asked for.
  The kind of bug an override argument invites: the call site reads correctly and
  only the body is wrong.
- **The shrink ladder never shortened LINE names**, and its "drop the direction"
  rung only matched an appended `Westbound`, never the backend's `(Westbound)` —
  which is the form real data actually uses. So the widest headers were untouched
  at exactly the rung meant to rescue them.

`headerVariants` now runs full → `Plat.` → short line names → drop direction
(lossless rungs before the one lossy one), travels on `Group.headerVariants`, and
the widget picks with `ViewThatFits`. Written out as four explicit slots rather
than a `ForEach`, because whether `ViewThatFits` flattens a `ForEach` into
candidates or into one stacked child is a detail that would render every rung at
once if it went the wrong way.

**The widget can also see `BoardDisplayPrefs` now** (sort, pin). That was only
ever true of the EXTENSION — `StationPrefsRepository` writes the app's own
defaults suite. The board is built in the app, so the preferences apply there and
only finished blocks cross the process boundary. `rowsPerPlatform` stays a
display cap; the widget takes `WIDGET_ROW_RESERVE = 8` because it re-derives ETA
labels per minute and needs trains behind the visible ones.

### (2) Backend `shortName`, and the client's precedence chain

`TFL_LINE_SHORT_NAMES` + `shortNameFor()` in `stationly-backend`
`src/utils/tflUtils.ts`, served on `/lines/mode/:mode`. Verified against staging:
tube 11/11, overground 6/6, **bus 678 routes with the field absent** — omitted
rather than nulled, so absence keeps meaning "you decide" instead of becoming an
empty label.

`getLinesByMode` had FOUR return paths each rebuilding its own object literal
with its own colour lookup, so adding a field meant remembering all four. One
`decorateLine` now serves all of them — which surfaced a pre-existing bug: the
**Firestore fallback path returned raw documents with no `label` and no `color`
at all.**

Client side, all in `core/commonMain` so **Android inherits it**:
`LineNameStore` persists `lineId → shortName`, and `LineShortNames.shortName()`
goes **backend → local table → title-cased id**. Each step degrades into the
next and the last always returns a string, which is what makes adding the backend
as step 1 incapable of regressing a board that renders fine today.

Three details that are load-bearing:

- **Reads stay synchronous** — rendering a row cannot suspend.
- **`remember` merges, never replaces** — the lines endpoint answers for one
  mode, so browsing bus routes must not lose a tube line's name.
- **`abbreviate` resolves per call, not via `by lazy`.** Memoising it would pin
  the process to the local table and the backend's names would only apply after a
  restart. What IS memoised is the id list, since which lines exist does not
  change at runtime — only what they are called.

Populated from the line dropdown (fresh fetch AND the cached payload, so an
offline launch still learns), hydrated at home-screen init and in the widget's
board build.

### (3) Tap latency: what the device said, and what it corrected

The extension has no profiler, so `widget_refresh_trace` now records
`timing targets=…ms fetch=…ms write=…ms` and
`timeline tap|quiet read=…ms tick=…ms entries=N`. Two theories died on contact:

- **"The 61-entry rebuild is expensive."** It is **1ms**. Our data work was never
  the cost.
- **"WidgetKit re-renders the entry it already holds on a page move."** It does
  not — an interactive intent invalidates the timeline, and the trace shows a
  full `getTimeline` per arrow press (15 rebuilds in 113s of tapping, ×3 for
  three installed widgets).

So the real cost is WidgetKit rendering and ARCHIVING every entry before the tap
paints. That made entry count a latency lever, and timeline LENGTH a refresh-
budget lever, pulling in opposite directions — a short per-minute timeline paints
fast and then expires every few minutes, which on three widgets is enough
`.atEnd` reloads to get the whole kind throttled. **A throttled widget looks
exactly like one that will not update until you touch it**, so the first attempt
at this traded a visible bug for a worse invisible one.

Resolved by tapering rather than shortening: per-minute entries for the first
`denseMinutes` (8 after a tap), then `sparseStepMinutes` steps out to the same
horizon as before. Same expiry, same budget, a fraction of the archive cost.

**Also removed from the render path:** six `UserDefaults` reads that lived inside
view bodies (page index, last-move time and direction, refresh-failed), evaluated
per entry per widget — ~250 App Group reads per tap inside the archiving pass, to
answer four questions whose answers were identical across the batch. They are
resolved once per timeline into `BoardRenderState` and carried on the entry.
Beyond cost, this is the correctness point: a `TimelineEntry` is meant to be a
complete snapshot, and going back to disk mid-render made an entry's appearance
depend on when it happened to be rasterised.

Other wins: the flat `predictions` array is `@Transient` (every departure was on
the wire twice, parsed twice per render); `WidgetData.departures` was a computed
`flatMap` the render path called just to test emptiness, replaced by
`hasDepartures` / `firstDepartures` / `departureCount`; `UserDefaults(suiteName:)`
was a computed property in five places, now opened once per process via
`AppGroupDefaults`.

### (4) The clobber: two writers, no merge rule

**The one genuinely serious bug this session.** The user reported the "ago" timer
showing an old value, and coming back when they paged left/right.

Two processes write the App Group: the app rebuilds every board from SQLite on
every stream frame, and the extension's refresh writes it directly because it
**cannot open the app's SQLite** (a documented, accepted gap). Nothing reconciled
them. Plain last-writer-wins made the app the loser's opponent — a stream frame
landing seconds after a widget refresh rewrote that board from SQL rows the
refresh had already superseded, stamped with SQL's older timestamp.

Measured: three boards traced as written at T, all showing timestamps **130–159s
before T** moments later.

Paging "bringing it back" is the same bug: an arrow forces `getTimeline`, which
re-reads the App Group. The stale value was already there; the arrow is only what
makes the widget go and look. Nothing about the refresh is per-platform.

Fix: **the fresher observation wins.** The app refuses to write a board whose
`lastUpdated` is older than what is stored, per-station and legacy keys both.
Safe both ways — the app's timestamp advances on every sync, so genuinely newer
app data still overwrites.

**A related instrumentation failure worth remembering:** `writeBack` returned
silently on any parse failure while the trace printed `wrote …` unconditionally,
so the trace claimed success for boards that may never have been written. It
reports the real outcome now and logs the timestamp it stamped.

**The guard is BOUNDED, and that bound is the interesting part.** The first
version refused a staler write outright, which is right for the race and wrong
for everything else the same write carries: the timestamp describes the
DEPARTURES, but the payload also carries the station's name, its mode, its feeds
and the arrangement the user just picked. A quiet station's SQL timestamp can sit
still for minutes, so an unbounded rule would have swallowed a re-sort, a re-pin
and a rename alike — user changes their board, widget refuses, nothing on screen
explains why. `STALE_WRITE_GRACE_SECONDS = 90` protects the seconds-scale race
and lets the app win after that.

Which exposed the other half: **`StationSettingsViewModel.updateBoard` never told
the widget anything.** That was correct while the widget could not see
`BoardDisplayPrefs` — and became a bug the moment §6h(1) made it read them, since
nothing else in the app has an event meaning "the arrangement changed". It pushes
`updateWidget` now. Worth remembering as a shape: giving a consumer access to
some state silently creates an obligation to notify it, and the compiler says
nothing.

### (5) Interaction polish

- **Refresh button was ~13pt** — the hit area was the glyph, not the slot, so
  most taps fell through to the widget's own tap target, **which opens the app**.
  Now ~30pt with the whole slot as the button; arrows likewise ~23pt → ~37pt.
- **`invalidatableContent()` was tried and REMOVED.** On a `Group` the modifier
  lands on every child individually, so each row shimmered on its own schedule
  and the board read as flashing repeatedly; inside a `Button`'s label it broke
  the button entirely. Both failures are recorded in the code so it is not
  reintroduced as a good idea.
- **The 15s debounce is gone.** The thing worth preventing was never "two
  refreshes close together", it was "two refreshes at once" — the guard is on
  concurrency now, with a 12s ceiling only so a refresh killed mid-flight cannot
  leave the button inert.
- **Direction encodes cause**: horizontal push = you moved; vertical flip =
  the world moved (`refreshFlip`, scoped to payloads younger than 6s so an
  ambient tick does not flip the board while you read it).
- **`placeholder(in:)` is a skeleton**, replacing four invented departures at a
  real station.

### Gates

`:core:testDebugUnitTest` **104 green** (was 92 — 17 new across
`MultiLineBoardProcessorTest` and `LineShortNamesTest`, covering the rowCap
override, the two-poles case at widget depth, every ladder rung, and the
backend-first precedence chain), `:core:compileKotlinIosArm64`,
`:composeApp:compileKotlinIosArm64`, `:composeApp:compileDebugKotlinAndroid`,
`:android:app:compileProdReleaseKotlin`, backend `tsc --noEmit`, the
`StationlyWidget` target, installed and running on the iPhone 11 with the App
Group verified by hand at each step.

### Still open

- **Both Smithwood pages read `Bus 39`.** `headerFor` drops direction for buses
  ("a pole only runs one way, so it is implied") — true on the home screen where
  both blocks are visible at once, weaker on a widget showing one page at a time.
  The destinations differ; the header says nothing. Needs a product call.
- **Non-tapped widgets repaint late.** `reloadTimelines` is a request, not a
  redraw; only the tapped widget re-renders immediately. No API forces another
  widget to repaint. Platform limitation — but watch it now the taper has removed
  the throttling risk, because throttling and this look identical.
- **`WidgetData.ticked` and the extension's mapper are still untested** — neither
  target has a test source set. Unchanged from §6g.
- **Debug-build caveat**: SwiftUI archive cost in Debug is a multiple of Release,
  so every latency number above is a ceiling.

---

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
| `BOARD_AND_DREAM_UI.md` | The home screen, the board, collapsing/ordering stations, per-station settings. §10–§17 are the current layout. |
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

0. **Android smoke test — still not done, and now overdue.** `fetchInitialData`
   delegates to `refreshBoards` (§6g) and `LineNameStore` + the backend
   `shortName` chain landed in `core/commonMain` (§6h), so Android has taken
   shared-code changes across two sessions without once being run. It compiles
   and `:android:app:compileProdReleaseKotlin` is green; the add-a-station path
   and a board's line labels have not been exercised on a device.
1. **Product call on bus pager headers** (§6h "Still open"): both Smithwood
   Close pages read `Bus 39` with nothing to tell the directions apart. Cheap to
   fix once someone decides what the header should say.
2. **QA the 2026-08-07/08 work on device** (§6f "Not verified"): the three board
   controls; then two widgets pinned to two different stations, which is the
   whole point of the feature and the one thing a single widget cannot prove.
3. **QA the 2026-08-06 work on device** (§6e "Not verified"): the drag first,
   then the carousel on a small screen with a promo showing.
4. **Review backend subscriptions for multi-line** (§6b "Still open"). Deferred
   through five sessions now and never looked at.
5. **QA the promos and the stream on device**: widget promo appears only with
   no widget installed; dream promo retires after one run; notification banner
   tracks the Settings toggle; force-update dialog fires against a raised
   `app.minVersion`.
6. **Re-check reconnect churn** now that the `openIfNeeded` race is fixed — the
   trace should show no `stream:reconnect` bursts in steady state. If it still
   churns, the foreground/background-cycling hypothesis in
   `IOS_LIVE_STREAM.md` §7.2 is back on the table.
7. **Exercise the stream past one station** — the 25-cap and `unknown_station`
   paths are unexercised.
8. **More tests**: `LiveStreamManager` reconnect/backoff and force-resubscribe.
   The widget's `WidgetData.ticked` and the extension's payload mapper are now
   the largest untested surfaces, and neither has a test target to live in —
   see §6f's note on `:composeApp` having none either.
9. **Verify prod nginx** before pointing production builds at the stream.
