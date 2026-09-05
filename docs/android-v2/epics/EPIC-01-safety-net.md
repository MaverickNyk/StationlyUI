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

## AV2-1.1 — The gate, in CI · `M` · **Ready**

**Depends on:** — **Reads:** [`TEST_STRATEGY.md`](../analysis/TEST_STRATEGY.md) §0, §5
**Files:** `.github/workflows/`, `core/build.gradle.kts`, `android/app/build.gradle.kts`

### Why
Every later story ends with "run the gate". The gate has to exist, and it has to
run somewhere other than the agent's own machine, or it becomes a step people
believe they ran.

### Tasks
- [ ] **a.** Add a GitHub Actions job running the four gate tasks on every PR
      targeting `dev_android_bring_to_v2`.
- [ ] **b.** Add the `core/src/androidUnitTest` source set with
      `app.cash.sqldelight:sqlite-driver` as `testImplementation`. JDBC, JVM, no
      device and no Robolectric. EPIC-02 cannot be tested without it.
- [ ] **c.** Prove (b) with one trivial test that opens an in-memory database and
      runs `Schema.create`.
- [ ] **d.** Add a unit-test source set to `android/app`. It has none.
- [ ] **e.** Record the exact gate command in `README.md` if it drifts from what
      is written there.

### Acceptance criteria
- [ ] A PR into this branch runs the gate and can fail on it.
- [ ] `./gradlew :core:testDebugUnitTest` still passes and now includes the
      androidUnitTest source set.
- [ ] The workflow does **not** use `allTests` — it dies on `wasmJs`, and the iOS
      test target will not compile the existing comma-named test functions.

### Handoff notes
_(none yet)_

---

## AV2-1.2 — Lock the v1 contract · `M` · **Ready**

**Depends on:** — **Reads:** [`TEST_STRATEGY.md`](../analysis/TEST_STRATEGY.md) §1/C1, [`MIGRATION.md`](../analysis/MIGRATION.md) §2
**Files:** `core/src/commonTest/kotlin/contract/V1ContractTest.kt`

### Why
A live `versionCode 2` phone depends on a payload shape and a fold that no test
currently covers. The `stations` dual-write planned in AV2-4.3 rests entirely on
the `Board` ⇄ `SubscribedStation` fold being **total in both directions**, and
nothing proves that today.

### Tasks
- [ ] **a.** `Board` list → `SubscribedStation` list → `Board` list round-trips
      without losing a station, a line, a direction or a pole naptan.
- [ ] **b.** A v1-shaped `PredictionsPayload` (the FCM body v1 sends and
      receives) still deserializes.
- [ ] **c.** `Board.from(...)` over a v1 flat selection list produces one board
      per `groupingId`, with filters defaulted to `ALL`.
- [ ] **d.** A bus hub case specifically: two poles under one hub must fold to
      **one** board with two selections, not two boards.

### Acceptance criteria
- [ ] Every test fails if the corresponding production code is deleted. Check
      this literally — delete, watch it go red, put it back.
- [ ] What the flat form cannot carry (the filters) is asserted as *deliberately*
      dropped, not silently.

### Handoff notes
_(none yet)_

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
