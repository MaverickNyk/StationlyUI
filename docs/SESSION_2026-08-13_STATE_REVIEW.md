# Handover — strict review of the uncommitted iOS/KMP tree

_Session 2026-08-13. Branch `ios-parity` (StationlyUI). Reviews the four sessions
of uncommitted work sitting in this tree; the backend half was reviewed, fixed
and committed separately (`stationly-backend` `fcb5417`, see
`stationly-backend/docs/DEVICE_IDENTITY_AND_SESSIONS.md`)._

---

## 0. Read this first

This was a review pass, not new feature work. The tree was already building and
green; the question was whether it is **commit-ready**, and it was not quite.

Seven findings, all in the account/session state layer that the last four
sessions built. Three are genuine races or state bugs, one is a latent deadlock
that the code actively invited, and three are correctness-of-documentation or
dead code that would mislead the next reader.

Nothing here changes a feature. Every fix is behaviour-preserving in the happy
path — the point is the paths that are not happy.

**Still uncommitted, deliberately.** Verified green and device-tested, but left
staged-free for the user to commit.

---

## 1. Findings

| # | Where | Class | Severity |
|---|---|---|---|
| 1 | `UserStateRepository.currentBoards` | latent deadlock + data race | high |
| 2 | `UserSettings.update/reorder/forget` | lost-update race | high |
| 3 | `UserSyncBridge.reconcileOnForeground` | debounce race | medium |
| 4 | `UserSettings.forgetAccount` | re-adopts a deleted account's namespace | medium |
| 5 | `UserStateSync` | leaked `CoroutineScope`, dead flag | low |
| 6 | `UserSyncBridge.ACCOUNT_REMOVED_FLAG` | duplicated constant, zero readers | low |
| 7 | `UserStateSync.forgetAccount` KDoc | states the ordering backwards | low |
| 8 | `StationlyWidget` `CFBundleVersion` | **App Store submission blocker** | high |
| 9 | `core/build.gradle.kts` | migration drift is unchecked — tried to fix, could not | medium |

---

## 2. `currentBoards()` was a deadlock waiting for its first caller

`UserStateRepository.fetch` takes the push mutex to touch `addedAt`, and says
why in a comment that is exactly right:

> `addedAt` is a plain map — unsynchronised concurrent mutation is a data race,
> not merely a stale read.

`currentBoards()` mutates the same map — `addedAt.getOrPut(id) { now }` inserts a
row for every board it has not seen — and was **public and unlocked**, with a doc
comment inviting callers:

> Public so the widget directory and anything else that needs the account's real
> shape reads the same join everything else does.

So the invitation and the hazard shipped together. Any caller taking it up would
have been mutating that map concurrently with a debounced push or a login fetch.

It has no external callers today, which is the only reason this is latent.

### The part that makes the obvious fix wrong
`pushBoards()` runs entirely inside `mutex.withLock { … }` and calls
`currentBoards()`. `kotlinx.coroutines.sync.Mutex` is **not reentrant**, so
simply adding a lock to `currentBoards()` deadlocks the push — permanently and
silently: the coroutine suspends forever, the gate is never acknowledged, and
every later flush queues behind it.

**Fix.** Split the two responsibilities:
- `currentBoards()` — public, `mutex.withLock { buildBoards() }`
- `buildBoards()` — private, documented as "callers must already hold the lock",
  called by `pushBoards`

---

## 3. Board configuration could silently lose an edit

`UserSettings.update`, `reorder` and `forget` each did their own

```kotlin
val next = _configs.value.toMutableMap()
next[id] = …
persistConfigs(next)
```

That is a read-modify-write on shared state with no guard, and all three are
`suspend` functions with a disk write inside them — so there is real time to
overlap in. Two overlapping mutations both start from the map as it was, and
whichever writes second **discards the other's edit entirely**.

Not hypothetical: these are UI-driven, and the settings sheet and the card behind
it edit the same store. A drag-reorder landing while a rows-per-platform toggle
is persisting loses one of them, and the loser is invisible — the flow emits the
winner and the UI looks consistent with itself.

**Fix.** One private `mutateConfigs(transform)` under a `Mutex`, which all three
delegate to. It also owns the default-pruning that each used to apply separately,
so a future mutator cannot forget it. Read-modify-write, publish and persist now
happen as one step, which additionally guarantees the value in `configs` and the
value on disk are the same object.

It additionally **skips the write entirely when the transform changed nothing**.
`forget` of a board that was never configured, a re-drag into the same order, a
toggle back to the stored value — each used to cost a serialise, a disk write and
a `StateFlow` emission that recomposed every collector for no change at all.
Comparing two small maps is cheaper than any one of those.

---

## 4. The foreground debounce did not debounce

```kotlin
val now = …
if (now - lastForegroundSyncAt < FOREGROUND_MIN_INTERVAL_MS) return false
lastForegroundSyncAt = now     // ← outside the mutex
mutex.withLock { …reconcile… }
```

Two `didBecomeActive` callbacks close together — a Control Centre pull-down and
the return from it is a real pair — both read the old stamp, both pass, both
write it, and both queue on the mutex. The lock serialises them but does not
**dedupe** them, so the debounce that exists to prevent a second profile read
lets two through back to back, the second acting on a premise the first has
already invalidated.

`lastForegroundSyncAt` is also plain shared mutable state read from
`Dispatchers.Default` with no happens-before relationship to anything.

**Fix.** Move the check and its stamp inside the lock, so they are one atomic
step and the field is only ever touched under it.

---

## 5. Deleting an account left it as the current namespace

`UserSettings.forgetAccount(uid)` removed the account's keys and then called
`reset()`, which calls `switchUser()` → `load()` → **re-reads `UID_KEY`**.

That key has not been cleared yet. `AuthBridge.signOutForAccountDeletion` runs
the Kotlin teardown FIRST and only then `logout()` — the order is load-bearing
and commented as such on the Swift side. So the store re-adopted the uid whose
rows it had just deleted, and any write before the next sign-in would recreate
storage for an account that no longer exists.

**Fix.** Drop to the `anon` namespace and clear in memory, without re-reading —
there is nothing to read back, the keys are gone. That is also the honest end
state.

Latent rather than live: nothing writes settings between the teardown and the
next `switchUser()`. It is one navigation change away from being live.

---

## 6. Dead code and one self-defeating constant

**`UserStateSync`** held a `CoroutineScope(SupervisorJob() + Dispatchers.Default)`
created at class-init, used by nothing, cancelled by nothing — a live root kept
alive for a hook that had already been deleted when settings became device-local.
Alongside it, a `wired` flag guarding a `start()` whose body was a comment
explaining that it no longer does anything. `start()` stays (it is `ActivityBridge`'s
app-start seam, worth keeping) but is now honestly `= Unit`.

**`ACCOUNT_REMOVED_FLAG`** was declared twice — in `UserStateSync` and again in
`UserSyncBridge` — and the second declaration carried a doc comment explaining
that it existed *so the key could not be spelled two different ways*, while being
the second spelling. Nothing read it: both the writer and the login screen
already reference the common one. Deleted.

**`UserStateSync.forgetAccount`'s KDoc** claimed it "runs AFTER the Firebase
sign-out that clears it from storage". It runs **before**. The reason it takes a
uid is real but different — `UserSettings` may still be on the `anon` namespace
if no settings screen was ever opened — and a wrong "why" is what gets an
ordering reversed later by someone tidying up.

---

## 6b. The widget could not have been submitted

The build has been emitting this the whole time:

> warning: The CFBundleVersion of an app extension ('1') must match that of its
> containing parent app ('4').

It is a warning at build time and a **hard rejection at App Store Connect**: an
extension's `CFBundleVersion` must match its parent's. The first submission would
have failed validation.

Cause: the app target's `project.yml` explicitly names `CFBundleVersion` and
`CFBundleShortVersionString`, with a comment recording exactly why — XcodeGen
writes a hardcoded `1` into a generated plist otherwise. The widget target was
never given the same treatment, so it sat at `1` while the app moved to `4`.

Fixed in both places: `project.yml` (so a regenerate keeps it) and the generated
`StationlyWidget/Info.plist` (so the checked-in project is correct without
requiring one). Rebuilt — the warning is gone.

---

## 6c. Migration drift is unchecked, and closing it is its own change

`1.sqm` duplicates its `CREATE TABLE` from `StationlyDatabase.sq` by hand, and
the migration's own comment records the hazard: nothing compares the two, so they
drift silently and the drift surfaces only as a runtime failure on **upgraded**
installs, never on the fresh ones a developer tests with.

The obvious fix, `verifyMigrations.set(true)`, was tried here and **reverted**:

- `:core:testDebugUnitTest` and the compiles still pass with it on, because the
  verify task is not in their task graph — so it looks fine;
- `:core:build` **fails**: *"Verifying a migration requires a database file to be
  present"*;
- and the `generate…Schema` task the error points at **is not registered** by
  SQLDelight 2.0.2 in this configuration.

Closing it properly means adding a recorded schema baseline
(`sqldelight/databases/<version>.db`) and checking it in — its own change, with
its own verification. Shipping a build-breaking config change alongside unrelated
work would have been the wrong trade, so `build.gradle.kts` now carries the
finding, the failed approach and the real fix, so nobody repeats the attempt.

**Verified by hand in the meantime:** the `.sq` and `.sqm` declarations of
`ActivityEventEntity` are identical — same columns, types, defaults, primary key
— and both declare `activity_by_time`. No drift today. Change one, change the
other, in the same commit.

---

## 6d. Android exposure — checked explicitly

`core` is shared, so every change in it reaches Android. What actually lands
there:

- **Deleted APIs are genuinely unreferenced.** `BoardDisplayPrefs`, `StationPrefs`
  and `SyncSubscribedStationsUseCase` have no remaining callers; the one textual
  hit for the last is a historical note in a comment, not a reference.
- **`SelectionRepository`'s process-wide cache is a behaviour change for Android
  too**, and a beneficial one — Android builds the repository in four
  ViewModels (`Login`, `Summary`, `Selection`, `Profile`), which each held their
  own cache over the same SQLite table. It is the same divergence the class
  comment describes for iOS. Nothing on Android depended on the isolation.
- **The board list still reaches Android through the legacy `stations` path.**
  Nothing here puts Android on the v2 board list, which is what the split exists
  to prevent.
- `:android:app:compileStagingDebugKotlin` is clean, and the 193 `core` tests
  that Android shares pass.

---

## 6e. Web-app work removed from this change

The web-app plan is a separate thread and is **not** part of this commit. Removed
from the tree (backed up outside it first, nothing was overwritten):

```
docs/WEB_APP_PLAN.md
web/src/static/tv/index.html
```

The existing `web/` module — 27 tracked files — is untouched, and nothing in the
repo referenced either removed path.

⚠️ **`core/src/wasmJsMain/.../Platform.kt` looks like web work and is not.** It
implements `saveDurable` / `loadDurable` / `removeDurable`, the members the
account-settings change added to the shared `StorageManager` interface — every
platform needs an actual or the expect is incomplete. Its `clearExceptDurable`
also fixes a real bug on that target: `localStorage.clear()` was one namespace
for both the session store and the durable one, so a logout took the per-account
settings with it. **It stays.** (Still uncompiled by anything — see the wasmJs
note in §8.)

---

## 7. Reviewed and deliberately left alone

- **`ActivityUploader.flush`** — the twice-taken lock around a released network
  round trip is correct and the reasoning in its comment holds: enqueues only
  add rows, and the delete is by id and therefore idempotent. `tryLock` for
  "skip, don't queue" is right for its callers.
- **`BoardPushGate`** — the counter-not-boolean argument is sound and the 17
  tests pin it.
- **`SelectionRepository`'s** process-wide `shared` cache — the right call for
  the reason given; the six instances were already one repository.
- **`UserSettings.ensureLoaded`'s** double-read window — a documented tradeoff
  ("a duplicated read of one small string is the cheaper mistake"), and both
  readers produce the same result.
- **`reset()` emitting defaults before `load()` re-emits** — a redundant
  intermediate emission, but it happens only at a session boundary where the
  screen is being torn down anyway. Not worth the churn.

---

## 8. Verification

**Green after the changes**
- `:core:testDebugUnitTest` — **193 tests, 0 failures** (re-run, not up-to-date)
- `:android:app:compileStagingDebugKotlin` — clean
- `:composeApp:compileKotlinIosArm64` — clean
- Android untouched: the fixes are in `core` (shared) and `iosMain`; no
  behavioural change reaches Android, whose board list still goes through the
  legacy `stations` path.

⚠️ `:core:compileKotlinWasmJs` still does not build, and did not before this
session either — `app.cash.sqldelight:{runtime,coroutines-extensions}:2.0.2`
publish no wasmJs variant. Dependency resolution, not a code error.

⚠️ `:core:build` also fails, and did before this session — same wasmJs cause
(`app.cash.sqldelight:runtime:2.0.2` publishes no wasmJs variant). Confirmed
unrelated to anything here: the failure is dependency resolution on
`:core:wasmJsPackageJson`, not compilation. **So `:core:build` is not a usable
gate** — the three targets above are.

**On device** — see §9.

---

## 9. Device test

_Filled in from the run on Nick's iPhone 11 (`AB7B04C8-F9D6-5C05-8388-5767BC96C059`)._

Build sequence, and the trap it exists for:

```
./gradlew :composeApp:assembleComposeAppDebugXCFramework \
          :composeApp:assembleIosArm64MainResources
xcodebuild -project iosApp/iosApp.xcodeproj -scheme "iosApp Staging" …
```

**The XCFramework step is not optional.** `xcodebuild` links whatever framework
is already on disk and builds GREEN against a stale one, so a Kotlin change that
is not assembled first simply does not reach the device — and the app runs the
previous build with no error anywhere.

**Result:** XCFramework assembled (3m 07s), `** BUILD SUCCEEDED **`, signed,
installed to `com.stationly.mobile`, launched and **stable**. Rebuilt and
reinstalled again after the `CFBundleVersion` fix (§6b) — the warning is gone and
the app is running.

Backend state after the launch, read-only against staging, unchanged and
consistent: the device row is still bound to its account, 2 sessions, 1 FCM
token, **0 orphan device rows**.

> One false alarm worth recording: `devicectl … --console` reports *"App
> terminated due to signal 9"* when the console pipe closes. That is devicectl
> killing the app on detach, not a crash. Confirm with
> `devicectl device info processes | grep 'iosApp\.app/iosApp'` — and note the
> path has no "stationly" in it, so grepping for the product name finds only the
> widget extension and reads as a dead app.

### NOT verified — needs the UI driven
The fixes are races and lifecycle ordering; none is observable from a launch.
Still to exercise on the phone:
1. **Settings mutations (§3)** — change rows-per-platform and drag-reorder in
   quick succession, background, relaunch: both edits must survive.
2. **Foreground reconcile (§4)** — background and foreground twice inside two
   minutes: exactly one reconcile should run.
3. **Account deletion (§5)** — delete the account, then sign in as someone else
   on the same device: the new user must get defaults, not the deleted user's
   arrangement.

---

## 10. Traps carried forward

- **`Mutex` is not reentrant.** `pushBoards` holds it; anything it calls must be
  the unlocked variant (§2).
- **`forgetAccount` runs BEFORE `logout()`**, not after (§5). The Swift side
  comments this; the Kotlin side now agrees.
- **A foreground widget rebuild is free** — WidgetKit exempts reloads while the
  app is on screen. Do not gate it on "something changed" (from the previous
  session, still true).
- **`.id()` on the Compose host must not change on login** (previous session).
- **Never `git stash` this tree.**
