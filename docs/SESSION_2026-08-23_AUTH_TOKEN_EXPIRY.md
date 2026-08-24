# Handover — the app that logged itself out, again, for a different reason

_Session 2026-08-23. Branch `ios-parity` (StationlyUI). Diagnosed off the
connected iPhone 11 (`AB7B04C8-…`, iOS 26.3), staging backend. Nothing in this
session is committed; every change is in the working tree._

---

## 0. Read this first

Reported, in the user's words: *"the app signs itself out roughly an hour after
last foreground, and you find the login screen when you tap the icon or a
widget."*

That sentence appears almost verbatim in
[`SESSION_2026-08-15_PHANTOM_LOGOUT.md`](SESSION_2026-08-15_PHANTOM_LOGOUT.md),
which fixed it. **This is a second, unrelated bug with the same symptom and the
same period**, and the coincidence is not a coincidence: both are governed by the
one-hour lifetime of a Firebase ID token. August 15 was about the app losing its
*identity keys* to a race; this one is about the app sending a *dead bearer* and
treating the resulting 401 as a revoked account.

If a third report of "logs itself out after an hour" arrives, read §7 before
assuming it is either of them.

| # | Where | Class | Severity |
|---|---|---|---|
| 1 | `Platform.ios.getAuthToken()` | a cached string standing in for a live credential | **high** |
| 2 | `NetworkModule` 401 handler | an expired credential treated as a deleted account | **high** |
| 3 | `NetworkModule.forceLogout` | the sign-out left no evidence | medium |
| 4 | `ActivityLog` uid stamping | the one row that had to survive was the one guaranteed not to | medium |

---

## 1. The evidence, before a line was changed

Pulled from the device with no instrumentation and no rebuild, while the app sat
in the foreground streaming departures:

```
$ xcrun devicectl device copy from --device AB7B04C8-… \
    --domain-type appDataContainer --domain-identifier com.stationly.mobile.staging \
    --source Library/Preferences --destination ./appdata
```

```
uid : HdNrDVNLO1fD7hkolI5xFYOWz2H3
iat 2026-08-23 20:28:11
exp 2026-08-23 21:28:11     ← expired
now 2026-08-23 21:39:19     ← eleven minutes ago
```

**The app was holding a dead token while awake and working.** No reproduction
step was needed; the bug was simply present. Any `/user/*` request issued in that
state would have carried the dead bearer, earned a 401, and signed the user out.

The App Group ring told the other half of the story — forty entries, every one of
them `stream:update`. There was nothing about auth in it because **nothing about
auth was ever written**. See §5.

---

## 2. Root cause — iOS had no token authority

`core/src/iosMain/kotlin/platform/Platform.ios.kt` implemented the shared
contract as one dictionary read:

```kotlin
actual suspend fun getAuthToken(): String? = withContext(Dispatchers.IO) {
    NSUserDefaults.standardUserDefaults.stringForKey(ATOKEN)
}
```

`firebase_auth_token` is written by exactly three things — sign-in,
`settleAuthState`, and `AuthBridge.refreshTokenIfNeeded()` on foreground — and
**none of them is sequenced before an outbound request**. Firebase ID tokens live
one hour. So any `/user/*` call made more than an hour after the last foreground
carried a dead bearer.

Android never had this. Its actual asks the SDK:

```kotlin
user?.getIdToken(false)?.await()?.token     // auto-refreshes inside ~5 min of expiry
```

The `expect` declaration promised nothing about freshness, so both actuals were
"correct". The contract was the bug.

### The three trigger paths

1. **Foreground race.** `AppDelegate.handleDidBecomeActive()` starts
   `refreshTokenIfNeeded()` and `ActivityBridge.uploadActivityIfStale()` in two
   detached tasks with nothing ordering them. The upload usually wins and posts
   `/user/activity/batch` with the stale token.
2. **The overnight logout.** `ActivityUploadScheduler` runs a `BGProcessingTask`
   at 03:00 on a charger, in a process that never foregrounded — so
   `refreshTokenIfNeeded()` has not run and never will. The token is
   *guaranteed* stale. **No amount of task ordering inside `didBecomeActive`
   reaches this one**, which is why the fix had to move to the request.
3. **Widget tap.** Case 1, almost always from a cold start.

### And the 401 was treated as a deletion

`NetworkModule` signed the user out on any 401 outside a hand-kept path
allowlist. The list named `/auth/*`, three `/sdui/app/*` layouts and
`/user/sync/*`. It did **not** name `/user/activity/batch` — the endpoint both
triggers hit.

---

## 3. The fix, in four layers

### F1 — a real token authority for iOS

New: `core/src/iosMain/kotlin/platform/IosAuthTokenAuthority.kt`, plus
`composeApp/…/platform/AuthTokenBridge.kt` as the Swift-visible seam and
`AuthTokenResolver` in `AuthBridge.swift` as its Swift half.

`getAuthToken()` now resolves through FirebaseAuth in front of a cache, matching
Android's semantics. Three rungs, cheapest first:

1. the in-memory token, if it has more than five minutes left — no lock, no
   crossing, no defaults read;
2. `firebase_auth_token`, if IT does — this is what keeps a cold launch quiet;
3. Swift → `Auth.auth().currentUser.getIDToken()`, unforced, so the SDK decides.

**Why a direct framework call and not the existing bridge:** the
`auth_pending_command` protocol is polled every 250 ms. Acceptable for a login
button, unacceptable in front of every HTTP request.

**Why the seam is split across two modules:** only `composeApp` is compiled into
the framework Swift imports — `core` is an `implementation` dependency and none
of its declarations reach the generated header. (This is the same reason
`AppGroupKeys` has a hand-kept Swift twin.)

`LiveStreamManager.kt:369` needed no change: it already called
`Platform.getAuthToken()`, so teaching that function to refresh fixed the
WebSocket auth frame at the same time. A comment now records that, so the next
reader does not "fix" it twice.

### F2 — classify the 401 before destroying the session

`NetworkModule`'s handler moved from `HttpResponseValidator` to a `Send` hook,
and the default inverted:

- **`account_gone` → sign out**, on any path, including the ones the old
  allowlist exempted.
- **anything else → refresh the token and retry once.**
- **retry also 401s → record it and leave the session intact.**

Two reasons it had to be a `Send` hook, and the first is a bug the old version
had. `validateResponse` gave no way to read the body without taking it from the
caller: `bodyAsText()` consumes the content channel, so the `account_gone` check
was stealing the body of every 401 it inspected — `SduiApiService.syncProfile`
calls `.body()` unconditionally and would have got a consumed channel. It was
also wrapped in `runCatching { … }.getOrDefault(false)`, so any failure silently
became "not gone". The call is now buffered with `save()` first, so the body is
read from memory and the caller still gets a complete, re-readable response.
Second, a retry has to re-issue the request, and `Send` is the only place that
can — the same approach as Ktor's own `HttpRequestRetry`.

### F3 — the background hole closes by construction

No explicit `refreshTokenIfNeeded()` was added to `ActivityUploadScheduler`, and
**that is deliberate** — the comment in the file says so. Sequencing a refresh in
front of it would fix one caller and leave the next one to be written with the
same bug. The request resolves its own token now. There is also nothing useful a
refresh could do there: at 03:00 the phone is locked, protected data is
unavailable, FirebaseAuth cannot read the Keychain, and `refreshTokenIfNeeded`
returns on its first line.

### F4 — the tripwire

Every `signOutFromAuthExpiry` now writes a `PushTrace` line naming the path,
status and `account_gone`, plus an `auth.forced_logout` activity row with the
same fields. `auth.401_survived` records the opposite case.

Two defects surfaced *during* device testing and were fixed (§4):

- the row's **uid was empty**, which stranded it forever;
- **duplicate rows**, one per concurrent 401.

---

## 4. Two bugs the device found that reading could not

Both were in the tripwire itself, and both would have shipped silently.

### 4a. The uid was wiped before the row was written

First run, after disabling the account in the console:

```
22:45:42 token refresh → account gone, signing out        ← AuthBridge, first
22:45:42 auth:forced-logout path=/api/v1/user/sync/profile status=401 accountGone=true
```

```
(EMPTY)|auth.forced_logout|{"path":"…","status":"401","account_gone":"true"}|22:45:42
```

`AuthBridge.refreshTokenIfNeeded` reached the same verdict from the Firebase side
first and ran `clearUserInfo()`, deleting `firebase_user_uid` — the key
`ActivityLog` stamps events with.

**This is not cosmetic.** `ActivityUploader.flush` selects `WHERE uid = ?` for the
signed-in user, so a blank row matches nobody, never uploads, and is eventually
trimmed. The one event whose entire purpose is to be readable afterwards was the
one guaranteed to be thrown away.

Reading the key *earlier* was tried first and **did not work** — the re-run
produced an empty uid again. The two verdicts land within the same millisecond;
there is no ordering to win.

The fix reads the uid from the **`sub` claim of the token that was just
rejected**. It is already in the handler's hands, no teardown can erase it, and
it is the more truthful answer: the row describes a credential being refused, so
it belongs to the account that credential was minted for. New shared reader:
`core/src/commonMain/kotlin/util/JwtClaims.kt` (5 unit tests, JVM target).

### 4b. One logout, several rows

The same run recorded the forced logout **twice**, because a foreground fires
several `/user/*` calls at once and a gone account 401s all of them. A 30-second
dedupe window now applies — a window and not a permanent latch, because this
object outlives sign-outs and a latch would suppress the tripwire for a later,
genuinely separate logout.

---

## 5. Why this took several rounds to find

There was no evidence. A forced logout produced a login screen and nothing else:
the activity table showed the session simply stopping, indistinguishable from a
user putting their phone down, and nothing recorded which request had ended it or
what the server had actually said. Every hypothesis was equally consistent with
the data, because there was none.

That is why F4 is part of the fix and not scaffolding. **Do not remove it once
this feels fixed.** Its whole value is being there the next time something is
not.

One practical note: the `PushTrace` ring is 40 entries and `stream:update` fills
it in about twenty minutes of foreground use. Pull it promptly after a test, or
the auth lines will be gone.

---

## 6. Verified on device — iPhone 11, iOS 26.3, staging

Build discipline: `./gradlew :composeApp:assembleComposeAppDebugXCFramework`
before every `xcodebuild`, scheme `iosApp Staging`, configuration
`Debug Staging`. Auth was never verified through `devicectl device process
launch` — a CLI-launched process reads no Keychain (see §8 of the August 15
handover).

### Phase 1 — the request repairs its own bearer ✅

Forced an expired-but-syntactically-valid JWT, then posted
`/user/activity/batch`:

```
22:15:11 TEST forced expired token exp=1787516111          ← 21:15:11, an hour ago
22:15:11 auth:token cross force=false → exp=1787523309     ← 23:15:09
22:15:11 TEST stale-request before=1787516111 after=1787523309 advanced=true uploaded=true crossings=1->2
```

The stored token afterwards: `iat 22:15:09 / exp 23:15:09`, with a **real
signature** — not the test stub. `uploaded=true` means the backend accepted the
bearer. `force=false` means the SDK answered from its own cache, so the repair
cost no round trip to Google.

### Phase 1b — the fast path is silent ✅

```
22:17:23 TEST fast-path posted=10 crossings=0->0 delta=0
```

Ten real posts, **zero** bridge crossings. A fresh process reads the stored token
once, finds it good, and never crosses again.

### Phase 2a — must NOT sign out ✅

Airplane mode on, token deliberately corrupted, app backgrounded and reopened:

```
22:20:11 TEST forced expired token exp=1787516411
22:20:12 …Code=-1009 "The Internet connection appears to be offline."
22:20:12 auth:token cross force=false → exp=1787523436
```

Session survived, boards showing, `widget_signed_out: None`. **Bonus finding:**
the app repaired the corrupted token *with no network at all*, from the Firebase
SDK's own in-memory cache. Signal restored → token re-minted normally
(`exp 23:25:23`).

### Phase 2b — must sign out ✅

The decisive test. The device's own live token, curled against the deployed
staging backend one minute apart:

```
BEFORE disable: HTTP 200
AFTER  disable: HTTP 401 {"error":"Unauthorized","code":"account_gone",…}   /user/sync/profile
AFTER  disable: HTTP 401 {"error":"Unauthorized","code":"account_gone",…}   /user/activity/batch
```

Then on the phone:

```
23:04:57 auth nil we didn't ask for — session kept
23:04:58 auth:forced-logout path=/api/v1/user/sync/profile status=401 accountGone=true
```

```
HdNrDVNLO1fD7hkolI5xFYOWz2H3|auth.forced_logout|{"path":"…","status":"401","account_gone":"true"}|23:04:58
```

Login screen, `widget_signed_out: True`, **one** correctly-attributed row.

`Disable account` was used rather than `Delete account` — it fires
`auth/user-disabled` → `account_gone` server-side and `.userDisabled` in
`isSessionGone`, i.e. the same two branches, and is reversible.

### The open question, answered

**Does the backend reliably emit `account_gone`?** Yes, and it is deployed, not
merely in source. Probed live against `staging-api.stationly.co.uk`:

| condition | response |
|---|---|
| account disabled | `401 code=account_gone` |
| bad / expired token | `401 code=token_invalid` |
| no Authorization header | `401`, **no `code` field at all** |

`AuthMiddleware.validateUserToken` verifies with `checkRevoked: true`, so a
deletion or revocation takes effect on the next request from every device. F2 is
safe to depend on it: it signs out on exactly the first case and retries on the
other two.

---

## 7. Still open

- **Phase 0 formal reproduction — not run.** A pre-fix build was prepared in a
  detached worktree, then dropped: the live plist in §1 was already an
  unambiguous reproduction (a dead token held by a working app), and installing
  an older build would have cost two extra device wipes for weaker evidence.
- **Phase 3 (`_simulateLaunchForTaskWithIdentifier`) — not run.** It needs an
  LLDB debugger attached, which is not available from this environment. The code
  path is the same one Phase 1 exercised, and the resolver is registered in
  `didFinishLaunchingWithOptions`, which a background launch runs. Worth doing
  once from Xcode.
- **The "retry also 401s" tail — unreachable on the real backend.** A revoked or
  disabled account returns `account_gone`, and an expired token is repaired by
  the refresh, so nothing produces a *second* unlabelled 401. No
  `auth.401_survived` row was ever recorded on device. It needs a stubbed backend
  to exercise.
- **Phase 4 soak — with the user.** Seven days of normal use including at least
  two three-day gaps. Pass condition: zero `auth.forced_logout` rows and zero
  unexpected login screens.
- **`DevicePushCoordinator.register()`** still reads `firebase_auth_token`
  directly for its bearer (`DevicePushCoordinator.swift:157`). It is not on the
  Ktor path so F1 does not cover it. Low impact — the endpoint is not auth-gated
  and a stale token means "no uid recorded" rather than a rejection — but it is
  the last reader of that key that can be stale.
- **Debug test controls are still in the tree**, behind `#if DEBUG`
  (`AuthTestControls` in `AuthBridge.swift`, the `debug` deep-link host in
  `ContentView.swift`, and `AuthTokenBridge.crossings()`). They ship in no
  release build and are how the soak gets re-verified. Note the deep link needs
  the **Shortcuts app** or a marker file — Safari's address bar treats
  `stationly-staging://` as a search term on this device.
- **Housekeeping:** a `devicectl device copy to` during testing replaced the App
  Group's `Library/Caches` *directory* with a 13-byte file. Nothing in the app,
  the widget, or either Kotlin iOS source set reads that path, so it is inert;
  deleting and reinstalling the app clears the container if it ever matters.

---

## 8. What a future reader must not undo

- **Do not make `getAuthToken()` read a stored key again.** That is the bug. Any
  implementation must be able to produce a token that is valid *when the caller
  uses it*, which means it must be able to refresh.
- **Do not force a refresh on the ordinary request path.** The resolver passes
  `forcingRefresh: false` on purpose, so the SDK's own judgement applies. Forcing
  would put a Google round trip in front of every `/user/*` call to replace a
  token that was going to work.
- **Do not delete `refreshTokenIfNeeded`'s forced refresh either.** It is no
  longer keeping requests authenticated, but it is now the *only* thing that asks
  Google whether the account still exists. Its doc comment says this; keep them
  in step.
- **Do not give `IosAuthTokenAuthority.forceRefresh()` a stored-token fallback.**
  `token()` has one and should; `forceRefresh()` must not. Its only caller is the
  401 retry, which is holding the server's verdict that this exact credential was
  refused — handing the same token back spends a second request to be told the
  same thing and records an `auth.401_survived` row blaming a retry that never
  had anything different to send. The asymmetry between the two is written on
  both.
- **Do not restore the path allowlist in `NetworkModule`.** It answered the wrong
  question. The server labels its 401s; ask it.
- **Do not sign out on a bare 401.** The asymmetry is the whole point and is
  argued at length in `AuthBridge.swift`: missing a real deletion for one more
  foreground is invisible, while taking a live account away in front of somebody
  is unrecoverable without their password and happens at the moment they opened
  the app to use it. This file was the last place still treating an unexplained
  failure as proof of a sign-out.
- **Do not stamp `auth.forced_logout` from storage.** §4a: it is empty by the
  time the row is written, and an empty uid means the row never uploads. Read the
  rejected token's `sub`.
- **Do not remove the tripwire.** §5.
- **Untouched on purpose:** `settleAuthState`, `hasSession`,
  `discardTornIdentity`, and the `signedOutGeneration` rebuild in `ContentView`.
  They are load-bearing for the Keychain and protected-data cases and are
  correct.

---

## 9. Review pass — 2026-08-24

A strict re-read of this session's own diff, after the device evidence in §6 was
already in hand. Four defects, all introduced by this work, none of them visible
from the passing tests.

### Correctness

**9a. The dedupe suppressed the sign-out, not just the row.**
`NetworkModule.forceLogout` returned early on a duplicate — before
`Platform.signOutFromAuthExpiry`. So a second, genuinely distinct `account_gone`
arriving inside the 30-second window would have been dropped entirely. The
comment said "one logout, one row"; the code did something broader. Suppressing a
diagnostic is housekeeping, and nothing about deduplicating a diagnostic should
decide whether the app responds to "this account is gone". The sign-out is now
outside the window and always runs — it is idempotent, so the repeat costs
nothing.

The window check was also a bare read-then-write on shared state, which the
concurrent 401s it exists to collapse would all have passed. It is now one step
under a `Mutex` — the same correction `UserSyncBridge.reconcileOnForeground`
needed, for the same reason.

**9b. A crossing that landed after a sign-out repopulated the cache.**
`IosAuthTokenAuthority.crossToSwift` wrote its result unconditionally. A sign-out
running inside the round trip cleared the cache and the crossing then put the
ended session's bearer straight back, where every later request would use it.

This is the same shape as the write that resurrected a token in
`refreshTokenIfNeeded` (August 15). Swift guards its own half —
`persistFetchedToken` refuses once `expectingSignOut` is set — but that cannot
cover the Kotlin-initiated path, because `Platform.signOutFromAuthExpiry` only
*enqueues* the `signOut` command and `AuthBridge` picks it up on a later run-loop
turn. For that window Swift still believes the session is live. A generation
counter, bumped by `invalidate()` and compared after the crossing, closes it from
the side that opened it.

**9c. A data race on `expectingSignOut`.**
Every other reader and writer of that flag runs on the main queue. The resolver
is driven by the shared Ktor client and calls `persistFetchedToken` from whatever
background thread a request is on — an unsynchronised read of the one piece of
state deciding whether a token may be written at all. `persistFetchedToken` is
now `@MainActor`. The hop is free where it is paid: crossings are rare, and the
caller is already suspended on a network round trip.

### Performance

**9d. A builder copy on every request in the app.**
`HttpRequestBuilder().takeFrom(request)` ran before `proceed` on all traffic, to
serve a retry that only ever applies to requests carrying a bearer. This hook
sits in front of every call the app makes and the overwhelming majority —
`/stations`, `/lines`, `/sdui`, the departure fetches — are API-key-only and can
never reach the retry. The copy is now conditional on `bearer != null`.

### Cleanliness

- The base64url + claim decoding was duplicated between `IosAuthTokenAuthority`
  and the tripwire's uid lookup. Both now use `JwtClaims`, which is common code
  with unit tests; the iOS copy is gone.
- `FORCED_LOGOUT_DEDUPE_MS` gained a comment saying what its value is traded
  against, which every other constant in this change already had.
- The DEBUG test JWT's payload literal had lost its line continuations. It still
  produced valid JSON — whitespace between tokens is legal — so it worked, and
  read as though it did not.

### Re-verified on device after the refactor

```
08:54:35 TEST forced expired token exp=1787554475
08:54:35 auth:token cross force=false → exp=1787561673
08:54:35 TEST stale-request before=1787554475 after=1787561673 advanced=true uploaded=true crossings=0->1
```

`widget_signed_out: None`, no forced-logout line, activity queue empty (drained
normally). Phase 1 holds.

**Not re-verified:** Phase 2b was not re-run after the refactor, so 9a and 9b are
covered by compile and reasoning rather than by a second account deletion. 9b in
particular has no test — it needs a sign-out to land inside a crossing, which is
a millisecond window that cannot be arranged from outside.

---

## 10. Second review pass — 2026-08-24

The first pass (§9) looked for defects. This one looked for the thing that makes
defects invisible, and found it: **the retry branch had never executed once.**
Not on device, not in a test, nowhere. It shipped on reading alone.

### The gap, and why it was structural

The two branches that matter cannot be produced against the real backend.
`account_gone` needs a deleted account; the retry needs a server that rejects a
token it has just accepted, which a correct server never does. §7 recorded that
as "unreachable" and moved on. It was unreachable *through the backend* — it was
never unreachable through a stubbed engine.

What stopped it being tested was a design detail, not the network: the guard
called `Platform` and `NetworkModule` directly, and `Platform`'s Android actual
needs Firebase and a `Context` to exist at all, so no unit test could construct
one.

### The seam

`AuthExpiryActions` — three methods, `refreshToken` / `onAccountGone` /
`onSurvived` — with `PlatformAuthExpiryActions` as the production wiring and
`AuthExpiryGuard` becoming `authExpiryGuard(actions)`. The delegate is
deliberately thin: anything with a decision in it belongs in the guard, where the
tests can reach it.

**11 tests, all passing** (`AuthExpiryGuardTest`, via `ktor-client-mock`):

| test | what it pins down |
|---|---|
| `successPassesThroughUntouched` | a 200 is not retried and not inspected |
| `accountGoneEndsTheSessionAndDoesNotRetry` | the only branch allowed to sign out, and it hands over the rejected bearer |
| `callerCanStillReadTheBodyAfterTheGuardInspectedIt` | the consumption bug, twice over — buffered means *re*-readable |
| `plainUnauthorizedRefreshesAndRetriesOnceWithTheNewBearer` | **the branch that had never run**, and that the retry carries the refreshed token |
| `retryPreservesMethodBodyAndOtherHeaders` | the builder copy keeps POST, body, API key, path |
| `refreshFailingLeavesTheSessionAlone` | offline is not a deleted account |
| `aRetryThatAlso401sRecordsButDoesNotSignOut` | the tail §7 called unreachable |
| `tokenlessRequestIsNeitherRetriedNorSignedOut` | `/auth/` and the login layouts |
| `accountGoneIsHonouredOnFormerlyExemptPaths` | the old allowlist cannot come back by accident |
| `anUnlabelledBodyIsNeverTreatedAsGone` | empty, non-JSON and unlabelled bodies all fail safe |
| `otherErrorStatusesAreIgnored` | 403/404/429/500 are not auth verdicts |

`retryPreservesMethodBodyAndOtherHeaders` also settles a question §9 could only
reason about: copying the builder before `proceed` genuinely preserves a
re-sendable body.

### Also fixed

- **Two stacked KDoc blocks on `ActivityLog.recordBlocking`.** An earlier edit
  inserted a second one instead of merging, orphaning the original — the "two
  accounts of the same thing" failure, in the one file that had just been changed
  to prevent it. Merged, and the mechanism now lives once, in `persist`, with
  `forceLogout` pointing at it rather than restating it.
- **The retry's timeout ceiling is now stated.** `HttpTimeout` applies per engine
  call, so a request reaching the retry can take 15 s + refresh + 15 s. It is
  only reachable on a 401 whose retry also times out, and shortening the retry's
  budget would make the repair fail on exactly the slow networks it exists for.

### Re-verified on device

```
09:08:10 TEST forced expired token exp=1787555290
09:08:10 auth:token cross force=false → exp=1787562488
09:08:10 TEST stale-request before=1787555290 after=1787562488 advanced=true uploaded=true crossings=0->1
```

Full core suite: **264 tests, 0 failures.**

### What is still not covered

- **The generation guard (§9b) has no test.** It needs a sign-out to land inside
  a crossing — a millisecond window that cannot be arranged from outside. Testing
  it needs a seam on the authority too, the way the guard just got one.
- **Phase 2b has not been re-run** since §9. The `account_gone` path is now
  covered by unit test, but the end-to-end chain through a real disabled account
  was last exercised before the dedupe and seam changes.
