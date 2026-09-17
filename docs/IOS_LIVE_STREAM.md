# iOS live departure stream (WebSocket) — handover

**Status: built, deployed and verified on a physical iPhone 11 against STAGING (2026-08-01/02). Nothing is committed — branch `ios-parity`, all changes are working-tree only.**

iOS no longer calls REST for departures or line status. Both now arrive over the
backend's WebSocket stream (`wss://…/api/v1/stream`). Android is behaviourally
untouched — every shared-code change is behind an `expect`/`actual` seam whose
Android `actual` is either a no-op or the pre-existing code verbatim.

Backend-side protocol/ops doc (read it first, it is the contract this client
implements): `stationly-backend/docs/LIVE_STREAM_HANDOVER.md`.

---

## 1. Why

The board previously refreshed by calling `GET /stations/predictions/:id` and
`GET /lines/status` on app open, on pull-to-refresh, and on adding a station,
while a 30s loop re-read SQLite. Polling REST every 30s for data that changes
continuously is the wrong shape — and per the backend handover, iOS *silently
drops* background FCM pushes, so a live foreground socket is the only reliable
freshness path on this platform. Every second counts here: a stale board means
someone misses a train.

---

## 2. Architecture

```
                    ┌── snapshot/update (station) ──► ProcessFcmPayloadUseCase
wss://…/api/v1/stream                                   .processStationUpdate()
   │                └── snapshot/update (line) ─────►   .processLineStatusUpdate()
   │                                                          │
LiveStreamManager (core/iosMain)                              ▼
   ▲  ▲                                            SQLite + widget + FreshDataNotifier
   │  └── ensureStation()/ensureLine()  ◄── StreamBackedTflApiService ◄── DepartureRepository
   └── notifyForeground/Background/PullToRefresh ◄── LiveStreamBridge ◄── iOSApp.swift scenePhase
```

**The single most important design point:** inbound frames are handed to the
*exact same* `ProcessFcmPayloadUseCase` methods that FCM pushes already used.
Parsing, SQLite writes, widget refresh and `FreshDataNotifier` are 100%
unchanged — only the transport differs. Do not add a parallel write path.

Second design point: `StreamBackedTflApiService` implements the existing
`TflApiService` interface. So `DepartureRepository.fetchInitialData()` and all
its callers (`refreshDataIfStale`, `refreshAll`, `StationLifecycleUseCase
.persistAndFetch`) were **not modified** — on iOS their `getPredictions()` /
`getLineStatuses()` calls transparently resolve over the socket instead of HTTP.

### Files

| File | Role |
|---|---|
| `core/src/iosMain/kotlin/platform/LiveStreamManager.kt` | **The client.** Connect, auth, subscribe, receive loop, reconnect, heartbeat. All the logic lives here. |
| `core/src/iosMain/kotlin/platform/LiveStream.ios.kt` | `actual object LiveStream` → delegates to the manager |
| `core/src/androidMain/kotlin/platform/LiveStream.android.kt` | `actual object LiveStream` — three empty no-ops |
| `core/src/iosMain/kotlin/service/StreamBackedTflApiService.kt` | `TflApiService` impl: predictions/line-status → stream; everything else → REST |
| `core/src/iosMain/kotlin/service/TflApiServiceFactory.ios.kt` | `actual` → wraps `TflApiServiceImpl` in the stream-backed one |
| `core/src/androidMain/kotlin/service/TflApiServiceFactory.android.kt` | `actual` → `TflApiServiceImpl(httpClient)` **verbatim, unchanged behaviour** |
| `composeApp/src/iosMain/…/platform/LiveStreamBridge.kt` | Swift-visible delegate (same pattern as `FcmPayloadBridge`) |

### Shared-file touches (only 3, all guarded)

1. `core/src/commonMain/kotlin/platform/Platform.kt` — added `expect object LiveStream`.
2. `core/src/commonMain/kotlin/service/NetworkModule.kt` — `tflApi` now built via
   new `expect fun createTflApiService(httpClient)`.
3. `composeApp/src/commonMain/…/summary/SummaryViewModel.kt` — one
   `LiveStream.notifyPullToRefresh()` call in `refreshAll()`, plus the
   `lastUpdated` fix in §4.2.

Plus `core/src/iosMain/…/Platform.ios.kt`: `IosNotificationManager
.subscribeToTopics/unsubscribeFromTopics` now also parse the existing
`Station_{id}` / `LineStatus_{mode}_{line}` topic strings and forward them to
the stream (`parseTopics()`). This deliberately reuses an existing call site so
station add/remove keeps FCM and stream subscriptions in lockstep.

Gradle: `ktor-client-websockets:3.0.0-rc-1` added to **`core/build.gradle.kts`
`iosMain` only**.

---

## 3. Behaviour / tuning

| Thing | Value | Where |
|---|---|---|
| Connect trigger | `scenePhase == .active` | `iOSApp.swift` |
| Disconnect trigger | `scenePhase == .background` (`.inactive` ignored on purpose — Control Center/app-switcher must not thrash the socket) | `iOSApp.swift` |
| Heartbeat | app-level `Frame.Ping` every **15s** | `LiveStreamManager` ~L311 |
| Reconnect backoff | 1s → ×2 → cap **30s**, + 0–500ms jitter, reset to 1s on `ready` | ~L336 |
| `ensureStation`/`ensureLine` timeout | **6s** | ~L162/L169 |
| Subscription cap | 25 stations/socket (backend-enforced) | backend doc §3 |

**Reconnect policy:** the *only* trigger is the read loop terminating
(exception or close). There is deliberately **no frame-staleness watchdog** — a
quiet station can legitimately go 2+ minutes without a frame (see §4.2), and
treating that as a dead socket would cause constant pointless reconnects. The
15s ping is what makes a genuinely dead peer surface fast.

**Foreground-only.** No VoIP/background-fetch entitlements were added. Background
freshness is unchanged from before (whatever FCM alert pushes already did).

---

## 4. Three real bugs found on-device — do not regress these

### 4.1 Line-status frames failed to decode

`LiveStreamManager`'s own `Json` was `{ ignoreUnknownKeys = true }` only.
Real payloads have an unquoted `lastUpdatedTime`, so every line frame threw
`stream:decodeError … Use 'isLenient = true'` while station frames were fine.
`NetworkModule.json` already had the needed flags — the REST path had been
silently carrying them.

**Fix:** the manager's `Json` now mirrors `NetworkModule.json` exactly
(`ignoreUnknownKeys` + `isLenient` + `coerceInputValues`). **If you add another
decode site, copy those flags.**

### 4.2 "ago" timer reset every 30s, hiding the stream

`SummaryViewModel.loadPredictions()` stamped `lastUpdated = now` whenever rows
existed — not the real sync time. The pre-existing 30s SQLite re-read loop
(`SummaryViewModel` init, ~L142 — **still present, and correct: it is local-only,
no network**) calls that every 30s, so the timer reset every 30s regardless of
whether backend data had actually arrived. From the UI it was indistinguishable
from polling.

**Fix:** on iOS only, `predictionTimestamp` now reads
`sqlStorage.getLastUpdatedTimestamp(...)`, which `SyncPredictionsUseCase.execute()`
stamps on every genuine backend sync. Android still uses `now` — unchanged.

**Consequence to understand before "fixing" it again:** the Syncer only pushes
on *actual change*, plus a heartbeat every ~150s. Observed real gaps on-device
were 29s / 60s / 80s / 89s / 129s / 131s. **An "ago" value climbing past 30s is
now correct, honest behaviour** — it means nothing changed at that station, not
that the socket died. If a visible liveness indicator is wanted, that is a
separate feature (a "connected" dot), not a change to this timestamp.

### 4.3 Pull-to-refresh took ~10s

Two compounding faults:

- `requestSubscribe()` only sent a frame for *newly* tracked ids
  (`stations.filter { subscribedStations.add(it) }`). On a pull the station was
  already subscribed → empty list → **no frame sent at all** → `ensureStation`'s
  `CompletableDeferred` waited out its full timeout.
- `notifyPullToRefresh()` also force-closed a perfectly healthy socket, paying a
  TLS handshake + auth round-trip before any data.

**Fix — and the key protocol fact enabling it:** the server replays a cached
`snapshot` on *every* subscribe frame, **including repeats** (verified in
`stationStreamServer.ts:170-183` — it iterates all `subscribed` ids, not just
newly-added ones; `stationStreamHub.ts:108-131`'s `has()` check only guards room
membership, and `subscribed.push()` is unconditional).

So pull-to-refresh now: if `session != null && isReady`, **reuse the live socket**
and force a resubscribe (`force = true` bypasses the new-ids filter) → fresh
board sub-second, no reconnect. Only a dead/never-established socket gets the
full teardown-and-reconnect, since that is the only case reconnecting fixes.

---

## 5. Verification (what was actually observed, on device)

Read the on-device trace — this is the primary debugging tool:

```bash
xcrun devicectl device copy from --device <UDID> --domain-type appGroupDataContainer \
  --domain-identifier group.com.stationly.shared --source / --destination /tmp/pull
plutil -convert xml1 -o /tmp/ag.xml /tmp/pull/Library/Preferences/group.com.stationly.shared.plist
python3 -c "import plistlib;print(*plistlib.load(open('/tmp/ag.xml','rb')).get('push_trace',[]),sep='\n')"
```

`PushTrace` keys emitted by this feature: `stream:ready`, `stream:subscribe …
force=`, `stream:subscribe(connect)`, `stream:update station=`/`line=`,
`stream:refresh reusing live socket`, `stream:reconnect backoff=`,
`stream:reconnect trigger=pullToRefresh(dead)`, `stream:error code=`,
`stream:decodeError`, `stream:heartbeatFailed`, `stream:EXCEPTION`.

Confirmed on an iPhone 11 against staging: connect → `ready` → subscribe →
continuous `stream:update station=490012211N`; `stream:update line=39` decoding
cleanly after 4.1; and a real mid-session drop recovering automatically
(`EXCEPTION` → `reconnect backoff=1000ms` → `ready` → updates, ~1s).

Compile gates (both must stay green — the second is the Android no-regression proof):

```bash
./gradlew :core:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64
./gradlew :core:compileDebugKotlinAndroid :composeApp:compileDebugKotlinAndroid
```

Deploy to device (note: **you must rebuild the XCFramework first** — Xcode links
a prebuilt artifact, so editing Kotlin and only running `xcodebuild` silently
ships stale code; this cost real time):

```bash
./gradlew :composeApp:assembleComposeAppDebugXCFramework   # ~6-9 min
cd iosApp && xcodebuild -scheme iosApp -configuration "Debug Staging" -destination "id=<UDID>" -allowProvisioningUpdates build
xcrun devicectl device install app --device <UDID> "<DerivedData>/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device process launch --device <UDID> com.stationly.mobile
```

`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` does **not** work from
a plain shell — it needs Xcode's env vars and fails with "Could not infer iOS
target architectures".

---

## 6. Deliberately out of scope / unchanged

- **Android** — no behavioural change. Two new `actual` files only.
- **Selection screen data** (`getModes`/`getLines`/`searchStations`/`getRoute`) —
  still REST by design; one-shot lookups, not live board data.
- **Widget (`StationlyWidget`)** — still refreshes itself over REST via the
  App Group `WIDGET_API_*` keys. It is a separate process that cannot hold a
  socket. Unchanged.
- **Dream/screensaver surface** — still event-driven off `FreshDataNotifier`,
  which the stream now feeds. Unchanged, and works automatically.
- **The 30s SQLite re-read loop** in `SummaryViewModel` — intentionally kept. It
  is a local read with no network call; it drives per-minute ETA ticking.

---

## 7. Outstanding / next steps

1. **Nothing is committed.** Branch `ios-parity` also carries unrelated
   uncommitted work from earlier sessions (widget, promos, auth). Separate the
   stream changes carefully when committing — see §2 for the exact file list.
2. **Reconnect churn worth a look.** Traces showed bursts of
   `JobCancellationException` → reconnect every few seconds at times. Each cycle
   recovers correctly and refetches, so it is not user-visible, but it was never
   root-caused; the working hypothesis is foreground/background cycling during
   testing (`bgRefresh=` lines interleave with them). Confirm whether the app is
   self-toggling before shipping widely.
3. **Prod nginx** — the backend doc lists prod as lacking the stream `location`
   block; the user reported adding it. Verify before pointing prod builds at the
   stream. Staging is verified.
4. **Only tested with 1 station + 1 line.** The 25-subscription cap, and
   `unknown_station` handling, are implemented but unexercised.
5. **`getTimeline` / force-quit hole** — backend doc §7.5 still applies: the
   widget has no socket, so a force-quit app means widget freshness relies on
   the existing REST refresh path.
6. **No automated tests.** `LiveStreamManager` has none; the reconnect/backoff
   and force-resubscribe logic are the parts most worth covering.
