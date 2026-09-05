# EPIC-02 — Database migration · 11 pts

**Goal.** An existing `stationly.db` migrates to the v2 schema without losing the
user's boards.

**Why it is the highest-risk epic.** `Schema.create` runs only on an *empty*
database. Every development device is a fresh install, so a missing migration is
invisible right up to the moment it reaches the users who have had the app
longest. Two primary keys changed, so `ALTER TABLE` cannot do it. And
`backup_rules.xml` includes `domain="database"`, so a v1 database can arrive on a
phone that never had v1 installed.

**Read [`MIGRATION.md`](../analysis/MIGRATION.md) §1 in full before starting.**

**Exit.** A real v1 database with real rows migrates, provably, and the `.sq` and
`.sqm` can no longer drift apart without the build failing.

---

## AV2-2.1 — Reinstate migrations · `L` · **Done** (S004)

**Depends on:** AV2-1.1 **Reads:** [`MIGRATION.md`](../analysis/MIGRATION.md) §1
**Files:** `core/src/commonMain/sqldelight/com/stationly/db/StationlyDatabase.sq`,
`core/src/commonMain/sqldelight/com/stationly/db/migrations/1.sqm`

### Why
The `.sq` banner removed migrations on one stated premise — *"Android is live and
is FROZEN — no new Android release is planned"* — and named its own expiry
condition: *"If you find yourself writing one, check that premise first."* D1
falsifies it.

### ⚠️ The banner's own list is wrong. Do not use it as the spec.
It names **7** items. The computed delta is **21**. It omits
`parentStationId`, `filterMode`, `viaStationId`, `viaStationName`,
`routeResolvedAt`, `PredictionEntity.direction`, `.destId`, `.matchesFilter`,
**both** primary-key changes, and the `prediction_lookup` index change.
`MIGRATION.md` §1.2 has the full table. Regenerate it rather than trusting either
list:

```bash
git show master:core/src/commonMain/sqldelight/com/stationly/db/StationlyDatabase.sq
git show HEAD:core/src/commonMain/sqldelight/com/stationly/db/StationlyDatabase.sq
```

### Tasks
- [x] **a.** Rewrite the banner. The premise is dead; say so, say why, and either
      delete the hand-maintained list or replace it with the command that
      regenerates it. A list that drifted once will drift again.
- [x] **b.** Write `migrations/1.sqm` as a **table rebuild** — create under a
      temporary name, copy, drop, rename — for `UserSelectionEntity`,
      `PredictionEntity` and `SyncStatusEntity`. Not a list of `ALTER`s.
- [x] **c.** Preserve every `UserSelectionEntity` row. New columns take their
      `.sq` defaults; `parentStationId=''` is correct for a pre-hub row and means
      "same as station".
- [x] **d.** **Drop** all `PredictionEntity` and `SyncStatusEntity` rows.
      MIGRATION.md §1.4 says why this is the honest answer and not the lazy one:
      old rows have no `direction`, `direction` is now in the primary key and in
      every board query's `WHERE`, backfilling it is guesswork on a bus hub, and
      the payoff is nil because departures older than ~2 minutes are filtered on
      read anyway and FCM repopulates within seconds.
- [x] **e.** `CREATE TABLE ActivityEventEntity` and both indexes.
- [x] **f.** Leave `LineStatusEntity` alone. It is unchanged.
- [x] **g.** Bump the tripwire. `SchemaHarnessTest.the schema version matches the
      migration count` asserts version `1`; adding `1.sqm` makes it `2` and the
      test will fail. That is deliberate — bump the expectation in the same
      commit, and do **not** delete the test to make it pass.

### Acceptance criteria
- [x] `:core` builds; the database version is 2.
- [x] The `.sqm` and the `.sq` describe the same schema, checked by eye now and
      by the build in AV2-2.3.
- [x] The banner no longer contradicts the file it sits on top of.

### Explicitly NOT in this story
The test. It is AV2-2.2, and the split is deliberate: writing a migration and its
test in one sitting produces a test shaped like the migration rather than like
the requirement.

### Handoff notes
_(none yet)_

---

## AV2-2.2 — Prove the migration · `M` · Backlog

**Depends on:** AV2-2.1 **Reads:** [`TEST_STRATEGY.md`](../analysis/TEST_STRATEGY.md) §1/C3
**Files:** `core/src/androidUnitTest/kotlin/db/MigrationTest.kt`, a checked-in v1 schema fixture

### Tasks
- [ ] **a.** Check in the **v1 schema as literal SQL**, taken from
      `git show master:...StationlyDatabase.sq`. A fixture, so it cannot drift
      with the live `.sq` — which is the entire failure mode being defended
      against.
- [ ] **b.** Build a v1 database from it and insert representative rows: a tube
      selection, a bus selection with a distinct pole, predictions on two lines,
      a line status, a sync status.
- [ ] **c.** Run `StationlyDatabase.Schema.migrate(driver, 1, 2)` and assert:
      every `UserSelectionEntity` row survived with the documented defaults;
      `PredictionEntity` and `SyncStatusEntity` are empty and carry the new keys;
      `LineStatusEntity` is untouched; `ActivityEventEntity` accepts an insert;
      both indexes exist.
- [ ] **d.** Assert the **point** of the key change: inserting the same
      `(station, line, destination, platform, eta)` under two different
      `direction` values produces two rows, not one.
- [ ] **e.** Assert `Schema.create` on an empty database yields a schema
      **identical** to the migrated one, by comparing `PRAGMA table_info` for
      every table. This is the assertion that catches `.sq`/`.sqm` drift, and it
      is worth keeping even after AV2-2.3.
- [ ] **f.** Running the migration twice must fail cleanly rather than corrupt.
      Migrations are not idempotent and should not pretend to be.
- [ ] **g.** **The iOS-shaped case.** Build a database from the CURRENT `.sq`
      (which is what `Schema.create` gave every iOS TestFlight device at version
      1), attempt the migration, and assert it throws **and changes nothing** —
      `parentStationId`, `filterMode`, `viaKeys` and `patternIds` all intact, no
      orphan `_v1` table. See MIGRATION.md §1.4b. This was verified by hand in
      AV2-2.1; it needs to be a test, because the guard is one statement's
      position in a file and nothing else protects it.

### Acceptance criteria
- [ ] The test fails if `1.sqm` is deleted.
- [ ] The test fails if a column is added to the `.sq` and not to the `.sqm`.

### Handoff notes
_(none yet)_

---

## AV2-2.3 — Close the drift permanently · `M` · Backlog

**Depends on:** AV2-2.2 **Files:** `core/build.gradle.kts`, `core/.../sqldelight/databases/`

### Why
`core/build.gradle.kts` already documents why `verifyMigrations` is off, that
turning it on naively fails `:core:build` with *"Verifying a migration requires a
database file to be present"*, that the `generate…Schema` task the error names is
not registered by SQLDelight 2.0.2 in this configuration, and that the real fix
is a recorded schema baseline. Start from that note — it was written by someone
who already tried and reverted.

### Tasks
- [ ] **a.** Generate and check in the schema baseline under `sqldelight/databases/`.
- [ ] **b.** Enable `verifyMigrations`.
- [ ] **c.** Prove it: add a column to the `.sq` only, confirm the build fails,
      revert.
- [ ] **d.** Update the comment in `core/build.gradle.kts` to describe what is now
      true rather than what was.

### Acceptance criteria
- [ ] `:core:build` fails when `.sq` and `.sqm` disagree.
- [ ] The AV2-2.2 equivalence test still passes and is kept — it tests the schema,
      where this tests the build.

### Handoff notes
_(none yet)_
