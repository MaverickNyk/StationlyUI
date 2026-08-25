# Handover — accounts, devices, sessions and sync (client)

_2026-08-25. Branch `ios-parity`. **All five phases built and verified on the
connected iPhone 11. Nothing committed.**_

**This is the only client document for this work.** The session records and
the two implementation specs were folded in here and deleted — there is no
second place to look.

**There are exactly two documents for this work, plus the backend's one.**
Everything else from this session was folded in here and deleted.

| Read | For |
|---|---|
| `DESIGN_SESSIONS_AND_SYNC.md` | the design and the reasoning. §12 is the phase plan. Kept because it is a REFERENCE, not a session record |
| **this file** | what was built, how it was verified, every bug found |
| `stationly-backend/docs/HANDOVER_SESSION_SYNC.md` | **the backend half, and the PRODUCTION RUNBOOK** |

> Where the design and this document disagree, **this one is right** — §5 lists
> four places the design was wrong and had to be corrected against a running
> system.

---

## 1. Status

| Phase | Built | Verified on device |
|---|---|---|
| P0 crons (backend only) | ✅ | fired on its own overnight, 03:00/03:20 UTC |
| P1 `stateRev` | ✅ | **0 Firestore reads** on an unchanged app open |
| P2 storage move | ✅ | steal, delete-on-logout, re-registration, account deletion |
| P3 client consolidation | ✅ | sign-out/sign-in, theme persistence, `PendingOps` replay |
| P4 socket tier | ✅ | all three tiers on one write |

**Tests:** backend 85/85, `core` 19.
Device: the connected **iPhone 11**, staging build, `testnyk67` / `testnyk66`.

---

## 2. What changed in the client

### `core` — new
```
session/SessionStore.kt      THE answer to "who is signed in"
session/PendingOps.kt        durable logout queue
repository/LocalRevStore.kt  the client's localRev, keyed by uid
commonTest/session/          SessionStoreTest (3), PendingOpsTest (6)
commonTest/repository/       LocalRevStoreTest (12)
```

### `core` — modified
```
model/sdui/SduiAppModels.kt   stateRev on the profile; deviceId on both sync requests;
                                rev on SyncStateResponse; new UserStateRevResponse
service/SduiApiService.kt     getUserStateRev(); deviceId on syncBoards/syncStations
repository/UserSyncRepository.kt  THE REV GATE in reconcileBoards; returns nullable now
repository/UserStateRepository.kt stamps localRev on login fetch and on an accepted write
iosMain/platform/LiveStreamManager.kt  the `user_sync` socket frame + onUserSync seam
iosMain/platform/Platform.ios.kt       forced auth-expiry now QUEUES a logout
```

### `composeApp`
```
sync/UserStateSync.kt         binds the per-account stores at session boundaries
ui/dream/DreamSettings.kt     namespaced by uid
ui/theme/AppTheme.kt          durable; deliberately NOT uid-scoped (§5.2)
ui/login/LoginViewModel.kt    replays PendingOps after a successful sign-in
ui/profile/ProfileViewModel.kt  queues the logout when the network fails
iosMain/platform/ActivityBridge.kt  installs the socket handler; scoped replay at launch
iosMain/platform/UserSyncBridge.kt  handleSignal() — one path for push, socket and foreground
util/FcmTokenRegistrar.kt     DELETED
```

### `iosApp`
```
DevicePushCoordinator.swift   session-gated; mints a LIVE bearer; clears the signature cache
```

**`android/` was not touched.** Every `core` change is source-compatible
(defaulted parameters, plus one nullable return on `reconcileBoards`, which
Android does not call — it uses the legacy `reconcile`).

---

## 3. The three mechanisms, and why they are shaped that way

### 3.1 The rev gate — P1

**Fetch the profile iff an observed rev exceeds `localRev`; after applying, set
`localRev` to the rev that came back.**

Lives in `UserSyncRepository.reconcileBoards`, the single entry point both iOS
paths funnel through (push and foreground). Returns `UserProfileResponse?` —
**null means the gate was closed and nothing was fetched.** That is the most
common outcome in the app, not a failure; a caller logging it as an error would
drown the log.

`localRev` is per-uid, in ORDINARY storage so logout clears it. The login fetch
(`UserStateRepository.fetch`) is deliberately **never gated** — it is the
guarded destructive restore a fresh session depends on.

> **An observed rev of 0 means "I cannot tell you" and MUST fetch.** Read
> literally, "fetch iff observed > localRev" leaves both at zero and never
> fetches — and every account that predates `stateRev` reads 0. A literal gate
> would have switched off cross-device reconcile on every existing account.
> See §5.5 below.

### 3.2 Three delivery tiers, one gate

| Tier | When | Cost |
|---|---|---|
| socket | foregrounded — it already holds the departures WebSocket | instant, 0 reads |
| silent push | backgrounded — APNs | 0 to send |
| foreground rev check | every open | 0 when unchanged |

All three land on `UserSyncBridge.handleSignal`, behind one mutex and one rev
gate. That is what makes adding a tier safe rather than a source of duplicate
work.

The socket handler is installed from `ActivityBridge.start()`, which
`AppDelegate.swift:144` already called — **no Swift change was needed.**

### 3.3 `PendingOps` — the durable logout queue

Closes two paths that never told the server a session had ended: an offline
sign-out (the call is capped at 4s and its failure swallowed — correctly, the
user asked to leave) and the **forced auth-expiry**, which ends a session
locally and had no live token to call with.

Replay runs **after a successful sign-in**, not at launch: `/user/logout` is
bearer-gated and rejects a mismatched uid, so signed out it is a 401 and signed
in as someone else a 403.

> **A sign-in SUPERSEDES a queued logout for that same device** — the op is
> discarded, not sent. See §5.4; the first version got this wrong.

---

### 3.4 The two nightly jobs (P0), in full

The backend spec is in the backend handover; this is what a client engineer
needs to know exists.

- **`sweep`** — `users where loggedIn == true`, then per account: if EVERY device
  row is past the 90-day TTL, run the full sign-out teardown. The predicate is
  **all** stale, not any: an account with one live device is somebody's working
  phone, and the lazy prune inside login/logout already covers its dead siblings.
- **`reconcile`** — recompute `metadata/subscribed_stations` from live accounts,
  write only the differing keys, `FieldValue.delete()` at zero.

`reconcile` is the dangerous one and is **not symmetric with sweep**. Sweep acts
through an existing transactional function over a narrow query — at worst slow.
Reconcile compares a snapshot taken across a whole collection scan against a
document every login, logout and board edit also writes, so a naive version does
not merely fail to fix drift: it can DELETE a live user's registry entry and stop
their boards updating, silently. Its race guard and its empty-target check are
not optional.

### 3.5 Why the rev ledger costs a read, and must

The backend mirrors `stateRev` into SQLite. **That mirror may only ever hold
values read from the master**, which costs one Firestore read per content write.

The obvious optimisation is to mirror `(the value we just read) + 1` and spend
nothing. It is wrong and it fails silently:

> A and B both write, both read `stateRev = N`, both increment. Firestore
> correctly reaches **N+2** — but both compute **N+1** for the ledger. Device C
> reads the profile in the sliver between the two increments, genuinely sees
> N+1, and stores it as its `localRev`. C's next check compares N+1 against N+1,
> decides nothing changed, and **never learns about B's write.**

Two reads a day to remove twenty is the trade, and it buys exactness in the one
mechanism whose entire job is deciding whether the client is stale.

**The writer's own rev is the deliberate exception:** the sync endpoints return
an optimistic `read + 1`, because that can only ever UNDERSHOOT the truth, and an
undershoot merely causes one extra fetch — which is correct when a concurrent
write really did land.

## 4. P3 — what was built, and what was deliberately not

**Built**

| Piece | What it closes |
|---|---|
| `SessionStore` | The single declaration of every identity key, each stating its own storage DOMAIN rather than inheriting it from an object's name — the mistake that once silently disabled cross-device sync. **Adoption, precisely:** `UserSettings`, `ActivityLog` and `UserStateRepository` now reference `SessionStore.Key.UID.storageKey` instead of declaring their own copy; `ProfileViewModel` (×2) and `SummaryViewModel` read through `SessionStore.uid()` / `.get(Key)` instead of raw literals. **Two spellings survive on purpose**: `AppGroupKeys.FIREBASE_USER_UID`, because Swift's `AuthBridge` is the WRITER and reaches `UserDefaults.standard` outside KMP entirely, and the literals in `AuthBridge.swift` itself. A test asserts the two agree, which is the honest version of the guarantee — pointing the Kotlin half at this object while leaving Swift untouched would have looked like one declaration without being one. |
| `PendingOps` | the retry hole (§3.3) |
| `FcmTokenRegistrar` deleted | dead on iOS from its second line (`registerDevice()` returns `""`), called from 5 places, guarded by something that read like a safety check. Also **not** the registrar Android uses — a different object with the same name. |
| `app_theme` durable | signing out silently reset the theme every time |
| Dream settings namespaced by uid | a store that survives logout meant the next account inherited a stranger's layout, theme, clock style and station |
| Bearer from a live token | pulled forward — P2 made it load-bearing |
| `DevicePushCoordinator.release()` | the signature cache surviving a session change. `register()` skips a POST whose body is unchanged, and the body says nothing about WHO is signed in — so sign out, sign back in without backgrounding, and the identical body was skipped while the backend had DELETED the row. The device sat in its own account's audience with no push address. `register()`'s signed-out guard only helped if it happened to run while signed out, and its three call sites (APNs callback, foreground, account change) mean it usually did not. Cleared from `AuthBridge.logout()`, which every deliberate teardown routes through — including `signOutForAccountDeletion`. This is the §8 `PushRegistrar.release()` iOS never had. |

**Deliberately NOT built: `SessionLifecycle` and `SyncEngine`.**

Both are consolidations of code that works. They would rewrite the four sign-out
paths and merge `UserSyncBridge`/`UserSyncCoordinator` — real regression risk on
paths verified by hand, for a payoff that is structural rather than behavioural.
**Every concrete bug §8.1 of the design names is closed by the list above**; what
remains is duplication.

The seams exist if they are picked up later: `LiveStreamManager.onUserSync` is
the `SyncEngine` entry point in all but name, and `SessionStore` is what
`SessionLifecycle` would be built on.

---

## 5. Every bug found, and how

**Seven. Not one was visible from the server** — every server-visible one
returned HTTP 200 while being wrong. Two were found only because the user
reported behaviour; four only because we ran it rather than reasoned about it.

### 5.1 A Firestore sentinel in the login response
`createOrUpdateUser` returned `{...existingData, ...updateData}`, and
`updateData` carried `stateRev: FieldValue.increment(1)` — a **write
instruction**, not a value. Serialised to JSON it became an opaque object; iOS
declares `stateRev: Long`, threw, and `LoginViewModel` caught it, rolled the
session back and signed the user out.

**The POST logged 200 every time.** Intermittent in the most misleading way: it
only fired when a profile field had actually changed, so the immediate retry
wrote nothing and worked. **The user's "it works on the second try" was the
entire diagnosis.**

Pinned by a test asserting the **JSON round trip** — the in-memory object looks
correct, which is why the earlier tests missed it.

### 5.2 Push audiences padded with unreachable rows
Login creates a device row and deliberately writes no token, so a signed-in
device that has not registered had a row with no way to reach it. Observed as
`APNs → 0/2 device(s)` where the honest count was 1. `devicesTargeted` is the
exact number the design's verification advice relies on ("assert the audience is
non-empty, verify the audience, never the send"), so this made the check that is
supposed to catch a silent push failure report success.

### 5.3 Registration reading a cached auth token
`firebase_auth_token` is written opportunistically and is routinely empty right
after launch. Once `/device/register` became bearer-gated, registration skipped
itself entirely and the device never got its push tokens back after a sign-out.
Fixed by minting a live token (`getIDToken()`).

### 5.4 `PendingOps` deleting the session that had just drained it
The replay fired correctly — and deleted the row the sign-in had just written.
`POST /user/logout 200` arrived AFTER the login.

The safety argument (`/user/logout` addresses a path that NAMES the account, so
another account's row is under a different parent and cannot be touched) is true
and still holds. It says nothing about the **same** account signing back in —
which is exactly the forced-auth-expiry case the queue exists for.

### 5.5 and 5.6 Identity-scoped values read before the identity loads — twice
**`app_theme` must NOT be uid-scoped.** It is read at theme-host start, the
earliest thing the UI does, before the uid is restored. The scoped read looked up
a key with no account attached, found nothing, and fell back to system. The value
was written correctly and never read back.

The design says namespace the DREAM settings by uid and move `app_theme` to
durable storage — two different instructions, and collapsing them is the mistake.
A per-account theme would need the read deferred until after auth restores, i.e.
a visible flash of the wrong theme on every launch.

**The same fault one layer over:** `DreamSettings.bindAccount()` was called at
login and logout, but an ordinary cold launch of an already-signed-in app crosses
**neither** boundary. Now bound at launch too.

> **A value scoped by identity can only be read once that identity is
> available**, and the reads that happen earliest are exactly the ones that look
> like plain preference lookups.

### 5.7 A superseded store that was still readable
`users.sessions` stopped being written but was not deleted, and one login guard
still read it — so after a sign-out deleted the device row, the frozen map still
said the device was active, `startSession` was skipped, and **the row was never
recreated on sign-in.** The device silently dropped out of its own account's push
audience while `loggedIn` stayed true on the strength of other devices.

> **"Stopped writing it" is not the same as "nothing reads it".**

All seven are pinned by tests.

---

## 6. Not a defect, but worth knowing

**Signing out with no network shows `ServiceUnavailableScreen` over the login
form.** That is `LoginScreen`'s `isBackendOffline` state — the SDUI layout cannot
be fetched — and it is deliberate Android parity, dismissible, with a retry. The
brief flash of the login form beforehand is the cached layout rendering. The
sign-out itself completes normally.

---

## 7. How to verify from cold

```bash
# Backend
cd stationly-backend && npm run build && npm test          # 85/85
STAGING_KEY=~/workspace/Projects/Stationly/Env/Staging/firebase/service_account.json
node src/scripts/check_session_state.cjs   --key=$STAGING_KEY   # invariants
node src/scripts/check_state_rev.cjs       --key=$STAGING_KEY   # ledger ≤ master
node src/scripts/check_device_indexes.cjs  --key=$STAGING_KEY   # queries run

# Client
cd ../StationlyUI
./gradlew :core:testDebugUnitTest                          # 19
./gradlew :core:compileKotlinIosArm64
./gradlew :composeApp:compileKotlinIosArm64
```

`:core:testDebugUnitTest` specifically — `allTests` dies on wasmJs, and the iOS
test target will not compile the existing comma-named tests.

**iOS build:** scheme `iosApp Staging`, configuration `Debug Staging`, and
`assembleComposeAppDebugXCFramework` MUST run before `xcodebuild` or the app
ships stale Kotlin and a green build proves nothing. Device is the connected
iPhone 11, never the simulator. Do not launch with `devicectl` to verify auth —
a CLI-launched app reads no Keychain.

---

## 8. Uncommitted, and a suggested commit split

```
StationlyUI:      16 modified, 1 deleted, 4 new source dirs, 7 docs
stationly-backend: 15 modified, 1 deleted, 16 new files
```

1. **P0** — `sessionMaintenanceService`, the two internal routes, the enabling
   changes, the probes, `run_maintenance.cjs`, 6 tests.
2. **P0 ops** — `.scripts/maintenance_cron.sh` + `.crontab` **plus the
   `.gitignore` change**, without which they are not committable at all.
3. **P1** — `userRevLedger`, `user_revs`, the five bump sites, the rev route,
   push payloads, `LocalRevStore` + tests.
4. **P4** — `sendToUid`, the notifier tier, `LiveStreamManager` frame, 5 tests.
5. **P2** — indexes, `userDeviceService`, `userWatchIndex`, the transactions,
   audience move, admin move, backfill + probes.
6. **P2 cleanup** — `deviceRegistryService` deleted, dead fields removed,
   `cleanup_legacy_stores.cjs`.
7. **P3** — `SessionStore`, `PendingOps`, `FcmTokenRegistrar` deleted, settings
   scoping, `DevicePushCoordinator`.
8. **Docs.**

---

## 9. Open

- **Two-device convergence.** One phone; A→B is evidenced by the push audience
  and the fan-out log, not by watching B apply it.
- **`SessionLifecycle` / `SyncEngine`** — §4.
- **Production** — nothing done. The runbook is in the backend handover, and its
  first section is a hazard that would sign out every Android user if the
  ordering is ignored.
