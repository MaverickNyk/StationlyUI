# Handover — account identity, deletion propagation, sync hardening

_Session 2026-08-12 (evening), continuing the same day as
`SESSION_2026-08-12_BOARD_MODEL.md`. Branch `ios-parity` (StationlyUI) +
`dev_13Jul` (stationly-backend). **iOS + backend only —
`git status --porcelain -- android/` → 0.**_

---

## 0. READ THIS FIRST (30 seconds)

This session began as an **audit** of the board-model work and turned into an
account-identity investigation. Headlines:

1. The previous session's "we fixed the write volume" claim was **false**. Every
   app backgrounding still wrote to Firestore. §2
2. The same code path **could destroy a user's entire board list**. §3
3. One email had **two Firestore user documents** — a deleted account was being
   resurrected by the other device. Root-caused and fixed. §5
4. Account deletion **did not sign the other device out**, and when it finally
   did, it **did not tear anything down**. Both fixed and verified on device. §6
5. I introduced two bugs and fixed them: an app **crash on login** (§7.2) and a
   **wrong widget-quota optimisation** (§8.2). Both are called out so you do not
   re-introduce them.

**Nothing is committed.** Both trees are dirty and carry four sessions of work.

---

## 1. Index — read only what you need

| § | Topic | Read it if… |
|---|---|---|
| 2 | Board push gate (write volume) | touching `UserStateRepository` / sync cadence |
| 3 | Empty-list data loss + `allowEmpty` | touching `/user/sync/boards` |
| 4 | `preferences` erased from the backend | anyone asks "where do settings sync" |
| 5 | **Account identity / resurrection** | anything auth, deletion, or duplicate accounts |
| 6 | Deletion propagation (3 layers) | "why didn't the other device sign out" |
| 7 | Session teardown + UI reset | logout leaves the app in a weird state |
| 8 | Foreground reconcile + widget quota | cross-device board updates, widget refresh |
| 9 | `SelectionRepository` shared cache | "board is empty after login" |
| 10 | `BoardView.NEXT_ONLY` removed | board layout / settings screen |
| 11 | Files touched | orienting |
| 12 | Verification state — what IS and IS NOT proven | before you trust any of this |
| 13 | **Open items / next actions** | starting work |
| 14 | **NEXT BIG THING: device registry unification** | the planned refactor |
| 15 | Traps | before debugging anything here |

---

## 2. The write volume was never actually fixed

### What the previous session claimed
Appearance settings moved off the wire, therefore the write problem was solved.

### What was true
`UserStateRepository.flushNow()` pushed **unconditionally** — no dirty tracking.
It is called on **every backgrounding** (`iOSApp.swift`) and **again nightly**
(`ActivityBridge.uploadActivity`). So the cost moved from "per settings tap" to
"per app close":

```
1 backgrounding = userRef.get() + userRef.update()
                + UserSyncNotifier fan-out to EVERY device (incl. the sender)
                + a getUserProfile read on each of those devices
```

Twenty app opens a day ≈ 20 writes + 40 reads + 20 pushes, on the document every
login reads. `UserSyncNotifier.notify` does **not** exclude the originating
device, which multiplies it.

### Fix — `BoardPushGate`
New: `core/src/commonMain/kotlin/repository/BoardPushGate.kt` (+ 17 tests in
`core/src/commonTest/kotlin/user/BoardPushGateTest.kt`).

Extracted from the repository specifically **because the repository cannot be
unit-tested** — it reaches the network and `Platform`, an `expect object` with no
injection seam. The gate has neither.

- `revision` counts user changes; `pushedRevision` records the last the server
  accepted. Equal → `pending()` returns null → the flush costs nothing.
- **A counter, not a boolean.** An edit arriving mid-request bumps `revision`
  past the value the request captured, so the acknowledgement cannot clear a
  newer edit. A boolean would drop it silently, surfacing as a change that
  appears on the other device and then vanishes.
- A failed push leaves the gate pending, so the nightly flush **genuinely**
  retries. It previously "recovered" only because the next flush pushed
  regardless.

---

## 3. The same push could wipe the account

`pushBoards()` sent whatever `currentBoards()` returned, stamped
`Clock.System.now()` — which always wins the server's LWW guard.

`UserSyncRepository.syncUserAndGetSavedStations` calls `sqlStorage.clearAllData()`
(line ~45) and the v2 boards only return later, in `restoreBoards`. **Background
the app inside that window** — a slow network on the login loader is enough — and
the device posts `boards: []`. Nothing refused it: the controller checked only
`Array.isArray`, and `sanitiseBoards([])` returns `[]`. Account cleared, then
propagated to every other device by the next reconcile.

Same shape if the `runCatching`-wrapped `setupStation` calls inside
`restoreBoards` fail.

### Fix — two layers
1. **The gate closes it** (login never calls `boardsChanged()`).
2. **Server backstop:** `allowEmpty` on `SyncBoardsRequest`, default `false`.
   An empty list over a non-empty stored one is refused with
   `200 {applied:false, reason:'empty_rejected'}` and logged loudly. A 200, not
   an error — the client must **not** retry a destructive write.

Call sites pass the **live** answer, never a literal:
```kotlin
UserStateSync.boardsChanged(emptiedByUser = selectionRepository.selections.value.isEmpty())
```
so deleting one of four boards never hands out the permission. The flag is
surrendered only once a **non-empty** list is accepted — while the account
legitimately has no boards, every later push is also empty and needs the same
justification.

---

## 4. `preferences` is gone from the backend

It survived the previous session in three places, one of which mattered:

- `UserProfile.preferences?: Record<string, unknown>` — dead surface
- **`server.ts` swagger** still advertised it as *"Account-scoped client settings,
  stored verbatim"* — i.e. the public API still told the next client (and Android,
  on migration) to sync settings there
- **Stored documents** still carried whatever was last written

Now: removed from the interface and the schema, plus `DROP_LEGACY_PREFERENCES =
{ preferences: FieldValue.delete() }` folded into the `update()` calls
`syncBoards`/`syncStations` **already make** — zero extra writes, converges every
active account.

⚠️ **`preferences` stays on `PROTECTED_PROFILE_FIELDS`.** That is a *deny* list;
it is what stops a client resurrecting settings via `/user/sync/profile`.

---

## 5. Account identity — one email, two documents

### The evidence (staging, read-only)
```
AUTH      users with testnyk67@gmail.com : 1   HpQSHEdrnOStM4naVYdgNhMwInB2
FIRESTORE users/ docs with that email    : 2
    HpQSHEdrnOStM4naVYdgNhMwInB2   0 boards
    ysS99B6XmdPk4u9unhI9Wo2BMm93   2 boards   ← NO auth user behind it
```
Firebase Auth was correct throughout — one user per email. The duplicate was
**ours**.

### Root cause — a three-link chain
1. `validateUserToken` called `auth.verifyIdToken(idToken)` **without
   `checkRevoked`**. A Firebase ID token stays cryptographically valid for its
   full ~1h life after its user is deleted.
2. `createOrUpdateUser`'s `if (!snapshot.exists)` branch **created a document for
   any authenticated uid**, with no check that the uid still had an Auth user. It
   cannot tell "new signup" from "just deleted".
3. **Nothing ever checked email uniqueness across documents.** Every read and
   write is keyed by uid, so an orphan is undetectable by construction.

Sequence: Android deletes → doc + Auth user gone → iOS (still holding a valid
token) calls `/user/sync/profile` → **document re-created** → board sync
re-populates it → user signs in again → new uid, new doc.

### Fixes (all backend)
| Where | Change |
|---|---|
| `authMiddleware.validateUserToken` | `verifyIdToken(idToken, true)`. Deletion takes effect on the next request from **every** device, independent of push delivery. |
| same | 401 now carries `code: 'account_gone'` vs `'token_invalid'` — a client must distinguish "refresh and retry" from "end this session". |
| `deleteAccount` | `auth.revokeRefreshTokens(uid)` **first**, before anything else — closes the window rather than relying on `deleteUser` at the end. |
| `createOrUpdateUser` | `auth.getUser(uid)` before creating; throws `Account no longer exists` if gone. |
| new `purgeOrphanDocsForEmail` | On signup, removes same-email docs whose uid Auth **confirms** is gone (+ their subcollections). Conservative: a doc whose uid still resolves is never touched — it logs that the project may allow multiple accounts per email. |

**Verified after the fix:** `h5JHYbpKoVMD2FbQFEe5G7uOI3J3` → AUTH not found,
FIRESTORE no document, 0 docs / 0 auth users for the email. Resurrection closed.

---

## 6. Deletion propagation — why it silently failed

Deleting on Android left iOS fully working. Three separate reasons:

**Layer 1 (push) did not arrive.** Targeting was provably correct — the device
was in the registry under the right uid, with a valid 64-char `appToken` and
`environment: sandbox`. The failure is downstream at delivery. **STILL
UNDIAGNOSED — see §13.3.**

**Layer 2 (401) could not fire.** Browsing, refreshing and the departure stream
all authenticate with the **API key**, not the user token, and boards render from
local SQLite. A signed-out-but-unaware device has a complete working app and *no
reason to contact `/user/*` at all*. Worse, `NetworkModule` **excluded
`/user/sync/*` from the 401 sign-out** — exactly the endpoints such a device
would hit.

**Layer 3 (Firebase SDK) was ~55 minutes away.** `refreshTokenIfNeeded()` runs on
every foreground but called `getIDToken()` **without forcing**, so Firebase
returned the cached token until it was within ~5 min of expiry. Its `catch` only
`print`ed.

### Fixes
- `NetworkModule`: `account_gone` in the body **overrides** the auth-endpoint skip
  list. The exemption exists for "not signed in *yet*", which must not swallow
  "this account no longer exists".
- `AuthBridge.refreshTokenIfNeeded()`: `getIDToken(forcingRefresh: true)`, which
  asks Google directly — **costs our backend nothing**. On failure, only a *gone*
  session signs out (`isSessionGone`: `.userNotFound`, `.userDisabled`,
  `.userTokenExpired`, `.invalidUserToken`). Anything else keeps the session —
  signing out on a tunnel is far worse than noticing a deletion one foreground
  late.
- `DevicePushCoordinator`: the `deleted` branch **bypassed the uid check** (it
  returned before reaching `UserSyncBridge.handle`, where every other reason is
  checked). A device token outlives a session, so a deletion push could sign out
  the wrong person. Now checked via new `UserSyncBridge.currentUidOrNull()`.

**✅ VERIFIED ON DEVICE:** with the account already deleted, launching the app
cleared **every** `firebase_*` key from the app's plist — with no push involved.

---

## 7. Session teardown and UI reset

### 7.1 `signOutForAccountDeletion` was only the Swift half
It called `logout()` — Firebase out, Google out, identity keys cleared — and
stopped. Everything Kotlin owns (SQLite boards, widget, topics, per-account
settings) was left standing, because on the deliberate path that work lives in
`ProfileViewModel.signOut` and nothing here called the equivalent.

Result: **no credentials and a completely working app.**

Fix: new `UserSyncBridge.tearDownDeletedAccount()` → `lifecycle.cleanupAll()` +
`UserStateSync.forgetAccount(uid)`. **Must run BEFORE `logout()`** — it reads the
uid from storage that `clearUserInfo()` wipes.

`forgetAccount` (not `resetForNewSession`): a signed-out account keeps its
arrangement because the same person is expected back; a **deleted** one has
nobody to come back.

### 7.2 ⚠️ THE CRASH — a bug I introduced, then fixed
`ComposeHostView.updateUIViewController` is empty, so `startLoggedIn` is read
once in `makeUIViewController` and never again — a sign-out originating outside
Compose never reached the UI. I added an `.id()` to force a rebuild, and wrote it
**wrong**:

```swift
.id(isLoggedIn ? "session" : "signed-out-\(signedOutGeneration)")   // ❌ changes on BOTH transitions
```

That rebuilt the entire Compose host **on login too** — tearing down the tree the
loader had just populated. With one board: an empty-screen flash that resolved in
1–2 s. With two boards: **a crash.**

```swift
.id(signedOutGeneration)   // ✅ counter only increments logged-in → logged-out
```

**Do not "simplify" this back.** Login must leave the id untouched; Compose
handles that direction itself.

---

## 8. Foreground reconcile and the widget quota

### 8.1 The push was the ONLY path
`UserSyncBridge.handle` was reachable from exactly one place — the APNs handler.
Miss the push and that device stayed stale **forever**, not until next launch.
Android has had `UserSyncCoordinator.reconcile` since it had cross-device sync;
iOS never got the counterpart.

New `UserSyncBridge.reconcileOnForeground()`, wired into
`AppDelegate.handleDidBecomeActive`, debounced 2 min
(`FOREGROUND_MIN_INTERVAL_MS`). Shares the mutex with `handle` so the two cannot
run the diff concurrently. Returns whether anything **actually changed**, by
diffing `boardKey` sets before/after.

### 8.2 ⚠️ A WRONG OPTIMISATION I MADE, THEN REVERTED
I gated the foreground widget refresh on "something changed", reasoning that
Apple meters reloads at ~40–70/day. **That is wrong, and the codebase already
says so** — `RefreshScheduleStore`:

> **Only SCHEDULED rebuilds are charged.** *Foreground* — WidgetKit exempts
> reloads requested while the app is on screen… Counting those measured fourteen
> phantom "spends" in the five seconds after an install.

`isAppForeground()` is checked before anything is charged, fed by a heartbeat the
app writes into the App Group (`AppGroupKeys.appForegroundHeartbeat`, flushed with
`synchronize()` — see the comment there, it is load-bearing).

**A foreground widget rebuild is FREE. Always do it.** The change flag is used
only for `DevicePushCoordinator.register()`, which is a real network write.

---

## 9. "Your board is empty" after login

**Six** places construct a `SelectionRepository`, and each had its **own**
in-memory `MutableStateFlow` over the same SQLite table. Login restored boards
through *its* instance — SQLite correct, Firestore correct — while the home
screen collected a *different* instance's flow, still holding the empty list it
was constructed with. Visiting the profile screen and returning "fixed" it by
rebuilding the ViewModel.

Fix:
1. The cache is now a **process-wide companion** `shared` flow. Same reasoning
   `UserSettings` already documents: two independently-loaded copies drift the
   moment one writes. The storage underneath was always a singleton.
2. `LoginViewModel` calls `selectionRepository.initialize()` **before**
   `restoreBoards`, because `syncUserAndGetSavedStations` wipes SQLite directly
   without going through the repository — so the shared cache could still hold the
   *previous* user's boards (iOS deliberately skips `cleanupAll()` on login).

---

## 10. `BoardView.NEXT_ONLY` removed

Dropped on request. With it went every line that existed to hide the board:
`BoardView.showsBoard`, the card's `showBoard` param and its `if` wrapper,
`BoardPreview.withBoard` and its `else` branch, and the picker's third tile (the
picker is driven off `BoardView.entries`, so it shrank on its own).

A stored `view: "NEXT_ONLY"` decodes to `FULL` — `UserSettings` reads with
`coerceInputValues`, which falls back to the property default for an unknown enum
member. **Tested** (`BoardTest`), along with an assertion that
`entries == [FULL, BOARD_ONLY]` so re-adding a board-hiding view fails loudly.

---

## 11. Files touched this session

**StationlyUI — new**
```
core/src/commonMain/kotlin/repository/BoardPushGate.kt
core/src/commonTest/kotlin/user/BoardPushGateTest.kt
docs/SESSION_2026-08-12_SYNC_AND_IDENTITY.md   (this file)
```

**StationlyUI — modified**
```
core/.../repository/UserStateRepository.kt      gate wiring, allowEmpty
core/.../repository/UserSettings.kt             forgetAccount, rememberedWidgetBoards DELETED
core/.../repository/SelectionRepository.kt      shared cache
core/.../platform/Platform.kt (+ ios/android/wasm)  removeDurable; wasm clearAll keeps durable_
core/.../service/NetworkModule.kt               account_gone overrides skip list
core/.../service/SduiApiService.kt              syncBoards(allowEmpty)
core/.../model/sdui/SduiAppModels.kt            SyncBoardsRequest.allowEmpty
core/.../model/user/Board.kt                    NEXT_ONLY removed
composeApp/.../sync/UserStateSync.kt            forgetAccount, ACCOUNT_REMOVED_FLAG
composeApp/.../platform/UserSyncBridge.kt       currentUidOrNull, tearDownDeletedAccount, reconcileOnForeground
composeApp/.../ui/login/LoginViewModel.kt       initialize(), removed-notice read
composeApp/.../ui/login/LoginUiState.kt         accountRemovedNotice
composeApp/.../ui/login/LoginScreen.kt          notice takes precedence
composeApp/.../ui/profile/ProfileViewModel.kt   deleteAccount → forgetAccount
composeApp/.../ui/station/StationSettingsViewModel.kt   emptiedByUser, stale KDoc
composeApp/.../ui/selection/SelectionViewModel.kt       emptiedByUser
composeApp/.../ui/station/StationSettingsScreen.kt      withBoard removed
composeApp/.../ui/summary/SummaryScreen.kt              showBoard removed
composeApp/.../ui/summary/components/Board.kt           showBoard removed
iosApp/iosApp/AuthBridge.swift                  forcingRefresh, isSessionGone, teardown, import composeApp
iosApp/iosApp/ContentView.swift                 .id(signedOutGeneration)
iosApp/iosApp/AppDelegate.swift                 foreground reconcile + unconditional widget refresh
iosApp/iosApp/DevicePushCoordinator.swift       uid check on deleted
```

**stationly-backend — modified**
```
src/middleware/authMiddleware.ts   checkRevoked, account_gone
src/services/userService.ts        getUser guard, purgeOrphanDocsForEmail, revokeRefreshTokens,
                                   generic subcollection purge, devices-row purge,
                                   allowEmpty, DROP_LEGACY_PREFERENCES, preferences removed
src/controllers/userController.ts  allowEmpty === true, swagger
src/server.ts                      preferences removed from UserProfile schema
```

---

## 12. Verification state

**Proven**
- `:core:testDebugUnitTest` — **193 tests, 0 failures** (BoardPushGate 17, BoardTest 17)
- `:composeApp:compileKotlinIosArm64`, `:android:app:compileStagingDebugKotlin`, backend `tsc --noEmit` — all clean
- `git status --porcelain -- android/` → **0**
- iOS built, installed and launched on Nick's iPhone 11 repeatedly
- **Self-sign-out on a deleted account, on device**, with no push (§6)
- **Resurrection closed** — verified against live staging (§5)
- Phantom doc + `fcm_tokens` leak confirmed by direct Firestore inspection (§13.1)

**NOT proven**
1. A backgrounding with no board edits produces **zero** network traffic (the whole point of §2)
2. `allowEmpty` end-to-end
3. Cross-device board delete → other device updates (§8.1) — *user was about to test*
4. Account-removed notice actually rendering
5. APNs delivery (§13.3)

⚠️ `:core:compileKotlinWasmJs` **does not build** — and did not before this session
either. `app.cash.sqldelight:{runtime,coroutines-extensions}:2.0.2` publish no
wasmJs variant. Dependency resolution, not a code error; the wasm `StorageManager`
edits are unverified by a compiler.

---

## 13. Open items — do these first

### 13.1 Redeploy the backend ⚠️
The user deployed mid-session. **These landed AFTER that deploy and are NOT live:**
- generic subcollection purge in `deleteAccount` (the `fcm_tokens` phantom)
- root `devices` row purge on delete

Evidence they are needed — live staging:
```
users/UJ3Pgl4PIkgibH5FC7f9tht16Q92  *** PHANTOM (no doc, subcollections remain) ***
   subcollections: fcm_tokens(1)
```
A push token surviving under a deleted uid. **Verify what is actually deployed
before assuming.**

### 13.2 Commit both repos
Four sessions of uncommitted work. A `git stash` during this session briefly
reverted the tree — recovered, but the risk is real.

### 13.3 Diagnose the APNs delivery failure
Targeting was correct (§6), so the answer is delivery-side, in the staging PM2
log: `USER_SYNC: 📡 APNs reason='deleted' → N/M device(s)`.
```
ssh -i ~/workspace/Projects/Stationly/Env/Staging/ssh/staging_main_key ubuntu@79.72.94.209
```
Prime suspect: the app was **reinstalled** shortly before the test, which
invalidates the previous APNs device token — the registry may have held a stale
one, and APNs would reject it silently.

### 13.4 Logout does not deactivate the device
`UserService.logOut()` only calls `endSession` — it never touches the `devices`
registry, so the row keeps its `uid` and `listForUid` still finds it. Pushes go to
a signed-out phone. **Intended behaviour is that logged-out devices are inactive.**
Superseded by §14 if that lands; otherwise clear `uid` on logout.

### 13.5 Android rebuild for the `account_gone` fast path
`NetworkModule` is in `core`, which Android shares. Its FCM `deleted` →
`forceLogout` path already works without it.

### 13.6 Android still emits no activity events
`ActivityLog` / `ActivityUploader` are in `commonMain` and ready. Missing:
`record()` call sites and a WorkManager job calling `flush`.

---

## 14. NEXT BIG THING — device registry unification

### The problem: one entity, three records
A *device belonging to a user* is stored in **three** places:

| Store | Keyed by | Cleaned on logout? | On delete? |
|---|---|---|---|
| `users/{uid}.sessions` (map) | deviceId | yes | yes |
| `users/{uid}/fcm_tokens/{token}` | **the FCM token** | no | no (fixed §13.1) |
| `devices/{deviceId}` (root) | deviceId | **no** (§13.4) | no (fixed §13.1) |

Every seam produced a bug this session. Note `fcm_tokens` is keyed by the
**token**, not the device — one phone rotating its token creates a *second*
document, which is why `pruneStale(90d)` exists. That function is a garbage
collector compensating for a wrong primary key.

Second problem: `sessions` is written on every session start/refresh — device
heartbeat traffic writing the same document that holds the boards, which is the
hottest read (every login) and now the hottest write (every board change).

### Target shape
```
users/{uid}
   identity       uid, email, displayName, photoURL, signInProvider, emailVerified
   lifecycle      createdAt, updatedAt, welcomeSent
   boards[]                          ← write path: POST /user/sync/boards
   boardsUpdatedAt
   stations[]     LEGACY — read-only, deleted when Android migrates

   /devices/{deviceId}               ← ONE record. Replaces all three above.
        platform, model, osVersion, appVersion
        firstSeen, lastSeen
        fcmToken? | appToken? | widgetToken?, environment
        stations[], lines[]           ← push scoping, BOTH platforms

   /activity/{YYYY-MM-DD}_{deviceId} ← already correct

metadata/subscribed_stations          ← derived union, unchanged
```
**Disappears:** root `devices`, the `sessions` map, `fcm_tokens`, `pruneStale`.

### What it buys
- Logout = delete one document. "Only logged-in devices get pushes" becomes a
  property of *where data lives*, not a rule to remember.
- Account deletion already handled by the generic subcollection purge.
- Keyed by deviceId → token rotation **updates** instead of accumulating.
- The user document stops being written by device activity.
- Android gains station/line-scoped disruption pushes instead of `Station_*`
  topics waking every subscriber.
- `UserSyncNotifier` collapses from two fan-out paths to one audience query with
  a per-row transport choice.

### The two rules that stop it re-scattering
1. **One entity, one record, keyed by its own identity, under its owner.**
2. **The account document holds only what a login needs.** Anything written at
   device cadence lives in a subcollection.

(Same principles `Board` already follows — `config`/`position` on the board,
`mode`/`lines` derived not stored. This applies them one level up.)

### Costs — get these right or it fails silently
- **Collection-group index** on `devices.lines` / `devices.stations` must exist
  *before* the query ships, or disruption pushes return an empty audience with no
  error — the exact failure `notifyFcm` already warns about.
- **Registration must be a MOVE, not a copy.** `devices/{deviceId}` at root
  structurally guaranteed one row per device; under subcollections it does not. A
  missed delete leaves a phone registered under two accounts and the previous
  owner's pushes land on it. `register()` becomes "delete this deviceId wherever
  it exists, then write here", batched.
- **`loggedIn` is load-bearing.** It gates `endSession`'s transaction so
  subscription counts cannot double-decrement. Under the new model that becomes
  "last device removed" — expressible, but do not fold it into the same pass.

### Also worth folding in
`DreamSettings` writes through `DreamPrefsBackend`; `UserSettings` through
`StorageManager.saveDurable`. On iOS both are the same App Group suite — two APIs
over one store, which is why teardown resets them separately (`forgetAccount` +
`clearDreamSettings`). Folding Dream onto `UserSettings` with uid namespacing
makes it one mechanism and one reset.

### Do NOT change
The board model, the local-first activity queue, the synced-vs-device-local
split, `metadata/subscribed_stations`.

### Sequencing
**Commit (§13.2) → redeploy (§13.1) → then this**, ideally paired with Android
adopting the board model, since both rewrite the same registration path.

---

## 15. Traps

- **The XCFramework is not optional.** `xcodebuild` ships a STALE framework and
  builds green. Any Kotlin change needs
  `./gradlew :composeApp:assembleComposeAppDebugXCFramework
  :composeApp:assembleIosArm64MainResources` **first**. Cost me a build this
  session (`no member 'currentUidOrNull'`).
- **`AuthBridge.swift` needs `import composeApp`** to see KMP types. It shipped
  with that import commented out.
- **SourceKit reports `No such module 'UIKit'/'FirebaseCore'`** on every Swift
  edit. It is an indexer artifact — `xcodebuild` is the authority.
- **Foreground widget rebuilds are FREE.** Do not "optimise" them away (§8.2).
- **`.id()` on the Compose host must not change on login** (§7.2).
- **Never `git stash` this tree.** Four sessions of uncommitted work.
- **Production is off limits.** Staging only: service account at
  `Env/Staging/firebase/service_account.json`, project `mindthetimefcm`.
  Diagnostic scripts need `NODE_PATH="$(pwd)/node_modules"` and to be run from
  the backend directory.
- **iOS has ONE `GoogleService-Info.plist`** (`mindthetimefcm` = staging).
  `STATIONLY_ENVIRONMENT` switches the API URL, **not** the Firebase project — so
  an iOS "Production" build authenticates against the staging Firebase project
  while talking to the production API. Did not cause any bug here, but it will
  cause a confusing one.
- **Rows equal to the defaults are pruned from `UserSettings`.** Always read via
  `configOf(id)`.
- **A hub id is not a fetch key.** Never let it reach predictions or the
  subscription registry.
