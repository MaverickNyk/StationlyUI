# Update gate — review, refactor and staging verification

2026-08-31. Follows the initial build of the app update policy
(`docs/APP_UPDATE_POLICY.md`). This is the record of the review pass over that
work: what was wrong with it, what changed, and what was actually verified
against a running system rather than asserted.

## 1. The contract that mattered most: the deployed Android app

The production Android binary (versionCode 2, frozen, no rollback) must not
notice any of this. Four ways it could have been disturbed, and where each now
stands:

| Risk | Status |
|---|---|
| `app.minVersion` / `app.storeUrl` removed or repurposed | **Unchanged.** Still `1.0` and the Play Store URL. Asserted by a test, and by `sdui_keys.py --check` (2 keys added, none removed). |
| A new key breaks the payload | Additive only. The client reads a flat map by key; unknown keys are inert. |
| The version gate 426s the Android app | **Cannot.** The shipped binary sends no `X-Stationly-Client`, which parses to `unknown` and passes ungated. Even if it sent one, Android is gated by its own half of the document, which sits at the floor. And the enforcement env switch is off. Three independent reasons. |
| Android's client-side gate changed | Untouched. `android/` was not modified. |

The test `GATE: the DEPLOYED Android client is unaffected by an iOS floor`
pins the header-less case explicitly, on both a public and a `/user/` route.

Verified live on staging after deploy:

```
app.minVersion = 1.0
app.storeUrl   = https://play.google.com/store/apps/details?id=com.stationly.mobile
```

## 2. Bugs found and fixed

### 2.1 A cached `"0"` would have blocked healthy clients forever

`StationlyAuth.clientIdentity` was `by lazy`. Android's `Platform.appVersion()`
reads through `appContext`, a `lateinit` set in `Platform.initialize()`. Any
request issued before that lands returns the `"0"` fallback — and `lazy` would
have cached `"0"` for the life of the process. A client reporting version 0 is
below every possible floor, so the gate would have blocked a current build,
permanently, on whichever launches lost the race.

Now memoised only once the value is real, recomputed until then.

### 2.2 "Not now" could clear a server-issued block

`snoozeNudge()` ended with an unconditional `_verdict.value = Ok`. A 426 can land
while the nudge dialog is on screen (it is a background request on any
endpoint), flipping the verdict to `Blocked` with the dialog still composed. The
user's tap then arrived against a verdict it was never about and cleared a
server block.

Now guarded on entry and re-checked after the write.

### 2.3 The 426 body's copy was parsed and thrown away

The backend sends `title`, `message` and `cta` in the rejection precisely so a
client that has never fetched the policy can draw a correct screen. The client
read only the store links and rendered compiled fallbacks. Now used, with
per-field fallback to the cached document.

### 2.4 The UI read non-snapshot state during composition

`UpdateSurfaces` read `ReleaseGate.policy.strings`, a plain `var`. Adopting a
document while a surface was on screen did not recompose it, so the words shown
were whichever happened to be loaded when the *verdict* last changed.

Copy is now resolved at decision time into the verdict, so the composable is a
pure function of one flow. This also collapsed seven `?:` fallbacks in the UI
into one `ReleasePolicy.blockedCopy()` / `nudgeCopy()` pair.

### 2.5 A wrong factual claim about store links

Documented as "https bounces through Safari". On iOS `https://apps.apple.com/…`
is a universal link the App Store app claims, so it is equally direct. The pair
earns its place on **Android**, where `market://` throws on a device with no Play
Store. Comment and docs corrected; the fallback now also states honestly that it
cannot catch a handler that fails silently, and that the real guarantee is
`blockedOrOk` refusing to block when both links are empty.

## 3. Maintainability

| Change | Why |
|---|---|
| `BlockReason` enum replaces the `"http_426"` magic string | Compared in two files. A typo would have silently made a server block re-derivable, defeating the "server wins" rule. |
| `UpgradeRejection` struct replaces five nullable string params | The call site parses untrusted JSON; a positional call of five same-typed nullables is one transposition from showing a URL where the title goes. |
| Second `CoroutineScope` deleted | It duplicated `authExpiryScope` exactly. `NetworkModule.noteUpgradeRequired` now mirrors `noteSurvived401`. |
| `parseUpgradeRejection` extracted from the Ktor plugin | The plugin needs a live engine; the parser is where every branch worth testing lives. Now has 3 tests. |
| `evaluateRequest()` extracted from `VersionGateMiddleware` | Separates policy from transport. The middleware is now a five-line adapter; every branch is testable without a socket or a mutated global. |
| `verdictFor(identity, policy)` / `forPlatform(platform, policy)` | The test suite was reaching in and reassigning `POLICY.ios` around each case with a `finally` restore. One throw in the wrong place leaks a raised floor into every later test. |
| `appVersionName()` deleted | Duplicated `Platform.appVersion()`, and its Android actual returned a hardcoded `"0"` — so the Profile About card showed "v0" on Android. |

## 4. Performance

Little here is hot, and the review found no bottleneck worth inventing one for.
Two real items:

- **Per-request work is one map lookup and one string concat**, after the
  identity memoises. The pre-refactor `lazy` was cheaper and wrong; the current
  form recomputes at most a handful of times at startup.
- **The nudge's activity row** is keyed on the version in `LaunchedEffect`, so
  re-entering the same nudge does not write a duplicate. `recordBlocking` is
  `suspend` and runs on the effect's coroutine, never on the composition.

## 5. What was verified, and how

### Automated

| Suite | Result |
|---|---|
| Backend `npm test` | **203/203** |
| Backend `tsc --noEmit` | clean |
| `:core:testDebugUnitTest` | green, **26** cases in `ReleaseGateTest` |
| `:composeApp:assembleComposeAppDebugXCFramework` | BUILD SUCCESSFUL |
| `:android:app:compileStagingDebugKotlin` | BUILD SUCCESSFUL |
| `sdui_keys.py --check` | 2 added, none removed |

One test is worth calling out. `GATE TRANSPORT: req.path inside the router is
stripped of the /api/v1 mount prefix` stands up a real Express server and
asserts the path the middleware actually sees. `EXEMPT_PREFIXES` is written
against that assumption, and if it were wrong every prefix would silently stop
matching — leaving a blocked client unable to fetch the document explaining its
block, which is the one failure the exempt list exists to prevent.

### Live on staging

Backend deployed via `.scripts/staging_deploy.sh`. Health check passed.

- `GET /sdui/app/release-policy` returns the document (893 bytes, both platforms,
  iOS pointing at `itms-apps://`, Android at `market://`).
- Gate dormant: `X-Stationly-Client: ios;0.0.1;1` against `/stations/search`
  returns **200**, confirming an ancient client is still served while the switch
  is off.
- Legacy Android keys unchanged in the live payload.

### Live on device

iPhone 11, `iosApp Staging` / `Debug Staging`, built with `--kotlin` so the
Kotlin framework is not stale. Installed and launched. Staging logs show the app
fetching the new endpoint:

```
GET /api/v1/sdui/app/release-policy 200 2.757 ms - 893
GET /api/v1/sdui/app/home-config    200 2.971 ms - 18868
```

No crash reports. Widget extension running.

## 6. NOT verified live

Two things, both needing the enforcement switch on:

1. **That `X-Stationly-Client` is actually on the wire.** Morgan logs method and
   URL, not headers, and the gate being dormant means a wrong header would look
   identical to a right one.
2. **That a 426 reaches the blocking screen.**

Both are covered by unit tests, and both fall out of one command. The floor is
`1.0` and the app reports `1.0`, so enabling the switch does **not** block the
device — an ancient client header does:

```bash
KEY_PATH="$HOME/workspace/Projects/Stationly/Env/Staging/ssh/staging_main_key"
ssh -i "$KEY_PATH" ubuntu@79.72.94.209 "
  cd ~/stationly-backend
  sed -i 's/^VERSION_GATE_ENABLED=.*/VERSION_GATE_ENABLED=true/' .env
  pm2 reload stationly-backend --update-env
"

BASE=https://staging-api.stationly.co.uk/api/v1
KEY=f7d6c5b4-3a2b-1c0d-e9f8-a7b6c5d4e3f2

# expect 426 with a JSON body carrying title/message/cta/storeUrl
curl -s -w '\n%{http_code}\n' "$BASE/stations/search?query=oxford" \
  -H "X-Stationly-Key: $KEY" -H "X-Stationly-Client: ios;0.0.1;1"

# expect 200 — this is what the phone sends, and it must keep working
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/stations/search?query=oxford" \
  -H "X-Stationly-Key: $KEY" -H "X-Stationly-Client: ios;1.0;4"
```

To see the blocking screen on the phone, raise `ios.minimumVersion` to `1.1` and
`ios.latestVersion` to `1.1` in `appReleaseService.ts`, redeploy, and open the
app. Put both back afterwards.

## 7. Still outstanding

- **App Store numeric id is a placeholder** (`id0000000000`) in
  `appReleaseService.ts` and `app.ios.storeUrl`. Nothing can usefully gate until
  it is real. This is why every floor sits at `1.0`.
- **Per-OS floors.** iOS serves users on an old OS the *last compatible version*
  of an app, forever. Raising `minimumVersion` above that build locks those users
  out with no update available, and `assertSafe` cannot catch it because
  `latestVersion` is a single global number. Not urgent — the deployment target
  is 17.0 and v1 has not shipped, so no last-compatible build of Stationly exists
  yet. It becomes real the first time the deployment target is raised.
- **Android client port** of `ReleaseGate`. Phase 2, with the rest of the SDUI
  work.
- **What's New** on first launch of a new version. Deliberately not half-built.
