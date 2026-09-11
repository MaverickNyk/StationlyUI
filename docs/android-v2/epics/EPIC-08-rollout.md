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

## AV2-8.1 — Release build integrity · `L` · In Progress (S016) — **(b) needs a phone**

**Depends on:** everything **Files:** `proguard-rules.pro`, `android/app/build.gradle.kts`

R8 runs in full mode with `isMinifyEnabled` and `isShrinkResources`. The build
file already warns, in as many words, to always smoke-test a release build before
uploading because R8 strips unused code aggressively. This programme changes the
dependency graph substantially — a whole Compose Multiplatform UI, JetBrains
navigation and lifecycle, Coil 3, kotlinx-serialization across new types.

> ### Partial result, S014 (2026-09-06) — R8 COMPILES after the cutover
> `:android:app:assembleStagingRelease` **succeeds**: R8 full mode plus
> `shrinkResources`, against the post-cutover dependency graph (`:composeApp` as
> `implementation`, `navigation-compose` and `ui-text-google-fonts` gone). That
> is the first time the minified build has been exercised since AV2-3.5, and it
> retires the largest unknown here — that the whole Compose Multiplatform UI
> would not survive shrinking at all. **10m 31s, 6.98 MB unsigned APK** (task
> (e)'s baseline; the pre-cutover number was never recorded, so this is the
> first datum rather than a delta).
>
> The only R8 output is the familiar `An error occurred when parsing kotlin
> metadata` version-skew warning, repeated eleven times. Pre-existing and benign.
>
> **This does NOT close task (b), which is the one that matters.** A release
> build that compiles is not a release build that runs: serialization and
> reflection failures under R8 surface as runtime crashes on screens nobody
> opened. Nothing has been installed or walked. Keep the story open.

### Tasks
- [x] **a.** Build a release APK **and the AAB**. *(Both, S016, on the full
      post-cutover graph including Play Core. Numbers below.)*
- [ ] **b.** Walk every screen. **Still the one that matters, and it needs a
      phone.** Serialization and reflection failures under R8 surface as runtime
      crashes on screens nobody opened during testing.
- [x] **c.** Add keep rules for anything the new graph needs. *(Audited — none
      needed. See the findings.)*
- [x] **d.** Confirm `debugSymbolLevel = "FULL"` still applies. *(It applies and
      it has nothing to do — see the findings, because "no symbols in the AAB"
      looks exactly like the setting having been lost.)*
- [x] **e.** Check the bundle size delta and note it.

### Measured, S016 (2026-09-11)

| | S014 (2026-09-06) | S016 | Δ |
|---|---|---|---|
| `app-staging-release-unsigned.apk` | 7,320,203 B (6.98 MB) | 7,304,153 B (6.96 MB) | **−16 KB** |
| `app-staging-release.aab` | not built | 12,417,068 B (11.84 MB) | — |
| Build time | 10m 31s | ~7m (`--no-build-cache`) | — |

Rebuilt at the end of the session so **the artifact on disk is HEAD** — including
`versionCode 3` and the Q7 schema change. Whoever does task (b) installs what is
committed, not what was committed three hours earlier. Those two changes cost 8
bytes in the APK and 621 in the AAB, which is the honest answer to "what does a
migration cost".

⚠️ **The APK on disk is `versionCode 3`.** It will install OVER a live v1 and
migrate its database 1 → 3 in one pass, which is the point of AV2-8.2 (a) and is
NOT reversible. If you want a release build to poke at without committing to
that, use a device that has never had Stationly on it.

**Smaller, after a session that added a dependency.** Play In-App Updates went in
(AV2-7.1) and ~10 dream files plus the whole of `com.stationly.mobile.ui.theme`
came out (AV2-6.2); the deletions won. Worth recording because the intuition
runs the other way, and because a size jump on the next release now has a real
baseline to be a jump FROM.

The AAB is larger than the APK by design: `BUNDLE-METADATA/` carries a 76 MB
uncompressed `proguard.map` for Play's de-obfuscation, and none of it is
delivered to a device.

### Findings

#### The `shrinkResources` failure is a Gradle cache bug, not a build failure

The first attempt failed at `:android:app:shrinkStagingReleaseRes` with

```
Failed to store cache entry … Could not pack tree 'params.logFile':
Request to write '64403' bytes exceeds size in header of '1630207' bytes
```

That is the build CACHE failing to store the task's log file, after the task
itself ran. `--no-build-cache` makes it go away. Worth knowing before somebody
reads it as R8 refusing the graph — which is what it looks like, because it is
the one task in the build most likely to genuinely fail.

#### No new keep rules are needed, and the reason is worth keeping

The new graph is mostly Compose Multiplatform, Coil 3, JetBrains lifecycle and
Play Core, and every one of those ships **consumer ProGuard rules** in its own
artifact. The reflection in our code is unchanged: Gson over `com.stationly.core.model.**`
(kept wholesale) and kotlinx-serialization, whose generated `$$serializer`s and
`Companion`s are pinned explicitly under `com.stationly.**` so an SDK bump cannot
silently drop them.

The shared UI adds exactly **three** `@Serializable` files in `com.stationly.app.**`
(`SduiConditions`, `SupportStore`, `SupportMoneyConfig`) and the existing
wildcards already cover them. Checked rather than assumed — this is the story
whose whole point is that "it built" is not evidence.

#### `debugSymbolLevel = "FULL"` is applied and has nothing to extract

The AAB carries no native-symbols entry, and that is correct: the app has exactly
**four** `.so` files, all of them `libandroidx.graphics.path.so`, and AndroidX
ships it already stripped. There is nothing for the packaging step to bundle.

Left in the build file, because the moment a dependency arrives with unstripped
natives the setting is what puts them in the AAB — and because deleting it would
make the Play Console's "no debug symbols" warning permanent and unexplained.

### Acceptance criteria
- [ ] A minified release build runs, and every screen opens. **Needs a phone.**
- [ ] Widget, dream and FCM all work in the release build, not just in debug.
      **Needs a phone**, and the dream half of it has never run at all — see
      AV2-6.2.

### Handoff notes — S016, 2026-09-11

**This story is the gate for everything left, and it is one install away.**

```bash
# Sign it (staging uses the debug key) and put it on the Pixel:
apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android \
  --out /tmp/stationly-staging-release.apk \
  android/app/build/outputs/apk/staging/release/app-staging-release-unsigned.apk
adb install -r /tmp/stationly-staging-release.apk
```

Then walk: login, home, station settings, line picker, the filter sheet, profile,
the widget manager, add a widget, the screensaver, and a push arriving on each.
R8 failures land as `ClassNotFoundException` / `SerializationException` on the
screen nobody opened — so the walk has to be every screen, not a smoke test.

**The mapping file for any crash you get is at**
`android/app/build/outputs/mapping/stagingRelease/mapping.txt`.

---

## AV2-8.2 — Upgrade verification on hardware · `M` · Backlog

**Depends on:** AV2-2.2, AV2-8.1 **Reads:** [`MIGRATION.md`](../analysis/MIGRATION.md) §1.5, §5

The migration test proves the SQL. This proves the phone. They are not the same
claim, and the difference is where R1 lives.

### Tasks
- [ ] **a.** **In-place upgrade** from a genuine v1 install — a real
      `versionCode 2` build with real boards, real predictions, and a real
      history of use. Not a fresh install with rows inserted.
      *(Unblocked S016: this build is `versionCode 3` now, so it will install
      over the live one. It migrates 1 → 3 in one pass, through both `1.sqm` and
      `2.sqm` — `MigrationTest` proves the pair lands on the same shape as
      `Schema.create`, and this is the half that proves the phone.)*
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

## AV2-8.3 — Ship · `M` · Backlog — **(a) done early, see below**

**Depends on:** AV2-8.2 **Reads:** [`MIGRATION.md`](../analysis/MIGRATION.md) §5

### Tasks
- [x] **a.** `versionCode 3`, `versionName 2.0`. *(Done S016, and done EARLY on
      purpose: AV2-8.2 installs this build over the live one, and Android
      refuses a package whose `versionCode` is not higher. The bump is a
      prerequisite for the hardware verification rather than a step after it.
      Staging reads `2.0-staging`, which `ReleaseGate` already handles —
      `a staging suffix does not read as an older build` is a pinned test.)*
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
