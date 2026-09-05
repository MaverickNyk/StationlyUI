# EPIC-01 — Safety net · 9 pts

**Goal.** Make it impossible to break the three contracts silently, and capture
what v1 does before anything changes it.

**Why first.** There is no test job in CI today — `auto-pr.yml`,
`branch-guard.yml` and `deploy-prod.yml` are all of it. `android/app` has zero
unit tests. The programme is about to swap an entire UI layer and migrate a
database under live users, and right now nothing would tell us.

**Exit.** The gate runs in CI on every PR into this branch; the v1 contract is
locked by a test; v1's golden outputs are checked in as fixtures.

---

## AV2-1.1 — The gate, in CI · `M` · **Done** (S001)

**Depends on:** — **Reads:** [`TEST_STRATEGY.md`](../analysis/TEST_STRATEGY.md) §0, §5
**Files:** `.github/workflows/`, `core/build.gradle.kts`, `android/app/build.gradle.kts`

### Why
Every later story ends with "run the gate". The gate has to exist, and it has to
run somewhere other than the agent's own machine, or it becomes a step people
believe they ran.

### Tasks
- [x] **a.** Add a GitHub Actions job running the four gate tasks on every PR
      targeting `dev_android_bring_to_v2`.
- [x] **b.** Add the `core/src/androidUnitTest` source set with
      `app.cash.sqldelight:sqlite-driver` as `testImplementation`. JDBC, JVM, no
      device and no Robolectric. EPIC-02 cannot be tested without it.
- [x] **c.** Prove (b) with one trivial test that opens an in-memory database and
      runs `Schema.create`.
- [x] **d.** Add a unit-test source set to `android/app`. It has none.
- [x] **e.** Record the exact gate command in `README.md` if it drifts from what
      is written there.

### Acceptance criteria
- [x] A PR into this branch runs the gate and can fail on it. *(shared half unconditionally; the `:android:app` half unlocks with a secret — see the handoff note)*
- [x] `./gradlew :core:testDebugUnitTest` still passes and now includes the
      androidUnitTest source set. 374 tests.
- [x] The workflow does **not** use `allTests` — it dies on `wasmJs`, and the iOS
      test target will not compile the existing comma-named test functions.

### Handoff notes — S001, 2026-09-05

**Done.** `.github/workflows/android-v2-gate.yml`, the `core/src/androidUnitTest`
source set on the SQLDelight JDBC driver, `SchemaHarnessTest` (2 tests),
`android/app`'s first unit test (`BackendErrorUtilTest`, 4 tests). Gate green.

**⚠️ CI cannot compile `:android:app`, and this needs the owner.**
`google-services.json` is gitignored and untracked, and the
`com.google.gms.google-services` plugin fails outright without it. The workflow
therefore runs the shared half unconditionally and the `:android:app` half only
when a repository secret `GOOGLE_SERVICES_STAGING_B64` exists — it emits a
**GitHub warning annotation** when it does not, so the job cannot pass quietly
while implying coverage it does not have.

To close it: `base64 -i android/app/src/staging/google-services.json | pbcopy`
and add it as that secret. Use the **staging** file; the prod one points at the
production Firebase project and has no business on a runner. Nothing else
changes — the steps are already written and conditional.

**A tripwire was planted, on purpose.** `SchemaHarnessTest.the schema version
matches the migration count` asserts version `1`. AV2-2.1 adds `migrations/1.sqm`
and **will fail this test**. That is the design: SQLDelight derives the version
from the migration count, so the assertion is what catches a `.sq` changed
without a matching `.sqm`. AV2-2.1 task (g) says to bump it to `2` in the same
commit. Do not delete the test to make it pass.

**Also added:** an `ios-contract` job on macOS running
`:composeApp:assembleComposeAppDebugXCFramework`. It was not in the story, and
it is the C2 contract's only real check — Xcode links a stale framework happily,
so a green iOS build proves nothing on its own. It needs no secret.

**Not done, and deliberately:** no test for the workflow itself. It is verified
by running, and its first real run is the first PR.

---

## AV2-1.2 — Lock the v1 contract · `M` · **Done** (S002)

**Depends on:** — **Reads:** [`TEST_STRATEGY.md`](../analysis/TEST_STRATEGY.md) §1/C1, [`MIGRATION.md`](../analysis/MIGRATION.md) §2
**Files:** `core/src/commonTest/kotlin/contract/V1ContractTest.kt`

### Why
A live `versionCode 2` phone depends on a payload shape and a fold that no test
currently covers. The `stations` dual-write planned in AV2-4.3 rests entirely on
the `Board` ⇄ `SubscribedStation` fold being **total in both directions**, and
nothing proves that today.

### Tasks
- [x] **a.** `Board` list → `SubscribedStation` list → `Board` list round-trips
      without losing a station, a line, a direction or a pole naptan.
- [x] **b.** A v1-shaped `PredictionsPayload` (the FCM body v1 sends and
      receives) still deserializes.
- [x] **c.** `Board.from(...)` over a v1 flat selection list produces one board
      per `groupingId`, with filters defaulted to `ALL`.
- [x] **d.** A bus hub case specifically: two poles under one hub must fold to
      **one** board with two selections, not two boards.

### Acceptance criteria
- [x] Every test fails if the corresponding production code is deleted. Check
      this literally — delete, watch it go red, put it back.
- [x] What the flat form cannot carry (the filters) is asserted as *deliberately*
      dropped, not silently.

### Handoff notes — S002, 2026-09-05

**Done.** `core/src/commonTest/kotlin/contract/V1ContractTest.kt` — 9 tests, all
green, and mutation-checked: dropping `parentStationId` in the conversion fails
3 of them, folding only the first queue per board fails 3.

**⚠️ The story needed a production change, and this is why.** There was no
`Board → SubscribedStation` conversion anywhere to test. It existed **four
times, written by hand**: `ProfileViewModel`, `SelectionViewModel`,
`SummaryViewModel` on the way out, and `UserSyncRepository` on the way back.
A contract test needs one named thing to point at, so the four became one:
`core/.../model/user/LegacyStationList.kt`, holding `toSubscribedStations()` and
`toUserSelections()` as a visible pair. `UserSyncRepository` now calls the
shared one. The three ViewModels are deleted by AV2-3.5 and were left alone.

**And the reason that mattered more than tidiness: two of the three outbound
copies dropped `parentStationId`.** `ProfileViewModel.loadStations` and
`SummaryViewModel`'s delete-sync both build `SubscribedStation` without it. A
station restored that way groups on its own naptan, so **one bus hub comes back
as a card per pole, all with the same name**. Only `SelectionViewModel` — the one
already patched on this branch — carried it. The test now asserts both the
correct behaviour and the counterfactual, so the field cannot be quietly dropped
again.

**Already covered elsewhere, so not duplicated here.** `BoardTest` (18 tests)
covers `Board` ⇄ `UserSelection` — hub folding, pre-hub grouping, filters on the
right queue. This file covers only what that one does not: the **wire** form v1
actually reads, and the v1 FCM payload.

**For AV2-4.3:** `toSubscribedStations()` is the dual-write's outbound half, and
it is now proven total. Its counterpart is not yet called anywhere on the push
path — wiring it in is AV2-4.3's job, not this one's.

---

## AV2-1.3 — Capture v1's golden outputs · `M` · **Ready**

**Depends on:** — **Reads:** [`TEST_STRATEGY.md`](../analysis/TEST_STRATEGY.md) §4
**Files:** `docs/android-v2/fixtures/v1/`, tests that read them

### Why
AV2-3.5 deletes the v1 UI. After that, "what did v1 do here?" is answerable only
from git history. Capture the four outputs whose *meaning* must survive the swap,
while a v1 build still exists to capture them from.

### Tasks
- [ ] **a.** The JSON `AndroidStorageManager` writes to `StationlyPrefs.selections`.
- [ ] **b.** The topic set a given board list produces (`Station_{naptan}`,
      `LineStatus_{mode}_{line}`).
- [ ] **c.** The `WidgetState` a given prediction set produces.
- [ ] **d.** The deep-link → destination mapping from the v1 manifest and
      `MainActivity.handleDeepLink`.
- [ ] **e.** Tests that read the fixture files rather than inlining literals.

### Acceptance criteria
- [ ] Four fixtures exist under `docs/android-v2/fixtures/v1/` and are asserted.
- [ ] Each fixture carries a one-line header saying what it is and when it was
      captured. These are **characterization data, not aspirations**: when v2
      deliberately changes one, it is updated in the same commit with a note
      saying why. A fixture nobody may change becomes a fixture everybody routes
      around.

### Handoff notes
_(none yet)_
