# Android v2 — kanban board

**Protocol:** [`README.md`](README.md). **Detail:** [`epics/`](epics/). **Index:** [`BACKLOG.md`](BACKLOG.md).

---

## Live state

```
SPRINT:            5-6 — release surfaces + rollout (sprints 1-4 complete)
WIP LIMIT:         1 story In Progress per agent, 2 across the project
LAST SESSION:      S016 (2026-09-11) — EPIC-04, EPIC-05, EPIC-06 and EPIC-07
                   closed. Eleven stories. The headline is AV2-4.3: Android was
                   writing `boards` and reading `stations`, so every board a
                   user saved was deleted by their own next foreground.
GATE:              GREEN (see the close-out log)
                   :core:testDebugUnitTest                  PASS
                   :core:verify…DatabaseMigration           PASS
                   :composeApp:testDebugUnitTest            PASS
                   :android:app:testStagingDebugUnitTest    PASS
                   :android:app:compileStaging/ProdDebugKotlin  PASS
                   :composeApp:compileDebugKotlinAndroid    PASS
                   :composeApp:compileKotlinIosArm64        PASS
                   :composeApp:assembleComposeAppDebugXCFramework  PASS
                                                            (commonMain CHANGED
                                                            in six places — all
                                                            additive or shared
                                                            fixes iOS wants too)
WORKING TREE:      clean
MASTER:            merged in at 91a51f7 — the eight iOS commits that shipped
                   v1.0 (production Firebase, the iOS 26 icon, iPhone-only,
                   the TestFlight scripts). No conflicts; none of it is Android.
DEVICE:            NOTHING IN THIS SESSION HAS BEEN RUN ON A PHONE. Eleven
                   stories, all reasoned + unit-tested, zero device passes. The
                   per-story handoffs each carry their own device script; the
                   three that most need one are AV2-4.3 (add a board, cold
                   start, is it still there), AV2-6.2 (the screensaver renders
                   at all) and AV2-8.1 (walk every screen on a release build).
BLOCKED ON OWNER:  Q7 the prediction primary key collapses two real trains —
                   now the only OPEN question that changes code.
                   Q1/Q2/Q4/Q5/Q6 are decisions or console work, not blockers.
                   Q3 answered by taking the reversible option (EPIC-06 header).
NEXT UP:           AV2-8.1 (b)-(d): install the release build and walk it. Then
                   AV2-8.2 on hardware, then AV2-8.3.
                   Everything else on this board is in Review.
```

### 🅑 Backlog — dependencies not yet met

| Story | Epic | Title | Waiting on |
|---|---|---|---|
| AV2-8.2 | 08 | Upgrade verification on hardware | AV2-8.1, a phone |
| AV2-8.3 | 08 | Ship | AV2-8.2 |

### 🅡 Ready — take the top one

| Story | Epic | Title | Size |
|---|---|---|---|
| _(none — AV2-8.1 is the live one)_ | | | |

### 🅘 In Progress — WIP limit 2

| Story | Claimed by | Since | Note |
|---|---|---|---|
| AV2-8.1 | S016 | 2026-09-11 | Task (a) done twice now — the release APK and AAB build with the post-cutover graph plus Play Core. **(b) is the one that matters and needs a phone**: a release build that compiles is not a release build that runs. |

### 🅥 Review — done, awaiting owner sign-off

Twenty stories. In the order somebody signing off should read them:

| Story | Session | What to look at |
|---|---|---|
| **AV2-4.3** | S016 | **The worst bug on the branch, and it had been live since the cutover.** Android wrote `boards` (the shared SelectionViewModel) and reconciled against `stations` (the legacy path), and the backend derives neither from the other on a write. So a board saved on Android left no trace in the array its own next foreground compared against — and that reconcile DELETES any local selection the cloud list does not have. On an account whose `stations` is empty, which is every account created on v2, that is every board the user has, gone within fifteen minutes, silently. Four device passes missed it because the test account's legacy array already described its one board. |
| AV2-6.2 | S016 | **Unverified on hardware, and it is the one surface you cannot open by tapping.** The screensaver is the shared one now and v1's `dream/` package is deleted. Failure mode is a blank screen on a bedside table with no crash dialog. Four-step device script in the handoff. |
| AV2-4.2 | S016 | The FCM topic ledger only ever grew — `unsubscribe` never removed from it — so the diff this story asks for would have made a re-added board silently never receive another push. Also: a token rotation was dropping `stationly_all` permanently, on a device that went on reporting itself subscribed. |
| AV2-7.3 | S016 | The support stub's reason for being safe had stopped being true. `enabled` comes from the BACKEND, and these composables are the Android app now — one config change would have put the money surface in front of Android users with a checkout that does nothing. Fixed with a platform capability rather than a bigger comment. |
| AV2-4.4 | S016 | Android has never uploaded a single activity event: `ActivityUploader` was complete and nothing called it. Also two objects could MINT the device id, one of them during logout. |
| AV2-5.3 | S016 | `Board.widget` was empty on every Android device, so deleting a station never warned about the widget it was blanking, and `widget.count` reported zero for a phone covered in them. `board.count` was counting the widget map — wrong on both platforms. |
| AV2-7.1 | S016 | The trap the story opens with is NOT set (the backend serves Play links correctly). What is new is Play In-App Updates: flexible for the nudge, immediate for the block. **Cannot be verified before an internal-track release.** |
| AV2-5.4 | S016 | The guide is wired and deliberately has no Android door — every instruction in the served payload is an iOS gesture. The exact config change is written out in the epic. |
| AV2-7.2 | S016 | Built nothing, which is the right outcome. The audit found there is no platform key to audit: one payload for both platforms, and three payloads whose content is platform-specific. One of them is an **iOS** bug (the About screen's `market://` rate link). |
| AV2-6.1 | S016 | Four placeholders became real. The prefs FILE NAME is the whole upgrade path, and the shared store's per-account scoping would have defeated it — fixed with a read-side fallback that helps iOS too. |
| AV2-5.1 + AV2-5.2 | S013 | Two stations is two widgets; each resolves its own binding. Still never driven by a human. |
| _(the S015 rework)_ | S015 | `requestPinAppWidget`, the two settings rows collapsed into one, "Add to Home Screen" on the station itself. Unlogged at the time — see SESSIONS.md. |
| AV2-4.1 | S012 | The push→board gap, the silent sign-out, the duplicate hero departure. Raised Q7. |
| AV2-3.5 | S011 | The irreversible one. ~8,000 lines of v1 deleted. |
| AV2-3.3 | S010 | The POST_NOTIFICATIONS prompt that never fired. |
| AV2-3.4 | S009 | Sign-in, and a scheme the two builds cannot confuse. |
| AV2-3.2 | S008 · device pass S009 | Real actuals, batch A. |
| AV2-3.1 | S007 · device pass S009 | Half passes, half fails — and the failure is a trap set for AV2-8.2. |

### 🅧 Blocked

| Story | Blocked by | Since |
|---|---|---|
| _(none)_ | | |

### 🅓 Done

| Story | Session | Date | Gate |
|---|---|---|---|
| AV2-1.1 | S001 | 2026-09-05 | GREEN |
| AV2-1.2 | S002 | 2026-09-05 | GREEN |
| AV2-1.3 | S003 | 2026-09-05 | GREEN |
| AV2-2.1 | S004 | 2026-09-05 | GREEN |
| AV2-2.2 | S005 | 2026-09-05 | GREEN |
| AV2-2.3 | S006 | 2026-09-05 | GREEN |

---

## Open questions for the owner

Do not block on these unless a story names one as a dependency. Log and continue.

| # | Question | Raised | Blocks | Status |
|---|---|---|---|---|
| Q1 | Play policy route for tips — Play Billing, or a charity/non-profit exemption for external checkout? | S000 | AV2-7.3 scope only (the surface ships off either way) | OPEN |
| Q2 | At what remaining-v1-install count do we drop the `stations` dual-write? | S000 | AV2-8.3 | OPEN |
| Q5 | **iOS TestFlight testers must delete and reinstall once** when the schema-version bump ships. Their databases are stamped version 1 but already hold the v2 schema, so `1.sqm` refuses to run on them (safely — see MIGRATION.md §1.4b). Standing iOS policy already says wipe-on-schema-change and boards restore from the cloud, but these are live testers. Tell them, or hold the bump until the next TestFlight build? | S004 | iOS TestFlight | OPEN |
| Q4 | Add `GOOGLE_SERVICES_STAGING_B64` as a repo secret so CI can compile and test `:android:app`? The file is gitignored, so CI skips those steps today and says so with a warning annotation. | S001 | `:android:app` coverage in CI | OPEN |
| Q3 | Does Daydream survive into v2? | S000 | **EPIC-06 — no longer** | **ANSWERED S016, by taking the reversible option.** The premise was wrong: *Daydream* the VR platform is dead, `DreamService` the **screen saver** is not — it is in Settings → Display on current Android and v1 ships it. Keeping and porting is undoable in an hour; deleting a live feature is not. EPIC-06 shipped, and the question is now cheap: saying no deletes two files, not a package. |
| Q7 | **The prediction primary key throws away real trains.** `PRIMARY KEY (stationId, lineId, direction, destination, platform, eta)` keys on the FORMATTED eta string, and `insertPrediction` is `INSERT OR REPLACE` — so two trains to the same destination on the same platform 40 seconds apart both format as "1 min" and the second overwrites the first. The rider sees one train where two are coming. `SyncPredictionsUseCase` already dedupes on `targetEpochMs` specifically to keep both; the schema undoes it one layer down. Fix is `targetEpochMs` in the key instead of `eta`, which needs `2.sqm` in a `.sq` shared with a build going to TestFlight. **The migration itself is cheap** — `1.sqm` already clears `PredictionEntity`, since cached departures are replaced within seconds of the next push, so `2.sqm` can just rebuild the table. The question is whether to add a second migration on top of the one Q5 is already about. Pinned by a test that asserts the wrong behaviour on purpose. | S012 | nothing blocking; a live correctness bug on both platforms | OPEN |
| Q6 | *(Now blocks AV2-3.5's own device pass too: verifying an upgrade in place means a v1 build and this one on the same phone.)* Should staging get its own `applicationId` (e.g. `com.stationly.mobile.staging`)? Today both flavours are `com.stationly.mobile`, so staging and prod **cannot be installed side by side** — which is why AV2-3.4's third criterion is unreachable rather than unverified. Not a build-file change: the google-services plugin fails unless that exact package is registered as an Android app in the staging Firebase project, and Google sign-in additionally needs the (package, SHA-1) pair registered there. Owner-side console work. iOS already did this split (`com.stationly.mobile.staging`). | S009 | AV2-3.4 criterion 3 · AV2-8.2 side-by-side testing | OPEN |

---

## Sprint plan

Sprints are groupings for reporting; the dependency graph is what actually
orders the work.

| Sprint | Stories | Theme | Exit |
|---|---|---|---|
| 1 ✅ | AV2-1.1 → AV2-2.3 | Safety net + database | **Done.** A real v1 database migrates without loss, provably, on both schema shapes, and the build fails if the migration and the schema drift apart |
| 2 | AV2-3.1 → AV2-3.5 | Host cutover | The shared UI **is** the Android app |
| 3 | AV2-4.1 → AV2-4.4 | Data plane | FCM feeds the v2 board model; cloud state dual-writes |
| 4 | AV2-5.1 → AV2-5.4, AV2-6.1 → AV2-6.2 | Widget + dream | One widget per station, configurable in-app |
| 5 | AV2-7.1 → AV2-7.3 | Release surfaces | Update gate, quotas, support (off) |
| 6 | AV2-8.1 → AV2-8.3 | Rollout | `versionCode 3` on a staged rollout |

Epics 05, 06 and 07 are independent of one another and may be reordered or run
in parallel lanes. Epics 01 → 02 → 03 → 04 are strictly sequential: 02 protects
the users, 03 is the foundation 04 builds on.

---

## Burn-up

| Epic | Stories | Done | In review | Points |
|---|---|---|---|---|
| 01 Safety net | 3 | **3** ✅ | — | 9 |
| 02 Database | 3 | **3** ✅ | — | 11 |
| 03 Host cutover | 5 | 0 | 5 | 21 |
| 04 Data plane | 4 | 0 | **4** ✅ | 18 |
| 05 Widget | 4 | 0 | **4** ✅ | 16 |
| 06 Dream | 2 | 0 | **2** ✅ | 6 |
| 07 Release surfaces | 3 | 0 | **3** ✅ | 10 |
| 08 Rollout | 3 | 0 | 0 (8.1 in progress) | 11 |
| **Total** | **27** | **6** | **18** | **102** |

**24 of 27 stories are built.** What is left is three, and all three need a phone
or the owner: finish AV2-8.1 by walking a release build, AV2-8.2's two upgrade
paths on hardware, and AV2-8.3's rollout.

Sizes: `S`=2, `M`=3, `L`=5, `XL`=8. One point is roughly one focused hour, so a
5h session is a `L` with room to close out, or an `XL` that will need two.
