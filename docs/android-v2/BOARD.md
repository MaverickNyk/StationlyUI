# Android v2 — kanban board

**Protocol:** [`README.md`](README.md). **Detail:** [`epics/`](epics/). **Index:** [`BACKLOG.md`](BACKLOG.md).

---

## Live state

```
SPRINT:            4 — widget  (sprints 1-2 complete; sprint 3 in progress)
WIP LIMIT:         1 story In Progress per agent, 2 across the project
LAST SESSION:      S013 (2026-09-06) — AV2-5.1 + AV2-5.2. Each widget shows its
                   own station, and every widget can be reassigned from the app
                   or from the widget itself.
GATE:              GREEN
                   :core:testDebugUnitTest                  PASS  (418 tests)
                   :core:verify…DatabaseMigration           PASS
                   :composeApp:testDebugUnitTest            PASS  (58 tests)
                   :android:app:testStagingDebugUnitTest    PASS  (30 tests, +4)
                   :android:app:compileStaging/ProdDebugKotlin  PASS
                   :composeApp:compileDebugKotlinAndroid    PASS
                   :composeApp:assembleComposeAppDebugXCFramework  PASS
                                                            (commonMain CHANGED —
                                                            all additive, defaults
                                                            preserve iOS exactly)
WORKING TREE:      clean
DEVICE:            Pixel 7 Pro. The config Activity launches and lays out (no
                   exception), but the widget flow COULD NOT be verified: adb
                   cannot drag a widget onto a home screen, and the phone locked
                   itself mid-session. The four-minute manual script is in
                   AV2-5.1's handoff and it is the next thing to do.
BLOCKED ON OWNER:  Q7 the prediction primary key collapses two real trains.
                   Q3 blocks EPIC-06 and holds the last v1 code.
                   Q4 CI. Q5 iOS testers. Q6 one applicationId.
                   EPIC-03 STILL HAS NOT HAD ITS UPGRADE-IN-PLACE PASS.
NEXT UP:           AV2-4.2 (topic lifecycle) or AV2-5.3 (targeted redraw). 5.3 is
                   the smaller one and the widget work just made it easy: a push
                   for station A still redraws every widget, and the binding
                   store now says which ones actually care.
```



---

## Board

Move a story by cutting its row and pasting it into the new column. Do not
duplicate rows. `Backlog → Ready` happens when every dependency is **Done**.

### 🅑 Backlog — dependencies not yet met

| Story | Epic | Title | Waiting on |
|---|---|---|---|
| AV2-4.3 | 04 | Cloud state dual-write | AV2-4.2 |
| AV2-4.4 | 04 | Sessions and activity | AV2-4.3 |
| AV2-5.4 | 05 | Widget guide | AV2-5.1, AV2-3.3 |
| AV2-6.1 | 06 | Real dream actuals | AV2-3.2, **Q3** |
| AV2-6.2 | 06 | Host the shared dream | AV2-6.1 |
| AV2-7.1 | 07 | The update gate | AV2-3.5 |
| AV2-7.2 | 07 | Config and quotas | AV2-3.5 |
| AV2-7.3 | 07 | Support, built and off | AV2-3.5 |
| AV2-8.1 | 08 | Release build integrity | all |
| AV2-8.2 | 08 | Upgrade verification on hardware | AV2-2.2, AV2-8.1 |
| AV2-8.3 | 08 | Ship | AV2-8.2 |

### 🅡 Ready — take the top one

| Story | Epic | Title | Size |
|---|---|---|---|
| _(none)_ | | | |

### 🅘 In Progress — WIP limit 2

| Story | Claimed by | Since | Note |
|---|---|---|---|
| _(none)_ | | | |

### 🅥 Review — done, awaiting owner sign-off

| Story | Session | What to look at |
|---|---|---|
| AV2-5.1 + AV2-5.2 | S013 | **"Widget stacking is not an option on Android — what can be done?"** Nothing needs inventing: Android's home screen has always allowed many instances of one provider, each with its own `appWidgetId`. Two stations is two widgets. What was missing was a binding — `updateFromStorage` pushed `selections.first()` to every widget id, so a widget could not show the wrong station only because it never showed a particular one. Each now resolves its own. Read the epic header for the full design answer and the three things Android can do here that iOS cannot, and AV2-5.1's finding that its AV2-4.3 dependency did not hold. Then run the four-minute device script in AV2-5.1's handoff — **none of this has been driven by a human yet**, because adb cannot place a widget. |
| AV2-4.1 | S012 | **The story's premise was stale and the real bug was somewhere else.** Direction scoping, per-board fan-out and the `matchesFilter` precompute were all already done in `SyncPredictionsUseCase`; they got pinned, not written. What was actually broken: since AV2-3.5, an FCM push wrote fresh departures to SQLite and **the open board did not move** — the fan-out was pinging a SharedPreferences key whose only listener was v1's deleted view model, and nothing emitted to the shared flow the new home screen collects. Fixed, logged, and verified from real pushes on the Pixel. Two more found on the way: Android was signing people out with no explanation (the receiving end already existed — closes AV2-4.4 (e) early), and the home screen was rendering the same departure twice off duplicate rows. One defect is left deliberately unfixed and is now **Q7**. |
| AV2-3.5 | S011 | **The irreversible one, and nothing has been run on a phone.** The shared UI is now the Android app: one launcher, one manifest, ~8,000 lines of v1 deleted, `navigation-compose` and the downloadable-fonts pipeline gone with it. Four things to read rather than re-derive: the **Apple button** is finally hidden on Android — and hiding it left the screen with no primary action, which is the half AV2-3.4 did not see; the **theme carry-over needed no migration** and the real bug was the screensaver reading a different file from the app; **two deep links** would have been registered and silently dropped; and **two v1 behaviours are gone** and are now written into EPIC-04 as tasks. Then two things nobody asked for and both worth knowing: the shared **Screensaver** row opened a settings screen whose Android actuals discard everything written to them (now routed to system settings, as v1 did), and the **update gate is live on Android** as of this commit — read the warning at the top of AV2-7.1 before anyone changes a release policy. Then do the upgrade-in-place pass in §"for the reviewer" — it is the only check this branch cannot recover from getting wrong. |
| AV2-3.3 | S010 | Three stubs became real, both behavioural criteria **verified on the Pixel**: the POST_NOTIFICATIONS prompt fires on a fresh install, and nearby-station search returns stations on *Approximate* location, which v1 refuses. The thing to read is the finding: `AndroidAppContext` tracked the Activity lazily and so never saw the first resume, which silently disarmed that prompt entirely — one of the two prompts Android gives you a single chance at. Fixed with a content provider, staging-only. |
| AV2-3.4 | S009 | All three tasks done and the first two criteria **verified on hardware**: Google sign-in completes from the shared landing screen, and `stationly://` no longer resolves on a staging build while `stationly-staging://` does. Criterion 3 is unreachable (Q6). Two things to read rather than re-test: the **crash** found by backing out of a slow sign-in (fixed, in `commonMain` — one guard, nine call sites), and the still-visible **"Continue with Apple"** button on Android, deliberately left for AV2-3.5. |
| AV2-3.2 | S008 · device pass S009 | Both criteria now **verified on a Pixel 7 Pro** and the epic updated. One correction worth reading: there is no offline *banner* over a live board and there should not be — `computeBoardFallbackState` short-circuits on `hasPredictions`, so a board with cached departures keeps ticking. The offline surface is the cold-start "Can't reach servers", and it appeared. Device id held one value across a crash, force-stops, an update install and a sign-out. |
| AV2-3.1 | S007 · device pass S009 | **Half passes, half fails, and the failure is not this story's.** Two launcher icons; **Stationly v2** opens the shared UI, signs in and navigates. **Stationly Staging** (v1) now *crashes on launch* on any account that has opened v2 — duplicate LazyColumn key, because the v2 board model keeps one selection per direction and v1's keys by station+line. Read that finding before signing off; it is a trap set for AV2-8.2. Prod remains untouched and proven so at the dependency graph and the merged manifest. |

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
| Q3 | Does Daydream survive into v2? It is deprecated on newer Android, v1 ships it, and users may rely on it. Two sessions ride on the answer. **Now also the last thing holding v1 code in the app** — AV2-3.5 deleted every v1 screen and stopped at what `dream/` imports (`ui/theme`, `ui/util`). A "no" deletes that residue for free. | S000 | **EPIC-06 entirely**, and the last of `com.stationly.mobile.ui` | OPEN |
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

| Epic | Stories | Done | Points | Done |
|---|---|---|---|---|
| 01 Safety net | 3 | **3** ✅ | 9 | **9** |
| 02 Database | 3 | **3** ✅ | 11 | **11** |
| 03 Host cutover | 5 | 0 (5 in review) | 21 | 0 |
| 04 Data plane | 4 | 0 (1 in review) | 18 | 0 |
| 05 Widget | 4 | 0 (2 in review) | 16 | 0 |
| 06 Dream | 2 | 0 | 6 | 0 |
| 07 Release surfaces | 3 | 0 | 10 | 0 |
| 08 Rollout | 3 | 0 | 11 | 0 |
| **Total** | **27** | **6** | **102** | **20** |

Sizes: `S`=2, `M`=3, `L`=5, `XL`=8. One point is roughly one focused hour, so a
5h session is a `L` with room to close out, or an `XL` that will need two.
