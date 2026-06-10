# Stationly iOS — Build, Architecture & Handoff

**Audience:** the next engineer/agent picking up the iOS app.
**Last updated:** 2026-06-10 (Session 3). **Branch:** `ios-parity` (off `dev_25Apr`, nothing merged).
**Companion doc:** `docs/IOS_PARITY_PLAN.md` (the phased plan + "⏯️ RESUME HERE").

This doc is the single source of truth for: how the iOS app is structured, how to
build/run it (including every Xcode-26 gotcha we hit), what's been ported, how FCM
and SDUI work on iOS, and what remains.

---

## 1. What the iOS app actually is

It is **NOT** a native-Swift rewrite. It is **Kotlin/Compose Multiplatform**:

| Module | Language | Role | Targets |
|---|---|---|---|
| `core/` | Kotlin | Business logic, models, repositories, SQLDelight DB, Ktor, use cases, SDUI models | common, android, ios, wasmJs |
| `composeApp/` | Kotlin + Compose | **Shared UI** (login, selection, summary/board, profile, theme) | common, android, ios |
| `iosApp/` | Swift | Thin host: app shell + Firebase Auth, Google Sign-In, FCM/APNs, **SwiftUI widget** | iOS |
| `android/` | Kotlin | **Standalone native Android app** — the design source of truth | Android |
| `web/` | Kotlin/Wasm | Web app | wasmJs |

The iOS app = `core` + `composeApp` rendered through
`MainViewController` (`ComposeUIViewController`) → `ComposeHostView`
(`UIViewControllerRepresentable`) in `ContentView.swift`. Swift owns only what
Kotlin/Native can't: Firebase Auth (`AuthBridge.swift`), FCM
(`FCMBridge.swift`, `AppDelegate.swift`), and the home-screen widget
(`StationlyWidget/`).

**`android/` is the design reference. We do not modify it.** We port its screens
into `composeApp/` (the shared module that iOS renders). Android renders the same
shared screens too, but historically had its own native screens — the redesign we
chased lives in `android/.../ui/`.

### Why some things differ from Android by necessity
- Android's **dot-matrix board is rendered with `AndroidView` + XML** (`R.layout.widget_departure_board`) — impossible on iOS. The iOS board is a **pure-Compose reimplementation** of that XML (see §6).
- No `java.*` in shared Kotlin → `kotlinx.datetime`, `Clock.System` instead of `java.time` / `System.currentTimeMillis`.
- No Coil image loader in common → avatar/logo are drawn placeholders for now.
- No Android `Context`/`Intent` → composition locals (`LocalOpenUrl`, `LocalUriHandler`) or `expect/actual` providers.

---

## 2. How to build & run on a device (Xcode 26)

The project was authored for Xcode 15.4; the machine now runs **Xcode 26.5**.
Several things had to change — all committed. **Decision: test on a physical
iPhone with the STAGING backend** (`iosApp Staging` scheme).

### One-time machine setup
1. Accept license + first-launch: `sudo xcodebuild -license accept` then `sudo xcodebuild -runFirstLaunch`.
2. **Install the iOS platform component** (the SDK ships with Xcode, but the
   device-support/runtime is a separate ~8 GB download):
   Xcode → Settings → Components → iOS, or `xcodebuild -downloadPlatform iOS`.
   Without it, `xcodebuild` errors `iOS 26.x is not installed` for every destination.

### Build + install (staging, device)
```bash
# 1. shared framework (device + simulator). SLOW (~8 min) — only when composeApp/core changed.
./gradlew :composeApp:assembleComposeAppDebugXCFramework      # debug-only, faster than assembleXCFramework

# 2. regenerate the Xcode project (idempotent; needed when project.yml changes)
cd iosApp && ./xcodegen.sh

# 3. resolve + clean SPM checkouts (see "gotchas" below)
DD=build/DD
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" -derivedDataPath "$DD" -resolvePackageDependencies
chmod -R u+w "$DD/SourcePackages/checkouts"
find "$DD/SourcePackages/checkouts" -maxdepth 2 -iname BUILD -type f -delete

# 4. build for the connected iPhone (device id from `xcrun xctrace list devices`)
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" \
  -destination 'id=<DEVICE_ID>' -derivedDataPath "$DD" -allowProvisioningUpdates build

# 5. install + (optional) launch
APP="$DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device install app --device <DEVICE_ID> "$APP"
```
**First install only:** on the iPhone, Settings → General → VPN & Device
Management → trust the developer cert, then reopen the app. (Current device id:
`00008030-001E0D9C3EFB802E`; signing team `6D3CXG8U25`; Apple ID
`nikhilkumar11896@gmail.com`.)

### Xcode-26 fixes already applied (committed)
1. **`iosApp/xcodegen.sh`** — removed the `objectVersion 77→56` downgrade (an
   Xcode-15.4 workaround that left `SUPPORTED_PLATFORMS` empty on Xcode 26 →
   "can't find destination"). Native objectVersion 77 now.
2. **`iosApp/project.yml` → `schemes:`** — added explicit shared schemes
   (`iosApp Production`, `iosApp Staging`). The 4 custom config names
   (Debug/Release × Staging/Production) meant xcodegen generated **no** scheme,
   so Xcode's implicit scheme resolved to empty supported platforms.
3. **`iosApp/project.yml` → Firebase `from: 11.0.0`** (was pinned `10.28.1`).
   Firebase 10's build-time generated module maps fail under Xcode 26 explicit
   clang modules (`GoogleUtilities-NSData.modulemap not found`).
4. **`xcodeVersion: "26.0"`**.

### Per-build SPM gotchas (not committable; handle in the build script / CI)
- **nanopb** ships a Bazel `BUILD` file that collides (case-insensitive FS) with
  the `build/` dir Xcode wants → `File exists but is not a directory`. Delete it
  before building (step 3 above).
- **Firebase** writes generated module maps **into its own (read-only) SPM
  checkout** → `Permission denied`. `chmod -R u+w` the checkouts first (step 3).
- The Xcode GUI build generally handles both; the CLI path needs them explicit.

### Environments
`iosApp Staging` → `STATIONLY_ENVIRONMENT=staging` → `AppConfig` points at
`staging-api.stationly.co.uk` / `staging.stationly.co.uk`. `iosApp Production`
→ prod. One real Firebase project (`mindthetimefcm`) is shared across envs
(auth/Google/FCM work regardless — the env only switches the Stationly backend).

---

## 3. Architecture & data flow

```
Compose UI (composeApp)                 core (shared)                 platform
─────────────────────                   ─────────────                 ────────
SummaryViewModel ─reads─▶ Platform.sqlStorage (SQLDelight) ◀─writes─ SyncPredictionsUseCase
   │  (30s poll + on-resume)                 ▲                              ▲
   ├─ predictions ─▶ Board (dot-matrix)      │                       ProcessFcmPayloadUseCase
   ├─ lineStatuses ─▶ ExploreSection         │                              ▲
   └─ homeConfig (SDUI strings) ◀─ NetworkModule.sduiApi (Ktor)      FcmPayloadBridge (iosMain)
                                                                            ▲
ProfileViewModel / LoginViewModel ─▶ PlatformAuthProvider ─▶ AuthBridge.swift (Firebase)
                                                                            ▲
Widget (SwiftUI) ◀─ App Group NSUserDefaults ◀─ IosWidgetManager.updateWidget  AppDelegate (APNs/FCM)
```

**Local-first / minimal-Firestore:** the client never touches Firestore directly.
It reads/writes **local SQLite** (SQLDelight) + in-memory `StateFlow`s, and talks
to the Stationly backend over Ktor (SDUI + sync endpoints). FCM pushes deltas that
are written to SQLite once; the UI polls SQLite. **No change in this branch adds a
Firestore read/write.** Keep it that way — surface new data via the backend SDUI
endpoints + SQLite, not direct client→Firestore.

---

## 4. SDUI — the app is server-driven; keep it that way

Every screen we ported **reuses the same SDUI contract as Android** (same backend,
same keys):
- **Login** renders its form from `uiState.layout.components` (SDUI
  `Input`/`Button` with `validation`/`condition`); actions map to the VM.
- **Home strings** (`homeConfig` = `sduiApi.getHomeConfig().strings`) drive board
  labels, ExploreSection (`explore.*`), promos, dialog copy.
- **Profile About** renders `sduiApi.getAboutLayout()` (Card/Section/LinkRow),
  falling back to a hardcoded layout when offline.
- **Board** renders `sduiPayload` (SDUI `Header`/`Row`/`Message`) when present,
  else `GlobalBoardProcessor.prepareLegacyRows`.
- **Theme** is SDUI-synced: `ThemeRepository` fetches `getThemeTokens()` and
  caches it; applied next launch.

**Rule for new work:** if Android drives it via SDUI, the iOS port must read the
same SDUI keys/endpoints — do not hardcode strings/layout that the backend owns.

---

## 5. FCM → board/widget update flow (verified)

```
APNs / FCM push
 → AppDelegate (willPresent / didReceive / didReceiveRemoteNotification)
 → processFcmPayload(userInfo)  →  FcmPayloadBridge.shared.processPayload(json)   [Swift]
 → composeApp.FcmPayloadBridge  →  core FcmPayloadBridge (Platform.ios.kt)        [KMP]
 → ProcessFcmPayloadUseCase.invoke(payload):
       • departureRepository.processFcmPayload()  → writes predictions to SQLite
       • widgetManager.updateWidget(state)        → writes departures to App Group
                                                    + bumps widget_reload_signal
```
Then:
- **In-app board** — `SummaryViewModel` polls SQLite every **30 s** (+ refresh on
  app foreground); ETAs self-tick each minute via `rememberMinuteTick`.
- **Widget** — `WidgetReloadObserver` (AppDelegate) polls `widget_reload_signal`
  every **5 s** → `WidgetCenter.reloadAllTimelines()` → re-reads the App Group.

**Known latency:** silent FCM takes up to ~30 s to appear in-app (vs Android's
near-instant FreshDataNotifier push). Improvement options (not yet done): shorten
the poll, or have `ProcessFcmPayloadUseCase` emit an in-memory "fresh data" signal
the `SummaryViewModel` collects. Immediate on foreground already.

---

## 6. The dot-matrix departure board (the core visual)

Android renders it via `AndroidView` inflating `widget_departure_board.xml` (also
used by the Android widget). The iOS board (`composeApp/.../summary/components/Board.kt`)
is a **pure-Compose reproduction** of that XML:

- **Chrome on the themed canvas** (light/dark aware): line-colour pill + delete
  trash; disruption banner (themed danger, expandable); **Next-Departure hero**
  (themed surface, line-tint wash, big monospace ETA + depletion bar).
- **Dark signage panel** — locked TfL amber `#FFC819` regardless of app theme
  (signage never themes; matches Android's `@color/tfl_amber`):
  - Centered **TfL roundel** (`Canvas`-drawn ring + bar, tinted per mode) + station name.
  - Centered **platform-section headers** (`Northern: Platform 3 (Northbound)` via
    `StationlyFormatters.platformHeaderText` + `formatLinePrefix`).
  - Departure rows: destination (left) + ETA (right), all amber, Due in red.
  - Status strip: `severity : reason` (reason scrolls via `basicMarquee`).
  - Footer: roundel mark + **live ticking clock** (HH:mm:ss, per-second) + `M:SS ago`.
- **Self-ticking ETAs** via `rememberMinuteTick` + `StationlyFormatters.formatMinutesRemaining`.
- **SDUI-first**: renders `sduiPayload` components when present; else
  `GlobalBoardProcessor.prepareLegacyRows`.

The **SwiftUI widget** (`iosApp/StationlyWidget/WidgetViews.swift`) was restyled to
match: roundel + station header, platform-grouped sections, all-amber rows, status
+ "ago" footer. **Caveat:** the App-Group payload is a *flat* `[DepartureRow]`
(destination/platform/eta/isDue/stopLetter) — it has no direction or line prefix —
so the widget shows `Platform N`, not `Northern: Platform 3 (Northbound)`. Matching
Android fully needs `IosWidgetManager.updateWidget` (`core/.../Platform.ios.kt`) to
write the grouped SDUI structure. Follow-up.

---

## 7. What's been done (branch `ios-parity`)

| Commit | Area |
|---|---|
| `4f26fee` | Theme tokens + ExploreSection port + Xcode-26 build fixes |
| `9a6b2f1` | Full light/dark/system theme + SDUI token sync (ThemeRepository/Host/toColorScheme) |
| `f7081f5` | **Login** — redesigned landing + SDUI form + reset-confirm |
| `144972d` | **Profile** — header, My Stations, SDUI About, sign-out/delete dialogs |
| `8dd595b` | **Summary** — theme migration, dropped stale header |
| `5870019` | **Selection** — theme-aware palette |
| `5fe713c` | **EmptyStates** — theme-aware first-launch |
| `c70786f` | **Board** — pure-Compose dot-matrix departure board |
| `b29a5c9` | **Widget** — dot-matrix restyle to match Android |
| `7fbf91d` | cleanup (dead `SummaryHeader`, stray imports) + this doc |
| `04de5da` | **widget FCM fix** + **iOS push transitions** (see below) |

Each Kotlin commit was verified with `./gradlew :composeApp:compileKotlinIosSimulatorArm64`;
the device build + install succeeds (staging). The app + widget are running on the
physical iPhone.

---

## 7b. Landed in `04de5da` (session 2b)
- **Widget now updates on FCM** — root cause: `ProcessFcmPayloadUseCase` looked up
  the primary selection via `storageManager.loadSelections()`, which is **never
  written on iOS** (selections persist to SQLite only via `SelectionRepository →
  sqlStorage`). Added an optional `sqlStorage` fallback to the use case (default
  `null` so Android call sites are untouched) and pass `Platform.sqlStorage` from
  the iOS `FcmPayloadBridge`. Also: `AppDelegate.processFcmPayload` now calls
  `WidgetCenter.reloadAllTimelines()` ~2 s after a push (the foreground 5 s
  `WidgetReloadObserver` doesn't run in the background).
- **iOS push/pop transitions** — `AppNavigation` `NavHost` now slides screens in
  from the right / out to the left (reversed on back) for a native feel. Board
  content untouched.

## 7c. Landed in Session 3 (2026-06-10) — branch `ios-parity`

Per-screen commits, each `compileKotlinIosSimulatorArm64`-verified. **No
`android/` changes** (design source of truth untouched).

- **Selection screen RE-PORTED** (was the §8 top-priority gap). Replaced the
  stale pre-redesign Compose `SelectionScreen` with a faithful port of the
  redesigned `android/.../ui/selection/SelectionScreen.kt`:
  - SDUI `sdText()` with `{mode}`/`{station}`/`{line}` interpolation + the
    context-aware vocabulary helpers (bus→stops/routes/Buses, rail→stations/
    lines/Trains) + `summariseDestinations`.
  - `StepHeader` carries the picked mode's roundel forward (coil3 AsyncImage).
  - branch-aware `DirCard`: "towards {next station}" headline, tappable
    destination chips that swap the stops timeline, rail-only compass badge, the
    `e488e81` shape-jump fix (compass header gated on compass presence + pinned
    height, never on `sel`), routes-split hint. Dropped the removed `DirFunFact`.
  - theme-aware backgrounds (killed the hardcoded black band in light mode);
    solid-primary CTA; `onSurface`/`onSurfaceVariant`/`onPrimary` text colours.
  - kept the iOS VM wiring (`onDropdownSelected`, separate flows), `parseColorSafe`.

- **Summary screen parity**: real Material3 `PullToRefreshBox`; **real
  `stationly_logo`** via composeResources (`Res.drawable.stationly_logo`, PNG
  copied from `android/.../res/drawable/`) in top bar + update dialog; **profile
  photo avatar** in the top bar via coil3 (`firebase_user_photo_url`), monogram
  fallback (`SummaryUiState.photoUrl` added); **floating `ThemeToggleButton`**
  bottom-right — the light/dark/system selector — **newly ported**
  `ui/common/ThemeToggleButton.kt` into shared composeApp; lifecycle `ON_RESUME`
  → `reloadSelectionsFromDb()` (cross-screen consistency + pulls FCM-written rows
  from SQLite on foreground); themed `UpdateNudgeDialog`.

- **Profile screen parity**: **photo avatar** in `ProfileHeaderCard` via coil3
  (`uiState.photoUrl` was already loaded; was monogram-only); real
  `stationly_logo` in the top bar; Google provider badge uses `AlternateEmail`.

- **Departure board + widget — spot-clean parity** (the product-critical surface):
  - **In-app board** (`composeApp/.../summary/components/Board.kt`): the rows now
    `verticalScroll` inside the capped panel — a many-platform station (Bank,
    King's Cross) no longer clips departures; the station strip + status + footer
    stay pinned (matches Android's ScrollView). The dot-matrix structure
    (roundel + station header, line-prefixed platform headers, amber rows / red
    Due, marquee status, footer roundel + per-second clock + "M:SS ago") was
    already faithful from Session 2.
  - **SwiftUI widget** (`iosApp/StationlyWidget/`): rebuilt to read like the
    Android board. Platform headers now render `Piccadilly: Platform 1
    (Eastbound)` — added optional `WidgetState.direction` (default `""`, so every
    call site incl. `android/` is unchanged), populated by composeApp
    `SummaryViewModel` + `FormatDeparturesUseCase`, written as `widget_direction`
    by `IosWidgetManager`, and assembled by `WidgetData.platformHeader()`. Real
    line status (severity : reason) is now written from the poll (was always
    null). **Live ticking "M:SS ago"** via WidgetKit `Text(_:style:.timer)` — the
    iOS analog of Android's `Chronometer` (self-updates, no timeline reload).
    Roundel marks, lit-cell rows, per-family caps (small 3 / medium 4 / large 9).
  - **iOS platform constraints (documented, not bugs):** WidgetKit widgets are
    static snapshots — they **cannot scroll** and **cannot tick a per-second wall
    clock** (Apple's refresh budget). So the widget shows the best fixed row set
    per family and the live "ago" carries freshness; the status truncates instead
    of marqueeing.

- **Font — Inter Tight bundled** (`Type.kt` `DisplayFamily`): the brand wordmark
  on Summary + Profile now uses Inter Tight, matching Android. Android loads it
  via GMS Downloadable Fonts (no iOS provider), so the official OFL variable font
  was sliced into 6 static weights with `fonttools` and bundled in
  `composeResources/font/inter_tight_*.ttf` (license in
  `composeApp/THIRD_PARTY_LICENSES/`). Only affects shared `composeApp` (iOS); the
  shipping `android/` app keeps GMS fonts → no APK bloat. **Available for broader
  use** — apply `fontFamily = DisplayFamily` to more headlines/section headers to
  extend the match (Login brand mark is a good next target).

### Notable finding
- **Coil 3 IS already a composeApp dependency** (`coil3.compose.AsyncImage`); the
  earlier "no network images" caveat is obsolete. Avatars / mode-roundels / logos
  all load fine, and the brand logo is now a real bundled asset.

## 8. What's REMAINING (priority order)

1. **On-device QA of the rebuilt board + widget (TOP — needs the iPhone).** The
   board + widget were rebuilt this session (§7c "Departure board + widget") and
   compile + Swift-type-check, but have **not been rendered on a device**. Verify:
   in-app board scroll + colours vs Android; the widget at **systemSmall / medium
   / large** — row counts, truncation, the line-prefixed headers (`Piccadilly:
   Platform 1 (Eastbound)`), and the live "ago" ticking. If anything overflows,
   tune the per-family caps in `WidgetViews.swift` (small 3 / medium 4 / large 9).
   (Build the widget via Xcode — `swiftc -typecheck` passes but can't render.)
2. **FCM in-app immediacy** — optional fresh-data signal so the in-app board
   updates instantly instead of on the 30 s poll / foreground reload.
3. **On-device visual QA pass** — colours/spacing/alignment vs Android, screen by
   screen (esp. the re-ported Selection + Summary). Needs the device build (§2).
4. **Login email-verification flow** — Android has `VerifyEmailScreen` +
   `onNeedsEmailVerification`; not ported. Add to nav + `LoginViewModel` if needed.
   Also re-verify Login flows/validation/copy vs the 1142-line Android screen.
5. **Profile edit-display-name** — Android has an edit-name dialog +
   `updateDisplayName`. NOT ported: needs `PlatformAuthProvider.updateDisplayName`
   + a Swift `AuthBridge` command (Firebase `createProfileChangeRequest`).
   Deferred — crosses the KMP/Swift boundary.
6. **Google "G" glyph** — still a drawn "G" on the Login Google button. Add
   `ic_google_standard` to composeResources and use `Res.drawable.*` (logo done).
7. **In-app WebView** — iOS hands links to Safari via `LocalOpenUrl`; port a
   `WKWebView` route if in-app browsing is wanted.
8. **Light-mode polish on shared toasts** — `OfflineBanner.kt`,
   `AnnouncementBanner.kt`, `SduiRenderer.kt` still hardcode dark. (Board's
   dot-matrix panel is intentionally always-dark signage — leave it.)
9. **iOS-native polish** — haptics on key interactions (selection commit, delete,
   pull-refresh), refine transitions, safe-area / Dynamic-Island correctness.
10. **App Store readiness** — icons, launch screen, prod Firebase plist decision,
    TestFlight, privacy manifest.

---

## 9. Conventions for the next agent
- Branch `ios-parity`, **per-screen commits**, messages explain the why.
- Verify every Kotlin change: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`.
- Theme-migration pattern: convert a screen's central palette to
  `@Composable get() = MaterialTheme.colorScheme.*` (names unchanged so call sites
  don't churn). Reads inside non-`@Composable` lambdas (`derivedStateOf`) must
  capture the colour in composable scope first.
- Keep it **SDUI-driven** and **local-first / zero direct Firestore**.
- Don't touch `android/` (design reference) or the backend (separate repo).
