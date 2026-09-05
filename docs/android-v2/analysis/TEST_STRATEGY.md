# Android v2 — test strategy and regression gates

**Branch:** `dev_android_bring_to_v2`. **Risks referenced:** [`GAP_ANALYSIS.md`](GAP_ANALYSIS.md) §6.

---

## 0. Where the tests are today

| Source set | Files | Covers |
|---|---|---|
| `core/src/commonTest` | 25 | Board model, tick, filters, route graph, quotas, release gate, session, JWT, list encoding |
| `composeApp/src/commonTest` | 4 | Board expansion, filter summary, focus, support money |
| `android/app/src/**/test` | **0** | nothing |
| `.github/workflows` | 3 | auto-PR, branch guard, prod deploy — **no test job** |

So the shared logic is reasonably covered, the Android app is not covered at
all, and nothing runs automatically. All three change in EPIC-01.

**Task selection.** Use `:core:testDebugUnitTest`. Do **not** use `allTests` —
it dies on the `wasmJs` target, and the iOS test target will not compile the
existing comma-named test functions.

---

## 1. Three contracts, three test suites

The programme's regression surface is not "did the app break". It is three named
contracts, each of which can break without the app looking broken.

### C1 — the v1 Android contract → `V1ContractTest`

Locks what a live `versionCode 2` phone depends on. **Written in EPIC-01, before
any Android code changes**, so it characterizes the shipped behaviour rather than
the behaviour we are about to write.

- A `SubscribedStation` payload built from a v2 `Board` list round-trips to the
  same JSON shape v1 posts.
- A v1-shaped `PredictionsPayload` (the FCM body v1 sends and receives) still
  deserializes.
- `Board.from(...)` over a v1 flat selection list produces one board per
  `groupingId`, filters defaulted to `ALL`.
- Flattening that board list back with `toSelections()` returns the original
  rows. **Round-trip in both directions** — §2.3 of the migration doc depends on
  the fold being total.

### C2 — the iOS contract → gate, not a suite

iOS is on TestFlight from the same `commonMain`. There is no unit test for
"iOS still works"; there is a build gate.

```
./gradlew :core:testDebugUnitTest :composeApp:testDebugUnitTest
./gradlew :composeApp:assembleComposeAppDebugXCFramework
```

The XCFramework assemble is not optional and not slow-path: Xcode will happily
link a **stale** framework and produce a green build that proves nothing. Any
session that edits `commonMain` runs it.

### C3 — the schema contract → `MigrationTest`

The one suite that cannot be written in `commonTest`, because it needs a real
SQLite file. Put it in a new `core/src/androidUnitTest` source set driven by
`app.cash.sqldelight:sqlite-driver` (JDBC), which runs on the JVM with no
device and no Robolectric.

The test that matters is not "the migration parses". It is:

1. Create a database with the **v1 schema** — the literal `CREATE TABLE`
   statements from `git show master:...StationlyDatabase.sq`, checked in as a
   fixture so it cannot drift with the current `.sq`.
2. Insert representative rows: a tube selection, a bus selection with a distinct
   pole, predictions on two lines, a line status, a sync status.
3. Run `StationlyDatabase.Schema.migrate(driver, 1, 2)`.
4. Assert:
   - every `UserSelectionEntity` row survived, with the new columns at their
     documented defaults (`parentStationId=''`, `filterMode='ALL'`, `routeResolvedAt=0`);
   - `PredictionEntity` and `SyncStatusEntity` are **empty** and carry the new
     primary keys — inserting the same `(station, line, destination, platform,
     eta)` under two different `direction` values must produce two rows, which is
     the whole point of the key change;
   - `LineStatusEntity` is untouched;
   - `ActivityEventEntity` exists and accepts an insert;
   - `prediction_lookup` and `activity_by_time` exist.
5. Run the same migration a second time on the result and assert it fails
   cleanly rather than corrupting — migrations are not idempotent and should not
   pretend to be.

Then the counterpart: `Schema.create` on an empty database must yield a schema
**identical** to the migrated one. Compare `PRAGMA table_info` for every table.
That is the assertion that catches §1.2 of the migration doc — the `.sqm` and
the `.sq` drifting apart — and it is worth having even after `verifyMigrations`
is enabled, because it tests the thing rather than the build config.

---

## 2. The order: tests before the change, per phase

Not "TDD" as a ritual. The rule is narrower and load-bearing here:

> **For anything marked `DIVERGENT` in the gap analysis, the test is written
> against the OLD behaviour first and must pass before the new code is written.**

`DIVERGENT` is the only status with a live user behind it. `SHARED` code is
already tested by iOS's own use of it; `STUB` code has no behaviour to preserve;
`ABSENT` and `PLATFORM` are new and get ordinary tests written alongside.

Applied per epic:

| Epic | Characterize first | Then build |
|---|---|---|
| EPIC-02 Database | v1 schema fixture + row set | `1.sqm`, `MigrationTest` |
| EPIC-03 Host | v1 deep-link routing table; nav destinations reachable | Activity hosting `App()` |
| EPIC-04 FCM | v1 payload → SQL writes, as a table of cases | `ProcessPredictionsUseCase` wiring |
| EPIC-05 Widget | current provider's render inputs → `WidgetState` | per-`appWidgetId` binding |
| EPIC-06 Dream | current snapshot loader's output | shared `DreamHost` |

---

## 3. What each layer gets

| Layer | Test kind | Where | Notes |
|---|---|---|---|
| L0 contract | payload round-trip | `core/commonTest` | Serialization only; never hit the network. |
| L1 core | pure unit | `core/commonTest` | Already the strongest layer. Extend, don't restructure. |
| L2 core-platform (android) | JVM unit | `core/androidUnitTest` **(new)** | Migration, prefs translation, topic diffing. |
| L3 shared UI | pure unit on the state/derivation functions | `composeApp/commonTest` | The existing four tests are the model: test the function that builds a string or a layout decision, not the composable. |
| L4 UI-platform | thin | `composeApp/androidUnitTest` **(new)** | Only where an actual has real logic. |
| L5 host | JVM unit + a small instrumented set | `android/app` **(new)** | Deep-link parsing and widget binding as unit tests; one instrumented smoke test for launch. |

Compose UI tests are deliberately **not** in the plan. They are slow, they break
on cosmetic change, and the failure mode this programme actually faces is
wrong-data and wrong-lifecycle, not wrong-pixels. Golden-output coverage for the
UI comes from §4 instead.

---

## 4. The v1 golden capture (EPIC-01, and it expires)

Before the UI swap, capture from a **v1 build** the outputs that must not change
in meaning:

- the JSON `AndroidStorageManager` writes to `StationlyPrefs.selections`
- the topic set a given board list produces
- the `WidgetState` a given prediction set produces
- the deep-link → destination mapping

Check these in as fixtures under `docs/fixtures/v1/`. They are **characterization
data, not aspirations**: when v2 deliberately changes one, the fixture is updated
in the same commit with a one-line note saying why. A fixture nobody is allowed
to change becomes a fixture everybody routes around.

---

## 5. The gate

One command, run at the end of every session, recorded in the ledger:

```
./gradlew :core:testDebugUnitTest \
          :composeApp:testDebugUnitTest \
          :android:app:compileStagingDebugKotlin \
          :composeApp:compileDebugKotlinAndroid
```

Plus, whenever `commonMain` changed:

```
./gradlew :composeApp:assembleComposeAppDebugXCFramework
```

EPIC-01 adds a GitHub Actions job running the first block on every PR into
`dev_android_bring_to_v2`. There is no test job in CI today — `auto-pr.yml`,
`branch-guard.yml` and `deploy-prod.yml` are all it has.

**A session that cannot get the gate green does not push a fix on top. It records
the failure in the ledger and stops.** A broken gate handed to the next agent as
"nearly done" costs more than the work it saved.

---

## 6. Definition of done, per work packet

A packet is done when all five hold:

1. The gate is green.
2. The behaviour it added has a test that fails without it. (Delete the
   implementation, watch it go red, put it back. Do this literally.)
3. If it touched a `DIVERGENT` area, the characterization test from §2 still
   passes, or its change is justified in the commit message.
4. The ledger entry is updated with what was done, what was learned, and what
   the next packet needs.
5. Nothing is left uncommitted. A half-applied change in a dirty tree is the
   worst possible handoff.
