# EPIC-08 — Rollout · 11 pts

**Goal.** Get v2 onto real phones without breaking the ones already running v1.

> ## ⚠️ There is no rollback
> A v1 APK installed over v2 opens a version-2 database with a version-1 schema.
> `AndroidSqliteDriver` has no downgrade path and the framework throws. Play does
> not serve downgrades, so this is sideload-only in practice — but the
> consequence stands: **once v2 reaches a device, that device cannot go back.**
> A bad v2 is fixed by shipping v2.0.1, never by rolling back.

**Exit.** `versionCode 3` on a staged rollout, with the upgrade path verified on
hardware rather than on a synthesized database.

---

## AV2-8.1 — Release build integrity · `L` · Backlog

**Depends on:** everything **Files:** `proguard-rules.pro`, `android/app/build.gradle.kts`

R8 runs in full mode with `isMinifyEnabled` and `isShrinkResources`. The build
file already warns, in as many words, to always smoke-test a release build before
uploading because R8 strips unused code aggressively. This programme changes the
dependency graph substantially — a whole Compose Multiplatform UI, JetBrains
navigation and lifecycle, Coil 3, kotlinx-serialization across new types.

### Tasks
- [ ] **a.** Build a release AAB and install it. Not a debug build.
- [ ] **b.** Walk every screen. Serialization and reflection failures under R8
      surface as runtime crashes on screens nobody opened during testing.
- [ ] **c.** Add keep rules for anything the new graph needs. Existing rules
      cover Gson, kotlinx-serialization and Firebase; the new UI stack is not
      covered.
- [ ] **d.** Confirm `debugSymbolLevel = "FULL"` still applies so Play can
      symbolicate native crashes.
- [ ] **e.** Check the bundle size delta and note it.

### Acceptance criteria
- [ ] A minified release build runs, and every screen opens.
- [ ] Widget, dream and FCM all work in the release build, not just in debug.

### Handoff notes
_(none yet)_

---

## AV2-8.2 — Upgrade verification on hardware · `M` · Backlog

**Depends on:** AV2-2.2, AV2-8.1 **Reads:** [`MIGRATION.md`](../analysis/MIGRATION.md) §1.5, §5

The migration test proves the SQL. This proves the phone. They are not the same
claim, and the difference is where R1 lives.

### Tasks
- [ ] **a.** **In-place upgrade** from a genuine v1 install — a real
      `versionCode 2` build with real boards, real predictions, and a real
      history of use. Not a fresh install with rows inserted.
- [ ] **b.** **Backup-restore path.** `backup_rules.xml` and
      `data_extraction_rules.xml` both include `domain="database"`, so a v1
      database travels through cloud backup and device-to-device transfer. A user
      on v1 who buys a new phone and installs **v2 as a fresh install from Play**
      still receives a v1 database during restore, and `Schema.create` never
      runs. **This reaches devices that never had v1 installed on them**, so any
      test plan scoped to in-place upgrades misses it entirely.
- [ ] **c.** Verify boards survive both paths, and that predictions repopulate
      within seconds of launch (they are dropped by the migration on purpose).
- [ ] **d.** Verify FCM topic re-subscription is a diff, not a flood (AV2-4.2c).
- [ ] **e.** Verify the widget survives the upgrade with its binding intact.

### Acceptance criteria
- [ ] Both paths verified on real hardware.
- [ ] No board lost on either path.
- [ ] The upgrade does not sign the user out.

### Handoff notes
_(none yet)_

---

## AV2-8.3 — Ship · `M` · Backlog

**Depends on:** AV2-8.2 **Reads:** [`MIGRATION.md`](../analysis/MIGRATION.md) §5

### Tasks
- [ ] **a.** `versionCode 3`, `versionName 2.0`. v1 is `versionCode 2`,
      `versionName 1.0`.
- [ ] **b.** Internal testing track → closed testing → staged rollout, smallest
      slice first. Watch the crash-free rate on the migration path specifically.
- [ ] **c.** Set `ReleasePolicy.android.minVersion` only **after** the staged
      rollout is healthy. This is what retires R2.
- [ ] **d.** Retire the `stations` dual-write when the v1 population falls below
      the Q2 threshold — **measured, not assumed**.
- [ ] **e.** Update Play Console listing, screenshots and the data-safety form if
      the new surfaces change what is collected.

### Acceptance criteria
- [ ] Staged rollout begun with a documented rollback plan that does **not**
      involve rolling back (see the banner at the top of this file).
- [ ] Q2 answered before (d) is attempted.

### Handoff notes
_(none yet)_
