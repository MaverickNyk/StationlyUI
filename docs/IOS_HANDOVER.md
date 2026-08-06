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

## 5. Branch history and uncommitted work

Everything through §6c is **committed** on `ios-parity`. The list below is the
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

**Uncommitted right now: (g) home-screen polish — §6d.** Station cards became real
containers, expand/collapse became one choreographed transition, pinning was
replaced by an ordered list, and the profile stopped duplicating the station list.
One item in it is **known broken and documented as such** — read §6d before
picking this up.

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

1. **QA the 2026-08-06 work on device** (§6e "Not verified"): the drag first,
   then the carousel on a small screen with a promo showing.
2. **Review backend subscriptions for multi-line** (§6b "Still open"). Deferred
   through four sessions now and never looked at.
4. **QA the promos and the stream on device**: widget promo appears only with
   no widget installed; dream promo retires after one run; notification banner
   tracks the Settings toggle; force-update dialog fires against a raised
   `app.minVersion`.
5. **Re-check reconnect churn** now that the `openIfNeeded` race is fixed — the
   trace should show no `stream:reconnect` bursts in steady state. If it still
   churns, the foreground/background-cycling hypothesis in
   `IOS_LIVE_STREAM.md` §7.2 is back on the table.
6. **Exercise the stream past one station** — the 25-cap and `unknown_station`
   paths are unexercised.
7. **More tests**: `LiveStreamManager` reconnect/backoff and force-resubscribe.
8. **Verify prod nginx** before pointing production builds at the stream.
