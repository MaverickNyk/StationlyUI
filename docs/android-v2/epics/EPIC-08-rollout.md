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
- [~] **b.** Walk every screen. **PARTLY DONE on a Pixel 7 Pro, 2026-09-12** —
      the minified build was signed, installed over the live `versionCode 2` and
      **launched clean**. What that retires and what it does not is below; the
      screen-by-screen walk still needs a person, because the phone locked.
- [x] **c.** Add keep rules for anything the new graph needs. *(None needed, and
      that is now **verified against the shipped `mapping.txt`** rather than
      reasoned from the rule files. See the findings.)*
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

### The release build RAN, 2026-09-12

Signed with the debug key, `adb install -r` over the live `versionCode 2`, on the
Pixel 7 Pro. It replaced a debug build with a **minified release** one and:

```
versionCode=3  versionName=2.0-staging          ← the in-place upgrade
PRAGMA user_version = 3                          ← 1.sqm + 2.sqm both ran
(logcat -b crash: empty)                         ← no R8 casualty at startup
ResumedActivity: com.stationly.mobile/.MainActivity
D/WidgetPlacement: observed 2 widget(s) across 1 board(s)
D/NotificationManager: reconcile: +0 -6 (ledger 10 → 4)
D/Widget: Updating widget 6 for Hackney Wick Rail Station with 6 departures
D/FreshData: fresh data → Station(stationId=940GZZDLBNK)
```

**What that retires.** The largest unknown in this story was whether the whole
Compose Multiplatform UI, Coil, the SDUI decoder and kotlinx-serialization
survive R8 at RUNTIME rather than merely at build time. They do: the app starts,
composes, reads its database, renders two widgets, reconciles against the
backend and takes a push. Those paths cover most of what the keep rules protect.

**What it does not.** Every screen. The phone locked mid-session and it is the
owner's daily device, so no unlocking and no tapping — an R8 failure on a screen
nobody opened is exactly the shape this task exists to catch, and the walk is
still owed. Untouched: sign-in, the selection flow, station settings, the filter
sheet, profile, support, the update surfaces, and the screensaver.

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

#### No new keep rules are needed, and here is the evidence rather than the argument

"It built" is not evidence, which is this story's whole point — so the claim is
checked against the 76 MB `mapping.txt` the release actually produced.

| What | Expected | In the mapping |
|---|---|---|
| Every manifest component | name preserved | ✅ all 7, unrenamed |
| `ActivityUploadWorker` | name **and** `<init>(Context, WorkerParameters)` | ✅ both |
| kotlinx `$$serializer` classes | present, unobfuscated | ✅ **99** |
| `SduiAppComponent` polymorphic subclasses | present | ✅ **61** |
| `com.stationly.core.model.**` (Gson reflects) | unobfuscated | ✅ (`UserSelection`, `Board`, `BoardSelection`) |
| Shared-UI `@Serializable` (`SupportMoneyConfig`) | class **and** its `$$serializer` | ✅ both |
| `TopicLedger`, `InAppUpdate` (nothing reflects on them) | obfuscated | ✅ `i6.t`, `X5.e` |

That last row matters as much as the others: things nothing reflects on **are**
being shrunk and renamed, so the rules are not accidentally keeping the world.

#### The WorkManager worker is saved by the second rule, not the one people quote

`androidx.work`'s consumer rules open with

```
-keep class * extends androidx.work.Worker
```

and `ActivityUploadWorker` is a `CoroutineWorker`, which extends
`ListenableWorker` and **not** `Worker`. That rule does not cover it. The next
one does:

```
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(...);
}
```

which is why both the class name and the `(Context, WorkerParameters)`
constructor survive — the exact two things WorkManager reflects on to
instantiate a job it read back out of its own database.

Worth writing down because the failure would be **delayed and silent**:
WorkManager persists `workerClassName` at enqueue time, so a rename would not
break the run that scheduled it — it would break the one after the next release,
as a job that quietly fails to instantiate and cancels itself. Exactly the shape
that reaches users and not a test.

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

## AV2-8.2 — Upgrade verification on hardware · `M` · Review (2026-09-12)

**Depends on:** AV2-2.2, AV2-8.1 **Reads:** [`MIGRATION.md`](../analysis/MIGRATION.md) §1.5, §5

The migration test proves the SQL. This proves the phone. They are not the same
claim, and the difference is where R1 lives.

### Tasks
- [x] **a.** **In-place upgrade** from a genuine v1 install.
      **VERIFIED ON HARDWARE, 2026-09-12 — both hops.** See below.
- [x] **b.** **Backup-restore path.** **VERIFIED, and by the same test** — a v1
      database placed into a v2 install's data directory IS the restore path,
      byte for byte. `Schema.create` never ran; `Schema.migrate(1, 3)` did.
- [x] **c.** Verify boards survive both paths. **All three survived, with their
      ids**, which is the part three surfaces depend on. Predictions were dropped
      as designed; they did not repopulate for these particular boards because
      the fixture's stations are not on the test account, which is the fixture's
      limitation and not the migration's.
- [ ] **d.** Verify FCM topic re-subscription is a diff, not a flood (AV2-4.2c).
- [ ] **e.** Verify the widget survives the upgrade with its binding intact.

### ✅ The 1 → 3 migration, on the Pixel 7 Pro, 2026-09-12

**R1 — the risk this whole programme is arranged around — has a hardware pass.**

A genuine v1 database was built from `fixtures/v1/schema-v1.sql` (captured from
the last released build by AV2-1.3, and deliberately frozen), seeded with the
same three boards `MigrationTest` uses — a tube board and a **bus hub whose two
directions resolve to different poles**, the case every hub bug hides behind —
stamped `user_version = 1`, and written into the app's data directory in place of
its own. That is simultaneously task (a) and task (b): a v1 database arriving
inside a v2 install is exactly what Android Auto Backup delivers.

Before launch:

```
PRAGMA user_version        1
UserSelectionEntity        3 rows
tables                     LineStatusEntity, PredictionEntity,
                           SyncStatusEntity, UserSelectionEntity
                           (no ActivityEventEntity — the 1.sqm guard's subject)
```

After one launch:

```
PRAGMA user_version        3          ← 1.sqm AND 2.sqm both ran, in one pass
logcat -b crash            empty      ← no throw, app resumed
UserSelectionEntity        1|tube|victoria|940GZZLUKSX||King's Cross…|southbound|ALL
                           2|bus|39|490008805N||Smithwood Close|inbound|ALL
                           3|bus|39|490012211N||Smithwood Close|outbound|ALL
ActivityEventEntity        created
PredictionEntity           PRIMARY KEY (stationId, lineId, direction,
                             destination, platform, eta, targetEpochMs)
```

Every claim the migration makes, held:

- **No board lost**, and the **ids are unchanged** — `1.sqm` copies `id`
  explicitly rather than letting AUTOINCREMENT renumber, because it is insertion
  order and three surfaces read "the user's first station" off it.
- **Both bus poles survived** as separate rows, with `parentStationId` blank,
  which is the correct reading of a pre-hub row ("same as station").
- **New columns took their defaults** (`filterMode = 'ALL'`).
- **`ActivityEventEntity` exists** — the table `Schema.create` never runs for on
  an upgraded database, and the first enqueue without it is "no such table" on
  the longest-standing installs and never on a development device.
- **Q7's new primary key is live on a migrated database**, not just a created one.

The device's own database was backed up first and restored afterwards; it is
back on its real two boards at `user_version` 3, both widgets bound and drawing.

### Acceptance criteria
- [x] Both paths verified on real hardware.
- [x] No board lost on either path.
- [ ] The upgrade does not sign the user out. **Not observed** — the fixture
      database carries no session, so this run could not answer it. The real
      2 → 3 upgrade earlier the same day did not sign the device out, which is
      evidence for the same claim on the hop that has a session behind it.

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
      *(**The permission half is answered, S016: nothing changed.** Checked
      rather than assumed — see below. Screenshots and the listing copy are
      still owner work, and they do change: the home screen, the widget manager
      and the screensaver all look different now.)*

### Permissions: v2 asks for exactly what v1 asks for (verified S016)

The app's own `AndroidManifest.xml` declares the **same five** permissions as
`origin/master` — INTERNET, ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION,
ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS. Byte-identical sets.

The merged release manifest carries eight more (WAKE_LOCK,
RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, AD_ID,
BIND_GET_INSTALL_REFERRER_SERVICE, C2DM RECEIVE, READ_GSERVICES, and the
generated DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) and **every one of them was
already in v1's merged manifest**: WorkManager, Firebase Analytics and Play
Services are all dependencies on master too. A declared dependency merges its
manifest whether or not the app calls it, so using WorkManager for the first
time in AV2-4.4 added nothing.

**Play In-App Updates — the one dependency this session added — contributes zero
permissions**, and exactly one component:
`com.google.android.play.core.common.PlayCoreDialogWrapperActivity`.

Worth checking rather than assuming, because a new permission on a live app is
visible in the Play listing, can require a data-safety update, and on some
permissions gates the update behind a user prompt. The answer here is that there
is nothing to declare.

### Acceptance criteria
- [ ] Staged rollout begun with a documented rollback plan that does **not**
      involve rolling back (see the banner at the top of this file).
- [ ] Q2 answered before (d) is attempted.

### Handoff notes
_(none yet)_
