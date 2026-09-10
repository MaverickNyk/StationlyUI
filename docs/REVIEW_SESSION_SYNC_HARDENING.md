# Review and hardening pass — accounts, devices, sessions and sync

_2026-08-25. Branch `ios-parity` / `dev_13Jul`. **Staging only. Production untouched.
Nothing committed.**_

A strict review of the uncommitted P0–P4 work, and the refactor that came out of
it. The feature is unchanged; what changed is whether it survives contact with
concurrency, a second writer, and the next person to read it.

| Read | For |
|---|---|
| `DESIGN_SESSIONS_AND_SYNC.md` | the design |
| `HANDOVER_SESSION_SYNC.md` (both repos) | what was built, and the PRODUCTION RUNBOOK |
| **this file** | what the review found, and what it changed |

**Verification:** backend **111/111** (was 85), `core` **23**, both iOS targets and
`:android:app:compileStagingDebugKotlin` compile, staging deployed, sign-out /
sign-in verified end to end on the connected iPhone 11.

---

## 1. Correctness

### 1.1 A signup created an account with no device row
`createOrUpdateUser`'s new-user branch returned before `startSession`, and its
only device work was `DeviceLifecycleService.bind()` — a named no-op since P2. A
comment three lines above claimed the branch "falls through to `startSession`
below". It did not.

**Failure:** sign up at 22:00, save a station, close the app. The account holds
`loggedIn: true` with zero device rows, which is precisely what the nightly sweep
reads as "every session has ended" — so at 03:00 it released the subscription
holds and purged the account's FCM tokens. Silent, and only self-healing on the
account's second app open.

**Fix:** `await this.startSession(uid, deviceId, deviceInfo)` after `userRef.set`,
so the row is created in exactly one place for a new account and a returning one.

### 1.2 `/device/register` could resurrect a deleted account, permanently
`resolveUid` verified the bearer with `auth.verifyIdToken(token)` — an OFFLINE
check. `deleteAccount` revokes REFRESH tokens, which does nothing to an ID token
already in a client's hand, and those live ~1h.

**Failure:** device A deletes the account; device B foregrounds within the hour;
the iOS client gates registration on `Auth.auth().currentUser`, still populated
locally. The row is written to `users/{deletedUid}/devices/{id}` with live APNs
tokens, under a document that no longer exists. **Nothing can then remove it** —
the sweep queries `loggedIn == true` and the reconcile scans `users` documents,
and a deleted account appears in neither, while `ref.parent.parent` is non-null
so the collection-group filter does not exclude it. It sits in the broadcast
audience forever.

**Fix:** `verifyIdToken(token, true)`. Restores design §10's rule on the one route
that had not applied it.

### 1.3 The sign-out → sign-in push hole (two defects, one symptom)
Both found on device; neither is visible from the server, which returned 200
throughout.

**(a) A stale signature suppressed the re-registration.** `register()` skips a
POST whose body is unchanged, and the body says nothing about who is signed in.
Sign out (backend deletes the row) → sign back in (login recreates it, writing no
token fields by design) → next `register()` computes an identical body and skips.
The device sits in its own account's audience with no push address.
**Fix:** `DevicePushCoordinator.release()`, called from `AuthBridge.logout()`,
which every deliberate teardown routes through including account deletion.

**(b) Nothing called `register()` on sign-in at all.** Its three call sites are the
APNs token callback, `didBecomeActive`, and an account change inside that same
foreground handler — none of which a sign-out/sign-in without backgrounding
touches. Measured: after `POST /user/logout`, the server saw the sign-in's
`/user/sync/profile` and **not one** `/device/register`.
**Fix:** `DevicePushCoordinator.register()` from `persistUserIdentity`.

### 1.4 The race that (b) created, and the duplicated predicate that hid the fix
Registering at sign-in made `/device/register` **win** against
`/user/sync/profile`. `upsert` creates the row; `startSession` then saw a
two-second-old `lastSeen`, judged it warm, and skipped its write. The row kept its
tokens and permanently lost `firstSeen` and `model` — permanently, because every
later login took the same short-circuit, refreshed by the one path that cannot
supply what was missing.

`model` is the field the design moved onto this row specifically so the push path
can say what a device IS, which is what makes an APNs environment mismatch
diagnosable.

**The first fix had no effect**, and the reason is worth recording: the freshness
question existed in **two** places — inside `startSession`, and in
`createOrUpdateUser`'s short-circuit deciding whether to call it. The caller
answered first and the transaction never ran. That is the same "one fact, several
implementations, a fix to one is silently absent from the others" pattern this
entire redesign exists to delete, reproduced inside a single file.

**Fix:** one `UserService.rowNeedsLoginWrite(row, now)` used by both, keyed on
`firstSeen` — this transaction is its only writer, so its absence is an exact
answer to "has login ever written this row". Verified on device: the row now
carries tokens, `firstSeen`, `model`, and `osVersion` in login's format.

### 1.5 A data race on the registration cache
`lastRegisteredSignature` was mutated from a URLSession completion handler (its
own queue), from `register()`, and from `release()`, with no synchronisation — a
race today and a hard error under Swift strict concurrency.

**Fix:** all mutations behind `NSLock`; the request moved from `dataTask` to
`await URLSession.shared.data(for:)` so the outcome is recorded on a thread the
function controls. A lock rather than an actor **deliberately**: `release()` at
sign-out and `register()` at the sign-in after it must stay synchronous and
ordered, and hopping them onto an actor would let a clear land second and
re-suppress the registration it exists to enable.

### 1.6 `clearTokens` could conjure a phantom session
`set(..., { merge: true })` CREATES the document when absent, and one whose only
contents are two `delete()` sentinels is empty — so unregistering an
already-signed-out device wrote a row at a path whose existence means "this
account is signed in on this device". It carries no `deviceId` field, so the steal
query cannot even see it.
**Fix:** `update`, with NOT_FOUND swallowed.

### 1.7 Every registration carried a payload nothing reads
The client sent `stations` and `lines` on every `/device/register`, and the
controller destructured them. §3.1 removed both fields from the device row — they
were named like device data and held ACCOUNT data — and the audiences come from
`UserWatchIndex` now. Nothing on either side of the wire had consumed them since.

Two costs. The payload itself, and — because the client's body signature covers
it — **a POST triggered by every board edit** that then wrote nothing. Removing
the per-device rewrite on a board edit was one of §3.1's stated wins; the server
half landed and the client half did not.

Found in the trace as `stations=0 lines=0` on a sign-in registration firing before
the boards restore: harmless precisely because nothing reads them, which is the
tell that they should not be sent.

**Fix:** client stops sending them, `trackedBoards()` deleted (it lower-cased line
ids "so the backend's `array-contains-any` matches" — a precise description of a
query that no longer exists), controller stops destructuring them, `asStringList`
deleted with its last caller, and the swagger description corrected.

### 1.8 `DreamSettings.accountScope` read across threads
Written from a coroutine at the session boundary, read synchronously by the dream
host on its own thread. **Fix:** `@Volatile`. A stale read answers from the wrong
account's keys.

---

## 2. Performance

**Batched the maintenance device reads.** `sweep` and `reconcile` each did one
Firestore round trip per account, sequentially, inside their scan loops. That is
not merely linear cost — it is linear **latency**, so a nightly job over a real
fleet spends almost all its wall clock waiting. `sweep` already batched its
teardown calls; the reads deciding who gets torn down were the half still going
one at a time, in both jobs. One `liveByUid` helper, `BATCH_SIZE` fan-out, used by
both. Bounded rather than one unbounded `Promise.all`, because a fan-out over
every account is how a maintenance job becomes a quota incident at 3am.

**Coalesced the registration storm.** Three registrations fire per sign-in (from
the sign-in, the auth restore and the token refresh), all in flight before the
first reply lands — so the body signature could not dedupe them and the log showed
three POSTs where one would do, each costing a `getIDToken()` and a server-side
`checkRevoked` round trip. An in-flight guard collapses them, and
`pendingRequest` runs exactly one more pass so a body that changed mid-flight (an
APNs token arriving, a board edit) is never lost. Bounded to one repeat so a busy
foreground cannot spin.

**Gave `user_watch` a scheduled repair.** `user_revs` self-heals — a miss means
"ask the master". `user_watch` cannot: an empty table is indistinguishable from
"nobody watches this line", so a lost SQLite file resolves every disruption
audience to zero devices and the notifier logs nothing (it only speaks when
`devicesTargeted > 0`). The rebuild is now folded into the nightly reconcile,
which had already paid for every user document and already computes
`effectiveStationIds`. Verified on staging: `watch=5`, byte-identical to the
standalone route (5 accounts, 31 rows).

**Made `UserWatchIndex.replaceForUid` actually atomic.** Its doc comment said
"atomically" over a DELETE followed by N unbatched INSERTs. Under `pm2 -i max`
several workers share one SQLite file, so `SQLITE_BUSY` is a real outcome, and a
failure between the two left the account with no rows — which reads as "watches
nothing" and fails silently. Now in a transaction.

---

## 3. Maintainability

- **One predicate** for "does this row still need its login write" (§1.4) — the
  duplication cost a wasted fix and a device test to find.
- **One suite handle** in `performRegister`; three `UserDefaults(suiteName:)`
  constructions read the same container and gave three chances to typo it.
- **One `osVersion` format.** `/device/register` sent bare `26.3` while login sent
  `iOS 26.3` via `DeviceIdentity`; both write the same field, so the stored value
  flipped depending on which path wrote last.
- **The UID key has one Kotlin declaration.** `UserSettings`, `ActivityLog` and
  `UserStateRepository` now reference `SessionStore.Key.UID.storageKey`;
  `ProfileViewModel` (×2) and `SummaryViewModel` read through `SessionStore`.
  **Two spellings survive on purpose** — `AppGroupKeys.FIREBASE_USER_UID` and
  `AuthBridge.swift`, because Swift is the WRITER and reaches
  `UserDefaults.standard` outside KMP entirely. A test asserts they agree, which
  is the honest version of the guarantee; single-sourcing only the Kotlin half
  would have looked like one declaration without being one.
- **Comments corrected where they had gone false**, which is the failure mode this
  codebase is most exposed to given how much it explains itself: the signup
  "falls through" claim (§1.1), `apiRoutes`' "API key only — deliberately no
  Firebase auth" over a now-bearer-gated route, `getUserStateRev`'s claim that a
  rev of 0 means "nothing newer" (it means *fetch*, and reading it the other way
  is the §5.5 bug), `ProfileViewModel`'s note about a cache-clearing line that had
  been deleted, and two pointers to docs that no longer exist
  (`SESSION_SYNC_MIGRATION_OPS.md`, `MAINTENANCE_CRONS_SPEC.md`).
- **`syncStations`' `deviceId` documented as reserved.** No producer exists or can:
  only the frozen APK calls that endpoint, and iOS writes through `syncBoards`.
  Said out loud so it is not read as working echo suppression.

### Diagnostics
`push_trace` was one 40-entry ring shared by a high-rate source and a low-rate
one. `stream:subscribe` / `stream:update` fire per station and per line on every
socket attach, filling all forty in about two seconds — so three attempts to read
a sign-out/sign-in on device came back 40/40 stream lines and nothing else, which
turned a device test into a guess. `stream:` now has its own ring; the quiet ring
is 120.

---

## 4. Tests: 85 → 111

**The two session transactions had ZERO coverage.** `startSession` and
`endSession` appeared in the suite only as stubs being replaced — so the
per-attempt flag reset (which the source comment calls "already bitten once"),
all-reads-before-writes, the steal's victim selection and last-out transition,
retry idempotence, and the lazy TTL prune were all untested. 17 tests now cover
them, over a fake Firestore that models the three read SHAPES the transactions use
(document, subcollection, collection-group query) and discards a losing attempt's
writes the way Firestore does — which is what makes a leaked result flag
observable.

**Mutation-tested rather than trusted.** Six deliberate breakages, all caught:
removing the flag resets in each transaction, breaking the `parent.parent` filter,
adding a read after a write, letting login write a token field, and reintroducing
the original `FieldValue.increment` sentinel. Two of the first drafts did NOT
catch their mutant and were tightened — the root-row test now asserts no foreign
account is read at all, and the token test passes a `deviceInfo` that actually
carries tokens.

**The Android contract was tested on the wrong endpoint.** All five existing tests
exercise `getUserProfile` (the GET). The released APK also decodes
`UserProfileResponse` from **POST** `/user/sync/profile` — verified at `1a6c846`,
`syncProfile(...).body()` — which is the login path and the one the sentinel bug
actually shipped on. Two tests added: the four required fields through
`JSON.stringify`, and a structural check that **no** field serialises as an object,
which generalises past the one sentinel already known.

**The sign-in race now has its own tests** — the gap named at the end of the first
pass, and the seam that broke twice. Three tests cover both orderings
(registration first, login first) plus the permanence case, asserting the finished
row carries the UNION of what each writer owns rather than whichever landed last.

Writing them required the harness to APPLY writes rather than only log them: every
existing test asserted on the write list, which answers "what did this transaction
do" and cannot answer "what does the row look like after two writers have both had
a go" — the only question the race turns on, and the reason it was found on a phone.

**And the harness had to be made less forgiving than it was.** Mutation-testing the
new tests caught the harness rather than the code: dropping `{merge:true}` from
login's row write — which in production wipes the tokens `/device/register` just
wrote — passed all 110, because `apply` merged unconditionally and ignored the
options argument. `set` replaces in Firestore; only `set(…, {merge:true})` merges.
Fixed, and the mutant is now caught. A fake more forgiving than the thing it stands
in for does not test the code, it tests the fake.

**Also pinned:** the short-circuit that skipped §1.4's fix, `rowNeedsLoginWrite`
directly, and the `/device/register`-created row reaching `startSession`.

A harness bug is recorded in the source too: `startSession` defers the victim's
FCM release via `setImmediate`, so without a drain before the stubs come down it
landed in a *later* test's world — and because these tests reuse account names it
was indistinguishable from that test's own call.

---

## 5. Verified on device (iPhone 11, staging)

Sign out → sign straight back in, no backgrounding:

```
appToken     4e2b1c3b…      survived the sign-out
widgetToken  80199202…      survived
environment  sandbox
firstSeen    1787664534874  restored by §1.4
model        iPhone         restored by §1.4
osVersion    iOS 26.3       login's format, so startSession really ran
```

Request order confirms the shape: `/user/logout` → `/device/register` ×3 →
`/user/sync/profile`, with login still filling its own fields despite
registration having created the row first.

---

## 6. Verified end to end, without a device

§1.1 and §1.2 were the two fixes that looked like they needed somebody holding a
phone. Both are now driven by `verify_signup_and_revoke.cjs` — the only WRITING
verification script here, which creates one throwaway Firebase Auth user, exercises
the real HTTP endpoints with a real ID token, and deletes everything it made in a
`finally` so a failure cannot leave litter.

```
§1.1  signup must create the device row
  ✓ device row EXISTS immediately after signup     ← the fix
  ✓ firstSeen / model written by login
  ✓ login invented NO token fields
  ✓ loggedIn ⇔ exactly one live row

§1.2  /device/register must refuse a deleted account
  ✓ register while alive        → 200
  ✓ register after deletion     → 401             ← the fix
  ✓ NO device row resurrected under the deleted account
```

**And both were shown to DISCRIMINATE**, which is the part that matters after a
harness in §4 passed a mutant for the wrong reason:

- §1.1 — reverting the fix locally fails the new unit test (`110/111`).
- §1.2 — proved directly against Firebase rather than through the backend, because
  the behaviour under test belongs to Google and a stub would have asserted only
  that an argument was passed. Same deleted user, same unexpired token:

  ```
  verifyIdToken(token)        → ACCEPTED
  verifyIdToken(token, true)  → REJECTED (auth/user-not-found)
  ```

  The second argument is the whole fix, and that is the evidence for it.

Deliberately NOT proved by deploying the reverted code to staging: that would mean
shipping a knowingly-broken build to a shared environment to watch it fail, and the
same conclusion was available without it.

## 7. Not done, and why

- **Production: everything.** No prod deploy this cycle. The runbook is
  `stationly-backend/docs/HANDOVER_SESSION_SYNC.md` §6 and its opening hazard box
  was strengthened with three consequences traced through the code: the sweep does
  not actually sign anyone out (`loggedIn` is a flag no client reads); boards stop
  updating and do NOT self-heal, because Android calls `syncProfile` only on
  explicit sign-in; and the `fcm_tokens` purge is **unrecoverable client-side**,
  because `FcmTokenRegistrar` short-circuits on a `(token, uid)` pair cached in
  SharedPreferences that nothing server-side can invalidate. A window the runbook
  did not name is now named: between deploying P2c and finishing the backfill, an
  ordinary Android sign-out takes `endSession`'s self-heal branch and deactivates
  an account whose other devices are still signed in.
- **One sign-in failure remains unexplained.** During testing, one attempt left the
  login screen and the retry succeeded. The backend saw a single clean sequence,
  all 200s — so it failed at Firebase Auth before reaching us, and nothing changed
  on that path between the failing and passing runs. Not reproduced since. Recorded
  rather than closed.
- **`SessionLifecycle` / `SyncEngine`** — still deliberately unbuilt (P3).

## 8. The pattern worth carrying forward

Four defects in this pass were found by running it on a device, not by review and
not by tests: §1.3(a), §1.3(b), §1.4, and the duplicated predicate that made the
first fix inert. Every one lives in the seam between two paths that are each
correct alone — login and `/device/register`, sign-out and sign-in.

That seam now has tests (§4), and writing them exposed the reason it had none: the
harness could only describe a single transaction's writes, so "what does this row
look like after two different writers have touched it" was not an expressible
question. It is now.

The general shape, since it recurred three times in one session and is not really
about this feature: **two writers, one record, each correct alone.** Login and
registration on the device row. The elision predicate in `startSession` and its
copy in the caller. `set` versus `set(merge)` in the harness and in Firestore. In
every case both halves read as obviously right, and the defect lived in the
assumption each made about the other.
